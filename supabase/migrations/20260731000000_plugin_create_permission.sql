-- ============================================================================
-- BOSS Database Schema: least-privilege plugin authoring (`boss_plugin_admin`)
-- ============================================================================
-- File: 20260731000000_plugin_create_permission.sql
-- Description:
--   Adds a `plugins.create` permission and a `boss_plugin_admin` role that can
--   create plugins and publish versions of plugins it owns -- and nothing else.
--
--   Until now the only way to hand out plugin publishing was `boss_admin`, which
--   also carries role.read/create/assign, secret.read and the whole
--   plugins.admin.* moderation family. `plugins.admin.publish` is deliberately
--   NOT reused here: its RLS policy
--   (20260131000000_plugin_store_admin_policies.sql) has no author scoping, so
--   it authorizes updates to *any* plugin. It stays a moderation permission.
--
--   Hierarchy after this migration (parent --> child):
--       admin --> boss_admin
--       admin --> finance_admin
--       boss_admin --> boss_plugin_admin     (new)
--       boss_plugin_admin --> user           (new)
--       boss_admin --> user                  (kept; the diamond is harmless
--                                             because get_role_descendants
--                                             uses UNION, not UNION ALL)
--       finance_admin --> user
--
--   Effective permissions for boss_plugin_admin:
--       plugins.create, api_key.create
--       + user.read / user.write / user.update / user.delete (inherited)
--
--   NOTE ON ORDERING: this migration must reach production BEFORE the edge
--   function starts enforcing `plugins.create`, and before any plugin manifest
--   declares it -- `validateDeclaredPermissions` in the plugin-store function
--   rejects a publish naming a permission that is not in the catalog.
--
-- All seed data is idempotent (ON CONFLICT DO NOTHING), so this migration is
-- safe to re-run.
-- ============================================================================


-- ============================================================================
-- SECTION 1: Permission catalog
-- ============================================================================

-- `plugins.create` is is_system so delete_permission() refuses to drop it.
-- `api_key.create` already exists (seeded non-system by
-- 20260625000000_role_hierarchy_and_granular_rbac.sql); it now gates real
-- server-side behaviour, so promote it to is_system as well.
INSERT INTO "public"."permissions" ("name", "description", "is_system")
VALUES
    ('plugins.create', 'Create plugins and publish versions of plugins owned by the caller.', true)
ON CONFLICT ("name") DO NOTHING;

UPDATE "public"."permissions"
SET "is_system" = true
WHERE "name" IN ('plugins.create', 'api_key.create');


-- ============================================================================
-- SECTION 2: Role + grants + hierarchy
-- ============================================================================

INSERT INTO "public"."roles" ("name", "description", "is_system")
VALUES
    ('boss_plugin_admin', 'Plugin author: create plugins and publish versions of plugins they own', true)
ON CONFLICT ("name") DO NOTHING;

UPDATE "public"."roles" SET "is_system" = true WHERE "name" = 'boss_plugin_admin';

-- DIRECT permissions. Deliberately excludes every plugins.admin.* moderation
-- permission, all role.* / finance.* / rpa.* verbs, and secret.read.
INSERT INTO "public"."role_permissions" ("role_id", "permission_id")
SELECT r."id", p."id"
FROM (VALUES
    ('boss_plugin_admin', 'plugins.create'),
    ('boss_plugin_admin', 'api_key.create')
) AS grant_map("role_name", "perm_name")
JOIN "public"."roles" r ON r."name" = grant_map."role_name"
JOIN "public"."permissions" p ON p."name" = grant_map."perm_name"
ON CONFLICT ("role_id", "permission_id") DO NOTHING;

-- Slot boss_plugin_admin between boss_admin and user, so boss_admin (and
-- therefore admin) inherits plugins.create, and boss_plugin_admin inherits
-- the user.* baseline.
INSERT INTO "public"."role_hierarchy" ("parent_role_id", "child_role_id")
SELECT parent."id", child."id"
FROM (VALUES
    ('boss_admin',        'boss_plugin_admin'),
    ('boss_plugin_admin', 'user')
) AS edge("parent_name", "child_name")
JOIN "public"."roles" parent ON parent."name" = edge."parent_name"
JOIN "public"."roles" child  ON child."name"  = edge."child_name"
ON CONFLICT ("parent_role_id", "child_role_id") DO NOTHING;


-- ============================================================================
-- SECTION 3: Backfill existing plugin authors
-- ============================================================================

-- Before this change the plugin-store publish endpoints required only
-- authentication -- any signed-in user could create a plugin and mint a
-- publish-scoped API key. Enforcing `plugins.create` without a backfill would
-- silently revoke publishing from every existing non-admin author (and break
-- their CI keys mid-release). Grant the new role to everyone who has already
-- authored a plugin or holds a live Plugin Store API key.
--
-- One-shot and non-destructive: later revocations are NOT re-applied, because
-- ON CONFLICT DO NOTHING cannot distinguish "never granted" from "granted then
-- removed" -- but re-running this migration will re-grant a role that was
-- revoked from a legacy author. Revoke such users after this migration has
-- been applied to production.
INSERT INTO "public"."user_roles" ("user_id", "role_id")
SELECT DISTINCT legacy_author."id", r."id"
FROM (
    SELECT "author_id" AS "id" FROM "public"."plugins" WHERE "author_id" IS NOT NULL
    UNION
    SELECT "user_id" AS "id" FROM "public"."plugin_api_keys" WHERE "revoked_at" IS NULL
) AS legacy_author
JOIN "public"."roles" r ON r."name" = 'boss_plugin_admin'
JOIN "auth"."users" au ON au."id" = legacy_author."id"
ON CONFLICT ("user_id", "role_id") DO NOTHING;


-- ============================================================================
-- SECTION 4: Service-role permission probe
-- ============================================================================

-- The plugin-store edge function authenticates API-key callers without a JWT,
-- so it cannot read the `user_permissions` claim -- it has to ask the database
-- whether the key's OWNER still holds a permission. That is what makes a role
-- revocation stop API-key publishing immediately instead of at key expiry.
--
-- get_effective_permissions is deliberately revoked from PUBLIC/anon/
-- authenticated (it exposes any user's full authz model) and granted only to
-- supabase_auth_admin, so the service-role client cannot call it. Rather than
-- widening that, expose a narrow boolean probe: one user, one permission.
--
-- The is_user_admin() clause is deliberate. It mirrors authorize()'s admin
-- short-circuit (20260625000000_role_hierarchy_and_granular_rbac.sql) and the
-- client's UserInfo.hasPermission, so all three answer the same question the
-- same way -- including for a permission outside admin's role closure, such as
-- one a plugin has just introduced.
--
-- This does NOT contradict validateApiKey's "API keys NEVER have admin access".
-- That invariant is about AuthResult.isAdmin, which stays false: admin-only
-- routes (functions/plugin-store/routes/admin.ts) call getUserFromToken and
-- never accept an X-API-Key at all. What this clause means is narrower -- an
-- admin's API key satisfies a *permission* check, which was already true before
-- this migration, when publishing required no permission whatsoever.
CREATE OR REPLACE FUNCTION "public"."user_has_permission"("p_user_id" "uuid", "p_permission" "text")
RETURNS boolean
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT p_user_id IS NOT NULL
       AND (
            public.is_user_admin(p_user_id)
            OR p_permission = ANY(public.get_effective_permissions(p_user_id))
       );
$$;

ALTER FUNCTION "public"."user_has_permission"("p_user_id" "uuid", "p_permission" "text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."user_has_permission"("p_user_id" "uuid", "p_permission" "text") IS
    'Does this user effectively hold this permission (admins hold everything)? Narrow service-role probe for callers that have no JWT to read user_permissions from, e.g. Plugin Store API keys.';

-- Same exposure rules as get_effective_permissions: no PostgREST surface. Only
-- the edge functions (service_role) may ask about another user.
REVOKE EXECUTE ON FUNCTION "public"."user_has_permission"("uuid", "text") FROM PUBLIC, "anon", "authenticated";
GRANT EXECUTE ON FUNCTION "public"."user_has_permission"("uuid", "text") TO "service_role";
