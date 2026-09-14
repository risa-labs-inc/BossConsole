-- Stop two audit logs accepting forged rows from any client.
--
-- `secret_access_log` and `plugin_api_key_logs` are the audit trails for secret
-- access and for plugin-store API key usage. Both had an INSERT policy whose
-- predicate was `WITH CHECK (true)` and, crucially, no `TO` clause. A policy
-- with no `TO` clause applies to PUBLIC, which includes `anon` and
-- `authenticated`.
--
-- Both tables are also writable by those roles:
--   * secret_access_log has an explicit `GRANT ALL ... TO anon, authenticated`
--     in 20251023000014_grants.sql.
--   * plugin_api_key_logs has no explicit grant, but the same migration sets
--     `ALTER DEFAULT PRIVILEGES ... GRANT ALL ON TABLES TO anon, authenticated`,
--     and that table is created later, so it inherits the grant.
--
-- The anon key ships in the desktop client, so the practical effect is that
-- anybody can append arbitrary rows to either log:
--
--   1. Misattribution. `secret_access_log.user_id` is unconstrained, so a row
--      claiming that another user viewed or shared a secret can be inserted at
--      will. The table's own SELECT policy then shows that row to the named
--      user and to every admin, as though it were real.
--   2. Cover. A real access event can be buried under forged entries, which is
--      the failure mode an audit log exists to prevent.
--   3. Forged API-key history. plugin_api_key_logs rows can be attributed to
--      any existing api_key_id, including another user's.
--
-- Neither policy comment described this. plugin_api_key_logs' policy is even
-- named "Service role can insert API key logs", which is what it was meant to
-- be and not what it did.
--
-- No legitimate writer is affected. Every writer is a SECURITY DEFINER
-- function owned by postgres, which owns both tables and so bypasses RLS
-- without needing any client table privilege:
--   * Every function that writes secret_access_log
--     (20251023000004_secret_functions.sql, 20260802000000_secrets_org_ownership.sql,
--     20260809000000_secret_read_for_user_role.sql) inserts `auth.uid()` as
--     user_id and runs as postgres.
--   * plugin_api_key_logs is written only by log_api_key_action(), which is
--     SECURITY DEFINER and owned by postgres; the Edge Function calls it with
--     the service role.
--
-- Closing the hole takes two strokes, because table privileges and the writer
-- RPC are separate doors:
--   1. Revoke the client table privileges and drop/re-scope the policies
--      (direct PostgREST DML).
--   2. Revoke client EXECUTE on the definer writer. PostgreSQL grants EXECUTE
--      on functions to PUBLIC by default, and log_api_key_action() also
--      inherits the schema-wide default-privilege grants to anon and
--      authenticated (20251023000014_grants.sql:697-699; the function was
--      created later, in 20260204000000). Its explicit service_role grant is
--      additive, not a restriction. Left open, any client could forge API-key
--      history through the RPC - the exact hole this migration closes for
--      direct table writes.

-- ---------------------------------------------------------------------------
-- secret_access_log
-- ---------------------------------------------------------------------------

-- anon has no reason to touch a secret audit trail in any way.
REVOKE ALL ON TABLE public.secret_access_log FROM anon;

-- authenticated keeps SELECT (policy "secret_access_log_select") and loses
-- INSERT as well: every writer is a SECURITY DEFINER function owned by
-- postgres, which bypasses RLS and needs no client table privilege. Keeping a
-- client INSERT - even one scoped to user_id = auth.uid() - would only let a
-- signed-in user forge "I did this" rows about themselves.
REVOKE INSERT, UPDATE, DELETE ON TABLE public.secret_access_log FROM authenticated;

-- The old unconditional INSERT policy is dropped and not recreated: with no
-- INSERT privilege on the table, no client statement can insert a row, and a
-- leftover policy would be misleading dead code.
DROP POLICY IF EXISTS "secret_access_log_insert" ON public.secret_access_log;

COMMENT ON TABLE public.secret_access_log IS
    'Audit log for all secret access and sharing operations. Append-only: '
    'written only by SECURITY DEFINER secret functions; a signed-in user may '
    'read their own rows, and nothing may insert, update or delete one. '
    'service_role bypasses RLS for retention work.';

-- ---------------------------------------------------------------------------
-- plugin_api_key_logs
-- ---------------------------------------------------------------------------

REVOKE ALL ON TABLE public.plugin_api_key_logs FROM anon;

-- authenticated keeps SELECT so "Users can view own API key logs" still
-- resolves. Writing is the Edge Function's job, through a definer function.
REVOKE INSERT, UPDATE, DELETE ON TABLE public.plugin_api_key_logs FROM authenticated;

DROP POLICY IF EXISTS "Service role can insert API key logs" ON public.plugin_api_key_logs;

CREATE POLICY "Service role can insert API key logs" ON public.plugin_api_key_logs
    FOR INSERT TO service_role
    WITH CHECK (true);

-- Second door: the definer writer itself. Without this, an anon client could
-- call the RPC and forge rows for any api_key_id even with the table locked.
REVOKE EXECUTE ON FUNCTION public.log_api_key_action(uuid, text, text, text, text, boolean, text)
    FROM PUBLIC, anon, authenticated;

COMMENT ON TABLE public.plugin_api_key_logs IS
    'Plugin store: audit log for API key usage. Written only by '
    'log_api_key_action() (SECURITY DEFINER, service_role; client EXECUTE '
    'revoked). Key owners may read their own rows; nobody may insert, amend '
    'or erase one.';

-- ---------------------------------------------------------------------------
-- Two doors the review named that the strokes above do not reach
-- ---------------------------------------------------------------------------

-- update_api_key_last_used() has the same shape as log_api_key_action(): same
-- file, SECURITY DEFINER, EXECUTE never revoked. Left open, a client can stamp
-- last_used_at on any key id, which corrupts the same audit story from the
-- other end and makes a dormant key look live.
REVOKE EXECUTE ON FUNCTION public.update_api_key_last_used(uuid)
    FROM PUBLIC, anon, authenticated;

-- Revoking named verbs leaves the rest of the original GRANT ALL in place:
-- TRUNCATE, REFERENCES and TRIGGER. TRUNCATE matters most, because it is not
-- subject to row level security at all, so no policy can be its backstop. anon
-- already lost these to the REVOKE ALL above; authenticated did not.
REVOKE TRUNCATE, REFERENCES, TRIGGER ON TABLE public.secret_access_log FROM authenticated;
REVOKE TRUNCATE, REFERENCES, TRIGGER ON TABLE public.plugin_api_key_logs FROM authenticated;
