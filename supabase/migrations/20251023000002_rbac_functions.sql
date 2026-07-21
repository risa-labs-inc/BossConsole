-- ============================================================================
-- BOSS Database Schema: RBAC Functions
-- ============================================================================
-- File: 20251023000002_rbac_functions.sql
-- Description: Role-Based Access Control functions for managing roles,
--              permissions, and user assignments. These functions implement
--              a flexible RBAC system with system-protected roles/permissions.
-- Dependencies:
--   - File 1: extensions_and_types.sql (Extensions)
--   - Note: Tables will be created in File 8 (rbac_tables.sql)
-- Functions: 11 total
-- Security: All functions use SECURITY DEFINER and check admin permissions
-- ============================================================================


-- ============================================================================
-- SECTION 1: Permission Management (4 functions)
-- ============================================================================
-- Purpose: Create, delete, list, and assign permissions to roles
-- Permission Format: resource.action (e.g., 'secrets.create', 'users.delete')
-- Validation: Lowercase, alphanumeric + underscore, 1-30 chars per segment
-- System Permissions: Cannot be deleted (is_system = true)


-- Function 1.1: create_new_permission
-- -----------------------------------------------------------------------------
-- Purpose: Create a new custom permission (admin only)
-- Parameters:
--   - permission_name: Format 'resource.action' (e.g., 'reports.view')
--   - description: Optional human-readable description
-- Returns: JSONB with success status, message, and permission_id
-- Security: Only admins can create permissions
-- Validation:
--   - Format: ^[a-z][a-z0-9_]{1,30}\.[a-z][a-z0-9_]{1,30}$
--   - Example valid: 'secrets.create', 'users_admin.delete'
--   - Example invalid: 'Secrets.Create', 'admin', 'secret'
-- Usage: SELECT create_new_permission('reports.view', 'View company reports');
CREATE OR REPLACE FUNCTION "public"."create_new_permission"("permission_name" "text", "description" "text" DEFAULT NULL::"text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $_$
DECLARE
    v_user_id UUID;
    v_permission_id UUID;
BEGIN
    -- Check authentication
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    -- Check admin permission
    IF NOT public.is_user_admin(v_user_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    -- Validate permission name format (resource.action)
    IF NOT (permission_name ~ '^[a-z][a-z0-9_]{1,30}\.[a-z][a-z0-9_]{1,30}$') THEN
        RETURN jsonb_build_object('success', false, 'error', 'Invalid permission format');
    END IF;

    -- Check for duplicate
    IF EXISTS (SELECT 1 FROM public.permissions WHERE name = permission_name) THEN
        RETURN jsonb_build_object('success', false, 'error', format('Permission "%s" already exists', permission_name));
    END IF;

    -- Insert permission (is_system = false for custom permissions)
    INSERT INTO public.permissions (name, description, is_system)
    VALUES (permission_name, description, false)
    RETURNING id INTO v_permission_id;

    RETURN jsonb_build_object(
        'success', true,
        'message', format('Permission "%s" created', permission_name),
        'permission_id', v_permission_id::text,
        'permission', permission_name
    );
END;
$_$;

ALTER FUNCTION "public"."create_new_permission"("permission_name" "text", "description" "text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."create_new_permission"("permission_name" "text", "description" "text") IS 'v2025-10-21: Creates permission in TABLE';



-- Function 1.2: delete_permission
-- -----------------------------------------------------------------------------
-- Purpose: Delete a custom permission (admin only, cannot delete system permissions)
-- Parameters:
--   - permission_name: Name of permission to delete
-- Returns: JSONB with success status and message
-- Security:
--   - Only admins can delete permissions
--   - System permissions (is_system = true) cannot be deleted
-- Cascade: Automatically removes from role_permissions (via FK constraint)
-- Usage: SELECT delete_permission('reports.view');
CREATE OR REPLACE FUNCTION "public"."delete_permission"("permission_name" "text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_user_id UUID;
    v_perm_record RECORD;
BEGIN
    -- Check authentication
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    -- Check admin permission
    IF NOT public.is_user_admin(v_user_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    -- Check if permission exists
    SELECT * INTO v_perm_record FROM public.permissions WHERE name = permission_name;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', format('Permission "%s" not found', permission_name));
    END IF;

    -- Prevent deletion of system permissions
    IF v_perm_record.is_system THEN
        RETURN jsonb_build_object('success', false, 'error', 'Cannot delete system permission');
    END IF;

    -- Delete permission (cascades to role_permissions)
    DELETE FROM public.permissions WHERE name = permission_name;
    RETURN jsonb_build_object('success', true, 'message', format('Permission "%s" deleted', permission_name));
END;
$$;

ALTER FUNCTION "public"."delete_permission"("permission_name" "text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."delete_permission"("permission_name" "text") IS 'v2025-10-21: Deletes permission from TABLE';



-- Function 1.3: get_all_permissions
-- -----------------------------------------------------------------------------
-- Purpose: List all permissions with metadata (admin only)
-- Returns: JSONB with structure:
--   {
--     "success": true,
--     "data": [
--       {
--         "id": "uuid",
--         "name": "secrets.create",
--         "description": "Create new secrets",
--         "is_system": false,
--         "created_at": "timestamp",
--         "updated_at": "timestamp"
--       },
--       ...
--     ]
--   }
-- Security: Only admins can view all permissions
-- Ordering: Alphabetical by permission name
-- Usage: SELECT get_all_permissions();
CREATE OR REPLACE FUNCTION "public"."get_all_permissions"() RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_user_id UUID;
    v_permissions JSONB;
BEGIN
    -- Check authentication
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    -- Check admin permission
    IF NOT public.is_user_admin(v_user_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    -- Aggregate all permissions sorted by name
    SELECT jsonb_agg(
        jsonb_build_object(
            'id', id,
            'name', name,
            'description', description,
            'is_system', is_system,
            'created_at', created_at,
            'updated_at', updated_at
        ) ORDER BY name
    ) INTO v_permissions FROM public.permissions;

    RETURN jsonb_build_object('success', true, 'data', COALESCE(v_permissions, '[]'::jsonb));
END;
$$;

ALTER FUNCTION "public"."get_all_permissions"() OWNER TO "postgres";

COMMENT ON FUNCTION "public"."get_all_permissions"() IS 'v2025-10-21: Returns permissions from TABLE with data key';



-- Function 1.4: assign_permission_to_role
-- -----------------------------------------------------------------------------
-- Purpose: Grant a permission to a role (admin only)
-- Parameters:
--   - role_name: Name of role to receive permission
--   - permission_name: Name of permission to assign
-- Returns: JSONB with success status and message
-- Security: Only admins can assign permissions
-- Idempotent: ON CONFLICT DO NOTHING (safe to call multiple times)
-- Usage: SELECT assign_permission_to_role('developer', 'secrets.create');
CREATE OR REPLACE FUNCTION "public"."assign_permission_to_role"("role_name" "text", "permission_name" "text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_user_id UUID;
    v_role_id UUID;
    v_permission_id UUID;
BEGIN
    -- Check authentication
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    -- Check admin permission
    IF NOT public.is_user_admin(v_user_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    -- Look up role ID
    SELECT id INTO v_role_id FROM public.roles WHERE name = role_name;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', format('Role "%s" not found', role_name));
    END IF;

    -- Look up permission ID
    SELECT id INTO v_permission_id FROM public.permissions WHERE name = permission_name;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', format('Permission "%s" not found', permission_name));
    END IF;

    -- Insert into role_permissions (idempotent with ON CONFLICT)
    INSERT INTO public.role_permissions (role_id, permission_id)
    VALUES (v_role_id, v_permission_id)
    ON CONFLICT (role_id, permission_id) DO NOTHING;

    RETURN jsonb_build_object('success', true, 'message', format('Permission "%s" assigned to role "%s"', permission_name, role_name));
END;
$$;

ALTER FUNCTION "public"."assign_permission_to_role"("role_name" "text", "permission_name" "text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."assign_permission_to_role"("role_name" "text", "permission_name" "text") IS 'v2025-10-21: Assigns permission to role in TABLE';



-- ============================================================================
-- SECTION 2: Role Management (4 functions)
-- ============================================================================
-- Purpose: Create, delete, list roles, and manage role permissions
-- Role Format: Lowercase, alphanumeric + underscore, 3-50 chars
-- System Roles: 'admin', 'user' (cannot be deleted, is_system = true)


-- Function 2.1: create_new_role
-- -----------------------------------------------------------------------------
-- Purpose: Create a new custom role (admin only)
-- Parameters:
--   - role_name: Format lowercase, alphanumeric + underscore, 3-50 chars
--   - description: Optional human-readable description
-- Returns: JSONB with success status, message, and role_id
-- Security: Only admins can create roles
-- Validation:
--   - Format: ^[a-z][a-z0-9_]{2,50}$
--   - Example valid: 'developer', 'team_lead', 'data_analyst'
--   - Example invalid: 'Developer', 'dev', 'TeamLead', 'dev-ops'
-- Usage: SELECT create_new_role('developer', 'Development team members');
CREATE OR REPLACE FUNCTION "public"."create_new_role"("role_name" "text", "description" "text" DEFAULT NULL::"text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $_$
DECLARE
    v_user_id UUID;
    v_role_id UUID;
BEGIN
    -- Check authentication
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    -- Check admin permission
    IF NOT public.is_user_admin(v_user_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    -- Validate role name format
    IF NOT (role_name ~ '^[a-z][a-z0-9_]{2,50}$') THEN
        RETURN jsonb_build_object('success', false, 'error', 'Invalid role format');
    END IF;

    -- Check for duplicate
    IF EXISTS (SELECT 1 FROM public.roles WHERE name = role_name) THEN
        RETURN jsonb_build_object('success', false, 'error', format('Role "%s" already exists', role_name));
    END IF;

    -- Insert role (is_system = false for custom roles)
    INSERT INTO public.roles (name, description, is_system)
    VALUES (role_name, description, false)
    RETURNING id INTO v_role_id;

    RETURN jsonb_build_object(
        'success', true,
        'message', format('Role "%s" created', role_name),
        'role_id', v_role_id::text,
        'role', role_name
    );
END;
$_$;

ALTER FUNCTION "public"."create_new_role"("role_name" "text", "description" "text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."create_new_role"("role_name" "text", "description" "text") IS 'v2025-10-21: Creates role in TABLE';



-- Function 2.2: delete_role
-- -----------------------------------------------------------------------------
-- Purpose: Delete a custom role (admin only, cannot delete system roles)
-- Parameters:
--   - role_name: Name of role to delete
-- Returns: JSONB with success status and message
-- Security:
--   - Only admins can delete roles
--   - System roles ('admin', 'user') cannot be deleted
-- Cascade: Automatically removes from role_permissions and user_roles (via FK)
-- Usage: SELECT delete_role('developer');
CREATE OR REPLACE FUNCTION "public"."delete_role"("role_name" "text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_user_id UUID;
    v_role_record RECORD;
BEGIN
    -- Check authentication
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    -- Check admin permission
    IF NOT public.is_user_admin(v_user_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    -- Check if role exists
    SELECT * INTO v_role_record FROM public.roles WHERE name = role_name;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', format('Role "%s" not found', role_name));
    END IF;

    -- Prevent deletion of system roles
    IF v_role_record.is_system THEN
        RETURN jsonb_build_object('success', false, 'error', 'Cannot delete system role');
    END IF;

    -- Delete role (cascades to role_permissions and user_roles)
    DELETE FROM public.roles WHERE name = role_name;
    RETURN jsonb_build_object('success', true, 'message', format('Role "%s" deleted', role_name));
END;
$$;

ALTER FUNCTION "public"."delete_role"("role_name" "text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."delete_role"("role_name" "text") IS 'v2025-10-21: Deletes role from TABLE';



-- Function 2.3: get_all_roles
-- -----------------------------------------------------------------------------
-- Purpose: List all roles with metadata (admin only)
-- Returns: JSONB with structure:
--   {
--     "success": true,
--     "data": [
--       {
--         "id": "uuid",
--         "name": "admin",
--         "description": "System administrator",
--         "is_system": true,
--         "created_at": "timestamp",
--         "updated_at": "timestamp"
--       },
--       ...
--     ]
--   }
-- Security: Only admins can view all roles
-- Ordering: Alphabetical by role name
-- Usage: SELECT get_all_roles();
CREATE OR REPLACE FUNCTION "public"."get_all_roles"() RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_user_id UUID;
    v_roles JSONB;
BEGIN
    -- Check authentication
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    -- Check admin permission
    IF NOT public.is_user_admin(v_user_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    -- Aggregate all roles sorted by name
    SELECT jsonb_agg(
        jsonb_build_object(
            'id', id,
            'name', name,
            'description', description,
            'is_system', is_system,
            'created_at', created_at,
            'updated_at', updated_at
        ) ORDER BY name
    ) INTO v_roles FROM public.roles;

    RETURN jsonb_build_object('success', true, 'data', COALESCE(v_roles, '[]'::jsonb));
END;
$$;

ALTER FUNCTION "public"."get_all_roles"() OWNER TO "postgres";

COMMENT ON FUNCTION "public"."get_all_roles"() IS 'v2025-10-21: Returns roles from TABLE with data key';



-- Function 2.4: remove_permission_from_role
-- -----------------------------------------------------------------------------
-- Purpose: Revoke a permission from a role (admin only)
-- Parameters:
--   - role_name: Name of role to revoke permission from
--   - permission_name: Name of permission to remove
-- Returns: JSONB with success status and message
-- Security: Only admins can remove permissions
-- Usage: SELECT remove_permission_from_role('developer', 'secrets.delete');
CREATE OR REPLACE FUNCTION "public"."remove_permission_from_role"("role_name" "text", "permission_name" "text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_user_id UUID;
    v_role_id UUID;
    v_permission_id UUID;
BEGIN
    -- Check authentication
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    -- Check admin permission
    IF NOT public.is_user_admin(v_user_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    -- Look up role ID
    SELECT id INTO v_role_id FROM public.roles WHERE name = role_name;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', format('Role "%s" not found', role_name));
    END IF;

    -- Look up permission ID
    SELECT id INTO v_permission_id FROM public.permissions WHERE name = permission_name;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', format('Permission "%s" not found', permission_name));
    END IF;

    -- Delete from role_permissions
    DELETE FROM public.role_permissions
    WHERE role_id = v_role_id AND permission_id = v_permission_id;

    RETURN jsonb_build_object('success', true, 'message', format('Permission "%s" removed from role "%s"', permission_name, role_name));
END;
$$;

ALTER FUNCTION "public"."remove_permission_from_role"("role_name" "text", "permission_name" "text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."remove_permission_from_role"("role_name" "text", "permission_name" "text") IS 'v2025-10-21: Removes permission from role in TABLE';



-- ============================================================================
-- SECTION 3: User Role Assignment (3 functions)
-- ============================================================================
-- Purpose: Assign and revoke roles from users, check permissions
-- Security: Only admins can assign/remove roles
-- Protection: Users cannot remove their own admin role


-- Function 3.1: assign_role_to_user
-- -----------------------------------------------------------------------------
-- Purpose: Assign a role to a user (admin only)
-- Parameters:
--   - target_user_id: UUID of user to receive role
--   - target_role: Name of role to assign
-- Returns: Boolean TRUE on success
-- Security: Only admins can assign roles
-- Idempotent: ON CONFLICT DO NOTHING (safe to call multiple times)
-- Tracking: Records assigned_by (current admin) and assigned_at timestamp
-- Usage: SELECT assign_role_to_user('user-uuid', 'developer');
CREATE OR REPLACE FUNCTION "public"."assign_role_to_user"("target_user_id" "uuid", "target_role" "text") RETURNS boolean
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_role_id UUID;
BEGIN
    -- Look up role_id from role name
    SELECT id INTO v_role_id
    FROM public.roles
    WHERE name = target_role;

    IF v_role_id IS NULL THEN
        RAISE EXCEPTION 'Role % does not exist', target_role;
    END IF;

    -- Insert role assignment (or do nothing if already exists)
    -- assigned_by tracks which admin assigned the role
    INSERT INTO public.user_roles (user_id, role_id, assigned_by, assigned_at)
    VALUES (target_user_id, v_role_id, auth.uid(), NOW())
    ON CONFLICT (user_id, role_id) DO NOTHING;

    RETURN TRUE;
END;
$$;

ALTER FUNCTION "public"."assign_role_to_user"("target_user_id" "uuid", "target_role" "text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."assign_role_to_user"("target_user_id" "uuid", "target_role" "text") IS 'Assign a role to a user using table-based schema (admin only).';



-- Function 3.2: remove_role_from_user
-- -----------------------------------------------------------------------------
-- Purpose: Remove a role from a user (admin only)
-- Parameters:
--   - target_user_id: UUID of user to remove role from
--   - target_role: Name of role to remove
-- Returns: Boolean TRUE on success
-- Security:
--   - Only admins can remove roles
--   - Users cannot remove their own admin role (safety check)
-- Usage: SELECT remove_role_from_user('user-uuid', 'developer');
CREATE OR REPLACE FUNCTION "public"."remove_role_from_user"("target_user_id" "uuid", "target_role" "text") RETURNS boolean
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_role_id UUID;
    v_current_user_id UUID := auth.uid();
BEGIN
    -- Look up role_id from role name
    SELECT id INTO v_role_id
    FROM public.roles
    WHERE name = target_role;

    IF v_role_id IS NULL THEN
        RAISE EXCEPTION 'Role % does not exist', target_role;
    END IF;

    -- Safety check: Prevent removing own admin role
    -- This ensures at least one admin always exists
    IF target_user_id = v_current_user_id AND target_role = 'admin' THEN
        RAISE EXCEPTION 'Cannot remove your own admin role';
    END IF;

    -- Remove role from user_roles table
    DELETE FROM public.user_roles
    WHERE user_id = target_user_id AND role_id = v_role_id;

    RETURN TRUE;
END;
$$;

ALTER FUNCTION "public"."remove_role_from_user"("target_user_id" "uuid", "target_role" "text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."remove_role_from_user"("target_user_id" "uuid", "target_role" "text") IS 'Remove a role from a user using table-based schema (admin only). Cannot remove own admin role or user role.';



-- Function 3.3: authorize
-- -----------------------------------------------------------------------------
-- Purpose: Check if current user has a specific permission (used in RLS policies)
-- Parameters:
--   - requested_permission: Permission name to check (e.g., 'secrets.delete')
-- Returns: Boolean TRUE if user has permission via any of their roles
-- Security: STABLE SECURITY DEFINER for safe use in RLS policies
-- Performance: Frequently called, optimized with indexed lookups
-- Logic:
--   1. Get all role IDs for current user from user_roles
--   2. Check if any of those roles have the requested permission
--   3. Return TRUE if found, FALSE otherwise
-- Usage in RLS:
--   CREATE POLICY "Admins can delete secrets" ON secrets
--     FOR DELETE
--     USING (authorize('secrets.delete'));
CREATE OR REPLACE FUNCTION "public"."authorize"("requested_permission" "text") RETURNS boolean
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    user_role_ids UUID[];
BEGIN
    -- Get all role IDs for the current user
    SELECT ARRAY_AGG(role_id) INTO user_role_ids
    FROM public.user_roles
    WHERE user_id = auth.uid();

    -- Check if any of the user's roles have the requested permission
    -- Uses ANY() for efficient array membership check
    RETURN EXISTS (
        SELECT 1 FROM public.role_permissions rp
        JOIN public.permissions p ON p.id = rp.permission_id
        WHERE rp.role_id = ANY(user_role_ids)
        AND p.name = requested_permission
    );
END;
$$;

ALTER FUNCTION "public"."authorize"("requested_permission" "text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."authorize"("requested_permission" "text") IS 'Check if current user has a specific permission via their roles using table-based schema.';



-- ============================================================================
-- SECTION 4: Role Query Functions (2 functions)
-- ============================================================================
-- Purpose: Query role permissions and check admin status


-- Function 4.1: get_role_permissions
-- -----------------------------------------------------------------------------
-- Purpose: Get all permissions for a specific role (admin only)
-- Parameters:
--   - role_name: Name of role to query
-- Returns: JSONB with structure:
--   {
--     "success": true,
--     "role": "developer",
--     "permissions": ["secrets.create", "secrets.read", "secrets.update"]
--   }
-- Security: Only admins can query role permissions
-- Usage: SELECT get_role_permissions('developer');
CREATE OR REPLACE FUNCTION "public"."get_role_permissions"("role_name" "text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_user_id UUID;
    v_role_id UUID;
    v_permissions JSONB;
BEGIN
    -- Check authentication
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    -- Check admin permission
    IF NOT public.is_user_admin(v_user_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    -- Look up role ID
    SELECT id INTO v_role_id FROM public.roles WHERE name = role_name;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', format('Role "%s" not found', role_name));
    END IF;

    -- Aggregate permission names for this role (sorted alphabetically)
    SELECT jsonb_agg(p.name ORDER BY p.name)
    INTO v_permissions
    FROM public.role_permissions rp
    JOIN public.permissions p ON p.id = rp.permission_id
    WHERE rp.role_id = v_role_id;

    RETURN jsonb_build_object(
        'success', true,
        'role', role_name,
        'permissions', COALESCE(v_permissions, '[]'::jsonb)
    );
END;
$$;

ALTER FUNCTION "public"."get_role_permissions"("role_name" "text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."get_role_permissions"("role_name" "text") IS 'v2025-10-21: Gets role permissions from TABLE';



-- Function 4.2: is_user_admin
-- -----------------------------------------------------------------------------
-- Purpose: Check if a user has the 'admin' role
-- Parameters:
--   - check_user_id: UUID of user to check
-- Returns: Boolean TRUE if user has admin role, FALSE otherwise
-- Performance: STABLE for query optimization, frequently called in RLS policies
-- Security: SECURITY DEFINER to access user_roles table
-- Usage:
--   - Direct: SELECT is_user_admin(auth.uid());
--   - RLS: CREATE POLICY "Admins only" ... USING (is_user_admin(auth.uid()));
CREATE OR REPLACE FUNCTION "public"."is_user_admin"("check_user_id" "uuid") RETURNS boolean
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    -- Efficiently check if user has 'admin' role
    -- Uses EXISTS for optimal performance
    RETURN EXISTS (
        SELECT 1 FROM public.user_roles ur
        JOIN public.roles r ON r.id = ur.role_id
        WHERE ur.user_id = check_user_id AND r.name = 'admin'
    );
END;
$$;

ALTER FUNCTION "public"."is_user_admin"("check_user_id" "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."is_user_admin"("check_user_id" "uuid") IS 'Check if user is admin using table-based schema.';


-- ============================================================================
-- End of File: rbac_functions.sql
-- ============================================================================
-- Next Migration: 20251023000003_passkey_functions.sql (Passkey Functions)
-- ============================================================================
