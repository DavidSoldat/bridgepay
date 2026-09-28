-- Who made an application's final decision. Rows decided before V5 keep decision_source NULL ("unknown").
ALTER TABLE application.applications
    ADD COLUMN decision_source VARCHAR(8),
    ADD COLUMN decided_by      VARCHAR(255),
    ADD COLUMN reviewer_note   VARCHAR(1000);
