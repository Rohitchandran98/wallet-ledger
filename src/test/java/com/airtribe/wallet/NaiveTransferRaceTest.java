package com.airtribe.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.airtribe.wallet.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Milestone 2, step 2 — "break it first". The textbook unsafe transfer: read the balance, check it in
 * code, then write the new balance. No row lock. Under 100 parallel requests this over-spends and loses
 * updates. The test asserts the bug is real (so the fix in TransferService is meaningful); numbers from a
 * run are recorded in docs/concurrency.md.
 */
class NaiveTransferRaceTest extends IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(NaiveTransferRaceTest.class);

    @Autowired
    PlatformTransactionManager txManager;

    /** DO NOT COPY: deliberately broken. */
    private boolean naiveTransfer(long from, long to, long amount) throws InterruptedException {
        TransactionTemplate tx = new TransactionTemplate(txManager); // READ COMMITTED, but no FOR UPDATE
        return Boolean.TRUE.equals(tx.execute(s -> {
            long balance = jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, from);
            if (balance < amount) {
                return false;
            }
            try {
                Thread.sleep(2); // the gap between "check" and "act" that real code always has
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            long transferId = jdbc.queryForObject("""
                    INSERT INTO transfers (type, status, amount, from_account_id, to_account_id, initiated_by)
                    VALUES ('PEER', 'COMPLETED', ?, ?, ?, 'naive') RETURNING id
                    """, Long.class, amount, from, to);
            jdbc.update("""
                    INSERT INTO ledger_entries (transfer_id, account_id, direction, amount)
                    VALUES (?, ?, 'DEBIT', ?), (?, ?, 'CREDIT', ?)
                    """, transferId, from, amount, transferId, to, amount);
            // absolute write computed from a stale read => lost update
            jdbc.update("UPDATE accounts SET balance = ? WHERE id = ?", balance - amount, from);
            jdbc.update("UPDATE accounts SET balance = balance + ? WHERE id = ?", amount, to);
            return true;
        }));
    }

    @Test
    void naiveReadCheckWriteOverspendsUnderConcurrency() throws Exception {
        long payer = walletFor("Payer");
        long payee = walletFor("Payee");
        topUp(payer, 50_000);

        ExecutorService pool = Executors.newFixedThreadPool(100);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return naiveTransfer(payer, payee, 1_000);
            }));
        }
        start.countDown();
        int ok = 0;
        for (Future<Boolean> f : results) {
            if (f.get()) {
                ok++;
            }
        }
        pool.shutdown();

        long stored = storedBalance(payer);
        long computed = computedBalance(payer);
        long payeeGot = storedBalance(payee);
        log.info("NAIVE RESULT: successes={} (expected 50), payer stored={} computed-from-entries={}, payee={}",
                ok, stored, computed, payeeGot);

        // The bug: more than ₹500 left the wallet, and the stored balance no longer matches the ledger.
        assertThat(ok).isGreaterThan(50);
        assertThat(computed).isNegative();
        assertThat(stored).isNotEqualTo(computed);
    }
}
