-- How many installments the shopper has paid, from repayments.installment-paid (max sequence seen), so the
-- spending limit frees up as a plan is paid down.
ALTER TABLE application.applications ADD COLUMN installments_paid INT NOT NULL DEFAULT 0;
