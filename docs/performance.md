# Query plans and performance

All numbers come from [`PerformanceReportTest`](../src/test/java/com/airtribe/wallet/PerformanceReportTest.java)
on PostgreSQL 16.15 (embedded, laptop) with **1,040,000 ledger entries**, 520,000 transfers and 2,003 accounts,
including one busy wallet that has 120,416 entries. The raw `EXPLAIN (ANALYZE, BUFFERS)` output for every query
is in [`perf/explain-output.md`](perf/explain-output.md). To regenerate it:

```bash
./mvnw test -Dtest.excludedGroups= -Dtest=PerformanceReportTest
```

## Reconciliation checks (Milestone 4)

| Check | Plan | Time | Needs an index? |
|---|---|---|---|
| 1. Total debits = total credits | Parallel Seq Scan + aggregate | 123 ms | **No.** It must read every entry by definition; an index cannot help a SUM over the whole table. |
| 2. Every transfer balances (`GROUP BY transfer_id HAVING ...`) | Index Scan on `ledger_entries_transfer_idx` feeding a GroupAggregate | 884 ms | **No new index.** It reads every entry anyway. The planner used the existing `transfer_id` index (already needed by `GET /transfers/{id}` and the commit-time balance trigger) to get rows pre-sorted. A seq scan + HashAggregate would do the same job; at this size either is well under a second. |
| 3. Stored balance = computed balance | Parallel Seq Scan of entries, hash join to `accounts` | 127 ms | **No.** It aggregates every entry per account. |
| 4. No negative wallet | Parallel Seq Scan + aggregate per account | 195 ms | **No**, for the same reason. |
| 5a. Completed transfers without entries | Parallel Seq Scan of transfers, anti-join to entries | 410 ms | **No.** It is a full anti-join. The `transfer_id` index would allow a nested-loop anti-join if it ever needed to be incremental. |
| 5b. Held/rejected transfers with entries | Seq Scan of transfers (filter on status) | 61 ms | **Optional.** The partial index `transfers_held_idx` covers HELD rows; REJECTED is rare. Not worth a general `status` index on a hot table. |
| 5c. Entries without a transfer | Parallel Seq Scan + hash anti-join | 332 ms | **No.** The foreign key already makes this impossible; the job checks anyway, as a full scan. |

**Total: about 2.1 s for all seven checks over 1M entries,** inside one REPEATABLE READ snapshot. It is a
nightly job, so full table scans are the right tool: each check has to look at every row, and sequential reads
(parallelised by Postgres across 3 workers) are the cheapest way to do that. Adding indexes such as
`(direction)`, `(account_id, direction, amount)` or `transfers (status)` to speed these up would add an index
insertion to **every payment**, all day, to save a second or two once a night. That trade-off is wrong, so none
were added.

What does need an index is the **online** traffic below, because it touches a handful of rows thousands of times
a day.

When the ledger grows 100x, the plan is to partition `ledger_entries` by month and reconcile closed months
once, keeping a per-account "checkpoint balance". See the README's "What I'd do next".

## Statement pagination: OFFSET vs cursor

Busy wallet with 120,416 entries, page size 50:

| Query | Plan | Time |
|---|---|---|
| Page 1: `ORDER BY created_at DESC, id DESC LIMIT 50 OFFSET 0` | Index Scan on `ledger_entries_account_created_idx`, stops after 50 rows | **0.09 ms** |
| Deep page: `... LIMIT 50 OFFSET 100000` | Bitmap Heap Scan of all 120,416 rows, then **Sort, external merge, 5.9 MB on disk**, discard 100,000 | **146 ms** |
| Same rows via cursor: `WHERE (created_at, id) < (:c, :id) ORDER BY ... LIMIT 50` | Index Scan on the same index, starts at the cursor, reads 50 rows | **0.09 ms** |

**Why OFFSET gets slow.** `OFFSET n` does not skip rows. Postgres has to produce rows 1..n in order and throw
them away; there is no way to jump to the 100,000th entry of an index. The cost grows linearly with the page
number. In this run the planner decided that reading 100,050 rows in index order would cost more than
fetching all 120k rows and sorting them, so it chose a bitmap scan plus a full sort that spilled to disk. Either
way the work is proportional to the offset, and on a busy wallet the deep pages hit the database hardest. Rows
inserted between two requests also shift every later page, so a user scrolling while money arrives sees
duplicates or misses entries.

**Cursor (keyset) pagination** remembers the `(created_at, id)` of the last row shown. The next page is a range
condition on exactly the index's sort key, so Postgres seeks straight to it and reads 50 rows: constant time on
every page (about 1,600x faster here), and stable while new entries arrive. `id` breaks ties between entries
with the same timestamp.

The API supports both: `GET /wallets/{id}/statement?limit=50&offset=100` (the required form) and
`?limit=50&cursor=<next_cursor>` (every response includes `next_cursor`). `TransferFlowTest` checks that the
two return identical pages.

## Fraud rules (Milestone 5A)

Each rule is one query inside the transfer transaction, measured on the wallet with the most outgoing transfers:

| Rule | Action | Index that keeps it fast | Plan | Query time |
|---|---|---|---|---|
| Velocity (> 5 outgoing in 10 min) | HOLD | `transfers_from_created_idx (from_account_id, created_at)` | Index Scan, range on the last 10 minutes only | 0.05 ms |
| Amount spike (> 5x 30-day average) | HOLD | same index; `INCLUDE (amount, status)` lets it avoid most heap visits | Bitmap Index Scan over ~150 rows | 1.4 ms |
| New payee, large amount (first payment > ₹10,000) | FLAG | `transfers_from_to_idx (from_account_id, to_account_id)` | Index Scan, stops at the first match | 0.09 ms |

**Measured time added per transfer:** six alternating rounds of 100 sequential transfers over HTTP were 4.97 ms
average with the rules off and 5.68 ms with all three rules evaluated, so the rules add **about 0.7 ms per
transfer**. Rules read thresholds from `risk_rules` on every transfer (one tiny indexed read). Caching them for a
few seconds would remove that, but it would make rule changes take effect late, so I didn't do it.
