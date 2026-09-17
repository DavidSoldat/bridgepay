-- Keycloak's own Liquibase-based migrations, unlike Flyway (used by every
-- Java service here), don't create their target schema themselves - it must
-- already exist. Runs once, only on a fresh/empty postgres_data volume (the
-- standard docker-entrypoint-initdb.d behavior).
CREATE SCHEMA IF NOT EXISTS keycloak;
