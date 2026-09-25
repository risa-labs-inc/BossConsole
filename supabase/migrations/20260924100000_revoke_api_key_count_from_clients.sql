-- Take `get_user_api_key_count` away from the client roles.
--
-- It is SECURITY DEFINER, takes a caller-supplied `p_user_id`, and applies no check
-- of its own, so any signed-in user could ask how many active API keys ANY other
-- user holds. `plugin_api_keys` has RLS, and its SELECT policy ("Users can view own
-- API keys", 20260204000000) gives a reader their own rows only: the table refuses
-- the question and this function answered it anyway. Tracked in #1590.
--
-- It is the last of the five plugin-store SECURITY DEFINER RPCs a client could
-- still execute. The other four were closed one at a time:
--
--   record_plugin_download, upsert_plugin_rating   20260909130000
--   update_api_key_last_used                       20260910000000
--   log_api_key_action                             20260911010000
--
-- Nothing calls it from a client. Its only caller is the plugin-store Edge
-- Function (routes/api-keys.ts, the key-creation limit check), which runs on the
-- service-role client built in plugin-store/index.ts and passes the caller's own
-- id taken from their verified token. The desktop app does not reference it.
--
-- WHY A REVOKE AND NOT AN OWN-KEYS CHECK. #1590 offered either. A check on
-- `auth.uid()` would keep an RPC reachable by every client for a question no client
-- asks, and add a branch that has to stay correct forever. Removing the grant
-- removes the surface. If a client ever needs its own count, that is a new
-- requirement with a caller to test against, not a reason to keep this grant.
--
-- The revoke survives CREATE OR REPLACE, which keeps the ACL, and that is how the
-- open #1171 rewrites this function to pin its search_path. It does NOT survive
-- DROP FUNCTION and CREATE: 20251023000014's default privileges hand every new
-- function in public to `authenticated`, and the enforce_explicit_anon_grants event
-- trigger strips only PUBLIC and `anon`. Assertions 1-3 in
-- api_key_count_grant_test.sql are what catch that, not this file.

REVOKE ALL ON FUNCTION "public"."get_user_api_key_count"("p_user_id" "uuid") FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."get_user_api_key_count"("p_user_id" "uuid") FROM "anon";
REVOKE ALL ON FUNCTION "public"."get_user_api_key_count"("p_user_id" "uuid") FROM "authenticated";

-- Restated rather than assumed: the Edge Function's key-limit check runs as
-- service_role, and a REVOKE that also caught it would fail every key creation.
GRANT EXECUTE ON FUNCTION "public"."get_user_api_key_count"("p_user_id" "uuid") TO "service_role";
GRANT EXECUTE ON FUNCTION "public"."get_user_api_key_count"("p_user_id" "uuid") TO "postgres";
