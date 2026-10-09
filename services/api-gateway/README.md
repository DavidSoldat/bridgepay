# BridgePay — API Gateway

The single `/api/v1/**` entry point (Spring Cloud Gateway, WebMVC server). It routes by path, validates
the Keycloak JWT before proxying (401 without one), applies a global Resilience4j rate limit, stamps an
`X-Correlation-Id` on every request and response, and records an audit trail of staff actions.

Role checks stay on the downstream services (`@PreAuthorize`); the gateway only authenticates.
`/internal/**` endpoints are never routed here: they are reachable only inside the cluster.

## Routes

| Path | Target |
|---|---|
| `/api/v1/applicants/**` | Applicant Service |
| `/api/v1/ops/applicants/**` | Applicant Service |
| `/api/v1/applications/**`, `/api/v1/merchants/**` | Application Service |
| `/api/v1/repayment-plans/**`, `/api/v1/ops/**` | Repayment Reconciliation |
| `/api/v1/notifications/**` | Notifications Service |
| `/api/v1/model/**` | Credit Risk Engine |
| `/api/v1/audit` | served by the gateway itself (ops only) |

`/api/v1/ops/applicants/**` is declared before `/api/v1/ops/**` so it isn't shadowed.

## Audit log

`AuditFilter` records ops and merchant actions and every 403 into the gateway's own append-only
`audit.audit_entries` table: who, which action (14 known routes), which target id, and the outcome.
Shoppers' own reads are never recorded. Ops read it at `GET /api/v1/audit` (filter by actor, action,
target, date range and outcome; 50 per page, newest first). Recording is fail-open: an audit write
failure never blocks the request.

## Errors

Gateway-generated errors (no route, access denied, validation) use the platform's shared
`{error, message, traceId, timestamp}` shape.
