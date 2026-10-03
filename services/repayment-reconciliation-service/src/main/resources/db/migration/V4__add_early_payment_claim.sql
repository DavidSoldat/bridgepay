-- Held while an early payment is charged at Paddle, so a double click or second tab can't charge twice.
-- Deliberately not mapped on RepaymentPlan: JPA saves write every mapped column and could clear a claim taken meanwhile.
ALTER TABLE repayment.repayment_plans ADD COLUMN early_payment_claimed_at TIMESTAMPTZ;
