package com.airtribe.wallet.transfer;

import com.airtribe.wallet.common.ApiException;
import com.airtribe.wallet.common.ApiResult;
import com.airtribe.wallet.common.Json;
import com.airtribe.wallet.risk.RiskEngine;
import com.airtribe.wallet.risk.RiskEngine.RuleHit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The money path. Every public method here MUST run inside the transaction opened by
 * {@link com.airtribe.wallet.idempotency.IdempotencyService} (Propagation.MANDATORY enforces it):
 *
 * <pre>
 * BEGIN ISOLATION LEVEL READ COMMITTED;                       -- IdempotencyService
 *   INSERT INTO idempotency_keys ...;                          -- IdempotencyService
 *   SELECT ... FROM accounts WHERE id = :lowest  FOR UPDATE;   -- lockAccountsInIdOrder()
 *   SELECT ... FROM accounts WHERE id = :highest FOR UPDATE;
 *   -- re-check balance while holding the locks
 *   INSERT INTO transfers ...;
 *   INSERT INTO ledger_entries ... DEBIT ..., ... CREDIT ...;  -- postEntries()
 *   UPDATE accounts SET balance = balance - :amt WHERE id = :from;
 *   UPDATE accounts SET balance = balance + :amt WHERE id = :to;
 *   UPDATE idempotency_keys SET status = 'COMPLETED', ...;     -- IdempotencyService
 * COMMIT;                                                      -- deferred trigger checks debits = credits here
 * </pre>
 *
 * Lock ordering rule: account rows are always locked in ascending id, and a transfer row (for reverse /
 * release) is always locked before any account row. With one global order no two transactions can wait on
 * each other in a cycle, so there are no deadlocks.
 */
@Service
// noRollbackFor: a business error must not doom the outer transaction; IdempotencyService rolls back to its
// savepoint instead and still commits the stored error response.
@Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ApiException.class)
public class TransferService {

    public static final long MAX_AMOUNT = 100_000_000_000L; // ₹1,000,000,000 — guards against overflow / typos

    private final JdbcTemplate jdbc;
    private final RiskEngine risk;
    private final Json json;
    private final TransferViews views;
    private final long withdrawalFeePaise;

    public TransferService(JdbcTemplate jdbc, RiskEngine risk, Json json, TransferViews views,
                           @Value("${wallet.fees.withdrawal-paise:0}") long withdrawalFeePaise) {
        this.jdbc = jdbc;
        this.risk = risk;
        this.json = json;
        this.views = views;
        this.withdrawalFeePaise = withdrawalFeePaise;
    }

    /** A row of {@code accounts} read under SELECT ... FOR UPDATE. */
    record LockedAccount(long id, String kind, Long userId, String systemCode, long balance) {
        boolean isWallet() {
            return "USER_WALLET".equals(kind);
        }
    }

    // ------------------------------------------------------------------ top-up: CASH_IN -> wallet

    public ApiResult topUp(long walletId, long amount, String note, String actor) {
        validateAmount(amount);
        setActor(actor, null);
        long cashIn = systemAccountId("CASH_IN");

        Map<Long, LockedAccount> locked = lockAccountsInIdOrder(cashIn, walletId);
        requireWallet(locked.get(walletId));

        long transferId = insertTransfer("TOPUP", "COMPLETED", amount, cashIn, walletId, null, null, actor, note);
        postEntries(transferId, cashIn, walletId, amount);
        return new ApiResult(201, json.write(views.transferJson(transferId, List.of())));
    }

    // ------------------------------------------------------------------ peer transfer: wallet -> wallet

    public ApiResult transfer(long fromWalletId, long toWalletId, long amount, String note, String actor) {
        validateAmount(amount);
        if (fromWalletId == toWalletId) {
            throw ApiException.unprocessable("SAME_ACCOUNT", "Cannot transfer to the same wallet");
        }
        setActor(actor, null);

        // 1. Lock both wallets in ascending id order.
        Map<Long, LockedAccount> locked = lockAccountsInIdOrder(fromWalletId, toWalletId);
        LockedAccount from = requireWallet(locked.get(fromWalletId));
        requireWallet(locked.get(toWalletId));

        // 2. Re-read the balance while holding the lock; nobody else can change it until we commit.
        if (from.balance() < amount) {
            throw insufficientFunds(from.balance(), amount);
        }

        // 3. Fraud rules run inside the same transaction, before commit. The wallet lock also serialises
        //    the velocity count, so 100 parallel requests cannot all see "only 4 recent transfers".
        List<RuleHit> hits = risk.evaluate(fromWalletId, toWalletId, amount);
        boolean hold = hits.stream().anyMatch(h -> "HOLD".equals(h.action()));

        // 4. Insert the transfer and (unless held) its balanced entries.
        String status = hold ? "HELD" : "COMPLETED";
        if (hold) {
            setActor(actor, "held by risk rule " + hits.stream().filter(h -> "HOLD".equals(h.action()))
                    .map(RuleHit::code).toList());
        }
        long transferId = insertTransfer("PEER", status, amount, fromWalletId, toWalletId, null, null, actor, note);
        if (!hold) {
            postEntries(transferId, fromWalletId, toWalletId, amount);
        }
        risk.recordFlags(transferId, hits);
        return new ApiResult(hold ? 202 : 201, json.write(views.transferJson(transferId, List.of())));
    }

    // ------------------------------------------------------------------ withdrawal: wallet -> CASH_OUT (+ fee)

    public ApiResult withdraw(long walletId, long amount, String note, String actor) {
        validateAmount(amount);
        setActor(actor, null);
        long cashOut = systemAccountId("CASH_OUT");
        long feeRevenue = systemAccountId("FEE_REVENUE");

        Map<Long, LockedAccount> locked = withdrawalFeePaise > 0
                ? lockAccountsInIdOrder(walletId, cashOut, feeRevenue)
                : lockAccountsInIdOrder(walletId, cashOut);
        LockedAccount wallet = requireWallet(locked.get(walletId));

        long needed = amount + withdrawalFeePaise;
        if (wallet.balance() < needed) {
            throw insufficientFunds(wallet.balance(), needed);
        }

        long transferId = insertTransfer("WITHDRAWAL", "COMPLETED", amount, walletId, cashOut, null, null, actor, note);
        postEntries(transferId, walletId, cashOut, amount);

        List<Long> related = new ArrayList<>();
        if (withdrawalFeePaise > 0) {
            long feeId = insertTransfer("FEE", "COMPLETED", withdrawalFeePaise, walletId, feeRevenue, null,
                    transferId, actor, "Withdrawal fee for transfer " + transferId);
            postEntries(feeId, walletId, feeRevenue, withdrawalFeePaise);
            related.add(feeId);
        }
        return new ApiResult(201, json.write(views.transferJson(transferId, related)));
    }

    // ------------------------------------------------------------------ reversal: new transfer pointing to the original

    public ApiResult reverse(long originalId, String reason, String actor) {
        setActor(actor, reason);

        // Transfer row first, then accounts (global lock order).
        Map<String, Object> original = lockTransfer(originalId);
        String type = (String) original.get("type");
        String status = (String) original.get("status");
        if ("REVERSAL".equals(type)) {
            throw ApiException.conflict("CANNOT_REVERSE_REVERSAL", "A reversal cannot itself be reversed");
        }
        if ("REVERSED".equals(status)) {
            throw ApiException.conflict("ALREADY_REVERSED", "Transfer " + originalId + " has already been reversed");
        }
        if (!"COMPLETED".equals(status)) {
            throw ApiException.conflict("NOT_REVERSIBLE",
                    "Only COMPLETED transfers can be reversed; this one is " + status
                            + ("HELD".equals(status) ? " (use POST /admin/risk/{id}/decision)" : ""));
        }

        long amount = ((Number) original.get("amount")).longValue();
        long origFrom = ((Number) original.get("from_account_id")).longValue();
        long origTo = ((Number) original.get("to_account_id")).longValue();

        // Money flows back: debit whoever was credited, credit whoever was debited.
        Map<Long, LockedAccount> locked = lockAccountsInIdOrder(origFrom, origTo);
        LockedAccount payer = locked.get(origTo);
        if (payer.isWallet() && payer.balance() < amount) {
            throw insufficientFunds(payer.balance(), amount);
        }

        long reversalId = insertTransfer("REVERSAL", "COMPLETED", amount, origTo, origFrom, originalId, null, actor,
                reason == null ? "Reversal of transfer " + originalId : reason);
        postEntries(reversalId, origTo, origFrom, amount);
        jdbc.update("UPDATE transfers SET status = 'REVERSED', updated_at = now() WHERE id = ?", originalId);
        return new ApiResult(201, json.write(views.transferJson(reversalId, List.of())));
    }

    // ------------------------------------------------------------------ admin decision on a HELD transfer

    public ApiResult release(long transferId, String reason, String actor) {
        setActor(actor, reason == null ? "released by admin" : reason);
        Map<String, Object> transfer = lockTransfer(transferId);
        requireHeld(transfer);

        long amount = ((Number) transfer.get("amount")).longValue();
        long from = ((Number) transfer.get("from_account_id")).longValue();
        long to = ((Number) transfer.get("to_account_id")).longValue();

        Map<Long, LockedAccount> locked = lockAccountsInIdOrder(from, to);
        LockedAccount payer = locked.get(from);
        if (payer.balance() < amount) {
            throw insufficientFunds(payer.balance(), amount);
        }
        postEntries(transferId, from, to, amount);
        jdbc.update("UPDATE transfers SET status = 'COMPLETED', updated_at = now() WHERE id = ?", transferId);
        return new ApiResult(200, json.write(views.transferJson(transferId, List.of())));
    }

    public ApiResult reject(long transferId, String reason, String actor) {
        setActor(actor, reason == null ? "rejected by admin" : reason);
        Map<String, Object> transfer = lockTransfer(transferId);
        requireHeld(transfer);
        // No entries were ever written for a HELD transfer, so rejecting it moves no money.
        jdbc.update("UPDATE transfers SET status = 'REJECTED', updated_at = now() WHERE id = ?", transferId);
        return new ApiResult(200, json.write(views.transferJson(transferId, List.of())));
    }

    // ================================================================== building blocks

    /**
     * SELECT ... FOR UPDATE on each account, one statement per row, in ascending id order.
     * Any other transaction that wants one of these rows waits here until we COMMIT/ROLLBACK.
     */
    Map<Long, LockedAccount> lockAccountsInIdOrder(long... accountIds) {
        long[] ordered = Arrays.stream(accountIds).distinct().sorted().toArray();
        Map<Long, LockedAccount> locked = new LinkedHashMap<>();
        for (long id : ordered) {
            List<LockedAccount> rows = jdbc.query("""
                    SELECT id, kind, user_id, system_code, balance
                      FROM accounts
                     WHERE id = ?
                       FOR UPDATE
                    """, (rs, i) -> new LockedAccount(rs.getLong("id"), rs.getString("kind"),
                    (Long) rs.getObject("user_id"), rs.getString("system_code"), rs.getLong("balance")), id);
            if (rows.isEmpty()) {
                throw ApiException.notFound("Wallet", id);
            }
            locked.put(id, rows.get(0));
        }
        return locked;
    }

    /** Two balanced lines plus the matching stored-balance updates (relative, never "SET balance = :x"). */
    private void postEntries(long transferId, long debitAccountId, long creditAccountId, long amount) {
        jdbc.update("""
                INSERT INTO ledger_entries (transfer_id, account_id, direction, amount)
                VALUES (?, ?, 'DEBIT', ?), (?, ?, 'CREDIT', ?)
                """, transferId, debitAccountId, amount, transferId, creditAccountId, amount);
        jdbc.update("UPDATE accounts SET balance = balance - ? WHERE id = ?", amount, debitAccountId);
        jdbc.update("UPDATE accounts SET balance = balance + ? WHERE id = ?", amount, creditAccountId);
    }

    private long insertTransfer(String type, String status, long amount, long from, long to,
                                Long reversesId, Long parentId, String actor, String note) {
        return jdbc.queryForObject("""
                INSERT INTO transfers (type, status, amount, from_account_id, to_account_id,
                                       reverses_transfer_id, parent_transfer_id, initiated_by, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, type, status, amount, from, to, reversesId, parentId, actor, note);
    }

    private Map<String, Object> lockTransfer(long transferId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, type, status, amount, from_account_id, to_account_id
                  FROM transfers
                 WHERE id = ?
                   FOR UPDATE
                """, transferId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Transfer", transferId);
        }
        return rows.get(0);
    }

    /** Who is changing transfer statuses in this transaction; read by the audit trigger. */
    private void setActor(String actor, String reason) {
        jdbc.queryForObject("SELECT set_config('app.actor', ?, true)", String.class, actor);
        jdbc.queryForObject("SELECT set_config('app.reason', ?, true)", String.class, reason == null ? "" : reason);
    }

    private long systemAccountId(String code) {
        return jdbc.queryForObject("SELECT id FROM accounts WHERE system_code = ?", Long.class, code);
    }

    private static LockedAccount requireWallet(LockedAccount account) {
        if (!account.isWallet()) {
            throw ApiException.unprocessable("NOT_A_WALLET", "Account " + account.id() + " is not a user wallet");
        }
        return account;
    }

    private static void requireHeld(Map<String, Object> transfer) {
        if (!"HELD".equals(transfer.get("status"))) {
            throw ApiException.conflict("NOT_HELD", "Transfer " + transfer.get("id") + " is " + transfer.get("status")
                    + ", not HELD");
        }
    }

    private static void validateAmount(long amount) {
        if (amount <= 0 || amount > MAX_AMOUNT) {
            throw ApiException.unprocessable("INVALID_AMOUNT", "amount must be a positive whole number of paise");
        }
    }

    private static ApiException insufficientFunds(long balance, long needed) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS",
                "Balance " + balance + " paise is less than the " + needed + " paise required");
    }
}
