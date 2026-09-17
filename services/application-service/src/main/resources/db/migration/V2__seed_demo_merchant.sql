-- Demo merchant for local dev / the storefront demo frontend. Its id is
-- fixed (not a real UuidCreator-generated value) so it can be wired as the
-- merchantId attribute on the Keycloak "merchant1" demo user - a seed row
-- needs a stable, known id for that to work.
INSERT INTO application.merchants (id, name, fee_rate_pct, created_at)
VALUES ('00000000-0000-7000-8000-000000000001', 'Demo Storefront Merchant', 2.90, now());
