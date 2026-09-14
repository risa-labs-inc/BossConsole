-- Restrict completed_authentications to the service-role Edge Function only.
--
-- Problem
-- -------
-- completed_authentications holds the freshly minted `access_token` and
-- `refresh_token` for the cross-device (QR) login flow. Its anon policies
-- (20251023000013_rls_policies.sql :359, :373, :387) are each guarded only by
-- `session_id IS NOT NULL`.
--
-- `session_id` is NOT NULL on every real row, so that predicate is TRUE for all
-- of them: the policies are `USING (true)` written the long way. Nothing anywhere
-- requires the caller to actually know a session id, so the "unguessable UUID
-- acts as a temporary bearer token" model the surrounding comments describe is
-- not enforced. Combined with `GRANT ALL ON TABLE ... TO anon`
-- (20251023000014_grants.sql:545) and an anon key that is public by construction
-- (it ships in the desktop app and this repo is open source), that is reachable
-- by anyone who can reach PostgREST:
--
--   1. Token harvest. `GET /rest/v1/completed_authentications?select=*` returns
--      every in-flight login's access_token and refresh_token. Polling faster
--      than the legitimate desktop wins the race to any QR-logging-in user's
--      session, which is account takeover rather than a leak.
--   2. Denial of service. `DELETE /rest/v1/completed_authentications` wipes all
--      in-flight logins at once.
--   3. Session fixation. anon INSERT lets an attacker pre-seed a victim's
--      session_id with attacker-controlled tokens, which the victim's desktop
--      then imports as its own session.
--
-- Reported as BossConsole#528.
--
-- Why this is safe
-- ----------------
-- No client reaches this table directly any more. Verified on dev:
--
--   - `grep -rn completed_authentications --include=*.kt` over the desktop source
--     returns nothing outside build output. The desktop polls the Edge Function
--     instead: `GET /passkey/auth/status/{sessionId}`
--     (SupabaseApiClient.kt:103).
--   - Every read and write of the table happens inside the passkey Edge
--     Function, whose Supabase client is built with SUPABASE_SERVICE_ROLE_KEY
--     (supabase/functions/passkey/index.ts:36).
--
-- service_role bypasses RLS, so the anon and authenticated policies and their
-- table grants are dead code on the live path. Dropping them changes nothing for
-- the application and closes all three defects above.
--
-- The authenticated policies go with the anon ones for the same reason: the flow
-- does not use them either, and leaving a role with standing access to a table of
-- bearer tokens is the condition that made this exploitable in the first place.

-- Belt and braces, matching 20260910120000_restrict_plugin_downloads_rls.sql:
-- revoke the base-table privileges as well, so the gate does not rest on RLS
-- alone. PUBLIC is included because a grant there would survive revoking the two
-- named roles.
REVOKE ALL ON TABLE public.completed_authentications FROM PUBLIC, anon, authenticated;

DROP POLICY IF EXISTS "Anon can insert completed authentications" ON public.completed_authentications;
DROP POLICY IF EXISTS "Anon can select by session_id" ON public.completed_authentications;
DROP POLICY IF EXISTS "Anon can delete by session_id" ON public.completed_authentications;
DROP POLICY IF EXISTS "Authenticated users can insert own results" ON public.completed_authentications;
DROP POLICY IF EXISTS "Authenticated users can select own results" ON public.completed_authentications;
DROP POLICY IF EXISTS "Authenticated users can delete own results" ON public.completed_authentications;

-- The service_role policy (20251023000013_rls_policies.sql:347) and its grant
-- (20251023000014_grants.sql:547) are deliberately untouched: that is the path
-- the Edge Function uses and the only one that has to keep working.

-- The cleanup RPC is granted to both client roles
-- (20251023000014_grants.sql:240-241), but no client code calls it: it returns
-- void and only deletes rows past expires_at_timestamp. The REVOKE EXECUTE below
-- is the gate: no client role keeps any handle to this table's data; the
-- service_role grant is left for the server-side path. (A second, weaker
-- argument - with the table privileges revoked above, the DELETE would also
-- fail if a client ran the function - only holds while the function is SECURITY
-- INVOKER. Its own header comment at 20251023000003_passkey_functions.sql:221
-- claims SECURITY DEFINER, so if someone ever "fixes" the function to match that
-- comment, only this revoke still holds.) PUBLIC is included because PostgreSQL
-- grants EXECUTE on functions to PUBLIC by default, so revoking the two named
-- roles alone would leave the door open (verified: has_function_privilege still
-- returned true for both client roles).
REVOKE EXECUTE ON FUNCTION public.cleanup_expired_completed_authentications() FROM PUBLIC, anon, authenticated;

COMMENT ON TABLE public.completed_authentications IS
    'Cross-device login handoff: holds freshly minted access/refresh tokens until the desktop claims them. '
    'Service-role only. anon and authenticated hold no policy and no table privilege (BossConsole#528); '
    'clients reach this data through the passkey Edge Function, never through PostgREST. '
    'The expired-row cleanup RPC is likewise service-role only.';
