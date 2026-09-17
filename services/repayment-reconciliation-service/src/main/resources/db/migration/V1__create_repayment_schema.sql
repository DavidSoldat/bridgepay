CREATE SCHEMA IF NOT EXISTS repayment;

CREATE TABLE repayment.repayment_plans (
    id                      UUID PRIMARY KEY,
    application_id          UUID NOT NULL,
    applicant_id            UUID NOT NULL,
    paddle_customer_id      VARCHAR(64) NOT NULL,
    paddle_subscription_id  VARCHAR(64),
    total_amount            NUMERIC(12, 2) NOT NULL,
    installment_count       INTEGER NOT NULL,
    installment_amount      NUMERIC(12, 2) NOT NULL,
    status                  VARCHAR(32) NOT NULL,
    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_repayment_plans_application_id UNIQUE (application_id)
);

CREATE INDEX idx_repayment_plans_applicant_id ON repayment.repayment_plans (applicant_id);
CREATE INDEX idx_repayment_plans_paddle_subscription_id ON repayment.repayment_plans (paddle_subscription_id);

CREATE TABLE repayment.installments (
    id                      UUID PRIMARY KEY,
    repayment_plan_id       UUID NOT NULL REFERENCES repayment.repayment_plans (id),
    sequence_number         INTEGER NOT NULL,
    due_date                DATE NOT NULL,
    amount                  NUMERIC(12, 2) NOT NULL,
    status                  VARCHAR(16) NOT NULL,
    paddle_transaction_id   VARCHAR(64),
    paid_at                 TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_installments_due_date_status ON repayment.installments (due_date, status);

CREATE TABLE repayment.outbox_events (
    id            UUID PRIMARY KEY,
    event_id      UUID NOT NULL,
    topic         VARCHAR(100) NOT NULL,
    partition_key VARCHAR(100) NOT NULL,
    payload       TEXT NOT NULL,
    published     BOOLEAN NOT NULL DEFAULT false,
    created_at    TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_outbox_event_id UNIQUE (event_id)
);

CREATE INDEX idx_outbox_events_unpublished ON repayment.outbox_events (created_at) WHERE NOT published;
