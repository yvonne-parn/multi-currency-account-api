INSERT INTO account (account_number, account_holder_name, created_at)
VALUES ('EE000000000001', 'Test Holder', CURRENT_TIMESTAMP);

INSERT INTO account_balance (account_id, currency, balance, updated_at)
SELECT id, 'EUR', 0.00, CURRENT_TIMESTAMP FROM account WHERE account_number = 'EE000000000001';
INSERT INTO account_balance (account_id, currency, balance, updated_at)
SELECT id, 'USD', 0.00, CURRENT_TIMESTAMP FROM account WHERE account_number = 'EE000000000001';
INSERT INTO account_balance (account_id, currency, balance, updated_at)
SELECT id, 'SEK', 0.00, CURRENT_TIMESTAMP FROM account WHERE account_number = 'EE000000000001';
INSERT INTO account_balance (account_id, currency, balance, updated_at)
SELECT id, 'GBP', 0.00, CURRENT_TIMESTAMP FROM account WHERE account_number = 'EE000000000001';