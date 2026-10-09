# BridgePay — Main app

Live: **[app.bridgepay.duckdns.org](https://app.bridgepay.duckdns.org)**

The staff app, one Angular app with role-gated views. Login is Keycloak OIDC with PKCE
(`login-required`); `realm_access.roles` decides what the sidebar shows.

**Ops** (`ops1`)
- Review queue with a status filter, and a case file per application: decision record, shopper,
  merchant and payout, repayment progress, and the score factors as an explanation chart.
- Dashboard: queue, decision mix, risk-score histogram, review times, per-reviewer stats.
- Shoppers: search, and a 360 view per shopper (credit standing, applications, payment timeline,
  notifications sent, access history).
- Model: score drift, per-feature drift, policy rules fired, and default rate per score band
  against the training baseline.
- Failed events with retry, and the audit log.

**Merchant** (`merchant1`)
- Sales dashboard (7/30/90 days, period-over-period change, charts), a sales list with filters,
  refunds and CSV export, and the payout ledger.

Angular 21 with standalone components and signals, Tailwind v4, and hand-written SVG charts (no
chart library). API calls go to the same origin (`/api/`), which nginx proxies to the gateway; the
Keycloak URL comes from a runtime `/config.json`.
