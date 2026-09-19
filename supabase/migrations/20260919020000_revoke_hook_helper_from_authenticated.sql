-- Take `get_user_roles_for_hook` away from the client roles.
--
-- It is one of three SECURITY DEFINER helpers the token hook calls, all with the
-- same shape, and it is the only one a client can execute:
--
--   get_user_roles_for_hook     authenticated, postgres, service_role, supabase_auth_admin
--   get_effective_permissions                  postgres, service_role, supabase_auth_admin
--   get_user_orgs_for_hook                     postgres, service_role, supabase_auth_admin
--
-- Same family, same caller, same `check_user_id uuid` parameter, and only one of
-- them is reachable from a session. The grant comes from 20251023000014, which
-- handed `anon` and `authenticated` EXECUTE on it explicitly. 20260908030000
-- swept `anon` off it and says in its own header that `authenticated` was left
-- alone as that migration's scoping decision, not as a judgement about this
-- function. 20260909130000 then made exactly this revoke for
-- `custom_access_token_hook`, the function these three serve.
--
-- Nothing calls it from a client. The only calls outside the migrations are two
-- assertions in supabase/tests/boss_org_member_role_test.sql, which run as the
-- test role; its other mentions are documentation. The desktop client reads its
-- own roles from the JWT claims the hook wrote (RoleService.decodeJWTClaims) and
-- other users' through get_user_roles_with_names, and the Edge Functions read the
-- claims too (passkey/utils/jwt.ts, plugin-store/utils/auth.ts).
--
-- WHAT THIS DOES NOT DO, stated plainly because the function name invites the
-- stronger reading: it does not stop a signed-in user reading another user's
-- roles. Three more SECURITY DEFINER functions take a caller-supplied subject id,
-- apply no authorization check of their own, and are executable by
-- `authenticated`:
--
--   get_user_roles(check_user_id uuid)              -> SETOF text
--   get_user_roles_with_names(target_user_id uuid)  -> jsonb, including assigned_by
--   user_has_role(check_user_id uuid, text)         -> boolean
--
-- `user_roles` has RLS and gives an ordinary reader their own rows only, so each
-- of these answers a question the table refuses. They are deliberately NOT
-- touched here, because the fix is not the same: `get_user_roles_with_names` has
-- a live caller in `RoleService.getUserRoles`, which `UserService` calls per user
-- while building the admin user list, so revoking it would break the RBAC screen.
-- Closing that family needs a check inside the functions, and it cannot be
-- admins-only: `users` lets `role.read` holders list everyone, and `boss_admin`
-- holds `role.read` without being an admin. That is a behaviour change and
-- belongs in its own migration with its own tests.
--
-- This migration is the part with no such question attached: an unused grant on a
-- hook helper, removed so it matches the two functions beside it.

REVOKE ALL ON FUNCTION "public"."get_user_roles_for_hook"("check_user_id" "uuid") FROM PUBLIC;
REVOKE ALL ON FUNCTION "public"."get_user_roles_for_hook"("check_user_id" "uuid") FROM "anon";
REVOKE ALL ON FUNCTION "public"."get_user_roles_for_hook"("check_user_id" "uuid") FROM "authenticated";

-- Restated rather than assumed: these three are what the hook needs to keep
-- working, and a REVOKE that also caught them would stop every login.
GRANT EXECUTE ON FUNCTION "public"."get_user_roles_for_hook"("check_user_id" "uuid") TO "supabase_auth_admin";
GRANT EXECUTE ON FUNCTION "public"."get_user_roles_for_hook"("check_user_id" "uuid") TO "service_role";
GRANT EXECUTE ON FUNCTION "public"."get_user_roles_for_hook"("check_user_id" "uuid") TO "postgres";
