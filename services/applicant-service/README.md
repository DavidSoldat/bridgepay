# BridgePay — Applicant Service

Shopper signup and identity profile, linked to an existing Keycloak identity. It never creates Keycloak
users itself, only the profile row for the authenticated subject.

Deliberately has **no Kafka**: it neither publishes nor consumes any topic.

Signup validates the profile server-side: a dotted email domain (Paddle rejects `name@example`),
phone numbers with spaces, dashes or brackets stripped, trimmed non-blank names, and an age of at
least 18.

## Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/v1/applicants` | shopper | Create the caller's profile |
| GET | `/api/v1/applicants/me` | shopper | The caller's own profile |
| GET | `/api/v1/ops/applicants?q=` | ops | Search shoppers by name or email (at least 2 characters) |
| GET | `/api/v1/ops/applicants/{subject}` | ops | One shopper's profile |
| GET | `/internal/applicants/{subject}` | cluster only | Profile lookup for Repayment Reconciliation |
| PATCH | `/internal/applicants/{subject}/paddle-customer` | cluster only | Store the shopper's Paddle customer id |

`{subject}` is the Keycloak subject: the platform-wide applicant id that every other service stores.
