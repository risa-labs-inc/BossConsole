-- Run explicitly after the cleanup migration on a Supabase project.
-- Named scheduling is idempotent: repeated runs update this job, not other jobs.
BEGIN;
CREATE EXTENSION IF NOT EXISTS pg_cron WITH SCHEMA pg_catalog;
SELECT cron.schedule(
  'terminal-relay-expired-records',
  '* * * * *',
  'SELECT public.cleanup_expired_terminal_relay_records(500);'
);
COMMIT;
