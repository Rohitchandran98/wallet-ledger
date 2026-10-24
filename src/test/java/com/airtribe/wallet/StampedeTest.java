package com.airtribe.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.airtribe.wallet.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.RepeatedTest;

/**
 * Milestone 2 acceptance test: ₹500 in one wallet, 100 concurrent ₹10 transfers out of it.
 * Exactly 50 succeed, the wallet ends at ₹0 and is never negative, debits = credits. Repeated 20 times.
 */
class StampedeTest extends IntegrationTest {

    private static final int REQUESTS = 100;

    @RepeatedTest(20)
    void hundredConcurrentTransfersFromOneWallet() throws Exception {
        disableRiskRules(); // the velocity rule would (correctly) hold transfer #6; this test is about locking
        long payer = walletFor("Payer");
        long payee = walletFor("Payee");
        assertThat(topUp(payer, 50_000).status()).isEqualTo(201); // ₹500

        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Resp>> futures = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return transfer(payer, payee, 1_000); // ₹10
            }));
        }
        start.countDown(); // fire all at once
        int ok = 0;
        int insufficient = 0;
        for (Future<Resp> f : futures) {
            Resp r = f.get();
            if (r.status() == 201) {
                ok++;
            } else if (r.status() == 422 && "INSUFFICIENT_FUNDS".equals(r.json().get("code").asText())) {
                insufficient++;
            } else {
                throw new AssertionError("unexpected response " + r.status() + " " + r.raw());
            }
        }
        pool.shutdown();

        assertThat(ok).as("successful transfers").isEqualTo(50);
        assertThat(insufficient).isEqualTo(50);
        assertThat(storedBalance(payer)).isZero();
        assertThat(computedBalance(payer)).isZero();
        assertThat(storedBalance(payee)).isEqualTo(50_000).isEqualTo(computedBalance(payee));
        assertThat(totalDebits()).isEqualTo(totalCredits());

        // "never negative": replay the payer's entries in commit order; the running balance never dips below 0
        Long lowest = jdbc.queryForObject("""
                SELECT min(running) FROM (
                    SELECT SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END)
                               OVER (ORDER BY id) AS running
                      FROM ledger_entries WHERE account_id = ?) r
                """, Long.class, payer);
        assertThat(lowest).isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers WHERE type = 'PEER'", Integer.class))
                .isEqualTo(50);
    }
}
