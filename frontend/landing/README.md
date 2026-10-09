# BridgePay — Landing

Live: **[bridgepay.duckdns.org](https://bridgepay.duckdns.org)**

The public home page: what BridgePay is, how a "Pay in 4" purchase works, a "Try the demo" section
with one card per role (shopper, ops, merchant) and a five-step walkthrough, an architecture diagram
with a component table, and a look under the hood.

Demo logins and the app URLs come only from the runtime `/config.json`. If it fails to load, the page
falls back to defaults with no logins at all; no password is ever built into the bundle as a
fallback.

Angular 21 with standalone components and signals, Tailwind v4.
