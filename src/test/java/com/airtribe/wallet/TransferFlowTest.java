package com.airtribe.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.airtribe.wallet.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/** End-to-end money movement plus the database constraints that make invalid states impossible. */
class TransferFlowTest extends IntegrationTest {

    @Test
    void topUpTransferWithdrawWritesBalancedEntries() {
        long asha = walletFor("Asha");
        long rahul = walletFor("Rahul");

        Resp top = topUp(asha, 100_000);
        assertThat(top.status()).isEqualTo(201);
        assertThat(top.json().get("type").asText()).isEqualTo("TOPUP");

        Resp send = transfer(asha, rahul, 50_000);
        assertThat(send.status()).isEqualTo(201);
        assertThat(send.json().get("status").asText()).isEqualTo("COMPLETED");

        Resp out = post("/transfers/withdraw", Map.of("wallet_id", rahul, "amount", 20_000));
        assertThat(out.status()).isEqualTo(201);

        assertThat(get("/wallets/" + asha + "/balance").json().get("balance").asLong()).isEqualTo(50_000);
        assertThat(storedBalance(rahul)).isEqualTo(30_000).isEqualTo(computedBalance(rahul));
        assertThat(totalDebits()).isEqualTo(totalCredits());

        // system accounts: cash-in went negative by what entered, cash-out positive by what left
        assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE system_code = 'CASH_IN'", Long.class))
                .isEqualTo(-100_000);
        assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE system_code = 'CASH_OUT'", Long.class))
                .isEqualTo(20_000);

        JsonNode detail = get("/transfers/" + send.id("transfer_id")).json();
        assertThat(detail.get("entries")).hasSize(2);
        assertThat(detail.get("entries").get(0).get("direction").asText()).isEqualTo("DEBIT");
        assertThat(detail.get("entries").get(0).get("account_id").asLong()).isEqualTo(asha);
        assertThat(detail.get("status_history").get(0).get("to_status").asText()).isEqualTo("COMPLETED");
        assertThat(detail.get("status_history").get(0).get("changed_by").asText()).startsWith("user:");
    }

    @Test
    void insufficientFundsIsRejectedAndLeavesNoTrace() {
        long asha = walletFor("Asha");
        long rahul = walletFor("Rahul");
        topUp(asha, 1_000);

        Resp r = transfer(asha, rahul, 1_001);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.json().get("code").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(r.json().has("request_id")).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers WHERE type = 'PEER'", Integer.class)).isZero();
        assertThat(storedBalance(asha)).isEqualTo(1_000);
    }

    @Test
    void validationAndNotFoundUseTheOneErrorShape() {
        long asha = walletFor("Asha");
        Resp bad = post("/transfers", Map.of("from_wallet_id", asha, "to_wallet_id", asha, "amount", -5));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.json().get("code").asText()).isEqualTo("VALIDATION_ERROR");

        Resp missing = transfer(999_999, asha, 100);
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.json().fieldNames()).toIterable().containsExactly("code", "message", "request_id");

        Resp same = transfer(asha, asha, 100);
        assertThat(same.status()).isEqualTo(422);
        assertThat(same.json().get("code").asText()).isEqualTo("SAME_ACCOUNT");
    }

    @Test
    void reversalCreatesAPointingTransferAndCanHappenOnlyOnce() {
        long asha = walletFor("Asha");
        long rahul = walletFor("Rahul");
        topUp(asha, 10_000);
        long original = transfer(asha, rahul, 4_000).id("transfer_id");

        Resp rev = post("/transfers/" + original + "/reverse", Map.of("reason", "sent by mistake"));
        assertThat(rev.status()).isEqualTo(201);
        assertThat(rev.json().get("type").asText()).isEqualTo("REVERSAL");
        assertThat(rev.json().get("reverses_transfer_id").asLong()).isEqualTo(original);
        assertThat(storedBalance(asha)).isEqualTo(10_000);
        assertThat(storedBalance(rahul)).isZero();

        Resp again = post("/transfers/" + original + "/reverse", Map.of("reason", "again"));
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.json().get("code").asText()).isEqualTo("ALREADY_REVERSED");

        JsonNode detail = get("/transfers/" + original).json();
        assertThat(detail.get("status").asText()).isEqualTo("REVERSED");
        assertThat(detail.get("reversed_by_transfer_id").asLong()).isEqualTo(rev.id("transfer_id"));
        assertThat(detail.get("status_history")).hasSize(2);
        assertThat(detail.get("status_history").get(1).get("reason").asText()).isEqualTo("sent by mistake");
        // original entries are untouched: 2 for the original + 2 for the reversal
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE transfer_id IN (?, ?)",
                Integer.class, original, rev.id("transfer_id"))).isEqualTo(4);
    }

    @Test
    void withdrawalFeeIsASeparateFeeTransferWhenConfigured() {
        long asha = walletFor("Asha");
        topUp(asha, 10_000);
        // fee is 0 by default; the FEE leg is exercised by flipping the property in WithdrawalFeeTest
        Resp r = post("/transfers/withdraw", Map.of("wallet_id", asha, "amount", 10_000));
        assertThat(r.status()).isEqualTo(201);
        assertThat(storedBalance(asha)).isZero();
        Resp tooMuch = post("/transfers/withdraw", Map.of("wallet_id", asha, "amount", 1));
        assertThat(tooMuch.status()).isEqualTo(422);
    }

    @Test
    void statementIsNewestFirstAndCursorMatchesOffset() {
        long asha = walletFor("Asha");
        long rahul = walletFor("Rahul");
        topUp(asha, 100_000);
        disableRiskRules();
        for (int i = 1; i <= 7; i++) {
            transfer(asha, rahul, i * 100L);
        }
        JsonNode page1 = get("/wallets/" + asha + "/statement?limit=3").json();
        JsonNode page2 = get("/wallets/" + asha + "/statement?limit=3&offset=3").json();
        JsonNode cursor2 = get("/wallets/" + asha + "/statement?limit=3&cursor="
                + page1.get("page").get("next_cursor").asText()).json();

        List<Long> amounts = new ArrayList<>();
        page1.get("entries").forEach(e -> amounts.add(e.get("amount").asLong()));
        assertThat(amounts).containsExactly(700L, 600L, 500L);
        assertThat(cursor2.get("entries").toString()).isEqualTo(page2.get("entries").toString());
        assertThat(get("/wallets/" + asha + "/statement?limit=0").status()).isEqualTo(400);
    }

    @Test
    void missingIdempotencyKeyIsRejectedWith400() {
        long asha = walletFor("Asha");
        Resp r = post("/transfers/topup", Map.of("wallet_id", asha, "amount", 100), null);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.json().get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        Resp bad = post("/transfers/topup", Map.of("wallet_id", asha, "amount", 100), "not-a-uuid");
        assertThat(bad.json().get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_INVALID");
        assertThat(storedBalance(asha)).isZero();
    }

    // ------------------------------------------------------------------ the database protects itself

    @Test
    void ledgerEntriesCannotBeUpdatedOrDeleted() {
        long asha = walletFor("Asha");
        topUp(asha, 500);
        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount = amount + 1"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE ledger_entries CASCADE"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void walletBalanceCannotGoNegativeEvenWithRawSql() {
        long asha = walletFor("Asha");
        assertThatThrownBy(() -> jdbc.update("UPDATE accounts SET balance = -1 WHERE id = ?", asha))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("accounts_wallet_non_negative_ck");
        // system accounts may go negative
        jdbc.update("UPDATE accounts SET balance = -1 WHERE system_code = 'CASH_IN'");
    }

    @Test
    void unbalancedTransferIsRejectedAtCommit() {
        long asha = walletFor("Asha");
        long cashIn = jdbc.queryForObject("SELECT id FROM accounts WHERE system_code = 'CASH_IN'", Long.class);
        long t = jdbc.queryForObject("""
                INSERT INTO transfers (type, status, amount, from_account_id, to_account_id, initiated_by)
                VALUES ('TOPUP', 'COMPLETED', 100, ?, ?, 'test') RETURNING id
                """, Long.class, cashIn, asha);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES
                    (?, ?, 'DEBIT', 100), (?, ?, 'CREDIT', 99)
                """, t, cashIn, t, asha))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("unbalanced");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) VALUES (?, ?, 'CREDIT', 0)",
                t, asha)).isInstanceOf(DataAccessException.class);
    }
}
