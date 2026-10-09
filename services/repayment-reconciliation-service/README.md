# BridgePay — Repayment Reconciliation Service

Collects the four installments through **Paddle** (sandbox) and tracks them. On
`applications.approved` it finds or creates the shopper's Paddle customer and creates a
weekly-recurring transaction for the plan. The shopper pays installment 1 in Paddle's overlay
checkout, which saves the card; Paddle's subscription then charges installments 2–4 weekly.

Paddle's webhooks drive the state machine: `transaction.completed` (installment paid, possibly
several at once), `transaction.payment_failed` / `subscription.past_due` (late), and
`subscription.canceled` (defaulted, unless we cancelled it ourselves after the last payment). Each
transition is published through the outbox on a `repayments.*` topic.

## Shopper-facing features

- **Pay early / pay off**: a one-time charge to the saved card for the next installment or all of
  them, guarded by an atomic claim so a double click or a racing webhook never charges twice.
- **Reconcile on read**: while installment 1 is unpaid, reading the plan asks Paddle directly, so a
  payment shows up even if its webhook is late or lost.
- **Unpaid-order expiry**: a scheduled job cancels the Paddle transaction and the order if
  installment 1 isn't paid within 24 hours (`FIRST_PAYMENT_EXPIRY`).
- **Refunds**: a merchant refund cancels an unpaid checkout or refunds every paid Paddle transaction
  in full, then cancels the subscription.

## Ops features

A failed `applications.approved` (for example Paddle unreachable) is saved to `failed_events`
instead of being dropped. Ops list and retry them; a retry replays the stored event through the same
idempotent listener, with an atomic claim so two ops tabs can't both create a Paddle transaction.

## Security

`/webhooks/paddle` is public by necessity (Paddle calls it from the internet). Its control is the
HMAC-SHA256 `Paddle-Signature` check, compared in constant time; a bad signature gets `400` before
any handler runs. In production the service refuses to start if the Paddle API key or webhook secret
is missing, blank or a placeholder (`PADDLE_REQUIRE_CREDENTIALS=true`).

## Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/v1/repayment-plans/{applicationId}` | owner or ops | Plan and installments |
| POST | `/api/v1/repayment-plans/{applicationId}/early-payment` | owner | Pay `NEXT` or `REMAINING` now |
| GET | `/api/v1/repayment-plans/applicants/{id}` | ops | A shopper's plans and repayment history |
| GET | `/api/v1/ops/failed-events?status=` | ops | Failed events, newest first |
| POST | `/api/v1/ops/failed-events/{id}/retry` | ops | Retry one |
| POST | `/webhooks/paddle` | Paddle signature | Paddle webhooks |
| GET | `/internal/repayment-history/{applicantId}` | cluster only | History for the Credit Risk Engine (all zeros for a new shopper, not 404) |

## Design notes

- `repayment_plans.paddle_subscription_id` holds the checkout transaction id until the first
  `transaction.completed` adopts the subscription Paddle creates; Paddle has no direct
  "create subscription" call.
- `HttpPaddleClient` forces HTTP/1.1. Paddle needs nothing newer, and it avoids h2c upgrade issues.
- Paddle's 4xx refusals don't count toward the circuit breaker, so retrying already-settled orders
  can't open it and block new approvals.
