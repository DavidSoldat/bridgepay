CREATE SCHEMA IF NOT EXISTS notifications;

CREATE TABLE notifications.notification_log (
    id            UUID PRIMARY KEY,
    event_id      UUID NOT NULL,
    applicant_id  UUID NOT NULL,
    type          VARCHAR(64) NOT NULL,
    sent_at       TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_notification_log_event_id UNIQUE (event_id)
);

CREATE INDEX idx_notification_log_applicant_id ON notifications.notification_log (applicant_id);
