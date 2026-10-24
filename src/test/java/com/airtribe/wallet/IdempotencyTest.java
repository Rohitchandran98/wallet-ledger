package com.airtribe.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.airtribe.wallet.support.IntegrationTest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Milestone 3 acceptance tests: a client can retry as often as it likes; money moves at most once. */
class IdempotencyTest extends IntegrationTest {

    long asha;
    long rahul;

    @BeforeEach
    void wallets() {
        asha = walletFor("Asha");
        rahul = walletFor("Rahul");
        topUp(asha, 100_000);
        disableRiskRules();
    }

    private Map<String, Object> body(long amount) {
        return Map.of("from_wallet_id", asha, "to_wallet_id", rahul, "amount", amount, "note", "dinner");
    }

    private int peerTransfers() {
        return jdbc.queryForObject("SELECT count(*) FROM transfers WHERE type = 'PEER'", Integer.class);
    }

    @Test
    void sameRequestFiveTimesInARowCreatesOneTransferAndFiveIdenticalResponses() {
        String key = UUID.randomUUID().toString();
        List<Resp> responses = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            responses.add(post("/transfers", body(2_500), key));
        }
        assertThat(peerTransfers()).isEqualTo(1);
        assertThat(responses).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(201);
            assertThat(r.raw()).isEqualTo(responses.get(0).raw()); // byte-for-byte, incl. transfer_id
        });
        assertThat(storedBalance(asha)).isEqualTo(97_500);
    }

    @Test
    void sameKeyTenTimesInParallelStillCreatesExactlyOneTransfer() throws Exception {
        String key = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Resp>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return post("/transfers", body(3_000), key);
            }));
        }
        start.countDown();
        Set<String> successBodies = new HashSet<>();
        for (Future<Resp> f : futures) {
            Resp r = f.get();
            // 201 (original or replay) or 409 "still running" are the only legal answers
            assertThat(r.status()).isIn(201, 409);
            if (r.status() == 201) {
                successBodies.add(r.raw());
            }
        }
        pool.shutdown();

        assertThat(peerTransfers()).isEqualTo(1);
        assertThat(successBodies).hasSize(1);
        assertThat(storedBalance(asha)).isEqualTo(97_000);
        assertThat(storedBalance(rahul)).isEqualTo(3_000);
    }

    @Test
    void sameKeyWithDifferentAmountIs422AndNoMoneyMoves() {
        String key = UUID.randomUUID().toString();
        assertThat(post("/transfers", body(1_000), key).status()).isEqualTo(201);

        Resp reused = post("/transfers", body(9_000), key);
        assertThat(reused.status()).isEqualTo(422);
        assertThat(reused.json().get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(peerTransfers()).isEqualTo(1);
        assertThat(storedBalance(asha)).isEqualTo(99_000);
        assertThat(storedBalance(rahul)).isEqualTo(1_000);
    }

    @Test
    void keyOrderAndWhitespaceInTheBodyDoNotMatter() {
        String key = UUID.randomUUID().toString();
        String a = "{\"from_wallet_id\":" + asha + ",\"to_wallet_id\":" + rahul + ",\"amount\":700}";
        String b = "{ \"amount\" : 700, \"to_wallet_id\" : " + rahul + ", \"from_wallet_id\" : " + asha + " }";
        Resp first = post("/transfers", a, key);
        Resp second = post("/transfers", b, key);
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.raw()).isEqualTo(first.raw());
        assertThat(peerTransfers()).isEqualTo(1);
    }

    @Test
    void aFailedRequestIsAlsoReplayedNotReExecuted() {
        String key = UUID.randomUUID().toString();
        Resp first = post("/transfers", body(500_000), key); // more than the balance
        assertThat(first.status()).isEqualTo(422);
        topUp(asha, 1_000_000); // now there would be enough money...
        Resp retry = post("/transfers", body(500_000), key);
        assertThat(retry.status()).isEqualTo(422); // ...but the retry gets the original answer
        assertThat(retry.raw()).isEqualTo(first.raw());
        assertThat(peerTransfers()).isZero();
    }

    @Test
    void keysAreScopedPerUser() {
        String key = UUID.randomUUID().toString();
        long priya = walletFor("Priya");
        topUp(priya, 10_000);
        assertThat(post("/transfers", body(1_000), key).status()).isEqualTo(201);
        Resp other = post("/transfers", Map.of("from_wallet_id", priya, "to_wallet_id", rahul, "amount", 1_000), key);
        assertThat(other.status()).isEqualTo(201); // same UUID, different user => different key
        assertThat(peerTransfers()).isEqualTo(2);
    }
}
