-- =====================================================================
-- Demo / load seed.
--   psql -f scripts/seed.sql -c "CALL seed_wallet_data(1000, 2000, 100000);"
-- creates 1,000 users, 2,000 wallets and 100,000 transfers in total
-- (one top-up per wallet + peer transfers spread over the last 60 days),
-- with balanced ledger entries and matching stored balances.
-- Safe to run more than once (emails/phones are offset by existing rows).
-- =====================================================================
CREATE OR REPLACE PROCEDURE seed_wallet_data(p_users INT, p_wallets INT, p_transfers INT)
LANGUAGE plpgsql AS $$
DECLARE
    v_cash_in      BIGINT;
    v_user_offset  BIGINT;
    v_max_user     BIGINT;
    v_max_account  BIGINT;
    v_max_transfer BIGINT;
    v_max_entry    BIGINT;
    v_peers        INT;
    v_topup        BIGINT;
BEGIN
    IF p_wallets < 2 OR p_users < 1 OR p_transfers < p_wallets THEN
        RAISE EXCEPTION 'need p_users >= 1, p_wallets >= 2 and p_transfers >= p_wallets';
    END IF;

    PERFORM set_config('app.actor', 'seed', true);
    SELECT id INTO v_cash_in FROM accounts WHERE system_code = 'CASH_IN';
    SELECT COALESCE(max(id), 0) INTO v_max_user FROM users;
    SELECT COALESCE(max(id), 0) INTO v_max_account FROM accounts;
    SELECT COALESCE(max(id), 0) INTO v_max_transfer FROM transfers;
    SELECT COALESCE(max(id), 0) INTO v_max_entry FROM ledger_entries;
    SELECT count(*) INTO v_user_offset FROM users;

    -- The seed writes pairs that balance by construction and reconciliation verifies them afterwards,
    -- so the per-row commit-time balance trigger is switched off for speed (owner-only DDL).
    ALTER TABLE ledger_entries DISABLE TRIGGER ledger_entries_balanced;

    -- users
    INSERT INTO users (name, email, phone, kyc_status)
    SELECT (ARRAY['Asha','Rahul','Priya','Vikram','Neha','Arjun','Kavya','Rohan','Isha','Dev'])[1 + (g % 10)]
               || ' ' || (ARRAY['Sharma','Iyer','Khan','Reddy','Das','Mehta','Nair','Gupta'])[1 + (g % 8)]
               || ' ' || (v_user_offset + g),
           'seed.user' || (v_user_offset + g) || '@example.com',
           '+91' || (7000000000 + v_user_offset + g),
           CASE WHEN g % 20 = 0 THEN 'PENDING' ELSE 'VERIFIED' END
      FROM generate_series(1, p_users) g;

    -- wallets, round-robin over the new users (so many users have 2+ wallets)
    CREATE TEMP TABLE seed_users ON COMMIT DROP AS
        SELECT id, row_number() OVER (ORDER BY id) AS rn FROM users WHERE id > v_max_user;
    INSERT INTO accounts (user_id, kind, name)
    SELECT u.id, 'USER_WALLET', CASE WHEN g <= p_users THEN 'Main' ELSE 'Savings ' || g END
      FROM generate_series(1, p_wallets) g
      JOIN seed_users u ON u.rn = 1 + ((g - 1) % p_users);
    CREATE TEMP TABLE seed_wallets ON COMMIT DROP AS
        SELECT id, row_number() OVER (ORDER BY id) AS rn FROM accounts
         WHERE id > v_max_account AND kind = 'USER_WALLET';

    -- one top-up per wallet, 60 days ago; big enough that random peer traffic never overdraws
    v_peers := p_transfers - p_wallets;
    v_topup := GREATEST(5000000, (v_peers::BIGINT / p_wallets) * 60000);
    INSERT INTO transfers (type, status, amount, from_account_id, to_account_id, initiated_by, note,
                           created_at, updated_at)
    SELECT 'TOPUP', 'COMPLETED', v_topup, v_cash_in, w.id, 'seed', 'opening top-up',
           now() - interval '60 days', now() - interval '60 days'
      FROM seed_wallets w;

    -- random peer transfers between distinct wallets, ₹1 to ₹500, over the last 59 days
    INSERT INTO transfers (type, status, amount, from_account_id, to_account_id, initiated_by, note,
                           created_at, updated_at)
    SELECT 'PEER', 'COMPLETED', x.amount, fw.id, tw.id, 'seed', 'seed transfer', x.ts, x.ts
      FROM (SELECT f,
                   1 + ((f - 1 + 1 + floor(random() * (p_wallets - 1))::INT) % p_wallets) AS t,
                   amount, ts
              FROM (SELECT 1 + floor(random() * p_wallets)::INT AS f,
                           (100 + floor(random() * 49900))::BIGINT AS amount,
                           now() - random() * interval '59 days' AS ts
                      FROM generate_series(1, v_peers)) r) x
      JOIN seed_wallets fw ON fw.rn = x.f
      JOIN seed_wallets tw ON tw.rn = x.t;

    -- the two balanced lines of every new transfer
    INSERT INTO ledger_entries (transfer_id, account_id, direction, amount, created_at)
    SELECT id, from_account_id, 'DEBIT', amount, created_at FROM transfers WHERE id > v_max_transfer
    UNION ALL
    SELECT id, to_account_id, 'CREDIT', amount, created_at FROM transfers WHERE id > v_max_transfer;

    -- stored balances follow the new entries
    UPDATE accounts a
       SET balance = a.balance + d.delta
      FROM (SELECT account_id, SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END) AS delta
              FROM ledger_entries WHERE id > v_max_entry
             GROUP BY account_id) d
     WHERE a.id = d.account_id;

    ALTER TABLE ledger_entries ENABLE TRIGGER ledger_entries_balanced;
    RAISE NOTICE 'seeded % users, % wallets, % transfers', p_users, p_wallets, p_transfers;
END;
$$;
