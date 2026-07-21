-- ============================================================================
-- BOSS Database Schema: Helper Functions
-- ============================================================================
-- File: 20251023000007_helper_functions.sql
-- Description: Helper functions for RBAC queries, auth hooks, user lifecycle
--              triggers, and automatic cleanup. These support core functionality
--              but aren't primary business logic operations.
-- Dependencies:
--   - File 1: extensions_and_types.sql
--   - File 2: rbac_functions.sql (is_user_admin)
--   - File 6: user_functions.sql (user-related queries)
--   - File 8: rbac_tables.sql (roles, permissions, role_permissions tables)
--   - File 9: passkey_tables.sql (passkey_challenges, completed_authentications)
-- Functions: 9 total
-- Categories: RBAC query helpers, auth hooks, lifecycle triggers, cleanup
-- ============================================================================


-- ============================================================================
-- SECTION 1: RBAC Query Helpers (2 functions)
-- ============================================================================
-- Purpose: Query role permissions for admin interfaces and client compatibility


-- Function 1.1: get_role_permissions
-- -----------------------------------------------------------------------------
-- Purpose: Get all permissions assigned to a role (admin only)
-- Use Case: Admin interface showing role details, permission management UI
-- Access Control: Admin-only (is_user_admin check)
-- Return Format: JSONB with success/error structure
--
-- Query Logic:
--   1. Check if caller is authenticated → error if not
--   2. Check if caller is admin → error if not
--   3. Look up role_id from role name → error if not found
--   4. Query role_permissions table and JOIN with permissions
--   5. Aggregate permission names into JSON array
--   6. Return success with role name and permissions array
--
-- Parameters:
--   - role_name: Name of role to query (e.g., 'admin', 'editor', 'viewer')
-- Returns: JSONB
--   Success: {"success": true, "role": "editor", "permissions": ["posts.edit", "posts.view"]}
--   Error: {"success": false, "error": "Not authenticated | Permission denied | Role not found"}
-- Security: SECURITY DEFINER with admin-only access check
-- Performance: Fast lookup via indexed role_permissions.role_id
-- NULL Handling: Returns empty array [] if role has no permissions
-- Usage:
--   -- Get permissions for editor role
--   SELECT get_role_permissions('editor');
--   -- Returns: {"success": true, "role": "editor", "permissions": ["posts.edit"]}
--
--   -- Error: Non-admin user
--   SELECT get_role_permissions('admin');
--   -- Returns: {"success": false, "error": "Permission denied"}
--
-- Comparison with Related Function:
--   - get_role_permissions() → Admin-only, error handling (for API endpoints)
--   - get_role_permissions_with_names() → Public, detailed format (for client app)
CREATE OR REPLACE FUNCTION "public"."get_role_permissions"("role_name" "text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_user_id UUID;
    v_role_id UUID;
    v_permissions JSONB;
BEGIN
    -- Auth Check: Ensure user is logged in
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    -- Permission Check: Only admins can query role permissions
    IF NOT public.is_user_admin(v_user_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    -- Lookup Role: Get role_id from role name
    SELECT id INTO v_role_id FROM public.roles WHERE name = role_name;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', format('Role "%s" not found', role_name));
    END IF;

    -- Query Permissions: JOIN role_permissions with permissions table
    -- Aggregate permission names into JSON array, ordered alphabetically
    SELECT jsonb_agg(p.name ORDER BY p.name)
    INTO v_permissions
    FROM public.role_permissions rp
    JOIN public.permissions p ON p.id = rp.permission_id
    WHERE rp.role_id = v_role_id;

    -- Return Success: Include role name and permissions (empty array if none)
    RETURN jsonb_build_object(
        'success', true,
        'role', role_name,
        'permissions', COALESCE(v_permissions, '[]'::jsonb)
    );
END;
$$;

ALTER FUNCTION "public"."get_role_permissions"("role_name" "text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."get_role_permissions"("role_name" "text") IS 'v2025-10-21: Gets role permissions from TABLE';



-- Function 1.2: get_role_permissions_with_names
-- -----------------------------------------------------------------------------
-- Purpose: Get role permissions with full details (for client compatibility)
-- Use Case: Client application (RoleService.kt) needs detailed permission info
-- Access Control: Public (no admin check) - assumes client-side filtering
-- Return Format: JSONB array with permission details
--
-- Query Logic:
--   1. Look up role_id from role name → return [] if not found
--   2. Query role_permissions table and JOIN with permissions
--   3. Build JSONB objects with permission details (id, role, permission, created_at)
--   4. Aggregate into JSON array ordered by permission name
--   5. Return array (empty [] if no permissions)
--
-- Difference from get_role_permissions:
--   - No auth/admin checks (client-side responsibility)
--   - Returns detailed objects (not just permission names)
--   - Returns [] instead of error if role not found
--
-- Parameters:
--   - role_name: Name of role to query (e.g., 'admin', 'editor')
-- Returns: JSONB array
--   Success: [{"id": "uuid", "role": "editor", "permission": "posts.edit", "created_at": "2024-10-21..."}]
--   Not Found: []
-- Performance: Fast lookup via indexed role_permissions.role_id
-- NULL Handling: Returns [] if role not found or has no permissions
-- Usage:
--   -- Get detailed permissions for editor role
--   SELECT get_role_permissions_with_names('editor');
--   -- Returns: [
--   --   {"id": "550e...", "role": "editor", "permission": "posts.edit", "created_at": "2024..."},
--   --   {"id": "660f...", "role": "editor", "permission": "posts.view", "created_at": "2024..."}
--   -- ]
--
--   -- Role not found
--   SELECT get_role_permissions_with_names('nonexistent');
--   -- Returns: []
--
-- Client Usage (Kotlin):
--   val permissions = supabase.functions.invoke("get_role_permissions_with_names")
--   // Returns list of RolePermission objects for UI rendering
CREATE OR REPLACE FUNCTION "public"."get_role_permissions_with_names"("role_name" "text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_role_id UUID;
    v_permissions JSONB;
BEGIN
    -- Lookup Role: Get role_id from role name
    -- No error if not found - just return empty array
    SELECT id INTO v_role_id
    FROM public.roles
    WHERE name = role_name;

    IF v_role_id IS NULL THEN
        RETURN '[]'::jsonb;
    END IF;

    -- Query Permissions: JOIN role_permissions with permissions table
    -- Build detailed JSONB objects with all permission info
    -- UUIDs cast to text for client compatibility (some JSON libs struggle with UUIDs)
    SELECT jsonb_agg(
        jsonb_build_object(
            'id', rp.id::text,
            'role', role_name,
            'permission', p.name,
            'created_at', rp.created_at::text
        )
        ORDER BY p.name  -- Alphabetical order for consistent UI
    ) INTO v_permissions
    FROM public.role_permissions rp
    JOIN public.permissions p ON p.id = rp.permission_id
    WHERE rp.role_id = v_role_id;

    -- Return array (empty [] if no permissions assigned)
    RETURN COALESCE(v_permissions, '[]'::jsonb);
END;
$$;

ALTER FUNCTION "public"."get_role_permissions_with_names"("role_name" "text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."get_role_permissions_with_names"("role_name" "text") IS 'Returns role permissions with names (not UUIDs) for backward compatibility with RoleService.kt';



-- ============================================================================
-- SECTION 2: User Role Query Helpers (2 functions)
-- ============================================================================
-- Purpose: Query user roles in different formats for auth hooks and client app


-- Function 2.1: get_user_roles_for_hook
-- -----------------------------------------------------------------------------
-- Purpose: Get user roles as text[] array for JWT claims injection
-- Use Case: Called by custom_access_token_hook to inject roles into JWT
-- Return Format: text[] (PostgreSQL array type, not JSONB)
-- Why Needed: Auth hooks expect text[] format for array claims
--
-- Query Logic:
--   1. JOIN user_roles with roles table
--   2. Aggregate role names into PostgreSQL array (ARRAY_AGG)
--   3. Order by assigned_at (chronological assignment order)
--   4. Return text[] array (NULL if user has no roles)
--
-- Parameters:
--   - check_user_id: UUID of user to check
-- Returns: text[] - PostgreSQL array of role names
--   Example: '{admin,editor}' (PostgreSQL array notation)
--   No roles: NULL (not empty array)
-- Performance: Fast lookup via indexed user_roles.user_id
-- NULL Handling: Returns NULL if user has no roles (auth hook handles this)
-- Function Type: None specified (regular function, not STABLE/IMMUTABLE)
-- Security: SECURITY DEFINER to read from user_roles table
-- Usage:
--   -- Called by custom_access_token_hook
--   user_roles_array := public.get_user_roles_for_hook((event->>'user_id')::uuid);
--   -- Returns: '{admin,editor}' or NULL
--
--   -- Direct SQL query (returns PostgreSQL array)
--   SELECT get_user_roles_for_hook('550e8400-e29b-41d4-a716-446655440000');
--   -- Returns: {admin,editor}
--
-- Comparison with Related Functions:
--   - get_user_roles() → SETOF text (one row per role)
--   - get_user_roles_for_hook() → text[] (array for JWT claims)
--   - get_user_roles_with_names() → jsonb (detailed objects for client)
CREATE OR REPLACE FUNCTION "public"."get_user_roles_for_hook"("check_user_id" "uuid") RETURNS "text"[]
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public'
    AS $$
BEGIN
    -- Query user_roles and JOIN with roles table
    -- ARRAY_AGG aggregates role names into PostgreSQL array
    -- Ordered by assigned_at to preserve assignment chronology
    RETURN (
        SELECT ARRAY_AGG(r.name ORDER BY ur.assigned_at)
        FROM public.user_roles ur
        JOIN public.roles r ON r.id = ur.role_id
        WHERE ur.user_id = check_user_id
    );
END;
$$;

ALTER FUNCTION "public"."get_user_roles_for_hook"("check_user_id" "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."get_user_roles_for_hook"("check_user_id" "uuid") IS 'Helper function for auth hook - returns user roles from table-based schema (REFRESHED)';



-- Function 2.2: get_user_roles_with_names
-- -----------------------------------------------------------------------------
-- Purpose: Get user roles with full details (for client compatibility)
-- Use Case: Client application (RoleService.kt) needs detailed role info
-- Return Format: JSONB array with role assignment details
-- Why Needed: Client needs assignment metadata (who assigned, when, etc.)
--
-- Query Logic:
--   1. JOIN user_roles with roles table
--   2. Build JSONB objects with role assignment details
--   3. Aggregate into JSON array ordered by assigned_at
--   4. Return array (empty [] if no roles assigned)
--
-- Parameters:
--   - target_user_id: UUID of user to query
-- Returns: JSONB array
--   Example: [
--     {"id": "uuid", "user_id": "uuid", "role": "admin", "assigned_by": "uuid", "assigned_at": "2024..."},
--     {"id": "uuid", "user_id": "uuid", "role": "editor", "assigned_by": "uuid", "assigned_at": "2024..."}
--   ]
--   No roles: []
-- Performance: Fast lookup via indexed user_roles.user_id
-- NULL Handling: Returns [] if user has no roles
-- Security: SECURITY DEFINER to read from user_roles table
-- Usage:
--   -- Get detailed roles for user
--   SELECT get_user_roles_with_names('550e8400-e29b-41d4-a716-446655440000');
--   -- Returns JSONB array with full role assignment details
--
-- Client Usage (Kotlin):
--   val roles = supabase.functions.invoke("get_user_roles_with_names")
--   // Returns list of UserRole objects with assignment metadata
--
-- Comparison with Related Functions:
--   - get_user_roles() → SETOF text (simple list)
--   - get_user_roles_for_hook() → text[] (array for JWT)
--   - get_user_roles_with_names() → jsonb (detailed objects for client)
CREATE OR REPLACE FUNCTION "public"."get_user_roles_with_names"("target_user_id" "uuid") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_roles JSONB;
BEGIN
    -- Query user_roles table and JOIN with roles to get role names
    -- Build detailed JSONB objects with all assignment metadata
    -- UUIDs cast to text for client compatibility
    SELECT jsonb_agg(
        jsonb_build_object(
            'id', ur.id::text,
            'user_id', ur.user_id::text,
            'role', r.name,
            'assigned_by', ur.assigned_by::text,
            'assigned_at', ur.assigned_at::text,
            'created_at', ur.created_at::text
        )
        ORDER BY ur.assigned_at  -- Chronological order
    ) INTO v_roles
    FROM public.user_roles ur
    JOIN public.roles r ON r.id = ur.role_id
    WHERE ur.user_id = target_user_id;

    -- Return array (empty [] if no roles assigned)
    RETURN COALESCE(v_roles, '[]'::jsonb);
END;
$$;

ALTER FUNCTION "public"."get_user_roles_with_names"("target_user_id" "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."get_user_roles_with_names"("target_user_id" "uuid") IS 'Returns user roles with role names (not UUIDs) for backward compatibility with RoleService.kt';



-- ============================================================================
-- SECTION 3: Authentication Hook (1 function)
-- ============================================================================
-- Purpose: Inject custom claims into JWT access tokens


-- Function 3.1: custom_access_token_hook
-- -----------------------------------------------------------------------------
-- Purpose: Supabase auth hook that injects user roles into JWT claims
-- Use Case: Automatically add RBAC claims to JWT during login/token refresh
-- Hook Type: Custom Access Token Hook (configured in Supabase Dashboard)
-- Trigger: Called automatically during:
--   - User login (password, magic link, OAuth, passkey)
--   - Token refresh (every hour by default)
--   - Session creation
--
-- Configuration (Supabase Dashboard):
--   1. Navigate to: Authentication → Hooks → Custom Access Token Hook
--   2. Enable hook and set SQL function: public.custom_access_token_hook
--   3. Hook runs automatically on every auth event
--
-- Claims Injected:
--   - user_role: Primary role (first assigned role, or 'user' if none)
--   - user_roles: Array of all role names (e.g., ['admin', 'editor'])
--   - is_admin: Boolean flag (true if user has 'admin' role)
--
-- Event Structure (input):
--   {
--     "user_id": "550e8400-...",
--     "claims": {
--       "sub": "550e8400-...",
--       "email": "user@example.com",
--       ...
--     }
--   }
--
-- Event Structure (output):
--   {
--     "user_id": "550e8400-...",
--     "claims": {
--       "sub": "550e8400-...",
--       "email": "user@example.com",
--       "user_role": "admin",
--       "user_roles": ["admin", "editor"],
--       "is_admin": true
--     }
--   }
--
-- Logic Flow:
--   1. Extract claims from event
--   2. Call get_user_roles_for_hook to fetch user's roles
--   3. Set primary_role (first role, or 'user' if none)
--   4. Inject user_role claim (primary role)
--   5. Inject user_roles claim (all roles array)
--   6. Inject is_admin claim (true if 'admin' in roles)
--   7. If user has no roles, set defaults ('user', ['user'], false)
--   8. Return modified event with updated claims
--
-- Parameters:
--   - event: JSONB event from Supabase Auth (contains user_id and claims)
-- Returns: JSONB - Modified event with injected claims
-- Function Type: STABLE (doesn't modify database, but depends on current user_roles)
-- Security: No SECURITY DEFINER (runs with auth context)
-- Performance: Fast via get_user_roles_for_hook helper
-- Usage:
--   -- Configured in Supabase Dashboard, not called directly
--   -- Auth automatically calls this function during login/token refresh
--
-- Client Usage (Kotlin):
--   val session = supabase.auth.signIn(email, password)
--   val roles = session.user.userMetadata["user_roles"] as List<String>
--   val isAdmin = session.user.userMetadata["is_admin"] as Boolean
--
-- Default User Behavior:
--   - New users with no roles get: user_role='user', user_roles=['user'], is_admin=false
--   - Ensures JWT always has role claims (never missing)
CREATE OR REPLACE FUNCTION "public"."custom_access_token_hook"("event" "jsonb") RETURNS "jsonb"
    LANGUAGE "plpgsql" STABLE
    AS $$
DECLARE
    claims jsonb;
    user_roles_array text[];
    primary_role text;
BEGIN
    -- Step 1: Extract claims from the auth event
    claims := event->'claims';

    -- Step 2: Fetch user's roles using helper function
    -- Returns text[] array (e.g., '{admin,editor}') or NULL if no roles
    user_roles_array := public.get_user_roles_for_hook((event->>'user_id')::uuid);

    -- Step 3: Determine primary role (first role, or 'user' default)
    IF user_roles_array IS NOT NULL AND array_length(user_roles_array, 1) > 0 THEN
        primary_role := user_roles_array[1];
    ELSE
        primary_role := 'user';
    END IF;

    -- Step 4-6: Inject claims based on user's roles
    IF user_roles_array IS NOT NULL THEN
        -- User has roles: Set actual roles and check for admin
        claims := jsonb_set(claims, '{user_role}', to_jsonb(primary_role));
        claims := jsonb_set(claims, '{user_roles}', to_jsonb(user_roles_array));

        -- Set is_admin flag if 'admin' role present
        IF 'admin' = ANY(user_roles_array) THEN
            claims := jsonb_set(claims, '{is_admin}', to_jsonb(true));
        ELSE
            claims := jsonb_set(claims, '{is_admin}', to_jsonb(false));
        END IF;
    ELSE
        -- User has no roles: Set defaults
        claims := jsonb_set(claims, '{user_role}', to_jsonb('user'::text));
        claims := jsonb_set(claims, '{user_roles}', to_jsonb(ARRAY['user']::text[]));
        claims := jsonb_set(claims, '{is_admin}', to_jsonb(false));
    END IF;

    -- Step 7: Update the event with modified claims
    event := jsonb_set(event, '{claims}', claims);

    RETURN event;
END;
$$;

ALTER FUNCTION "public"."custom_access_token_hook"("event" "jsonb") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."custom_access_token_hook"("event" "jsonb") IS 'Auth hook that injects user roles into JWT claims (REFRESHED)';



-- ============================================================================
-- SECTION 4: User Lifecycle Triggers (2 functions)
-- ============================================================================
-- Purpose: Automatically maintain user data on signup and email changes


-- Function 4.1: handle_new_user
-- -----------------------------------------------------------------------------
-- Purpose: Create user record and assign default role on signup
-- Trigger: AFTER INSERT on auth.users table (Supabase Auth)
-- Use Case: Automatically initialize new users with:
--   1. Record in public.users table (application user data)
--   2. Default 'user' role assignment in user_roles table
--
-- Why Needed: Supabase Auth creates users in auth.users table, but we need:
--   - Mirror record in public.users (for RLS policies and foreign keys)
--   - Default role assignment (ensures all users have at least 'user' role)
--
-- Trigger Flow:
--   1. User signs up (magic link, password, OAuth, passkey)
--   2. Supabase Auth inserts record into auth.users
--   3. This trigger fires AFTER INSERT
--   4. Trigger creates mirror record in public.users
--   5. Trigger assigns default 'user' role
--   6. User now has app access with base permissions
--
-- Logic:
--   1. Insert user into public.users table (id, email, timestamps)
--   2. ON CONFLICT DO NOTHING (prevent duplicates if trigger fires twice)
--   3. Look up 'user' role ID from roles table
--   4. Insert role assignment into user_roles table
--   5. ON CONFLICT DO NOTHING (prevent duplicate role assignments)
--   6. Return NEW (required for trigger functions)
--
-- Returns: TRIGGER - Returns NEW record (required by PostgreSQL)
-- Security: SECURITY DEFINER to insert into public.users and user_roles
-- Transaction Safety: If either insert fails, entire user creation rolls back
-- Idempotent: ON CONFLICT DO NOTHING ensures safe re-execution
-- Usage:
--   -- Configured as trigger (not called directly)
--   CREATE TRIGGER on_auth_user_created
--     AFTER INSERT ON auth.users
--     FOR EACH ROW EXECUTE FUNCTION handle_new_user();
--
-- Example Flow:
--   -- User signs up with email
--   1. INSERT INTO auth.users (email) VALUES ('user@example.com')
--   2. Trigger fires → INSERT INTO public.users (id, email)
--   3. Trigger fires → INSERT INTO user_roles (user_id, role_id='user')
--   4. User can now login and access app with 'user' role
CREATE OR REPLACE FUNCTION "public"."handle_new_user"() RETURNS "trigger"
    LANGUAGE "plpgsql" SECURITY DEFINER
    AS $$
DECLARE
    v_role_id UUID;
BEGIN
    -- Step 1: Insert user into public.users table
    -- Mirror auth.users record for application data
    -- ON CONFLICT DO NOTHING prevents duplicate if trigger fires multiple times
    INSERT INTO public.users (id, email, created_at, updated_at)
    VALUES (NEW.id, NEW.email, NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- Step 2: Lookup 'user' role ID from roles table
    -- Default role name: 'user' (must exist in roles table)
    SELECT id INTO v_role_id FROM public.roles WHERE name = 'user';

    -- Step 3: Assign default 'user' role
    -- Uses role_id (UUID) not role ENUM (table-based RBAC)
    -- assigned_by = NULL (system assignment, not manual admin assignment)
    -- ON CONFLICT DO NOTHING prevents duplicate if user already has role
    INSERT INTO public.user_roles (user_id, role_id, assigned_by, assigned_at)
    VALUES (NEW.id, v_role_id, NULL, NOW())
    ON CONFLICT (user_id, role_id) DO NOTHING;

    -- Return NEW record (required for AFTER INSERT triggers)
    RETURN NEW;
END;
$$;

ALTER FUNCTION "public"."handle_new_user"() OWNER TO "postgres";

COMMENT ON FUNCTION "public"."handle_new_user"() IS 'Automatically creates user record and assigns default "user" role on signup.';



-- Function 4.2: handle_user_email_update
-- -----------------------------------------------------------------------------
-- Purpose: Sync email changes from auth.users to public.users
-- Trigger: AFTER UPDATE on auth.users table (Supabase Auth)
-- Use Case: Keep public.users table in sync when user changes email
-- Why Needed: Email stored in both auth.users and public.users must stay consistent
--
-- Trigger Flow:
--   1. User updates email (via Supabase Auth)
--   2. Supabase Auth updates auth.users.email
--   3. This trigger fires AFTER UPDATE
--   4. Trigger updates public.users.email to match
--   5. Both tables now have consistent email
--
-- Logic:
--   1. UPDATE public.users SET email = NEW.email WHERE id = NEW.id
--   2. Also update updated_at timestamp
--   3. Return NEW (required for trigger functions)
--
-- Returns: TRIGGER - Returns NEW record (required by PostgreSQL)
-- Security: SECURITY DEFINER to update public.users
-- Performance: Fast via indexed public.users.id (primary key)
-- Usage:
--   -- Configured as trigger (not called directly)
--   CREATE TRIGGER on_auth_user_updated
--     AFTER UPDATE ON auth.users
--     FOR EACH ROW EXECUTE FUNCTION handle_user_email_update();
--
-- Example Flow:
--   -- User changes email
--   1. UPDATE auth.users SET email = 'new@example.com' WHERE id = '...'
--   2. Trigger fires → UPDATE public.users SET email = 'new@example.com'
--   3. Both tables now have matching email
CREATE OR REPLACE FUNCTION "public"."handle_user_email_update"() RETURNS "trigger"
    LANGUAGE "plpgsql" SECURITY DEFINER
    AS $$
BEGIN
    -- Update public.users to match new email from auth.users
    -- Also update updated_at timestamp for audit trail
    UPDATE public.users
    SET email = NEW.email, updated_at = NOW()
    WHERE id = NEW.id;

    -- Return NEW record (required for AFTER UPDATE triggers)
    RETURN NEW;
END;
$$;

ALTER FUNCTION "public"."handle_user_email_update"() OWNER TO "postgres";



-- ============================================================================
-- SECTION 5: Cleanup Triggers (2 functions)
-- ============================================================================
-- Purpose: Probabilistic cleanup of expired records to prevent table bloat


-- Function 5.1: trigger_cleanup_expired_challenges
-- -----------------------------------------------------------------------------
-- Purpose: Probabilistically clean up expired passkey challenges
-- Trigger: AFTER INSERT on passkey_challenges table
-- Probability: 10% (random() < 0.1) - reduces overhead
-- Why Probabilistic: Not every insert needs cleanup - occasional is sufficient
--
-- Cleanup Logic:
--   1. Random check: Only proceed 10% of the time
--   2. If proceeding: DELETE challenges WHERE expires_at < NOW()
--   3. Return NEW (required for triggers)
--
-- Why This Approach:
--   - Challenges expire after 5 minutes (short-lived)
--   - Table won't grow excessively even with daily cleanup
--   - 10% probability = ~1 cleanup per 10 insertions
--   - Avoids performance hit on every single insert
--
-- Alternative Approach (not used):
--   - Cron job (requires pg_cron extension)
--   - Separate cleanup function called manually
--   - This probabilistic approach is simpler and sufficient
--
-- Returns: TRIGGER - Returns NEW record (required by PostgreSQL)
-- Performance: Fast via indexed passkey_challenges.expires_at
-- Transaction Safety: Cleanup runs in same transaction as insert
-- Usage:
--   -- Configured as trigger (not called directly)
--   CREATE TRIGGER trigger_cleanup_expired_challenges
--     AFTER INSERT ON passkey_challenges
--     FOR EACH ROW EXECUTE FUNCTION trigger_cleanup_expired_challenges();
--
-- Example Flow:
--   -- Desktop creates passkey challenge
--   1. INSERT INTO passkey_challenges (challenge, expires_at=NOW()+5min)
--   2. Trigger fires → 10% chance of cleanup
--   3. If cleanup: DELETE challenges WHERE expires_at < NOW()
--   4. Old challenges removed, table stays clean
CREATE OR REPLACE FUNCTION "public"."trigger_cleanup_expired_challenges"() RETURNS "trigger"
    LANGUAGE "plpgsql"
    AS $$
BEGIN
  -- Probabilistic cleanup: Only run 10% of the time
  -- Reduces overhead while keeping table size manageable
  IF random() < 0.1 THEN
    -- Delete expired challenges (expires_at < NOW())
    -- Fast via index on expires_at column
    DELETE FROM passkey_challenges
    WHERE expires_at < NOW();
  END IF;

  -- Return NEW record (required for AFTER INSERT triggers)
  RETURN NEW;
END;
$$;

ALTER FUNCTION "public"."trigger_cleanup_expired_challenges"() OWNER TO "postgres";

COMMENT ON FUNCTION "public"."trigger_cleanup_expired_challenges"() IS 'Trigger function that probabilistically cleans up expired challenges on insert';



-- Function 5.2: trigger_cleanup_expired_completed_auths
-- -----------------------------------------------------------------------------
-- Purpose: Probabilistically clean up expired completed authentications
-- Trigger: AFTER INSERT on completed_authentications table
-- Probability: 10% (random() < 0.1) - reduces overhead
-- Why Probabilistic: Same reasoning as trigger_cleanup_expired_challenges
--
-- Cleanup Logic:
--   1. Random check: Only proceed 10% of the time
--   2. If proceeding: DELETE auths WHERE expires_at_timestamp < NOW()
--   3. Return NEW (required for triggers)
--
-- Table Purpose: completed_authentications stores successful auth results
--   - Mobile completes authentication
--   - Result stored temporarily for desktop to retrieve
--   - Desktop polls and consumes result
--   - Record expires after 2 minutes (short-lived)
--
-- Why This Approach:
--   - Authentication results are short-lived (2 minutes)
--   - Table won't grow excessively with occasional cleanup
--   - 10% probability balances performance and cleanliness
--
-- Returns: TRIGGER - Returns NEW record (required by PostgreSQL)
-- Performance: Fast via indexed completed_authentications.expires_at_timestamp
-- Transaction Safety: Cleanup runs in same transaction as insert
-- Usage:
--   -- Configured as trigger (not called directly)
--   CREATE TRIGGER trigger_cleanup_expired_completed_auths
--     AFTER INSERT ON completed_authentications
--     FOR EACH ROW EXECUTE FUNCTION trigger_cleanup_expired_completed_auths();
--
-- Example Flow:
--   -- Mobile completes authentication
--   1. INSERT INTO completed_authentications (session_id, expires_at=NOW()+2min)
--   2. Trigger fires → 10% chance of cleanup
--   3. If cleanup: DELETE auths WHERE expires_at_timestamp < NOW()
--   4. Old auth results removed, table stays clean
CREATE OR REPLACE FUNCTION "public"."trigger_cleanup_expired_completed_auths"() RETURNS "trigger"
    LANGUAGE "plpgsql"
    AS $$
BEGIN
  -- Probabilistic cleanup: Only run 10% of the time
  -- Reduces overhead while keeping table size manageable
  IF random() < 0.1 THEN
    -- Delete expired authentication results (expires_at_timestamp < NOW())
    -- Fast via index on expires_at_timestamp column
    DELETE FROM completed_authentications
    WHERE expires_at_timestamp < NOW();
  END IF;

  -- Return NEW record (required for AFTER INSERT triggers)
  RETURN NEW;
END;
$$;

ALTER FUNCTION "public"."trigger_cleanup_expired_completed_auths"() OWNER TO "postgres";

COMMENT ON FUNCTION "public"."trigger_cleanup_expired_completed_auths"() IS 'Trigger function that probabilistically cleans up expired completed authentications on insert';


-- ============================================================================
-- End of File: helper_functions.sql
-- ============================================================================
-- Next Migration: 20251023000008_rbac_tables.sql (RBAC Tables)
-- ============================================================================
