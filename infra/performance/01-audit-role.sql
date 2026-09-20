-- This init script is mounted only by compose.isolated.yml into a NEW lab volume.
-- Public synthetic-only credential; never use or copy a production credential.
CREATE ROLE fincore_audit LOGIN PASSWORD 'isolated-audit-only'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
GRANT CONNECT ON DATABASE fincore_performance TO fincore_audit;
GRANT USAGE ON SCHEMA public TO fincore_audit;
ALTER ROLE fincore_audit SET default_transaction_read_only = on;
ALTER DEFAULT PRIVILEGES FOR ROLE fincore_lab IN SCHEMA public
  GRANT SELECT ON TABLES TO fincore_audit;
