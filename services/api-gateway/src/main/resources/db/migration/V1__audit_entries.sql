CREATE TABLE audit_entries (
    id             uuid PRIMARY KEY,
    occurred_at    timestamptz  NOT NULL,
    correlation_id varchar(64),
    actor_subject  varchar(64)  NOT NULL,
    actor_username varchar(255),
    actor_role     varchar(16)  NOT NULL,
    action         varchar(40)  NOT NULL,
    target_type    varchar(20),
    target_id      varchar(64),
    detail         varchar(500),
    http_method    varchar(8)   NOT NULL,
    path           varchar(500) NOT NULL,
    status         int          NOT NULL
);

CREATE INDEX audit_entries_occurred_at_idx ON audit_entries (occurred_at DESC);
CREATE INDEX audit_entries_target_idx ON audit_entries (target_type, target_id, occurred_at DESC);
