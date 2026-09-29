-- Seeded demo orders (db/demo) are real rows for the merchant dashboard, but have no shopper profile or
-- repayment plan behind them, so the ops queue leaves them out.
ALTER TABLE application.applications ADD COLUMN is_demo BOOLEAN NOT NULL DEFAULT false;
