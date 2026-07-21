-- ============================================================================
-- BOSS Database Schema: User Management Functions
-- ============================================================================
-- File: 20251023000006_user_functions.sql
-- Description: Core user management functions for lookup, deletion, and role
--              checking. These functions provide essential user operations for
--              admin interfaces and RBAC authorization checks.
-- Dependencies:
--   - File 1: extensions_and_types.sql (uuid-ossp extension)
--   - File 8: rbac_tables.sql (users, roles, user_roles tables)
--   - auth.users table (Supabase Auth)
-- Functions: 5 total
-- Access Control: Admin-only functions use is_user_admin() checks
-- ============================================================================


-- ============================================================================
-- SECTION 1: User Lookup (1 function)
-- ============================================================================
-- Purpose: Find users by email address


-- Function 1.1: find_user_by_email
-- -----------------------------------------------------------------------------
-- Purpose: Look up user by email address from Supabase Auth
-- Use Case: Admin interfaces, user management screens, share secret dialogs
-- Why Needed: Client applications don't have direct access to auth.users table
--             (RLS restrictions), so this function provides secure lookup
--
-- Lookup Source: auth.users table (Supabase Auth's user table)
-- Scope: Returns first matching user (LIMIT 1)
-- Email Matching: Case-sensitive exact match (Supabase Auth stores lowercase)
--
-- Parameters:
--   - p_email: Email address to search for (e.g., "user@example.com")
-- Returns: TABLE with columns:
--   - id: User UUID (auth.users.id)
--   - email: User's email address (TEXT)
-- Security: SECURITY DEFINER to read from auth.users table
-- Performance: Fast lookup via indexed auth.users.email column
-- NULL Handling: Returns empty set if no user found (not NULL or error)
-- Usage:
--   -- Look up user for sharing a secret
--   SELECT * FROM find_user_by_email('colleague@company.com');
--   -- Returns: id='550e8400-...', email='colleague@company.com'
--
--   -- Check if user exists before creating share
--   SELECT EXISTS(
--     SELECT 1 FROM find_user_by_email('user@example.com')
--   );
--   -- Returns: true or false
CREATE OR REPLACE FUNCTION "public"."find_user_by_email"("p_email" "text") RETURNS TABLE("id" "uuid", "email" "text")
    LANGUAGE "plpgsql" SECURITY DEFINER
    AS $$
BEGIN
  -- Query Supabase Auth users table
  -- SECURITY DEFINER allows reading auth.users despite RLS
  -- LIMIT 1 ensures single result (emails are unique in auth.users)
  RETURN QUERY
  SELECT au.id, au.email::TEXT
  FROM auth.users au
  WHERE au.email = p_email
  LIMIT 1;
END;
$$;

ALTER FUNCTION "public"."find_user_by_email"("p_email" "text") OWNER TO "postgres";



-- ============================================================================
-- SECTION 2: User Deletion (1 function)
-- ============================================================================
-- Purpose: Delete users with safety checks (admin only)


-- Function 2.1: delete_user
-- -----------------------------------------------------------------------------
-- Purpose: Delete a user and their associated data (admin only)
-- Use Case: Admin user management interface, user cleanup operations
-- Safety Checks (3 validation rules):
--   1. Caller must be admin (is_user_admin check)
--   2. Cannot delete yourself (prevent admin lockout)
--   3. Cannot delete other admins (prevent privilege escalation abuse)
--
-- Deletion Flow:
--   1. Verify caller is admin → raise exception if not
--   2. Check if target is self → raise exception if yes
--   3. Check if target is admin → raise exception if yes
--   4. Delete user's role assignments from user_roles table
--   5. Delete user record from public.users table
--   6. Return true (success)
--
-- Important Notes:
--   - This ONLY deletes from public.users table (application data)
--   - Does NOT delete from auth.users (Supabase Auth table)
--   - To fully delete user, must also call Supabase Auth Admin API:
--       supabase.auth.admin.deleteUser(userId)
--   - RLS policies will cascade cleanup for user's secrets, shares, etc.
--
-- Cascading Deletions (automatic via RLS and foreign keys):
--   - user_roles: Explicitly deleted in function
--   - secrets: Cascaded via ON DELETE CASCADE
--   - secret_shares: Cascaded via ON DELETE CASCADE
--   - user_passkeys: Cascaded via ON DELETE CASCADE
--   - passkey_challenges: Cascaded via ON DELETE CASCADE
--
-- Parameters:
--   - target_user_id: UUID of user to delete
-- Returns: BOOLEAN - true on success (never returns false, raises exception instead)
-- Security: SECURITY DEFINER with admin-only access (is_user_admin check)
-- Error Handling: Raises exceptions with descriptive messages
-- Transaction Safety: All deletions are atomic (rollback on error)
-- Usage:
--   -- Delete a non-admin user
--   SELECT delete_user('550e8400-e29b-41d4-a716-446655440000');
--   -- Returns: true
--
--   -- Error: Cannot delete admin
--   SELECT delete_user('<admin-user-id>');
--   -- ERROR: Cannot delete admin users. Remove admin role first.
--
--   -- Error: Cannot delete self
--   SELECT delete_user(auth.uid());
--   -- ERROR: Cannot delete your own account
--
-- Full User Deletion Workflow:
--   -- Step 1: Remove from application (this function)
--   SELECT delete_user('<user-id>');
--
--   -- Step 2: Remove from auth (Supabase Auth API - client-side)
--   -- await supabase.auth.admin.deleteUser(userId);
CREATE OR REPLACE FUNCTION "public"."delete_user"("target_user_id" "uuid") RETURNS boolean
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    -- Safety Check 1: Verify caller is admin
    -- Only admins can delete users
    IF NOT public.is_user_admin(auth.uid()) THEN
        RAISE EXCEPTION 'Only admins can delete users';
    END IF;

    -- Safety Check 2: Prevent self-deletion
    -- Prevents admin from accidentally locking themselves out
    IF target_user_id = auth.uid() THEN
        RAISE EXCEPTION 'Cannot delete your own account';
    END IF;

    -- Safety Check 3: Prevent deleting other admins
    -- Requires removing admin role first (two-step process for safety)
    IF public.is_user_admin(target_user_id) THEN
        RAISE EXCEPTION 'Cannot delete admin users. Remove admin role first.';
    END IF;

    -- Deletion Step 1: Remove role assignments
    -- Clean up user_roles table (many-to-many assignments)
    DELETE FROM public.user_roles WHERE user_id = target_user_id;

    -- Deletion Step 2: Remove user record
    -- Cascading foreign keys will clean up related data:
    --   - secrets (ON DELETE CASCADE)
    --   - secret_shares (ON DELETE CASCADE)
    --   - user_passkeys (ON DELETE CASCADE)
    --   - passkey_challenges (ON DELETE CASCADE)
    DELETE FROM public.users WHERE id = target_user_id;

    -- Note: Supabase Auth user record (auth.users) is NOT deleted here
    -- Must be deleted separately via Supabase Auth Admin API:
    --   await supabase.auth.admin.deleteUser(target_user_id)

    RETURN true;
END;
$$;

ALTER FUNCTION "public"."delete_user"("target_user_id" "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."delete_user"("target_user_id" "uuid") IS 'Delete a user and their associated data (admin only). Cannot delete self or other admins.';



-- ============================================================================
-- SECTION 3: Role Query Functions (1 function)
-- ============================================================================
-- Purpose: Retrieve user's assigned roles


-- Function 3.1: get_user_roles
-- -----------------------------------------------------------------------------
-- Purpose: Get all role names assigned to a user
-- Use Case: Check user permissions, display roles in UI, RBAC authorization
-- Returns: SETOF text (one row per role name)
-- Result Format: Simple list of role names (not UUIDs or objects)
--
-- Query Logic:
--   1. JOIN user_roles with roles table
--   2. Filter by user_id
--   3. Return role names ordered by assignment date (oldest first)
--
-- Parameters:
--   - check_user_id: UUID of user to check
-- Returns: SETOF text - Role names (one row per role)
--   - Example: 'admin', 'editor', 'viewer'
-- Performance: Fast lookup via indexed user_roles.user_id and JOIN
-- NULL Handling: Returns empty set if user has no roles
-- Ordering: Roles ordered by assigned_at timestamp (oldest first)
-- Function Type: STABLE (does not modify database, results stable within transaction)
-- Security: SECURITY DEFINER to read from user_roles and roles tables
-- Usage:
--   -- Get all roles for a user
--   SELECT * FROM get_user_roles('550e8400-e29b-41d4-a716-446655440000');
--   -- Returns (example):
--   -- 'admin'
--   -- 'editor'
--
--   -- Check if user has any roles
--   SELECT COUNT(*) FROM get_user_roles(auth.uid());
--   -- Returns: 2 (if user has 2 roles)
--
--   -- Check if user has specific role (use user_has_role instead for boolean)
--   SELECT 'admin' IN (SELECT * FROM get_user_roles(auth.uid()));
--   -- Returns: true or false
--
-- Comparison with Related Functions:
--   - get_user_roles() → SETOF text (simple list)
--   - get_user_roles_for_hook() → text[] (array for JWT claims)
--   - get_user_roles_with_names() → jsonb (full details for client app)
CREATE OR REPLACE FUNCTION "public"."get_user_roles"("check_user_id" "uuid") RETURNS SETOF "text"
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    -- Query user_roles and JOIN with roles table
    -- Returns role names (not UUIDs) for easier consumption
    -- Ordered by assigned_at to show assignment chronology
    RETURN QUERY
    SELECT r.name FROM public.user_roles ur
    JOIN public.roles r ON r.id = ur.role_id
    WHERE ur.user_id = check_user_id
    ORDER BY ur.assigned_at;
END;
$$;

ALTER FUNCTION "public"."get_user_roles"("check_user_id" "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."get_user_roles"("check_user_id" "uuid") IS 'Returns all role names assigned to a user using table-based schema.';



-- ============================================================================
-- SECTION 4: Permission Check Functions (2 functions)
-- ============================================================================
-- Purpose: Boolean checks for admin status and specific roles


-- Function 4.1: is_user_admin
-- -----------------------------------------------------------------------------
-- Purpose: Check if a user has admin role (boolean check)
-- Use Case: Authorization gates, admin-only features, permission checks
-- Why Needed: Centralized admin check used by:
--   - Other functions (delete_user, create_new_role, etc.)
--   - RLS policies (admin bypass rules)
--   - Client application (show/hide admin features)
--
-- Check Logic:
--   1. JOIN user_roles with roles table
--   2. Filter by user_id AND role name = 'admin'
--   3. Return true if any row found (user is admin)
--   4. Return false if no rows (user is not admin)
--
-- Parameters:
--   - check_user_id: UUID of user to check
-- Returns: BOOLEAN
--   - true: User has admin role
--   - false: User does not have admin role (or user doesn't exist)
-- Performance: Fast lookup via indexed user_roles.user_id and JOIN
-- Function Type: STABLE (does not modify database, results stable within transaction)
-- Security: SECURITY DEFINER to read from user_roles and roles tables
-- NULL Handling: Returns false if user_id is NULL or doesn't exist
-- Usage:
--   -- Check if current user is admin
--   SELECT is_user_admin(auth.uid());
--   -- Returns: true or false
--
--   -- Use in authorization check (function)
--   IF NOT is_user_admin(auth.uid()) THEN
--     RAISE EXCEPTION 'Admin access required';
--   END IF;
--
--   -- Use in RLS policy (admin bypass)
--   CREATE POLICY "Admins can view all secrets"
--     ON secrets FOR SELECT
--     USING (is_user_admin(auth.uid()) OR user_id = auth.uid());
--
-- Common Patterns:
--   -- Guard clause in admin functions
--   IF NOT public.is_user_admin(auth.uid()) THEN
--     RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
--   END IF;
CREATE OR REPLACE FUNCTION "public"."is_user_admin"("check_user_id" "uuid") RETURNS boolean
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    -- Check if user has 'admin' role
    -- EXISTS is efficient - stops at first match
    -- Returns true if user is admin, false otherwise
    RETURN EXISTS (
        SELECT 1 FROM public.user_roles ur
        JOIN public.roles r ON r.id = ur.role_id
        WHERE ur.user_id = check_user_id AND r.name = 'admin'
    );
END;
$$;

ALTER FUNCTION "public"."is_user_admin"("check_user_id" "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."is_user_admin"("check_user_id" "uuid") IS 'Check if user is admin using table-based schema.';



-- Function 4.2: user_has_role
-- -----------------------------------------------------------------------------
-- Purpose: Check if a user has a specific role (boolean check)
-- Use Case: Fine-grained RBAC checks, conditional UI rendering, permission gates
-- Difference from is_user_admin:
--   - is_user_admin() checks for 'admin' role only (common case)
--   - user_has_role() checks for ANY specified role (flexible)
--
-- Check Logic:
--   1. JOIN user_roles with roles table
--   2. Filter by user_id AND role name (parameter)
--   3. Return true if any row found (user has role)
--   4. Return false if no rows (user doesn't have role)
--
-- Parameters:
--   - check_user_id: UUID of user to check
--   - check_role: Role name to check for (e.g., 'admin', 'editor', 'viewer')
-- Returns: BOOLEAN
--   - true: User has the specified role
--   - false: User does not have the role (or user doesn't exist)
-- Performance: Fast lookup via indexed user_roles.user_id and JOIN
-- Function Type: STABLE (does not modify database, results stable within transaction)
-- Security: SECURITY DEFINER to read from user_roles and roles tables
-- Case Sensitivity: Role name comparison is case-sensitive ('admin' ≠ 'Admin')
-- NULL Handling: Returns false if user_id or role name is NULL
-- Usage:
--   -- Check if user has editor role
--   SELECT user_has_role(auth.uid(), 'editor');
--   -- Returns: true or false
--
--   -- Use in conditional logic
--   IF user_has_role(auth.uid(), 'viewer') THEN
--     -- Grant read-only access
--   END IF;
--
--   -- Use in RLS policy (role-specific access)
--   CREATE POLICY "Editors can update posts"
--     ON posts FOR UPDATE
--     USING (user_has_role(auth.uid(), 'editor'));
--
--   -- Check multiple roles (OR logic)
--   SELECT user_has_role(auth.uid(), 'admin') OR user_has_role(auth.uid(), 'moderator');
--   -- Returns: true if user has either role
--
-- Common Patterns:
--   -- Guard clause for role-specific features
--   IF NOT user_has_role(auth.uid(), 'publisher') THEN
--     RETURN jsonb_build_object('success', false, 'error', 'Publisher role required');
--   END IF;
CREATE OR REPLACE FUNCTION "public"."user_has_role"("check_user_id" "uuid", "check_role" "text") RETURNS boolean
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    -- Check if user has specified role
    -- EXISTS is efficient - stops at first match
    -- Returns true if user has role, false otherwise
    RETURN EXISTS (
        SELECT 1 FROM public.user_roles ur
        JOIN public.roles r ON r.id = ur.role_id
        WHERE ur.user_id = check_user_id AND r.name = check_role
    );
END;
$$;

ALTER FUNCTION "public"."user_has_role"("check_user_id" "uuid", "check_role" "text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."user_has_role"("check_user_id" "uuid", "check_role" "text") IS 'Check if a user has a specific role using table-based schema.';


-- ============================================================================
-- End of File: user_functions.sql
-- ============================================================================
-- Next Migration: 20251023000007_helper_functions.sql (Helper Functions)
-- ============================================================================
