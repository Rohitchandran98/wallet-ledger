-- System accounts: belong to no user and may go negative.
--   CASH_IN     goes negative by the total ever topped up (money entering the platform)
--   CASH_OUT    goes positive by the total ever withdrawn
--   FEE_REVENUE goes positive by the fees collected
INSERT INTO accounts (kind, system_code, name) VALUES
    ('SYSTEM', 'CASH_IN',     'System: cash-in (top-ups)'),
    ('SYSTEM', 'CASH_OUT',    'System: cash-out (withdrawals)'),
    ('SYSTEM', 'FEE_REVENUE', 'System: fee revenue');

INSERT INTO risk_rules (code, description, action, window_minutes, max_count, multiplier, lookback_days, amount_threshold) VALUES
    ('VELOCITY',        'More than max_count outgoing transfers from one wallet in window_minutes',
                        'HOLD', 10,   5,    NULL, NULL, NULL),
    ('AMOUNT_SPIKE',    'Amount is over multiplier x the wallet''s lookback_days-day average outgoing transfer',
                        'HOLD', NULL, NULL, 5.00, 30,   NULL),
    ('NEW_PAYEE_LARGE', 'First transfer to this recipient and amount above amount_threshold paise',
                        'FLAG', NULL, NULL, NULL, NULL, 1000000);   -- ₹10,000

INSERT INTO job_locks (job_name) VALUES ('reconciliation');
