CREATE SCHEMA IF NOT EXISTS applicant;

CREATE TABLE applicant.applicants (
    id                  UUID PRIMARY KEY,
    keycloak_subject_id VARCHAR(64)  NOT NULL,
    first_name          VARCHAR(100) NOT NULL,
    last_name           VARCHAR(100) NOT NULL,
    date_of_birth       DATE         NOT NULL,
    email               VARCHAR(255) NOT NULL,
    phone               VARCHAR(32)  NOT NULL,
    paddle_customer_id  VARCHAR(64),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_applicants_keycloak_subject_id UNIQUE (keycloak_subject_id),
    CONSTRAINT uq_applicants_email UNIQUE (email)
);

CREATE INDEX idx_applicants_keycloak_subject_id ON applicant.applicants (keycloak_subject_id);
