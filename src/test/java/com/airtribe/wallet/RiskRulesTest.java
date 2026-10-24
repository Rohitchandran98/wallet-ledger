package com.airtribe.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.airtribe.wallet.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Milestone 5A: rules hold or flag transfers inside the transfer transaction; held money does not move. */
class RiskRulesTest extends IntegrationTest {

    private void onlyRule(String code) {
        jdbc.update("UPDATE risk_rules SET enabled = (code = ?)", code);
    }

    @Test
    void velocityHoldsTheSixthTransferInTenMinutesAndMovesNoMoney() {
        onlyRule("VELOCITY");
        long asha = walletFor("Asha");
        long rahul = walletFor("Rahul");
        topUp(asha, 100_000);
        for (int i = 0; i < 5; i++) {
            assertThat(transfer(asha, rahul, 1_000).status()).isEqualTo(201);
        }
        Resp sixth = transfer(asha, rahul, 1_000);
        assertThat(sixth.status()).isEqualTo(202);
        assertThat(sixth.json().get("status").asText()).isEqualTo("HELD");
        assertThat(sixth.json().get("risk_flags").get(0).get("rule").asText()).isEqualTo("VELOCITY");

        long held = sixth.id("transfer_id");
        assertThat(storedBalance(asha)).isEqualTo(95_000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE transfer_id = ?", Integer.class, held))
                .isZero();

        JsonNode queue = get("/admin/risk/queue").json();
        assertThat(queue.get("held")).hasSize(1);
        assertThat(queue.get("held").get(0).get("transfer_id").asLong()).isEqualTo(held);
    }

    @Test
    void adminReleaseMovesTheMoneyAndIsAudited() {
        onlyRule("VELOCITY");
        jdbc.update("UPDATE risk_rules SET max_count = 0 WHERE code = 'VELOCITY'"); // hold everything
        long asha = walletFor("Asha");
        long rahul = walletFor("Rahul");
        topUp(asha, 10_000);
        long held = transfer(asha, rahul, 4_000).id("transfer_id");

        Resp released = post("/admin/risk/" + held + "/decision", Map.of("decision", "RELEASE", "reason", "called user"));
        assertThat(released.status()).isEqualTo(200);
        assertThat(released.json().get("status").asText()).isEqualTo("COMPLETED");
        assertThat(storedBalance(asha)).isEqualTo(6_000);
        assertThat(storedBalance(rahul)).isEqualTo(4_000);

        JsonNode history = get("/transfers/" + held).json().get("status_history");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("to_status").asText()).isEqualTo("HELD");
        assertThat(history.get(1).get("from_status").asText()).isEqualTo("HELD");
        assertThat(history.get(1).get("to_status").asText()).isEqualTo("COMPLETED");
        assertThat(history.get(1).get("changed_by").asText()).startsWith("admin:");
        assertThat(history.get(1).get("reason").asText()).isEqualTo("called user");

        Resp twice = post("/admin/risk/" + held + "/decision", Map.of("decision", "RELEASE"));
        assertThat(twice.status()).isEqualTo(409);
    }

    @Test
    void adminReverseOfAHeldTransferRejectsItWithoutMovingMoney() {
        onlyRule("VELOCITY");
        jdbc.update("UPDATE risk_rules SET max_count = 0 WHERE code = 'VELOCITY'");
        long asha = walletFor("Asha");
        long rahul = walletFor("Rahul");
        topUp(asha, 10_000);
        long held = transfer(asha, rahul, 4_000).id("transfer_id");

        Resp rejected = post("/admin/risk/" + held + "/decision", Map.of("decision", "REVERSE"));
        assertThat(rejected.status()).isEqualTo(200);
        assertThat(rejected.json().get("status").asText()).isEqualTo("REJECTED");
        assertThat(storedBalance(asha)).isEqualTo(10_000);
        assertThat(totalDebits()).isEqualTo(totalCredits());
    }

    @Test
    void amountSpikeHoldsATransferFarAboveTheAverage() {
        onlyRule("AMOUNT_SPIKE");
        long asha = walletFor("Asha");
        long rahul = walletFor("Rahul");
        topUp(asha, 1_000_000);
        transfer(asha, rahul, 1_000);
        transfer(asha, rahul, 1_000);
        assertThat(transfer(asha, rahul, 5_000).status()).isEqualTo(201);   // avg 1000 -> 5x = 5000, not over
        Resp spike = transfer(asha, rahul, 20_000);                         // avg ~2333 -> 5x = 11666, over
        assertThat(spike.status()).isEqualTo(202);
        assertThat(spike.json().get("risk_flags").get(0).get("rule").asText()).isEqualTo("AMOUNT_SPIKE");
    }

    @Test
    void newPayeeLargeAmountIsFlaggedButAllowed() {
        onlyRule("NEW_PAYEE_LARGE");
        long asha = walletFor("Asha");
        long rahul = walletFor("Rahul");
        topUp(asha, 5_000_000);
        Resp big = transfer(asha, rahul, 1_500_000); // ₹15,000 to a first-time payee
        assertThat(big.status()).isEqualTo(201);
        assertThat(big.json().get("status").asText()).isEqualTo("COMPLETED");
        assertThat(big.json().get("risk_flags").get(0).get("rule").asText()).isEqualTo("NEW_PAYEE_LARGE");
        assertThat(big.json().get("risk_flags").get(0).get("action").asText()).isEqualTo("FLAG");

        Resp again = transfer(asha, rahul, 1_500_000); // known payee now
        assertThat(again.json().get("risk_flags")).isEmpty();
    }

    @Test
    void thresholdsComeFromTheRiskRulesTable() {
        onlyRule("NEW_PAYEE_LARGE");
        jdbc.update("UPDATE risk_rules SET amount_threshold = 100 WHERE code = 'NEW_PAYEE_LARGE'");
        long asha = walletFor("Asha");
        long rahul = walletFor("Rahul");
        topUp(asha, 5_000);
        assertThat(transfer(asha, rahul, 101).json().get("risk_flags")).hasSize(1);
    }

    @Test
    void nightlyJobAddsAnLlmExplanationAndLabelToTodaysFlags() {
        onlyRule("VELOCITY");
        jdbc.update("UPDATE risk_rules SET max_count = 0 WHERE code = 'VELOCITY'");
        long asha = walletFor("Asha");
        long rahul = walletFor("Rahul");
        topUp(asha, 10_000);
        long held = transfer(asha, rahul, 4_000).id("transfer_id");

        Resp run = post("/admin/reconciliation/run?wait=true", Map.of());
        assertThat(run.json().get("flags_explained").asInt()).isEqualTo(1);

        Map<String, Object> flag = jdbc.queryForMap(
                "SELECT llm_label, llm_explanation FROM risk_flags WHERE transfer_id = ?", held);
        assertThat(flag.get("llm_label")).isEqualTo("review");
        assertThat((String) flag.get("llm_explanation")).isNotBlank();
        JsonNode queue = get("/admin/risk/queue").json();
        assertThat(queue.get("flags").get(0).get("llm_label").asText()).isEqualTo("review");
    }
}
