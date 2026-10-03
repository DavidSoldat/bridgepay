ALTER TABLE notifications.notification_log
    ADD COLUMN title           VARCHAR(200),
    ADD COLUMN body            VARCHAR(500),
    ADD COLUMN application_id  UUID,
    ADD COLUMN group_key       VARCHAR(64),
    ADD COLUMN sequence_number INT,
    ADD COLUMN amount          NUMERIC(12, 2);

-- Rows recorded before the feed existed get a generic title per type and no body.
UPDATE notifications.notification_log
SET body  = '',
    title = CASE type
        WHEN 'APPLICATION_APPROVED' THEN 'You''re approved'
        WHEN 'APPLICATION_MANUAL_REVIEW' THEN 'Your order is being reviewed'
        WHEN 'APPLICATION_DECLINED' THEN 'Your order wasn''t approved'
        WHEN 'INSTALLMENT_PAID' THEN 'Payment received'
        WHEN 'INSTALLMENT_MISSED' THEN 'Payment missed'
        WHEN 'PLAN_COMPLETED' THEN 'Plan paid off'
        WHEN 'PLAN_DEFAULTED' THEN 'Plan defaulted'
        ELSE 'Notification'
    END;

ALTER TABLE notifications.notification_log
    ALTER COLUMN title SET NOT NULL,
    ALTER COLUMN body SET NOT NULL;

DROP INDEX notifications.idx_notification_log_applicant_id;
CREATE INDEX idx_notification_log_applicant_sent ON notifications.notification_log (applicant_id, sent_at DESC);

CREATE TABLE notifications.notification_reads (
    applicant_id UUID PRIMARY KEY,
    read_through TIMESTAMPTZ NOT NULL
);
