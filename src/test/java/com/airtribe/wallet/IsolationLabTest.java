package com.airtribe.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.airtribe.wallet.support.IntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Milestone 2, step 1: the two-session isolation lab, automated. Two real connections (session A and B)
 * run the exact statements from docs/isolation.md; the transcript is written to target/isolation-lab.md
 * and the observed behaviour is asserted.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IsolationLabTest extends IntegrationTest {

    private static final List<String> transcript = new ArrayList<>();

    @Autowired
    DataSource dataSource;

    long wallet;
    long savings;
    long user;

    @BeforeEach
    void wallets() {
        user = createUser("Asha");
        wallet = openWallet(user);
        savings = openWallet(user);
        topUp(wallet, 50_000);
        topUp(savings, 50_000);
    }

    @AfterAll
    static void writeTranscript() throws Exception {
        Files.createDirectories(Path.of("target"));
        String text = String.join("\n", transcript)
                .replace("```\n```\n", "")       // empty code blocks between consecutive notes
                .replaceAll("```\n*$", "")       // dangling fence after the last note
                .replace("```\n\n### ", "### "); // fence reopened by a note right before a heading
        Files.writeString(Path.of("target/isolation-lab.md"), text + "\n");
    }

    // ------------------------------------------------------------------ case 1

    @Test
    @Order(1)
    void readCommittedSeesOtherCommitsBetweenStatements() throws Exception {
        heading("Case 1 - READ COMMITTED (Postgres default): non-repeatable read");
        try (Session a = new Session("A"); Session b = new Session("B")) {
            a.run("BEGIN ISOLATION LEVEL READ COMMITTED");
            long first = a.queryLong("SELECT balance FROM accounts WHERE id = " + wallet);
            b.run("UPDATE accounts SET balance = balance + 100 WHERE id = " + wallet);
            long second = a.queryLong("SELECT balance FROM accounts WHERE id = " + wallet);
            a.run("COMMIT");
            assertThat(second).isEqualTo(first + 100);
            note("Session A read " + first + " then " + second + " inside ONE transaction: every statement takes a "
                    + "fresh snapshot, so A sees B's committed change.");
        }
    }

    // ------------------------------------------------------------------ case 2

    @Test
    @Order(2)
    void repeatableReadKeepsOneSnapshotAndRejectsALostUpdate() throws Exception {
        heading("Case 2 - REPEATABLE READ: one snapshot per transaction");
        try (Session a = new Session("A"); Session b = new Session("B")) {
            a.run("BEGIN ISOLATION LEVEL REPEATABLE READ");
            long first = a.queryLong("SELECT balance FROM accounts WHERE id = " + wallet);
            b.run("UPDATE accounts SET balance = balance + 100 WHERE id = " + wallet);
            long second = a.queryLong("SELECT balance FROM accounts WHERE id = " + wallet);
            assertThat(second).isEqualTo(first);
            String error = a.runExpectingError("UPDATE accounts SET balance = balance - 100 WHERE id = " + wallet);
            a.run("ROLLBACK");
            assertThat(error).contains("40001");
            note("A keeps seeing " + first + " even after B committed. When A then tries to write the row B changed, "
                    + "Postgres refuses (40001) instead of letting A overwrite B's update.");
        }
    }

    // ------------------------------------------------------------------ case 3

    @Test
    @Order(3)
    void serializableAbortsOneOfTwoReadThenWriteTransactions() throws Exception {
        heading("Case 3a - SERIALIZABLE: both sessions read a balance, then write based on it");
        try (Session a = new Session("A"); Session b = new Session("B")) {
            a.run("BEGIN ISOLATION LEVEL SERIALIZABLE");
            b.run("BEGIN ISOLATION LEVEL SERIALIZABLE");
            long ra = a.queryLong("SELECT balance FROM accounts WHERE id = " + wallet);
            long rb = b.queryLong("SELECT balance FROM accounts WHERE id = " + wallet);
            a.run("UPDATE accounts SET balance = " + (ra - 30_000) + " WHERE id = " + wallet);
            a.run("COMMIT");
            String error = b.runExpectingError("UPDATE accounts SET balance = " + (rb - 30_000) + " WHERE id = " + wallet);
            b.run("ROLLBACK");
            assertThat(error).contains("40001");
            note("Both read " + ra + ". A commits first; B's write is rejected with a serialization failure, so the "
                    + "client must retry B.");
        }

        heading("Case 3b - write skew: allowed by REPEATABLE READ, caught by SERIALIZABLE");
        note("Rule checked in code: a 60,000 withdrawal is allowed if the user's wallets TOGETHER hold >= 60,000. "
                + "Each of two wallets holds 50,000, so only ONE such withdrawal may succeed.");
        assertThat(writeSkew("REPEATABLE READ")).isNull();
        assertThat(writeSkew("SERIALIZABLE")).contains("40001");
    }

    /**
     * Uses a scratch table without the wallet CHECK constraint, so the anomaly is visible instead of being
     * stopped by the constraint.
     */
    private String writeSkew(String level) throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS lab_wallets");
        jdbc.execute("CREATE TABLE lab_wallets (id INT PRIMARY KEY, owner TEXT NOT NULL, balance BIGINT NOT NULL)");
        jdbc.execute("INSERT INTO lab_wallets VALUES (1, 'asha', 50000), (2, 'asha', 50000)");
        try (Session a = new Session("A"); Session b = new Session("B")) {
            String total = "SELECT sum(balance) AS total FROM lab_wallets WHERE owner = 'asha'";
            a.run("BEGIN ISOLATION LEVEL " + level);
            b.run("BEGIN ISOLATION LEVEL " + level);
            a.queryLong(total);   // 100000 >= 60000, OK in code
            b.queryLong(total);   // 100000 >= 60000, OK in code
            a.run("UPDATE lab_wallets SET balance = balance - 60000 WHERE id = 1");
            b.run("UPDATE lab_wallets SET balance = balance - 60000 WHERE id = 2");
            a.run("COMMIT");
            String error = b.runExpectingErrorOrNull("COMMIT");
            long finalTotal = jdbc.queryForObject("SELECT sum(balance) FROM lab_wallets", Long.class);
            if (error == null) {
                note("Under " + level + " BOTH commits succeed (they wrote different rows) and the user's total is now "
                        + finalTotal + ". This is write skew: the snapshot guarantee does not stop it.");
            } else {
                note("Under " + level + " the second COMMIT fails with 40001: Postgres tracked the read/write "
                        + "dependency and aborted one transaction. Final total " + finalTotal + ".");
            }
            return error;
        }
    }

    // ------------------------------------------------------------------ case 4

    @Test
    @Order(4)
    void repeatableReadHoldsAcrossJoins() throws Exception {
        heading("Case 4 - REPEATABLE READ with a join across three tables");
        String join = "SELECT u.name, a.balance, count(e.id) AS entries FROM accounts a "
                + "JOIN users u ON u.id = a.user_id LEFT JOIN ledger_entries e ON e.account_id = a.id "
                + "WHERE a.id = " + wallet + " GROUP BY u.name, a.balance";
        try (Session a = new Session("A"); Session b = new Session("B")) {
            a.run("BEGIN ISOLATION LEVEL REPEATABLE READ");
            String before = a.queryRow(join);
            b.run("UPDATE users SET name = 'Asha Renamed' WHERE id = " + user);
            b.run("UPDATE accounts SET balance = balance + 500 WHERE id = " + wallet);
            b.run("WITH t AS (INSERT INTO transfers (type, status, amount, from_account_id, to_account_id, initiated_by) "
                    + "VALUES ('PEER', 'COMPLETED', 500, " + savings + ", " + wallet + ", 'lab') RETURNING id) "
                    + "INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) "
                    + "SELECT id, " + savings + ", 'DEBIT', 500 FROM t UNION ALL SELECT id, " + wallet
                    + ", 'CREDIT', 500 FROM t");
            String after = a.queryRow(join);
            a.run("COMMIT");
            assertThat(after).isEqualTo(before);
            note("B changed all three joined tables and committed; A's join returns the identical row. The snapshot "
                    + "covers the whole database, not one table, so the guarantee holds for joins.");
        }
    }

    // ------------------------------------------------------------------ tiny psql-like session

    private void heading(String text) {
        transcript.add("");
        transcript.add("### " + text);
        transcript.add("");
        transcript.add("```");
    }

    private void note(String text) {
        transcript.add("```");
        transcript.add("");
        transcript.add("> " + text);
        transcript.add("");
        transcript.add("```");
    }

    private final class Session implements AutoCloseable {
        private final String name;
        private final Connection conn;

        Session(String name) throws SQLException {
            this.name = name;
            this.conn = dataSource.getConnection();
            this.conn.setAutoCommit(true); // BEGIN/COMMIT are sent explicitly, exactly as in psql
        }

        void run(String sql) throws SQLException {
            transcript.add(name + "=> " + sql + ";");
            try (Statement st = conn.createStatement()) {
                boolean rs = st.execute(sql);
                if (!rs) {
                    String tag = sql.split("\\s+")[0].toUpperCase();
                    transcript.add(tag.equals("UPDATE") || tag.equals("INSERT")
                            ? tag + (tag.equals("INSERT") ? " 0 " : " ") + st.getUpdateCount() : tag);
                }
            }
        }

        String runExpectingError(String sql) throws SQLException {
            String e = runExpectingErrorOrNull(sql);
            if (e == null) {
                throw new AssertionError("expected an error from: " + sql);
            }
            return e;
        }

        String runExpectingErrorOrNull(String sql) throws SQLException {
            try {
                run(sql);
                return null;
            } catch (SQLException e) {
                String msg = e.getMessage().split("\n")[0] + "  (SQLSTATE " + e.getSQLState() + ")";
                transcript.add(msg);
                return msg;
            }
        }

        long queryLong(String sql) throws SQLException {
            transcript.add(name + "=> " + sql + ";");
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                long v = rs.getLong(1);
                transcript.add(" " + rs.getMetaData().getColumnLabel(1));
                transcript.add(" " + v);
                return v;
            }
        }

        String queryRow(String sql) throws SQLException {
            transcript.add(name + "=> " + sql + ";");
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                ResultSetMetaData md = rs.getMetaData();
                StringBuilder head = new StringBuilder(" ");
                StringBuilder row = new StringBuilder(" ");
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    head.append(i > 1 ? " | " : "").append(md.getColumnLabel(i));
                    row.append(i > 1 ? " | " : "").append(rs.getString(i));
                }
                transcript.add(head.toString());
                transcript.add(row.toString());
                return row.toString();
            }
        }

        @Override
        public void close() throws SQLException {
            if (!conn.getAutoCommit()) {
                conn.rollback();
            }
            try (Statement st = conn.createStatement()) {
                st.execute("ROLLBACK"); // no-op if idle; ends any transaction a failed assertion left open
            } catch (SQLException ignored) {
                // nothing open
            }
            conn.close();
        }
    }
}
