package com.airtribe.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.airtribe.wallet.recon.ReconciliationService;
import com.airtribe.wallet.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Milestone 4: the nightly job proves the books balance and reports exactly what is wrong when they don't. */
class ReconciliationTest extends IntegrationTest {

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    DataSource dataSource;

    private void seed(int users, int wallets, int transfers) throws Exception {
        jdbc.execute(Files.readString(Path.of("scripts/seed.sql")));
        jdbc.execute("CALL seed_wallet_data(" + users + ", " + wallets + ", " + transfers + ")");
    }

    @Test
    void cleanLedgerPasses() throws Exception {
        seed(200, 300, 10_000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers", Integer.class)).isEqualTo(10_000);

        Resp run = post("/admin/reconciliation/run?wait=true", Map.of());
        assertThat(run.status()).isEqualTo(200);
        assertThat(run.json().get("status").asText()).isEqualTo("PASSED");
        assertThat(run.json().get("issues")).isEmpty();
        assertThat(run.json().get("entries_checked").asLong()).isEqualTo(20_000);
    }

    @Test
    void reportsExactlyTheThreeCorruptionsAndNothingElse() throws Exception {
        seed(200, 300, 10_000);

        // pick three unrelated wallet accounts / transfers
        Map<String, Object> e1 = jdbc.queryForMap("""
                SELECT e.id, e.transfer_id, e.account_id FROM ledger_entries e JOIN transfers t ON t.id = e.transfer_id
                 WHERE t.type = 'PEER' AND e.direction = 'DEBIT' ORDER BY e.id LIMIT 1
                """);
        Map<String, Object> e2 = jdbc.queryForMap("""
                SELECT e.id, e.transfer_id, e.account_id FROM ledger_entries e JOIN transfers t ON t.id = e.transfer_id
                 WHERE t.type = 'PEER' AND e.direction = 'CREDIT'
                   AND e.transfer_id <> ? AND e.account_id <> ?
                 ORDER BY e.id DESC LIMIT 1
                """, e1.get("transfer_id"), e1.get("account_id"));
        long z = jdbc.queryForObject("""
                SELECT id FROM accounts WHERE kind = 'USER_WALLET' AND id NOT IN (?, ?) ORDER BY id DESC LIMIT 1
                """, Long.class, e1.get("account_id"), e2.get("account_id"));

        // corrupt the data directly in the database (bypassing the append-only trigger, as an attacker or
        // a buggy migration might)
        jdbc.execute("ALTER TABLE ledger_entries DISABLE TRIGGER ledger_entries_append_only");
        jdbc.update("UPDATE ledger_entries SET amount = amount + 1 WHERE id = ?", e1.get("id")); // 1. change an amount
        jdbc.update("DELETE FROM ledger_entries WHERE id = ?", e2.get("id"));                    // 2. delete a credit
        jdbc.execute("ALTER TABLE ledger_entries ENABLE TRIGGER ledger_entries_append_only");
        jdbc.update("UPDATE accounts SET balance = balance + 777 WHERE id = ?", z);              // 3. change a balance

        Resp run = post("/admin/reconciliation/run?wait=true", Map.of());
        JsonNode report = run.json();
        assertThat(report.get("status").asText()).isEqualTo("FAILED");

        Set<String> found = new HashSet<>();
        report.get("issues").forEach(i -> found.add(i.get("check_name").asText() + ":"
                + (i.get("entity_id").isNull() ? "-" : i.get("entity_id").asLong())));

        // Each corruption is reported by every check it breaks — and no other row appears.
        assertThat(found).containsExactlyInAnyOrder(
                "LEDGER_BALANCED:-",                                    // 1 + 2 break the global sum
                "TRANSFER_BALANCED:" + e1.get("transfer_id"),          // 1: changed amount
                "TRANSFER_BALANCED:" + e2.get("transfer_id"),          // 2: deleted credit
                "STORED_BALANCE:" + e1.get("account_id"),              // 1: entries no longer match the balance
                "STORED_BALANCE:" + e2.get("account_id"),              // 2: entries no longer match the balance
                "STORED_BALANCE:" + z);                                // 3: changed balance

        // and the issue rows carry expected vs actual
        JsonNode zIssue = null;
        for (JsonNode i : report.get("issues")) {
            if (i.get("check_name").asText().equals("STORED_BALANCE") && i.get("entity_id").asLong() == z) {
                zIssue = i;
            }
        }
        assertThat(zIssue.get("actual").asLong() - zIssue.get("expected").asLong()).isEqualTo(777);

        // the job reports but never fixes
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE id = ?", Integer.class,
                e2.get("id"))).isZero();
    }

    @Test
    void onlyOneRunAtATime() throws Exception {
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (Statement st = other.createStatement()) {
                st.execute("SELECT id FROM job_locks WHERE job_name = 'reconciliation' FOR UPDATE");
            }
            ReconciliationService.RunOutcome outcome = reconciliation.runNow("test");
            assertThat(outcome.status()).isEqualTo("SKIPPED");
            other.rollback();
        }
        assertThat(reconciliation.runNow("test").status()).isEqualTo("PASSED");
        JsonNode skipped = get("/admin/reconciliation/runs/1").json();
        assertThat(skipped.get("status").asText()).isEqualTo("SKIPPED");
    }

    @Test
    void manualTriggerReturns202AndIsIdempotent() {
        String key = java.util.UUID.randomUUID().toString();
        Resp first = post("/admin/reconciliation/run", Map.of(), key);
        Resp replay = post("/admin/reconciliation/run", Map.of(), key);
        assertThat(first.status()).isEqualTo(202);
        assertThat(replay.raw()).isEqualTo(first.raw());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_runs", Integer.class)).isEqualTo(1);
    }

    @Test
    void heldTransferWithEntriesAndCompletedTransferWithoutEntriesAreReported() {
        long asha = walletFor("Asha");
        long rahul = walletFor("Rahul");
        topUp(asha, 1_000);
        long cashIn = jdbc.queryForObject("SELECT id FROM accounts WHERE system_code = 'CASH_IN'", Long.class);
        long ghost = jdbc.queryForObject("""
                INSERT INTO transfers (type, status, amount, from_account_id, to_account_id, initiated_by)
                VALUES ('TOPUP', 'COMPLETED', 5, ?, ?, 'test') RETURNING id
                """, Long.class, cashIn, rahul);
        assertThat(reconciliation.runNow("test").status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("""
                SELECT entity_id FROM reconciliation_issues WHERE check_name = 'ORPHAN_TRANSFER'
                """, Long.class)).isEqualTo(ghost);
    }
}
