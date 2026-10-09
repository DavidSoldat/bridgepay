# BridgePay — Notifications Service

Turns platform events into the shopper's notification feed. It consumes the application and
repayment topics (`applications.approved` / `manual-review` / `declined`, `repayments.installment-paid`
/ `installment-missed` / `plan-completed` / `plan-defaulted` / `plan-cancelled` / `plan-refunded`)
and stores one row per event in `notification_log`, with the shopper-facing title and body
rendered at write time. Delivery is in-app only: nothing is emailed or texted.

## Idempotency

The unique `event_id` column is the idempotency check: a redelivered event is a no-op.
`recordAndSend` is deliberately not `@Transactional`: the repository's `saveAndFlush` already runs
in its own transaction, and an outer one would turn the duplicate-key path into an
`UnexpectedRollbackException`.

## The feed

Installment payments that came from one Paddle transaction share a `group_key` and are folded in
SQL, so paying off a plan reads "Payments 3–4 received — $34.88" rather than two rows. Read state
is a forward-only `read_through` timestamp per shopper.

## Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/v1/notifications` | shopper | The caller's feed (grouped, paged) and unread count |
| POST | `/api/v1/notifications/read` | shopper | Mark everything up to now as read |
| GET | `/api/v1/notifications/applicants/{id}` | ops | A shopper's feed, read-only (never marks read) |
