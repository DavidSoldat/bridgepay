CREATE TABLE repayment.failed_events (
    id            UUID PRIMARY KEY,
    topic         VARCHAR(100) NOT NULL,
    message_key   VARCHAR(100),
    payload       TEXT         NOT NULL,
    error_message TEXT         NOT NULL,
    status        VARCHAR(32)  NOT NULL,
    attempts      INT          NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_failed_events_status_created ON repayment.failed_events (status, created_at DESC);
