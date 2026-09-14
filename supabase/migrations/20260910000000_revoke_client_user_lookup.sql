-- Close three routines that a self-registered account can still call.
--
-- These were locked down on the live project on 2026-09-08 but never captured
-- as a migration, so the repository was WEAKER than production and a fresh
-- deploy would have re-opened them. The standing audit caught it on the PR's
-- own preview branch: CHECK 2 reported find_user_by_email as identity-reaching
-- with no gate. That drift is the reason CHECK 2 exists.
--
-- Signup is open with email autoconfirm, so `authenticated` includes anyone on
-- the internet who registered; each of these is revoked from it, not only from
-- anon.
--
--   find_user_by_email(text)     SECURITY DEFINER over auth.users. Confirms
--                                whether any address has an account and returns
--                                its uuid: an unauthenticated-grade enumeration
--                                oracle for the whole user base.
--   get_session_status(text)     SECURITY DEFINER, no caller in this repository
--                                at all (no Kotlin, no edge function).
--   update_api_key_last_used(uuid)  a write, reachable by any signed-in caller.
--
-- Callers that must keep working, both verified on this branch:
--   supabase/functions/passkey       -> find_user_by_email
--   supabase/functions/plugin-store  -> update_api_key_last_used
-- Both construct their client with SUPABASE_SERVICE_ROLE_KEY, so service_role
-- keeps EXECUTE and neither path changes.
revoke all on function public.find_user_by_email(text) from public, anon, authenticated;
revoke all on function public.get_session_status(text) from public, anon, authenticated;
revoke all on function public.update_api_key_last_used(uuid) from public, anon, authenticated;

grant execute on function public.find_user_by_email(text) to service_role;
grant execute on function public.update_api_key_last_used(uuid) to service_role;
