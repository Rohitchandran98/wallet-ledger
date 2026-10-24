package com.airtribe.wallet.recon;

import com.airtribe.wallet.risk.RiskExplainer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Nightly proof that the books are right. Reports problems, never fixes them.
 *
 * <pre>
 * 1. INSERT reconciliation_runs (status RUNNING)                  -- own short transaction
 * 2. BEGIN ISOLATION LEVEL REPEATABLE READ;                       -- one snapshot for every check
 *      SELECT ... FROM job_locks WHERE job_name = 'reconciliation' FOR UPDATE NOWAIT;  -- busy? -> SKIPPED
 *      check 1..5  (plain SQL, see the constants below)
 *      INSERT reconciliation_issues ...;  UPDATE reconciliation_runs ...;
 *    COMMIT;
 * 3. purge idempotency keys older than 24h                        -- own transaction
 * 4. LLM explanations for today's risk flags                      -- no transaction held during model calls
 * </pre>
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    public static final String JOB = "reconciliation";

    // ---- the five checks, each one SQL query -----------------------------------------------------------

    /** 1. Everything balances: total debits = total credits. */
    static final String CHECK_LEDGER_BALANCED = """
            SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0)  AS debits,
                   COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0) AS credits
              FROM ledger_entries
            """;

    /** 2. Every transfer balances. */
    static final String CHECK_TRANSFERS_BALANCED = """
            SELECT transfer_id,
                   COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0)  AS debits,
                   COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0) AS credits
              FROM ledger_entries
             GROUP BY transfer_id
            HAVING COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0)
                <> COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
             ORDER BY transfer_id
            """;

    /** 3. Stored balances match the entries. */
    static final String CHECK_STORED_BALANCES = """
            SELECT a.id, a.balance AS stored, COALESCE(c.computed, 0) AS computed
              FROM accounts a
              LEFT JOIN (SELECT account_id,
                                SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END) AS computed
                           FROM ledger_entries
                          GROUP BY account_id) c ON c.account_id = a.id
             WHERE a.balance <> COALESCE(c.computed, 0)
             ORDER BY a.id
            """;

    /** 4. No user wallet's computed balance is below zero. */
    static final String CHECK_NO_OVERDRAFT = """
            SELECT a.id, SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END) AS computed
              FROM accounts a
              JOIN ledger_entries e ON e.account_id = a.id
             WHERE a.kind = 'USER_WALLET'
             GROUP BY a.id
            HAVING SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END) < 0
             ORDER BY a.id
            """;

    /** 5a. Transfers that moved money must have entries; held/rejected ones must not. */
    static final String CHECK_TRANSFERS_WITHOUT_ENTRIES = """
            SELECT t.id, t.status
              FROM transfers t
             WHERE t.status IN ('COMPLETED', 'REVERSED')
               AND NOT EXISTS (SELECT 1 FROM ledger_entries e WHERE e.transfer_id = t.id)
             ORDER BY t.id
            """;

    static final String CHECK_UNSETTLED_WITH_ENTRIES = """
            SELECT t.id, t.status, count(e.id) AS entries
              FROM transfers t
              JOIN ledger_entries e ON e.transfer_id = t.id
             WHERE t.status IN ('HELD', 'REJECTED')
             GROUP BY t.id, t.status
             ORDER BY t.id
            """;

    /** 5b. Every entry has a transfer (the FK already guarantees it; checked anyway, the job trusts nothing). */
    static final String CHECK_ENTRIES_WITHOUT_TRANSFER = """
            SELECT e.id, e.transfer_id
              FROM ledger_entries e
              LEFT JOIN transfers t ON t.id = e.transfer_id
             WHERE t.id IS NULL
             ORDER BY e.id
            """;

    public record Issue(String check, String entityType, Long entityId, Long expected, Long actual, String details) {
    }

    public record RunOutcome(long runId, String status, int issues) {
    }

    private static class AlreadyRunning extends RuntimeException {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate repeatableRead;
    private final TransactionTemplate readCommitted;
    private final RiskExplainer explainer;

    public ReconciliationService(JdbcTemplate jdbc, PlatformTransactionManager txManager, RiskExplainer explainer) {
        this.jdbc = jdbc;
        this.repeatableRead = new TransactionTemplate(txManager);
        this.repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.readCommitted = new TransactionTemplate(txManager);
        this.readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.explainer = explainer;
    }

    public long createRun(String triggeredBy) {
        return readCommitted.execute(s -> jdbc.queryForObject(
                "INSERT INTO reconciliation_runs (triggered_by, status) VALUES (?, 'RUNNING') RETURNING id",
                Long.class, triggeredBy));
    }

    public RunOutcome runNow(String triggeredBy) {
        return execute(createRun(triggeredBy));
    }

    public RunOutcome execute(long runId) {
        RunOutcome outcome;
        try {
            outcome = repeatableRead.execute(s -> checkAndRecord(runId));
        } catch (AlreadyRunning e) {
            readCommitted.executeWithoutResult(s -> jdbc.update("""
                    UPDATE reconciliation_runs
                       SET status = 'SKIPPED', finished_at = clock_timestamp(),
                           error = 'another reconciliation run holds the job lock'
                     WHERE id = ?
                    """, runId));
            log.info("Reconciliation run {} skipped: another run is in progress", runId);
            return new RunOutcome(runId, "SKIPPED", 0);
        } catch (RuntimeException e) {
            log.error("Reconciliation run {} failed", runId, e);
            readCommitted.executeWithoutResult(s -> jdbc.update("""
                    UPDATE reconciliation_runs SET status = 'ERROR', finished_at = clock_timestamp(), error = ?
                     WHERE id = ?
                    """, String.valueOf(e.getMessage()), runId));
            return new RunOutcome(runId, "ERROR", 0);
        }

        // Housekeeping + LLM step run after the checks commit, so the lock and snapshot are not held
        // across network calls.
        int purged = readCommitted.execute(s -> jdbc.update(
                "DELETE FROM idempotency_keys WHERE created_at < now() - interval '24 hours'"));
        int explained = explainer.explainTodaysFlags();
        readCommitted.executeWithoutResult(s -> jdbc.update(
                "UPDATE reconciliation_runs SET keys_purged = ?, flags_explained = ? WHERE id = ?",
                purged, explained, runId));
        return outcome;
    }

    private RunOutcome checkAndRecord(long runId) {
        // Only one run at a time. NOWAIT: if someone else holds the row, fail immediately instead of queueing.
        try {
            jdbc.queryForObject("SELECT id FROM job_locks WHERE job_name = ? FOR UPDATE NOWAIT", Long.class, JOB);
        } catch (DataAccessException e) {
            if (isLockNotAvailable(e)) {
                throw new AlreadyRunning();
            }
            throw e;
        }
        jdbc.update("UPDATE job_locks SET last_started_at = clock_timestamp() WHERE job_name = ?", JOB);

        List<Issue> issues = runChecks();

        for (Issue i : issues) {
            jdbc.update("""
                    INSERT INTO reconciliation_issues (run_id, check_name, entity_type, entity_id, expected, actual, details)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, runId, i.check(), i.entityType(), i.entityId(), i.expected(), i.actual(), i.details());
        }
        Map<String, Object> counts = jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM accounts)       AS accounts,
                       (SELECT count(*) FROM transfers)      AS transfers,
                       (SELECT count(*) FROM ledger_entries) AS entries
                """);
        String status = issues.isEmpty() ? "PASSED" : "FAILED";
        jdbc.update("""
                UPDATE reconciliation_runs
                   SET status = ?, finished_at = clock_timestamp(), checks_run = 7, issues_found = ?,
                       accounts_checked = ?, transfers_checked = ?, entries_checked = ?
                 WHERE id = ?
                """, status, issues.size(), counts.get("accounts"), counts.get("transfers"), counts.get("entries"),
                runId);
        jdbc.update("UPDATE job_locks SET last_finished_at = clock_timestamp() WHERE job_name = ?", JOB);
        log.info("Reconciliation run {} {} with {} issue(s)", runId, status, issues.size());
        return new RunOutcome(runId, status, issues.size());
    }

    List<Issue> runChecks() {
        List<Issue> issues = new ArrayList<>();

        Map<String, Object> totals = jdbc.queryForMap(CHECK_LEDGER_BALANCED);
        long debits = ((Number) totals.get("debits")).longValue();
        long credits = ((Number) totals.get("credits")).longValue();
        if (debits != credits) {
            issues.add(new Issue("LEDGER_BALANCED", "LEDGER", null, debits, credits,
                    "total debits (expected) differ from total credits (actual)"));
        }

        jdbc.query(CHECK_TRANSFERS_BALANCED, rs -> {
            issues.add(new Issue("TRANSFER_BALANCED", "TRANSFER", rs.getLong("transfer_id"),
                    rs.getLong("debits"), rs.getLong("credits"), "transfer debits (expected) != credits (actual)"));
        });

        jdbc.query(CHECK_STORED_BALANCES, rs -> {
            issues.add(new Issue("STORED_BALANCE", "ACCOUNT", rs.getLong("id"),
                    rs.getLong("computed"), rs.getLong("stored"), "stored balance (actual) != sum of entries (expected)"));
        });

        jdbc.query(CHECK_NO_OVERDRAFT, rs -> {
            issues.add(new Issue("NO_OVERDRAFT", "ACCOUNT", rs.getLong("id"),
                    0L, rs.getLong("computed"), "user wallet computed balance is negative"));
        });

        jdbc.query(CHECK_TRANSFERS_WITHOUT_ENTRIES, rs -> {
            issues.add(new Issue("ORPHAN_TRANSFER", "TRANSFER", rs.getLong("id"),
                    2L, 0L, rs.getString("status") + " transfer has no ledger entries"));
        });

        jdbc.query(CHECK_UNSETTLED_WITH_ENTRIES, rs -> {
            issues.add(new Issue("UNSETTLED_WITH_ENTRIES", "TRANSFER", rs.getLong("id"),
                    0L, rs.getLong("entries"), rs.getString("status") + " transfer must not have ledger entries"));
        });

        jdbc.query(CHECK_ENTRIES_WITHOUT_TRANSFER, rs -> {
            issues.add(new Issue("ORPHAN_ENTRY", "LEDGER_ENTRY", rs.getLong("id"),
                    null, null, "entry points to missing transfer " + rs.getLong("transfer_id")));
        });
        return issues;
    }

    private static boolean isLockNotAvailable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            // 55P03 = lock held by another run (NOWAIT). 40001 = under REPEATABLE READ, another run updated the
            // lock row after our snapshot began; it just finished, so skipping is also correct.
            if (t instanceof java.sql.SQLException sql
                    && ("55P03".equals(sql.getSQLState()) || "40001".equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }
}
