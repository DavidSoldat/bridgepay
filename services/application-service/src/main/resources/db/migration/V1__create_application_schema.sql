CREATE SCHEMA IF NOT EXISTS application;

CREATE TABLE application.merchants (
    id            UUID PRIMARY KEY,
    name          VARCHAR(200) NOT NULL,
    fee_rate_pct  NUMERIC(5, 2) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE application.applications (
    id                  UUID PRIMARY KEY,
    applicant_id        UUID NOT NULL,
    merchant_id         UUID NOT NULL REFERENCES application.merchants (id),
    amount              NUMERIC(12, 2) NOT NULL,
    status              VARCHAR(32) NOT NULL,
    risk_score          DOUBLE PRECISION,
    score_factors       TEXT,
    decision_at         TIMESTAMPTZ,
    installment_count   INTEGER,
    installment_amount  NUMERIC(12, 2),
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_applications_applicant_id ON application.applications (applicant_id);
CREATE INDEX idx_applications_status ON application.applications (status);

CREATE TABLE application.merchant_payouts (
    id             UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES application.applications (id),
    merchant_id    UUID NOT NULL REFERENCES application.merchants (id),
    amount         NUMERIC(12, 2) NOT NULL,
    fee_amount     NUMERIC(12, 2) NOT NULL,
    status         VARCHAR(16) NOT NULL,
    paid_at        TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE application.idempotency_keys (
    id                UUID PRIMARY KEY,
    idempotency_key   VARCHAR(100) NOT NULL,
    applicant_id      UUID NOT NULL,
    response_snapshot TEXT NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    expires_at        TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key)
);

CREATE TABLE application.outbox_events (
    id            UUID PRIMARY KEY,
    event_id      UUID NOT NULL,
    topic         VARCHAR(100) NOT NULL,
    partition_key VARCHAR(100) NOT NULL,
    payload       TEXT NOT NULL,
    published     BOOLEAN NOT NULL DEFAULT false,
    created_at    TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_outbox_event_id UNIQUE (event_id)
);

CREATE INDEX idx_outbox_events_unpublished ON application.outbox_events (created_at) WHERE NOT published;
