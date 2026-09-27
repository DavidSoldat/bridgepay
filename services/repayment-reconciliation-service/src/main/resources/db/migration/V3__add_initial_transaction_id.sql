ALTER TABLE repayment.repayment_plans ADD COLUMN paddle_initial_transaction_id VARCHAR(64);

-- Plans created before this column: the placeholder still holds the initial transaction id until adopted.
UPDATE repayment.repayment_plans
SET paddle_initial_transaction_id = paddle_subscription_id
WHERE paddle_subscription_id LIKE 'txn\_%';
