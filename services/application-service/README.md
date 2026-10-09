# BridgePay — Application Service

Owns the checkout: idempotency, the synchronous call into the Credit Risk Engine, the shopper's
spending limit, the transactional outbox, and the one "finalize decision" path shared by the model
and ops manual review. Also owns merchants, their sales and payouts, refunds, and the ops and
merchant dashboards.

## How a checkout is decided

1. The shopper's available limit (`limit − outstanding`) is checked first: an order above it is
   refused with `422 OVER_LIMIT` and nothing is stored.
2. The Credit Risk Engine scores it: `APPROVE`, `DECLINE` or `MANUAL_REVIEW`. Any scoring failure
   (engine down, circuit breaker open) falls back to `MANUAL_REVIEW`, never a blind decision.
3. The decision, its score factors and the outbox event are written in one transaction. Ops can
   later approve or decline a manual review; that goes through the same finalize path.

The merchant payout is created `PENDING` at approval and becomes `PAID` when the shopper's first
installment clears (`repayments.installment-paid`); an unpaid order that expires or is refunded
cancels it. A later shopper default never claws back a paid payout.

## Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/v1/applications` | shopper, `Idempotency-Key` header | Checkout |
| GET | `/api/v1/applications/me` | shopper | The caller's orders, newest first |
| GET | `/api/v1/applications/me/credit-limit` | shopper | Limit, outstanding and available |
| GET | `/api/v1/applications/{id}` | owner or ops | One application |
| GET | `/api/v1/applications?status=` | ops | Review queue / decision history |
| POST | `/api/v1/applications/{id}/review-decision` | ops | Approve or decline a manual review |
| GET | `/api/v1/applications/{id}/case` | ops | Case file: decision record, merchant, payout |
| GET | `/api/v1/applications/applicants/{id}` | ops | One shopper's applications |
| GET | `/api/v1/applications/applicants/{id}/credit-standing` | ops | One shopper's limit and outstanding |
| GET | `/api/v1/applications/dashboard?days=&tz=` | ops | Ops dashboard |
| GET | `/api/v1/applications/model-monitoring` | ops | Score drift and outcomes vs the training baseline |
| GET | `/api/v1/merchants/{id}/sales` | own merchant | Sales, filterable by status |
| GET | `/api/v1/merchants/{id}/sales/export` | own merchant | Sales as CSV |
| GET | `/api/v1/merchants/{id}/payouts` | own merchant | Payouts, newest first |
| GET | `/api/v1/merchants/{id}/dashboard?days=&tz=` | own merchant | Merchant dashboard |
| POST | `/api/v1/merchants/{id}/orders/{applicationId}/refund` | own merchant | Full refund |

Merchants never see shopper credit data: sales rows are their own DTO, without applicant id,
risk score or score factors.

## Events

Publishes `applications.approved`, `applications.manual-review`, `applications.declined` and
`applications.refund-requested` through the outbox. Consumes `repayments.installment-paid`,
`plan-completed`, `plan-defaulted`, `plan-cancelled` and `plan-refunded` to keep order, payout and
outstanding balance in step.

## Design notes

- `applicantId` is the Keycloak subject, not a reference into Applicant Service, so checkout makes
  no extra cross-service call.
- `score_factors` is stored as JSON text: the model's per-feature log-odds contributions plus any
  policy rules that fired, rendered as the ops explanation chart.

## Demo sales history

`src/main/resources/db/demo/R__demo_sales_history.sql` seeds about 90 days of sales, payouts and
decided manual reviews for the demo merchant, so the dashboards and the model page have data. It
runs only where `SPRING_FLYWAY_LOCATIONS` includes `classpath:db/demo`. Seeded rows carry
`is_demo = true` and never appear in the ops review queue.
