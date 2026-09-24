-- Both merchant endpoints (sales list, summary) filter applications by merchant.
CREATE INDEX idx_applications_merchant_id ON application.applications (merchant_id);
