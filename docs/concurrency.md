# Transfers under load: break it, then fix it

Milestone 2, steps 2 and 3.

## Step 2: the naive transfer

[`NaiveTransferRaceTest`](../src/test/java/com/airtribe/wallet/NaiveTransferRaceTest.java) implements the textbook
unsafe transfer: read the balance, check it in Java, then write the new balance. It runs inside a transaction at
the default READ COMMITTED level, but takes no lock:

```sql
SELECT balance FROM accounts WHERE id = :from;              -- 50000
-- if (balance < amount) reject      ... a few ms pass ...
INSERT INTO transfers ...; INSERT INTO ledger_entries ...;
UPDATE accounts SET balance = :balance - :amount WHERE id = :from;   -- absolute write from a stale read
UPDATE accounts SET balance = balance + :amount WHERE id = :to;
```

Result of firing **100 parallel ₹10 transfers at a wallet holding ₹500**, from a real run (the test asserts
that the bug appears):

```
NAIVE RESULT: successes=100 (expected 50), payer stored=44000 computed-from-entries=-50000, payee=100000
```

| | Expected | Naive |
|---|---|---|
| Successful transfers | 50 | **100** |
| Money that left the ₹500 wallet | ₹500 | **₹1,000** (overdraft of ₹500) |
| Payer's stored balance | ₹0 | **₹440** (lost updates: 94 of the 100 balance writes were overwritten) |
| Payer's balance computed from entries | ₹0 | **−₹500** |
| Money created from nothing | ₹0 | ₹1,000 credited to the payee, ₹60 debited from the stored balance |

Every request read `50000` (or a slightly smaller stale value) before any of the others wrote, so every check
passed. Then each wrote "its" new balance over the others'. Note that the `CHECK (balance >= 0)` constraint
**did not fire**: every absolute write was a positive number. A constraint catches impossible states, not wrong
arithmetic. Only the double-entry ledger, which the reconciliation job compares with the stored balance, shows
that the books are now wrong.

## Step 3: the fix, row locks in a fixed order

[`TransferService`](../src/main/java/com/airtribe/wallet/transfer/TransferService.java), one transaction opened
by `IdempotencyService`:

```sql
BEGIN ISOLATION LEVEL READ COMMITTED;
  INSERT INTO idempotency_keys ...;                              -- claim the retry key
  SELECT ... FROM accounts WHERE id = LEAST(:a, :b)    FOR UPDATE;  -- lock in ascending id order
  SELECT ... FROM accounts WHERE id = GREATEST(:a, :b) FOR UPDATE;
  -- re-read balance under the lock; reject if insufficient (no other writer can interleave now)
  -- fraud rules (queries; the payer lock also serialises the velocity count)
  INSERT INTO transfers ...;
  INSERT INTO ledger_entries ... ('DEBIT'), ... ('CREDIT');
  UPDATE accounts SET balance = balance - :amount WHERE id = :from;   -- relative updates
  UPDATE accounts SET balance = balance + :amount WHERE id = :to;
  UPDATE idempotency_keys SET status = 'COMPLETED', response_code = ..., response_body = ...;
COMMIT;   -- deferred trigger verifies debits = credits for the transfer
```

* **Why ascending id order?** Transfer X locks Asha then Rahul while transfer Y locks Rahul then Asha: each waits
  for the other forever (Postgres detects the cycle and kills one with a deadlock error). With one global order
  (lowest id first, and a transfer row before any account row for reversals/releases) a cycle cannot form.
  The lock helper sorts the ids itself, so callers cannot get the order wrong.
* **Why re-read after locking?** The balance read before the lock is a guess. The one read while holding
  `FOR UPDATE` is the truth until we commit.
* **Rollback.** Anything that fails before `COMMIT` (a constraint, the balance trigger, a crash) rolls back the
  transfer, its entries, both balance updates and the idempotency key together. A business rejection
  (insufficient funds) rolls back to a savepoint, so no money moves, but the error response is still saved
  for replays.

### Acceptance: the stampede

[`StampedeTest`](../src/test/java/com/airtribe/wallet/StampedeTest.java) puts ₹500 in one wallet and fires 100
concurrent ₹10 transfers over real HTTP. JUnit `@RepeatedTest(20)` runs it **20 times in a row**. In every run:

* exactly **50** transfers return `201`, and the other 50 return `422 INSUFFICIENT_FUNDS`
* the wallet ends at **₹0**, both as stored and as computed from its entries
* replaying the wallet's entries in id order, the running balance **never goes below zero**
* total debits = total credits across the whole ledger

```
Tests run: 20, Failures: 0, Errors: 0, Skipped: 0 -- in com.airtribe.wallet.StampedeTest
```
