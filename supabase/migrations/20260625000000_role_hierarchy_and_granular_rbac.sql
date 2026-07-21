-- ============================================================================
-- BOSS Database Schema: Granular Role Management via Role Hierarchy
-- ============================================================================
-- File: 20260625000000_role_hierarchy_and_granular_rbac.sql
-- Description:
--   Introduces a role hierarchy (a parent role inherits the effective
--   permissions of its children), computes effective permissions via that
--   hierarchy, re-gates the role-management RPCs from a hard `is_user_admin`
--   check to permission-based `authorize()` checks, derives role-grant
--   delegation from the tree (a role may assign only roles strictly below it),
--   and injects effective permissions into the JWT.
--
-- Hierarchy (parent --> child):
--     admin --> boss_admin
--     admin --> finance_admin
--     boss_admin --> user
--     finance_admin --> user
--
-- All function/table objects are created with CREATE OR REPLACE / IF NOT EXISTS
-- and all seed data is idempotent, so this migration is safe to re-run.
-- ============================================================================


-- ============================================================================
-- SECTION 1: role_hierarchy table + RLS
-- ============================================================================

CREATE TABLE IF NOT EXISTS "public"."role_hierarchy" (
    "parent_role_id" "uuid" NOT NULL,
    "child_role_id" "uuid" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "role_hierarchy_pkey" PRIMARY KEY ("parent_role_id", "child_role_id"),
    CONSTRAINT "role_hierarchy_no_self_loop" CHECK ("parent_role_id" <> "child_role_id"),
    CONSTRAINT "role_hierarchy_parent_fkey" FOREIGN KEY ("parent_role_id")
        REFERENCES "public"."roles"("id") ON DELETE CASCADE,
    CONSTRAINT "role_hierarchy_child_fkey" FOREIGN KEY ("child_role_id")
        REFERENCES "public"."roles"("id") ON DELETE CASCADE
);

ALTER TABLE "public"."role_hierarchy" OWNER TO "postgres";

COMMENT ON TABLE "public"."role_hierarchy" IS 'Directed edges (parent_role_id -> child_role_id). A parent role inherits the effective permissions of its children. Modeled as a DAG (a role may have multiple parents, e.g. "user" is a child of both boss_admin and finance_admin).';

CREATE INDEX IF NOT EXISTS "idx_role_hierarchy_parent" ON "public"."role_hierarchy" ("parent_role_id");
CREATE INDEX IF NOT EXISTS "idx_role_hierarchy_child" ON "public"."role_hierarchy" ("child_role_id");

ALTER TABLE "public"."role_hierarchy" ENABLE ROW LEVEL SECURITY;

-- Anyone authenticated may read the hierarchy (needed to compute grantable roles / future UI).
DROP POLICY IF EXISTS "Anyone can view role hierarchy" ON "public"."role_hierarchy";
CREATE POLICY "Anyone can view role hierarchy" ON "public"."role_hierarchy"
    FOR SELECT USING (true);

-- Only full admins may modify the hierarchy (edit-in-UI is a future, admin-gated feature).
DROP POLICY IF EXISTS "Admins can manage role hierarchy" ON "public"."role_hierarchy";
CREATE POLICY "Admins can manage role hierarchy" ON "public"."role_hierarchy"
    USING ("public"."is_user_admin"("auth"."uid"()))
    WITH CHECK ("public"."is_user_admin"("auth"."uid"()));

DROP POLICY IF EXISTS "Service role full access to role hierarchy" ON "public"."role_hierarchy";
CREATE POLICY "Service role full access to role hierarchy" ON "public"."role_hierarchy"
    USING ((("auth"."jwt"() ->> 'role'::"text") = 'service_role'::"text"));


-- ============================================================================
-- SECTION 2: Seed roles, permissions, hierarchy edges (idempotent)
-- ============================================================================

-- Ensure the four hierarchy roles exist. In production user/admin already exist
-- (system) and boss_admin/finance_admin were created via the UI; on a fresh DB
-- (e.g. local Supabase) only user/admin are seeded, so create the admin tiers
-- here. Non-destructive: ON CONFLICT DO NOTHING keeps any existing row as-is.
INSERT INTO "public"."roles" ("name", "description", "is_system")
VALUES
    ('user',          'Default role for all authenticated users', true),
    ('admin',         'Administrator role with full system access', true),
    ('boss_admin',    'BOSS administrator: manages roles, excluding the finance domain', true),
    ('finance_admin', 'Finance domain administrator', true)
ON CONFLICT ("name") DO NOTHING;

-- In production boss_admin/finance_admin pre-exist with is_system=false (created
-- via the UI); flip them to system now that the hierarchy depends on them.
UPDATE "public"."roles" SET "is_system" = true
WHERE "name" IN ('boss_admin', 'finance_admin');

-- Canonical permission catalog for the system roles. This migration is the
-- single source of truth for the tier-role permission matrix so local and
-- production cannot drift (previously these lived only in seed.sql / the UI).
-- plugins.admin.* are created by 20260131000000_plugin_store_admin_policies.sql;
-- role.read is new here. All inserts are NON-DESTRUCTIVE (ON CONFLICT DO NOTHING)
-- — they only ensure the baseline exists, never removing UI-made assignments.
INSERT INTO "public"."permissions" ("name", "description", "is_system")
VALUES
    ('role.read',      'View roles and their permissions', true),
    ('role.create',    'Create roles and permissions', false),
    ('role.assign',    'Assign/remove roles to users', false),
    ('role.update',    'Modify roles and role-permission mappings', false),
    ('role.delete',    'Delete roles and permissions', false),
    ('finance.read',   'View finance data', false),
    ('finance.write',  'Create finance data', false),
    ('finance.update', 'Update finance data', false),
    ('finance.delete', 'Delete finance data', false),
    ('user.read',      'View users', true),
    ('user.write',     'Create users', true),
    ('user.update',    'Update users', true),
    ('user.delete',    'Delete users', true),
    ('api_key.create', 'Create API keys', false),
    ('rpa.write',      'Author RPA flows', false)
ON CONFLICT ("name") DO NOTHING;

-- DIRECT (own) permissions per system role. Effective permissions add the
-- hierarchy on top: boss_admin/finance_admin inherit user.*, and admin inherits
-- both branches (so admin's direct set is just its own verbs). boss_admin's
-- grants stay tree-limited at assignment time (it can only assign its
-- descendants), so it can use the Admin: Roles plugin yet never assign finance_admin.
INSERT INTO "public"."role_permissions" ("role_id", "permission_id")
SELECT r."id", p."id"
FROM (VALUES
    -- user (baseline)
    ('user', 'user.read'), ('user', 'user.write'), ('user', 'user.update'), ('user', 'user.delete'),
    -- finance_admin (inherits user.* via hierarchy)
    ('finance_admin', 'finance.read'), ('finance_admin', 'finance.write'),
    ('finance_admin', 'finance.update'), ('finance_admin', 'finance.delete'),
    -- boss_admin (inherits user.* via hierarchy)
    ('boss_admin', 'role.read'), ('boss_admin', 'role.create'), ('boss_admin', 'role.assign'),
    ('boss_admin', 'api_key.create'),
    ('boss_admin', 'plugins.admin.view'), ('boss_admin', 'plugins.admin.publish'),
    ('boss_admin', 'plugins.admin.verify'), ('boss_admin', 'plugins.admin.delete'),
    -- admin (inherits boss_admin + finance_admin + user; direct = its own verbs)
    ('admin', 'role.read'), ('admin', 'role.assign'), ('admin', 'role.update'),
    ('admin', 'role.delete'), ('admin', 'rpa.write')
) AS grant_map("role_name", "perm_name")
JOIN "public"."roles" r ON r."name" = grant_map."role_name"
JOIN "public"."permissions" p ON p."name" = grant_map."perm_name"
ON CONFLICT ("role_id", "permission_id") DO NOTHING;

-- Seed hierarchy edges. Any edge whose role is missing is simply skipped.
INSERT INTO "public"."role_hierarchy" ("parent_role_id", "child_role_id")
SELECT parent."id", child."id"
FROM (VALUES
    ('admin',         'boss_admin'),
    ('admin',         'finance_admin'),
    ('boss_admin',    'user'),
    ('finance_admin', 'user')
) AS edge("parent_name", "child_name")
JOIN "public"."roles" parent ON parent."name" = edge."parent_name"
JOIN "public"."roles" child  ON child."name"  = edge."child_name"
ON CONFLICT ("parent_role_id", "child_role_id") DO NOTHING;


-- ============================================================================
-- SECTION 3: Effective-permission engine (recursive over the hierarchy)
-- ============================================================================

-- get_role_descendants: the role itself plus all transitive descendants
-- (children, grandchildren, ...). UNION (not UNION ALL) makes it cycle-safe
-- and DAG-safe.
CREATE OR REPLACE FUNCTION "public"."get_role_descendants"("p_role_id" "uuid")
RETURNS SETOF "uuid"
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    WITH RECURSIVE descendants("role_id") AS (
        SELECT p_role_id
        UNION
        SELECT rh."child_role_id"
        FROM public.role_hierarchy rh
        JOIN descendants d ON rh."parent_role_id" = d."role_id"
    )
    SELECT "role_id" FROM descendants;
$$;

ALTER FUNCTION "public"."get_role_descendants"("uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."get_role_descendants"("uuid") IS 'Returns the given role plus all transitive descendant roles via role_hierarchy. Cycle/DAG-safe.';


-- get_effective_permissions: distinct permission names a user holds, expanding
-- each assigned role to its descendant closure (inheritance).
CREATE OR REPLACE FUNCTION "public"."get_effective_permissions"("check_user_id" "uuid")
RETURNS "text"[]
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT COALESCE(array_agg(DISTINCT p."name"), ARRAY[]::text[])
    FROM public.user_roles ur
    CROSS JOIN LATERAL public.get_role_descendants(ur."role_id") AS d("role_id")
    JOIN public.role_permissions rp ON rp."role_id" = d."role_id"
    JOIN public.permissions p ON p."id" = rp."permission_id"
    WHERE ur."user_id" = check_user_id;
$$;

ALTER FUNCTION "public"."get_effective_permissions"("uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."get_effective_permissions"("uuid") IS 'Effective permission names for a user = permissions of all assigned roles plus their descendant roles (hierarchy inheritance).';


-- authorize: rewritten to honor hierarchy inheritance. Expands the current
-- user's roles to their descendant closure before checking role_permissions.
CREATE OR REPLACE FUNCTION "public"."authorize"("requested_permission" "text")
RETURNS boolean
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_effective_role_ids uuid[];
BEGIN
    -- Admins bypass all permission checks (admin = full access). This keeps the
    -- server in agreement with the client (UserInfo.hasPermission / canAccess
    -- short-circuit for admins), even for permissions outside admin's role
    -- closure (e.g. a brand-new permission a plugin introduces and gates on).
    IF public.is_user_admin(auth.uid()) THEN
        RETURN true;
    END IF;

    SELECT array_agg(DISTINCT d."role_id") INTO v_effective_role_ids
    FROM public.user_roles ur
    CROSS JOIN LATERAL public.get_role_descendants(ur."role_id") AS d("role_id")
    WHERE ur."user_id" = auth.uid();

    IF v_effective_role_ids IS NULL THEN
        RETURN false;
    END IF;

    RETURN EXISTS (
        SELECT 1
        FROM public.role_permissions rp
        JOIN public.permissions p ON p."id" = rp."permission_id"
        WHERE rp."role_id" = ANY(v_effective_role_ids)
          AND p."name" = requested_permission
    );
END;
$$;

ALTER FUNCTION "public"."authorize"("requested_permission" "text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."authorize"("requested_permission" "text") IS 'Check if the current user has a permission via their roles AND inherited (descendant) roles in the hierarchy.';


-- ============================================================================
-- SECTION 4: Grant delegation derived from the hierarchy
-- ============================================================================

-- get_grantable_role_ids: which roles a user may assign/revoke.
--   * Full admins may grant any role (preserves current "make admin" flow).
--   * Otherwise: roles strictly below the user's roles in the tree
--     (descendant closure minus the user's own roles).
CREATE OR REPLACE FUNCTION "public"."get_grantable_role_ids"("check_user_id" "uuid")
RETURNS SETOF "uuid"
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    -- Admins: every role
    SELECT r."id"
    FROM public.roles r
    WHERE public.is_user_admin(check_user_id)
    UNION
    -- Delegated: the STRICT descendants of each of the caller's roles (each
    -- role's own id excluded via <>, so you may grant roles below you but not
    -- your own tier). Excluding per-role (not the caller's whole role set) is
    -- important: a boss_admin also holds the auto-assigned `user` role, yet must
    -- still be able to grant `user` because it is strictly below boss_admin.
    SELECT DISTINCT d."role_id"
    FROM public.user_roles ur
    CROSS JOIN LATERAL public.get_role_descendants(ur."role_id") AS d("role_id")
    WHERE ur."user_id" = check_user_id
      AND d."role_id" <> ur."role_id";
$$;

ALTER FUNCTION "public"."get_grantable_role_ids"("uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."get_grantable_role_ids"("uuid") IS 'Role ids the user may assign/revoke: all roles for admins, otherwise strict descendants of the user''s roles.';


-- get_grantable_roles: JSON list of grantable roles for the current user
-- (used to populate the Admin: Roles assignment dropdown).
CREATE OR REPLACE FUNCTION "public"."get_grantable_roles"()
RETURNS "jsonb"
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_user_id uuid := auth.uid();
    v_roles jsonb;
BEGIN
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    SELECT jsonb_agg(
        jsonb_build_object(
            'id', r."id",
            'name', r."name",
            'description', r."description",
            'is_system', r."is_system"
        ) ORDER BY r."name"
    ) INTO v_roles
    FROM public.roles r
    WHERE r."id" IN (SELECT public.get_grantable_role_ids(v_user_id));

    RETURN jsonb_build_object('success', true, 'data', COALESCE(v_roles, '[]'::jsonb));
END;
$$;

ALTER FUNCTION "public"."get_grantable_roles"() OWNER TO "postgres";

COMMENT ON FUNCTION "public"."get_grantable_roles"() IS 'Returns roles the current user is allowed to assign (tree-derived delegation).';


-- ============================================================================
-- SECTION 5: Re-gate role-management RPCs to permission checks
-- ============================================================================
-- Each function below is identical to its original definition except the
-- authorization gate is swapped from is_user_admin() to authorize('<perm>').

-- 5.1 get_all_roles -> role.read
CREATE OR REPLACE FUNCTION "public"."get_all_roles"() RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_user_id UUID;
    v_roles JSONB;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.authorize('role.read') THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    SELECT jsonb_agg(
        jsonb_build_object(
            'id', id, 'name', name, 'description', description,
            'is_system', is_system, 'created_at', created_at, 'updated_at', updated_at
        ) ORDER BY name
    ) INTO v_roles FROM public.roles;

    RETURN jsonb_build_object('success', true, 'data', COALESCE(v_roles, '[]'::jsonb));
END;
$$;

-- 5.2 get_all_permissions -> role.read
CREATE OR REPLACE FUNCTION "public"."get_all_permissions"() RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_user_id UUID;
    v_permissions JSONB;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.authorize('role.read') THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    SELECT jsonb_agg(
        jsonb_build_object(
            'id', id, 'name', name, 'description', description,
            'is_system', is_system, 'created_at', created_at, 'updated_at', updated_at
        ) ORDER BY name
    ) INTO v_permissions FROM public.permissions;

    RETURN jsonb_build_object('success', true, 'data', COALESCE(v_permissions, '[]'::jsonb));
END;
$$;

-- 5.3 get_role_permissions -> role.read
CREATE OR REPLACE FUNCTION "public"."get_role_permissions"("role_name" "text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_user_id UUID;
    v_role_id UUID;
    v_permissions JSONB;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.authorize('role.read') THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    SELECT id INTO v_role_id FROM public.roles WHERE name = role_name;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', format('Role "%s" not found', role_name));
    END IF;

    SELECT jsonb_agg(p.name ORDER BY p.name)
    INTO v_permissions
    FROM public.role_permissions rp
    JOIN public.permissions p ON p.id = rp.permission_id
    WHERE rp.role_id = v_role_id;

    RETURN jsonb_build_object('success', true, 'role', role_name, 'permissions', COALESCE(v_permissions, '[]'::jsonb));
END;
$$;

-- 5.4 create_new_role -> role.create
CREATE OR REPLACE FUNCTION "public"."create_new_role"("role_name" "text", "description" "text" DEFAULT NULL::"text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $_$
DECLARE
    v_user_id UUID;
    v_role_id UUID;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.authorize('role.create') THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    IF NOT (role_name ~ '^[a-z][a-z0-9_]{2,50}$') THEN
        RETURN jsonb_build_object('success', false, 'error', 'Invalid role format');
    END IF;

    IF EXISTS (SELECT 1 FROM public.roles WHERE name = role_name) THEN
        RETURN jsonb_build_object('success', false, 'error', format('Role "%s" already exists', role_name));
    END IF;

    INSERT INTO public.roles (name, description, is_system)
    VALUES (role_name, description, false)
    RETURNING id INTO v_role_id;

    RETURN jsonb_build_object('success', true, 'message', format('Role "%s" created', role_name), 'role_id', v_role_id::text, 'role', role_name);
END;
$_$;

-- 5.5 create_new_permission -> role.create
CREATE OR REPLACE FUNCTION "public"."create_new_permission"("permission_name" "text", "description" "text" DEFAULT NULL::"text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $_$
DECLARE
    v_user_id UUID;
    v_permission_id UUID;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.authorize('role.create') THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    IF NOT (permission_name ~ '^[a-z][a-z0-9_]{1,30}\.[a-z][a-z0-9_]{1,30}$') THEN
        RETURN jsonb_build_object('success', false, 'error', 'Invalid permission format');
    END IF;

    IF EXISTS (SELECT 1 FROM public.permissions WHERE name = permission_name) THEN
        RETURN jsonb_build_object('success', false, 'error', format('Permission "%s" already exists', permission_name));
    END IF;

    INSERT INTO public.permissions (name, description, is_system)
    VALUES (permission_name, description, false)
    RETURNING id INTO v_permission_id;

    RETURN jsonb_build_object('success', true, 'message', format('Permission "%s" created', permission_name), 'permission_id', v_permission_id::text, 'permission', permission_name);
END;
$_$;

-- DELEGATION GUARD: assign/remove_permission_to_role require role.update. Admins
-- bypass (authorize() short-circuits). A NON-admin holder of role.update is
-- constrained to (a) roles strictly below it in the hierarchy
-- (get_grantable_role_ids) and (b) permissions it itself holds (authorize(perm)),
-- so a delegated role-manager can never mint an admin-level role. role.update
-- remains admin-only today; this is enforced, not just documented.
-- 5.6 assign_permission_to_role -> role.update (+ delegation guard)
CREATE OR REPLACE FUNCTION "public"."assign_permission_to_role"("role_name" "text", "permission_name" "text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_user_id UUID;
    v_role_id UUID;
    v_permission_id UUID;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.authorize('role.update') THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    SELECT id INTO v_role_id FROM public.roles WHERE name = role_name;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', format('Role "%s" not found', role_name));
    END IF;

    SELECT id INTO v_permission_id FROM public.permissions WHERE name = permission_name;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', format('Permission "%s" not found', permission_name));
    END IF;

    -- Delegation guard for non-admin role.update holders (see DELEGATION GUARD note).
    IF NOT public.is_user_admin(v_user_id) THEN
        IF v_role_id NOT IN (SELECT public.get_grantable_role_ids(v_user_id)) THEN
            RETURN jsonb_build_object('success', false, 'error', format('Permission denied: role "%s" is not in your delegated scope', role_name));
        END IF;
        IF NOT public.authorize(permission_name) THEN
            RETURN jsonb_build_object('success', false, 'error', format('Permission denied: cannot grant a permission you do not hold ("%s")', permission_name));
        END IF;
    END IF;

    INSERT INTO public.role_permissions (role_id, permission_id)
    VALUES (v_role_id, v_permission_id)
    ON CONFLICT (role_id, permission_id) DO NOTHING;

    RETURN jsonb_build_object('success', true, 'message', format('Permission "%s" assigned to role "%s"', permission_name, role_name));
END;
$$;

-- 5.7 remove_permission_from_role -> role.update
CREATE OR REPLACE FUNCTION "public"."remove_permission_from_role"("role_name" "text", "permission_name" "text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_user_id UUID;
    v_role_id UUID;
    v_permission_id UUID;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.authorize('role.update') THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    SELECT id INTO v_role_id FROM public.roles WHERE name = role_name;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', format('Role "%s" not found', role_name));
    END IF;

    SELECT id INTO v_permission_id FROM public.permissions WHERE name = permission_name;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', format('Permission "%s" not found', permission_name));
    END IF;

    -- Delegation guard for non-admin role.update holders (see DELEGATION GUARD note).
    IF NOT public.is_user_admin(v_user_id) THEN
        IF v_role_id NOT IN (SELECT public.get_grantable_role_ids(v_user_id)) THEN
            RETURN jsonb_build_object('success', false, 'error', format('Permission denied: role "%s" is not in your delegated scope', role_name));
        END IF;
        IF NOT public.authorize(permission_name) THEN
            RETURN jsonb_build_object('success', false, 'error', format('Permission denied: cannot modify a permission you do not hold ("%s")', permission_name));
        END IF;
    END IF;

    DELETE FROM public.role_permissions
    WHERE role_id = v_role_id AND permission_id = v_permission_id;

    RETURN jsonb_build_object('success', true, 'message', format('Permission "%s" removed from role "%s"', permission_name, role_name));
END;
$$;

-- 5.8 delete_role -> role.delete
CREATE OR REPLACE FUNCTION "public"."delete_role"("role_name" "text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_user_id UUID;
    v_role_record RECORD;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.authorize('role.delete') THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    SELECT * INTO v_role_record FROM public.roles WHERE name = role_name;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', format('Role "%s" not found', role_name));
    END IF;

    IF v_role_record.is_system THEN
        RETURN jsonb_build_object('success', false, 'error', 'Cannot delete system role');
    END IF;

    DELETE FROM public.roles WHERE name = role_name;
    RETURN jsonb_build_object('success', true, 'message', format('Role "%s" deleted', role_name));
END;
$$;

-- 5.9 delete_permission -> role.delete
CREATE OR REPLACE FUNCTION "public"."delete_permission"("permission_name" "text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_user_id UUID;
    v_perm_record RECORD;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.authorize('role.delete') THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    SELECT * INTO v_perm_record FROM public.permissions WHERE name = permission_name;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', format('Permission "%s" not found', permission_name));
    END IF;

    IF v_perm_record.is_system THEN
        RETURN jsonb_build_object('success', false, 'error', 'Cannot delete system permission');
    END IF;

    DELETE FROM public.permissions WHERE name = permission_name;
    RETURN jsonb_build_object('success', true, 'message', format('Permission "%s" deleted', permission_name));
END;
$$;


-- ============================================================================
-- SECTION 6: Delegated role assignment / removal (tree-derived grants)
-- ============================================================================

-- assign_role_to_user: now authorizes the caller. Full admins may assign any
-- role; otherwise the caller needs role.assign AND the target role must be one
-- the caller is allowed to grant (strictly below them in the hierarchy).
CREATE OR REPLACE FUNCTION "public"."assign_role_to_user"("target_user_id" "uuid", "target_role" "text") RETURNS boolean
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_role_id UUID;
    v_caller UUID := auth.uid();
BEGIN
    IF v_caller IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    SELECT id INTO v_role_id FROM public.roles WHERE name = target_role;
    IF v_role_id IS NULL THEN
        RAISE EXCEPTION 'Role % does not exist', target_role;
    END IF;

    IF NOT public.is_user_admin(v_caller) THEN
        IF NOT public.authorize('role.assign') THEN
            RAISE EXCEPTION 'Permission denied: role.assign required';
        END IF;
        IF v_role_id NOT IN (SELECT public.get_grantable_role_ids(v_caller)) THEN
            RAISE EXCEPTION 'Permission denied: you are not allowed to assign role %', target_role;
        END IF;
    END IF;

    INSERT INTO public.user_roles (user_id, role_id, assigned_by, assigned_at)
    VALUES (target_user_id, v_role_id, v_caller, NOW())
    ON CONFLICT (user_id, role_id) DO NOTHING;

    RETURN TRUE;
END;
$$;

COMMENT ON FUNCTION "public"."assign_role_to_user"("target_user_id" "uuid", "target_role" "text") IS 'Assign a role to a user. Admins may assign any role; other grantors need role.assign and may only assign roles strictly below them in the hierarchy.';

-- remove_role_from_user: same delegated-grant authorization; keeps the
-- "cannot remove your own admin role" safeguard.
CREATE OR REPLACE FUNCTION "public"."remove_role_from_user"("target_user_id" "uuid", "target_role" "text") RETURNS boolean
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_role_id UUID;
    v_caller UUID := auth.uid();
BEGIN
    IF v_caller IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    SELECT id INTO v_role_id FROM public.roles WHERE name = target_role;
    IF v_role_id IS NULL THEN
        RAISE EXCEPTION 'Role % does not exist', target_role;
    END IF;

    IF target_user_id = v_caller AND target_role = 'admin' THEN
        RAISE EXCEPTION 'Cannot remove your own admin role';
    END IF;

    IF NOT public.is_user_admin(v_caller) THEN
        IF NOT public.authorize('role.assign') THEN
            RAISE EXCEPTION 'Permission denied: role.assign required';
        END IF;
        IF v_role_id NOT IN (SELECT public.get_grantable_role_ids(v_caller)) THEN
            RAISE EXCEPTION 'Permission denied: you are not allowed to modify role %', target_role;
        END IF;
    END IF;

    DELETE FROM public.user_roles
    WHERE user_id = target_user_id AND role_id = v_role_id;

    RETURN TRUE;
END;
$$;

COMMENT ON FUNCTION "public"."remove_role_from_user"("target_user_id" "uuid", "target_role" "text") IS 'Remove a role from a user with the same delegated-grant rules as assignment. Cannot remove your own admin role.';


-- ============================================================================
-- SECTION 7: Inject effective permissions into the JWT (auth hook)
-- ============================================================================
-- The hook runs for BOTH magic-link and passkey logins, so this single change
-- covers all auth methods. Adds the `user_permissions` claim (effective set).
CREATE OR REPLACE FUNCTION "public"."custom_access_token_hook"("event" "jsonb") RETURNS "jsonb"
    LANGUAGE "plpgsql" STABLE
    AS $$
DECLARE
    claims jsonb;
    user_roles_array text[];
    user_perms_array text[];
    primary_role text;
    v_user_id uuid := (event->>'user_id')::uuid;
BEGIN
    claims := event->'claims';

    user_roles_array := public.get_user_roles_for_hook(v_user_id);
    user_perms_array := public.get_effective_permissions(v_user_id);

    IF user_roles_array IS NOT NULL AND array_length(user_roles_array, 1) > 0 THEN
        primary_role := user_roles_array[1];
    ELSE
        primary_role := 'user';
    END IF;

    IF user_roles_array IS NOT NULL THEN
        claims := jsonb_set(claims, '{user_role}', to_jsonb(primary_role));
        claims := jsonb_set(claims, '{user_roles}', to_jsonb(user_roles_array));
        IF 'admin' = ANY(user_roles_array) THEN
            claims := jsonb_set(claims, '{is_admin}', to_jsonb(true));
        ELSE
            claims := jsonb_set(claims, '{is_admin}', to_jsonb(false));
        END IF;
    ELSE
        claims := jsonb_set(claims, '{user_role}', to_jsonb('user'::text));
        claims := jsonb_set(claims, '{user_roles}', to_jsonb(ARRAY['user']::text[]));
        claims := jsonb_set(claims, '{is_admin}', to_jsonb(false));
    END IF;

    -- Effective permissions (own + inherited via the role hierarchy)
    claims := jsonb_set(claims, '{user_permissions}', to_jsonb(COALESCE(user_perms_array, ARRAY[]::text[])));

    event := jsonb_set(event, '{claims}', claims);
    RETURN event;
END;
$$;

ALTER FUNCTION "public"."custom_access_token_hook"("event" "jsonb") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."custom_access_token_hook"("event" "jsonb") IS 'Auth hook that injects user roles, is_admin, and effective permissions (user_permissions) into JWT claims.';


-- ============================================================================
-- SECTION 8: RLS — let permissioned (user.read) readers list users
-- ============================================================================
-- The users_with_roles view uses security_invoker, so the users-table policy
-- governs who can list ALL users. Broaden it from admin-only to role managers
-- (anyone whose JWT carries the role.read effective permission, i.e. admin +
-- boss_admin) — NOT user.read, which the baseline `user` role holds and would
-- therefore expose the full user list to every authenticated user. Plain users
-- still read their own row via the existing "Users can read own data" policy.
-- Uses JWT claims only -> no recursion.
DROP POLICY IF EXISTS "Admins can read all users" ON "public"."users";
DROP POLICY IF EXISTS "Privileged users can read all users" ON "public"."users";
CREATE POLICY "Privileged users can read all users" ON "public"."users"
    FOR SELECT
    USING (
        COALESCE((("auth"."jwt"() -> 'is_admin'::"text"))::boolean, false)
        OR (("auth"."jwt"() -> 'user_permissions'::"text") ? 'role.read')
    );

COMMENT ON POLICY "Privileged users can read all users" ON "public"."users" IS 'Allows admins (is_admin) or role managers (role.read in the user_permissions JWT claim) to list all users. Plain users read only their own row via "Users can read own data". Uses JWT claims to avoid recursion.';


-- ============================================================================
-- SECTION 9: Function privileges (least privilege)
-- ============================================================================
-- get_role_descendants / get_effective_permissions / get_grantable_role_ids are
-- INTERNAL helpers. They are SECURITY DEFINER and take an arbitrary user/role id,
-- so exposing them to anon/authenticated via PostgREST would let any caller
-- enumerate any user's authz model. They are only ever invoked from within other
-- SECURITY DEFINER functions (which run as the owner, not the caller) or from the
-- auth hook (supabase_auth_admin). Revoke the default PUBLIC grant and expose
-- them narrowly.
REVOKE EXECUTE ON FUNCTION "public"."get_role_descendants"("uuid") FROM PUBLIC, "anon", "authenticated";
REVOKE EXECUTE ON FUNCTION "public"."get_effective_permissions"("uuid") FROM PUBLIC, "anon", "authenticated";
REVOKE EXECUTE ON FUNCTION "public"."get_grantable_role_ids"("uuid") FROM PUBLIC, "anon", "authenticated";

-- The auth hook runs as supabase_auth_admin and needs the effective-permissions
-- helper to build the user_permissions claim.
GRANT EXECUTE ON FUNCTION "public"."get_effective_permissions"("uuid") TO "supabase_auth_admin";
GRANT EXECUTE ON FUNCTION "public"."get_role_descendants"("uuid") TO "supabase_auth_admin";

-- The only public surface: a self-scoped RPC (resolves the caller via auth.uid()).
GRANT EXECUTE ON FUNCTION "public"."get_grantable_roles"() TO "authenticated";

-- Defense in depth: 20251023000014_grants.sql granted assign/remove_role_from_user
-- to anon, and the original CREATE left the default PUBLIC grant in place. The
-- delegated-grant gate now rejects unauthenticated callers anyway (auth.uid() IS
-- NULL -> "Not authenticated"), but anon has no business calling these, so revoke
-- both PUBLIC and anon. authenticated/service_role keep their explicit grants
-- (from 20251023000014_grants.sql), so legitimate grantors are unaffected.
REVOKE EXECUTE ON FUNCTION "public"."assign_role_to_user"("uuid", "text") FROM PUBLIC, "anon";
REVOKE EXECUTE ON FUNCTION "public"."remove_role_from_user"("uuid", "text") FROM PUBLIC, "anon";

-- ============================================================================
-- End of File: 20260625000000_role_hierarchy_and_granular_rbac.sql
-- ============================================================================
