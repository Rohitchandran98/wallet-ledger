# Isolation levels — the two-session lab

Milestone 2, step 1. Each case uses **two separate database sessions** (A and B) against PostgreSQL 16, on the
real `accounts` table of this project. B runs in autocommit mode (each statement commits immediately) unless it
opens its own transaction.

The transcript below is real output. It was captured by `IsolationLabTest`, which opens two JDBC connections,
sends exactly these statements in this order, records each result and **asserts** the behaviour, so the lab
re-runs in CI on every push. To reproduce by hand, open two terminals with
`docker compose exec postgres psql -U wallet wallet` and paste the `A=>` lines in one and the `B=>` lines in
the other, in the order shown. (Ids differ on your machine; pick any wallet with `SELECT id FROM accounts`.)

| Case | Level | What A sees after B commits | Outcome |
|---|---|---|---|
| 1 | READ COMMITTED | B's new value (non-repeatable read) | both succeed |
| 2 | REPEATABLE READ | its old snapshot | A's later write to the same row fails with `40001` |
| 3a | SERIALIZABLE | its old snapshot | second writer fails with `40001` and must retry |
| 3b | RR vs SERIALIZABLE | — | write skew succeeds under RR, aborted under SERIALIZABLE |
| 4 | REPEATABLE READ + 3-table join | identical row | guarantee holds across joins |


### Case 1 - READ COMMITTED (Postgres default): non-repeatable read

```
A=> BEGIN ISOLATION LEVEL READ COMMITTED;
BEGIN
A=> SELECT balance FROM accounts WHERE id = 4;
 balance
 50000
B=> UPDATE accounts SET balance = balance + 100 WHERE id = 4;
UPDATE 1
A=> SELECT balance FROM accounts WHERE id = 4;
 balance
 50100
A=> COMMIT;
COMMIT
```

> Session A read 50000 then 50100 inside ONE transaction: every statement takes a fresh snapshot, so A sees B's committed change.

### Case 2 - REPEATABLE READ: one snapshot per transaction

```
A=> BEGIN ISOLATION LEVEL REPEATABLE READ;
BEGIN
A=> SELECT balance FROM accounts WHERE id = 4;
 balance
 50000
B=> UPDATE accounts SET balance = balance + 100 WHERE id = 4;
UPDATE 1
A=> SELECT balance FROM accounts WHERE id = 4;
 balance
 50000
A=> UPDATE accounts SET balance = balance - 100 WHERE id = 4;
ERROR: could not serialize access due to concurrent update  (SQLSTATE 40001)
A=> ROLLBACK;
ROLLBACK
```

> A keeps seeing 50000 even after B committed. When A then tries to write the row B changed, Postgres refuses (40001) instead of letting A overwrite B's update.

### Case 3a - SERIALIZABLE: both sessions read a balance, then write based on it

```
A=> BEGIN ISOLATION LEVEL SERIALIZABLE;
BEGIN
B=> BEGIN ISOLATION LEVEL SERIALIZABLE;
BEGIN
A=> SELECT balance FROM accounts WHERE id = 4;
 balance
 50000
B=> SELECT balance FROM accounts WHERE id = 4;
 balance
 50000
A=> UPDATE accounts SET balance = 20000 WHERE id = 4;
UPDATE 1
A=> COMMIT;
COMMIT
B=> UPDATE accounts SET balance = 20000 WHERE id = 4;
ERROR: could not serialize access due to concurrent update  (SQLSTATE 40001)
B=> ROLLBACK;
ROLLBACK
```

> Both read 50000. A commits first; B's write is rejected with a serialization failure, so the client must retry B.

### Case 3b - write skew: allowed by REPEATABLE READ, caught by SERIALIZABLE


> Rule checked in code: a 60,000 withdrawal is allowed if the user's wallets TOGETHER hold >= 60,000. Each of two wallets holds 50,000, so only ONE such withdrawal may succeed.

```
A=> BEGIN ISOLATION LEVEL REPEATABLE READ;
BEGIN
B=> BEGIN ISOLATION LEVEL REPEATABLE READ;
BEGIN
A=> SELECT sum(balance) AS total FROM lab_wallets WHERE owner = 'asha';
 total
 100000
B=> SELECT sum(balance) AS total FROM lab_wallets WHERE owner = 'asha';
 total
 100000
A=> UPDATE lab_wallets SET balance = balance - 60000 WHERE id = 1;
UPDATE 1
B=> UPDATE lab_wallets SET balance = balance - 60000 WHERE id = 2;
UPDATE 1
A=> COMMIT;
COMMIT
B=> COMMIT;
COMMIT
```

> Under REPEATABLE READ BOTH commits succeed (they wrote different rows) and the user's total is now -20000. This is write skew: the snapshot guarantee does not stop it.

```
A=> BEGIN ISOLATION LEVEL SERIALIZABLE;
BEGIN
B=> BEGIN ISOLATION LEVEL SERIALIZABLE;
BEGIN
A=> SELECT sum(balance) AS total FROM lab_wallets WHERE owner = 'asha';
 total
 100000
B=> SELECT sum(balance) AS total FROM lab_wallets WHERE owner = 'asha';
 total
 100000
A=> UPDATE lab_wallets SET balance = balance - 60000 WHERE id = 1;
UPDATE 1
B=> UPDATE lab_wallets SET balance = balance - 60000 WHERE id = 2;
UPDATE 1
A=> COMMIT;
COMMIT
B=> COMMIT;
ERROR: could not serialize access due to read/write dependencies among transactions  (SQLSTATE 40001)
```

> Under SERIALIZABLE the second COMMIT fails with 40001: Postgres tracked the read/write dependency and aborted one transaction. Final total 40000.

### Case 4 - REPEATABLE READ with a join across three tables

```
A=> BEGIN ISOLATION LEVEL REPEATABLE READ;
BEGIN
A=> SELECT u.name, a.balance, count(e.id) AS entries FROM accounts a JOIN users u ON u.id = a.user_id LEFT JOIN ledger_entries e ON e.account_id = a.id WHERE a.id = 4 GROUP BY u.name, a.balance;
 name | balance | entries
 Asha | 50000 | 1
B=> UPDATE users SET name = 'Asha Renamed' WHERE id = 1;
UPDATE 1
B=> UPDATE accounts SET balance = balance + 500 WHERE id = 4;
UPDATE 1
B=> WITH t AS (INSERT INTO transfers (type, status, amount, from_account_id, to_account_id, initiated_by) VALUES ('PEER', 'COMPLETED', 500, 5, 4, 'lab') RETURNING id) INSERT INTO ledger_entries (transfer_id, account_id, direction, amount) SELECT id, 5, 'DEBIT', 500 FROM t UNION ALL SELECT id, 4, 'CREDIT', 500 FROM t;
WITH
A=> SELECT u.name, a.balance, count(e.id) AS entries FROM accounts a JOIN users u ON u.id = a.user_id LEFT JOIN ledger_entries e ON e.account_id = a.id WHERE a.id = 4 GROUP BY u.name, a.balance;
 name | balance | entries
 Asha | 50000 | 1
A=> COMMIT;
COMMIT
```

> B changed all three joined tables and committed; A's join returns the identical row. The snapshot covers the whole database, not one table, so the guarantee holds for joins.



## What this means for the transfer code

* **Read Committed alone is not enough** for a read-check-write transfer: case 1 shows a value can change
  between two statements of the same transaction. That is exactly how the naive transfer in
  [concurrency.md](concurrency.md) over-spends.
* **Repeatable Read** would turn the lost update into an error (case 2), but it still allows write skew
  (case 3b) and forces the client to retry.
* **Serializable** is the most automatic: it catches everything here, but every conflict becomes a `40001`
  that the application must retry, and under a 100-request stampede on one wallet most transactions would
  abort and retry repeatedly.

The transfer path in this project therefore runs at **READ COMMITTED + explicit row locks**
(`SELECT ... FOR UPDATE` on both accounts in ascending id order). The lock makes the "check" and the "act"
happen on a row nobody else can change, so the balance re-read after locking is the truth. Conflicting
transfers wait rather than fail, nothing has to be retried, and the result is deterministic: the stampede test
gets exactly 50/100 successes 20 runs out of 20. The full argument is in the README ("Isolation level").

The nightly reconciliation job uses **REPEATABLE READ** on purpose: all of its checks must see one consistent
snapshot, even while transfers keep committing (case 4 shows the snapshot covers every table the checks join).
