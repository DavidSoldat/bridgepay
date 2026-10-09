# BridgePay — Storefront

Live: **[shop.bridgepay.duckdns.org](https://shop.bridgepay.duckdns.org)**

A mock merchant shop, "Ridgeline Supply Co.", with BridgePay's "Pay in 4" checkout embedded in it.
Browsing needs no login; Keycloak (OIDC + PKCE) is asked for only when the shopper checks out, with a
silent check-sso on load so a returning shopper stays signed in.

- **Product page** shows how much the shopper has available to spend with BridgePay.
- **Checkout**: delivery address → review and pay (shipping, tax, four installments of the financed
  total) → decision. First-time shoppers fill in a short signup form. The flow survives the Keycloak
  redirect.
- **First payment** opens Paddle's overlay checkout (sandbox card `4242 4242 4242 4242`); the order
  is confirmed once installment 1 is paid.
- **My Account**: orders with their installment schedules, pay early or pay off, a spending-power
  meter, and an activity feed. A notification bell in the header shows unread updates.

Angular 21 with standalone components and signals, Tailwind v4. API calls go to the same origin
(`/api/`), which nginx proxies to the gateway; the Keycloak URL and the public Paddle client token
come from a runtime `/config.json`.
