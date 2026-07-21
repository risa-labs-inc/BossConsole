-- ============================================================================
-- BOSS Database Schema: Row Level Security (RLS) Policies
-- ============================================================================
-- File: 20251023000013_rls_policies.sql
-- Description: Row Level Security policies for fine-grained access control.
--              Implements user ownership, role-based access, admin overrides,
--              and service role bypass for all tables.
-- Dependencies:
--   - File 2: rbac_functions.sql (is_user_admin, authorize)
--   - File 6: user_functions.sql (user_has_role)
--   - Files 8-10: All table definitions
-- Policies: 50+ total across 13 tables
-- Security Model: User ownership + RBAC + admin override + service role bypass
-- ============================================================================


-- ============================================================================
-- RLS Security Model Overview
-- ============================================================================
--
-- Access Control Layers:
--   1. User Ownership: Users access their own data (user_id = auth.uid())
--   2. Role-Based Access: Users access data via role membership
--   3. Admin Override: Admins bypass restrictions (is_user_admin check)
--   4. Service Role: Backend services bypass all restrictions
--
-- Policy Patterns:
--   - SELECT: Read access (USING clause)
--   - INSERT: Create access (WITH CHECK clause)
--   - UPDATE: Modify access (USING + WITH CHECK clauses)
--   - DELETE: Remove access (USING clause)
--
-- Common Patterns:
--   - Own data: auth.uid() = user_id
--   - Admin check: is_user_admin(auth.uid())
--   - Service role: (auth.jwt() ->> 'role') = 'service_role'
--   - Role membership: EXISTS (SELECT 1 FROM user_roles WHERE...)
--
-- Performance Considerations:
--   - RLS policies add WHERE clauses to queries
--   - Complex policies can impact performance
--   - Indexes on user_id, role_id optimize RLS checks
--
-- ============================================================================


-- ============================================================================
-- SECTION 1: RBAC Tables (14 policies + 4 ENABLE statements)
-- ============================================================================
-- Purpose: Control access to roles, permissions, and user assignments


-- Enable RLS on RBAC tables
-- -----------------------------------------------------------------------------
ALTER TABLE "public"."permissions" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."roles" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."role_permissions" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."user_roles" ENABLE ROW LEVEL SECURITY;


-- permissions table (6 policies)
-- -----------------------------------------------------------------------------

-- Policy 1.1: Anyone can view permissions
-- Use Case: Public permission list for UI dropdowns
-- Access: All authenticated users (no restrictions)
CREATE POLICY "Anyone can view permissions" ON "public"."permissions"
    FOR SELECT USING (true);

-- Policy 1.2: Admins can create permissions
-- Use Case: Admin creates new permission via admin interface
-- Access: Only admins (is_user_admin check)
CREATE POLICY "Admins can create permissions" ON "public"."permissions"
    FOR INSERT WITH CHECK ("public"."is_user_admin"("auth"."uid"()));

-- Policy 1.3: Admins can update non-system permissions
-- Use Case: Admin edits custom permission description
-- Access: Only admins, AND permission is not system-protected
CREATE POLICY "Admins can update non-system permissions" ON "public"."permissions"
    FOR UPDATE USING (((NOT "is_system") AND "public"."is_user_admin"("auth"."uid"())));

-- Policy 1.4: Admins can delete non-system permissions
-- Use Case: Admin deletes custom permission
-- Access: Only admins, AND permission is not system-protected
CREATE POLICY "Admins can delete non-system permissions" ON "public"."permissions"
    FOR DELETE USING (((NOT "is_system") AND "public"."is_user_admin"("auth"."uid"())));

-- Policy 1.5: Service role full access
-- Use Case: Backend operations via service role
-- Access: Service role only (bypasses all restrictions)
CREATE POLICY "Service role full access to permissions" ON "public"."permissions"
    USING ((("auth"."jwt"() ->> 'role'::"text") = 'service_role'::"text"));


-- roles table (6 policies)
-- -----------------------------------------------------------------------------

-- Policy 1.6: Anyone can view roles
-- Use Case: Public role list for UI dropdowns
-- Access: All authenticated users (no restrictions)
CREATE POLICY "Anyone can view roles" ON "public"."roles"
    FOR SELECT USING (true);

-- Policy 1.7: Auth admin can read roles (for JWT claims)
-- Use Case: Supabase Auth reads roles for JWT claims injection
-- Access: supabase_auth_admin role only
CREATE POLICY "Allow auth admin to read roles" ON "public"."roles"
    FOR SELECT TO "supabase_auth_admin" USING (true);

-- Policy 1.8: Admins can create roles
-- Use Case: Admin creates new role via admin interface
-- Access: Only admins (is_user_admin check)
CREATE POLICY "Admins can create roles" ON "public"."roles"
    FOR INSERT WITH CHECK ("public"."is_user_admin"("auth"."uid"()));

-- Policy 1.9: Admins can update non-system roles
-- Use Case: Admin edits custom role description
-- Access: Only admins, AND role is not system-protected
CREATE POLICY "Admins can update non-system roles" ON "public"."roles"
    FOR UPDATE USING (((NOT "is_system") AND "public"."is_user_admin"("auth"."uid"())));

-- Policy 1.10: Admins can delete non-system roles
-- Use Case: Admin deletes custom role
-- Access: Only admins, AND role is not system-protected
CREATE POLICY "Admins can delete non-system roles" ON "public"."roles"
    FOR DELETE USING (((NOT "is_system") AND "public"."is_user_admin"("auth"."uid"())));

-- Policy 1.11: Service role full access
-- Use Case: Backend operations via service role
-- Access: Service role only (bypasses all restrictions)
CREATE POLICY "Service role full access to roles" ON "public"."roles"
    USING ((("auth"."jwt"() ->> 'role'::"text") = 'service_role'::"text"));


-- role_permissions table (3 policies)
-- -----------------------------------------------------------------------------

-- Policy 1.12: Anyone can view role permissions
-- Use Case: Public permission assignments for UI
-- Access: All authenticated users (no restrictions)
CREATE POLICY "Anyone can view role permissions" ON "public"."role_permissions"
    FOR SELECT USING (true);

-- Policy 1.13: Admins can manage role permissions
-- Use Case: Admin assigns/removes permissions to/from roles
-- Access: Only admins (is_user_admin check)
-- Operations: ALL (SELECT, INSERT, UPDATE, DELETE)
CREATE POLICY "Admins can manage role permissions" ON "public"."role_permissions"
    USING ("public"."is_user_admin"("auth"."uid"()));

-- Policy 1.14: Service role full access
-- Use Case: Backend operations via service role
-- Access: Service role only (bypasses all restrictions)
CREATE POLICY "Service role full access to role_permissions" ON "public"."role_permissions"
    USING ((("auth"."jwt"() ->> 'role'::"text") = 'service_role'::"text"));


-- user_roles table (6 policies)
-- -----------------------------------------------------------------------------

-- Policy 1.15: Users can view their own roles
-- Use Case: User sees their assigned roles in profile
-- Access: Only own roles (user_id = auth.uid())
CREATE POLICY "Users can view their own roles" ON "public"."user_roles"
    FOR SELECT USING (("auth"."uid"() = "user_id"));

-- Policy 1.16: Admins can view all roles
-- Use Case: Admin sees all user role assignments
-- Access: Only admins (is_user_admin check)
CREATE POLICY "Admins can view all roles" ON "public"."user_roles"
    FOR SELECT USING ("public"."is_user_admin"("auth"."uid"()));

-- Policy 1.17: Auth admin can read user roles (for JWT claims)
-- Use Case: Supabase Auth reads user roles for JWT claims injection
-- Access: supabase_auth_admin role only
CREATE POLICY "Allow auth admin to read user roles" ON "public"."user_roles"
    FOR SELECT TO "supabase_auth_admin" USING (true);

-- Policy 1.18: Admins can assign roles
-- Use Case: Admin assigns role to user
-- Access: Only admins (is_user_admin check)
CREATE POLICY "Admins can assign roles" ON "public"."user_roles"
    FOR INSERT WITH CHECK ("public"."is_user_admin"("auth"."uid"()));

-- Policy 1.19: Admins can remove roles (with self-admin protection)
-- Use Case: Admin removes role from user
-- Access: Only admins, BUT cannot remove own admin role (safety check)
-- Safety: Prevents admin from accidentally removing their own admin access
CREATE POLICY "Admins can remove roles" ON "public"."user_roles"
    FOR DELETE USING ((
        "public"."is_user_admin"("auth"."uid"())
        AND NOT (
            ("user_id" = "auth"."uid"())
            AND ("role_id" IN (
                SELECT "roles"."id" FROM "public"."roles"
                WHERE ("roles"."name" = 'admin'::"text")
            ))
        )
    ));

-- Policy 1.20: Service role full access
-- Use Case: Backend operations via service role
-- Access: Service role only (bypasses all restrictions)
CREATE POLICY "Service role full access to user_roles" ON "public"."user_roles"
    USING ((("auth"."jwt"() ->> 'role'::"text") = 'service_role'::"text"));



-- ============================================================================
-- SECTION 2: User Table (3 policies + 1 ENABLE statement)
-- ============================================================================
-- Purpose: Control access to user profile data


-- Enable RLS on users table
-- -----------------------------------------------------------------------------
ALTER TABLE "public"."users" ENABLE ROW LEVEL SECURITY;


-- users table (3 policies)
-- -----------------------------------------------------------------------------

-- Policy 2.1: Users can read own data
-- Use Case: User views own profile
-- Access: Only own data (auth.uid() = id)
CREATE POLICY "Users can read own data" ON "public"."users"
    FOR SELECT USING (("auth"."uid"() = "id"));

-- Policy 2.2: Admins can read all users
-- Use Case: Admin views user list in admin interface
-- Access: Only admins (checks is_admin JWT claim to avoid recursion)
-- Important: Uses JWT claim instead of is_user_admin() to prevent infinite recursion
CREATE POLICY "Admins can read all users" ON "public"."users"
    FOR SELECT USING (COALESCE((("auth"."jwt"() -> 'is_admin'::"text"))::boolean, false));

COMMENT ON POLICY "Admins can read all users" ON "public"."users" IS 'Allows users with is_admin=true in JWT to view all users. Uses JWT claims to avoid infinite recursion.';

-- Policy 2.3: Users can update own data
-- Use Case: User updates profile (email sync handled by trigger)
-- Access: Only own data (auth.uid() = id)
CREATE POLICY "Users can update own data" ON "public"."users"
    FOR UPDATE USING (("auth"."uid"() = "id"));



-- ============================================================================
-- SECTION 3: Passkey Tables (11 policies + 3 ENABLE statements)
-- ============================================================================
-- Purpose: Control access to passkey credentials and challenges


-- Enable RLS on passkey tables
-- -----------------------------------------------------------------------------
ALTER TABLE "public"."user_passkeys" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."passkey_challenges" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."completed_authentications" ENABLE ROW LEVEL SECURITY;


-- user_passkeys table (4 policies)
-- -----------------------------------------------------------------------------

-- Policy 3.1: Users can view their own passkeys
-- Use Case: User lists registered passkeys in settings
-- Access: Only own passkeys (auth.uid() = user_id)
CREATE POLICY "Users can view their own passkeys" ON "public"."user_passkeys"
    FOR SELECT USING (("auth"."uid"() = "user_id"));

-- Policy 3.2: Users can insert their own passkeys
-- Use Case: User registers new passkey (Touch ID, security key, etc.)
-- Access: Only own passkeys (auth.uid() = user_id)
CREATE POLICY "Users can insert their own passkeys" ON "public"."user_passkeys"
    FOR INSERT WITH CHECK (("auth"."uid"() = "user_id"));

-- Policy 3.3: Users can update their own passkeys
-- Use Case: User renames passkey or marks as inactive
-- Access: Only own passkeys (auth.uid() = user_id)
CREATE POLICY "Users can update their own passkeys" ON "public"."user_passkeys"
    FOR UPDATE USING (("auth"."uid"() = "user_id"));

-- Policy 3.4: Users can delete their own passkeys
-- Use Case: User removes passkey from account
-- Access: Only own passkeys (auth.uid() = user_id)
CREATE POLICY "Users can delete their own passkeys" ON "public"."user_passkeys"
    FOR DELETE USING (("auth"."uid"() = "user_id"));

-- Policy 3.5: Service role can access all passkeys
-- Use Case: Backend operations via service role
-- Access: Service role only (bypasses all restrictions)
CREATE POLICY "Service role can access all passkeys" ON "public"."user_passkeys"
    USING ((("auth"."jwt"() ->> 'role'::"text") = 'service_role'::"text"));


-- passkey_challenges table (4 policies)
-- -----------------------------------------------------------------------------

-- Policy 3.6: Users can view their own challenges
-- Use Case: User retrieves challenge for WebAuthn registration/authentication
-- Access: Own challenges OR challenges with NULL user_id (pre-registration)
CREATE POLICY "Users can view their own challenges" ON "public"."passkey_challenges"
    FOR SELECT USING ((("auth"."uid"() = "user_id") OR ("user_id" IS NULL)));

-- Policy 3.7: Session-based access for mobile flows
-- Use Case: Mobile device retrieves challenge via session_id from QR code
-- Access: Challenges with session_id, AND (user matches OR user_id is NULL)
CREATE POLICY "Allow session-based access for mobile flows" ON "public"."passkey_challenges"
    FOR SELECT USING ((
        ("session_id" IS NOT NULL)
        AND (("auth"."uid"() = "user_id") OR ("user_id" IS NULL))
    ));

-- Policy 3.8: Users can insert their own challenges
-- Use Case: Desktop/mobile creates challenge for registration/authentication
-- Access: Own challenges OR NULL user_id (pre-registration challenges)
CREATE POLICY "Users can insert their own challenges" ON "public"."passkey_challenges"
    FOR INSERT WITH CHECK ((("auth"."uid"() = "user_id") OR ("user_id" IS NULL)));

-- Policy 3.9: Service role can access all challenges
-- Use Case: Backend operations via service role
-- Access: Service role only (bypasses all restrictions)
CREATE POLICY "Service role can access all challenges" ON "public"."passkey_challenges"
    USING ((("auth"."jwt"() ->> 'role'::"text") = 'service_role'::"text"));


-- completed_authentications table (7 policies)
-- -----------------------------------------------------------------------------
-- Purpose: Enable cross-device WebAuthn authentication flow
--
-- Cross-Device Authentication Flow:
--   1. Desktop (anon) generates session_id and displays QR code
--   2. Mobile scans QR and retrieves challenge
--   3. Mobile authenticates with passkey (biometric)
--   4. Edge Function calls Supabase Admin API to generate session tokens
--   5. Edge Function stores result in completed_authentications
--   6. Desktop (still anon) polls completed_authentications by session_id
--   7. Desktop retrieves access_token and refresh_token
--   8. Desktop establishes authenticated session
--
-- Security Model:
--   - session_id acts as temporary authentication token (UUID, unguessable)
--   - Records expire after 5 minutes (expires_at_timestamp)
--   - One-time use pattern (desktop should delete after retrieving)
--   - No sensitive data permanently stored (tokens are meant to be transmitted once)

-- Policy 3.10: Service role can manage completed authentications
-- Use Case: Backend stores/retrieves authentication results
-- Access: Service role only (ALL operations)
CREATE POLICY "Service role can manage completed authentications" ON "public"."completed_authentications"
    TO "service_role" USING (true) WITH CHECK (true);

COMMENT ON POLICY "Service role can manage completed authentications" ON "public"."completed_authentications"
IS 'Service role bypasses all RLS restrictions. Used for administrative operations and cleanup.';


-- Policy 3.11: Anon can insert completed authentications
-- Use Case: Edge Function stores authentication result during cross-device flow
-- Why anon: User is NOT authenticated yet - that's the whole point of authentication!
-- Security: session_id must be provided (prevents mass inserts), records auto-expire after 5 minutes
-- Access: anon role can INSERT with session_id
CREATE POLICY "Anon can insert completed authentications" ON "public"."completed_authentications"
    FOR INSERT
    TO "anon"
    WITH CHECK (session_id IS NOT NULL);

COMMENT ON POLICY "Anon can insert completed authentications" ON "public"."completed_authentications"
IS 'Allows Edge Functions to store authentication results during cross-device flow. Requires session_id to prevent abuse. Records expire after 5 minutes.';


-- Policy 3.12: Anon can select by session_id
-- Use Case: Desktop polls for authentication completion
-- Why anon: Desktop is NOT authenticated yet - polling for auth result
-- Security: Must know session_id (acts as temporary auth token, UUID format = unguessable)
-- Access: anon role can SELECT by session_id
CREATE POLICY "Anon can select by session_id" ON "public"."completed_authentications"
    FOR SELECT
    TO "anon"
    USING (session_id IS NOT NULL);

COMMENT ON POLICY "Anon can select by session_id" ON "public"."completed_authentications"
IS 'Allows desktop to poll for authentication results during cross-device flow. Must know session_id (acts as temporary auth token).';


-- Policy 3.13: Anon can delete by session_id (cleanup)
-- Use Case: Desktop deletes result after successfully retrieving tokens (one-time use)
-- Why anon: Desktop retrieves tokens and immediately deletes result
-- Security: Must know session_id, best practice to minimize token exposure window
-- Access: anon role can DELETE by session_id
CREATE POLICY "Anon can delete by session_id" ON "public"."completed_authentications"
    FOR DELETE
    TO "anon"
    USING (session_id IS NOT NULL);

COMMENT ON POLICY "Anon can delete by session_id" ON "public"."completed_authentications"
IS 'Allows desktop to delete authentication result after retrieving tokens. One-time use pattern.';


-- Policy 3.14: Authenticated users can insert own results
-- Use Case: Re-authentication flows where user is already logged in
-- Why authenticated: Some flows might re-authenticate already-logged-in users
-- Security: user_id must match auth.uid() (own results only), records expire after 5 minutes
-- Access: authenticated role can INSERT own results
CREATE POLICY "Authenticated users can insert own results" ON "public"."completed_authentications"
    FOR INSERT
    TO "authenticated"
    WITH CHECK (user_id = auth.uid());

COMMENT ON POLICY "Authenticated users can insert own results" ON "public"."completed_authentications"
IS 'Allows authenticated users to store their own authentication results. Used for re-authentication flows.';


-- Policy 3.15: Authenticated users can select own results
-- Use Case: User queries their own authentication history
-- Why authenticated: User might want to see their recent authentications
-- Security: user_id must match auth.uid() (own results only)
-- Access: authenticated role can SELECT own results
CREATE POLICY "Authenticated users can select own results" ON "public"."completed_authentications"
    FOR SELECT
    TO "authenticated"
    USING (user_id = auth.uid());

COMMENT ON POLICY "Authenticated users can select own results" ON "public"."completed_authentications"
IS 'Allows authenticated users to query their own authentication results.';


-- Policy 3.16: Authenticated users can delete own results
-- Use Case: User deletes their own authentication history
-- Why authenticated: User cleanup or privacy management
-- Security: user_id must match auth.uid() (own results only)
-- Access: authenticated role can DELETE own results
CREATE POLICY "Authenticated users can delete own results" ON "public"."completed_authentications"
    FOR DELETE
    TO "authenticated"
    USING (user_id = auth.uid());

COMMENT ON POLICY "Authenticated users can delete own results" ON "public"."completed_authentications"
IS 'Allows authenticated users to delete their own authentication results.';



-- ============================================================================
-- SECTION 4: Secret Tables (20 policies + 5 ENABLE statements)
-- ============================================================================
-- Purpose: Control access to encrypted secrets and related metadata


-- Enable RLS on secret tables
-- -----------------------------------------------------------------------------
ALTER TABLE "public"."secrets" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."secret_metadata" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."secret_tags" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."secret_shares" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."secret_access_log" ENABLE ROW LEVEL SECURITY;


-- secrets table (4 policies)
-- -----------------------------------------------------------------------------

-- Policy 4.1: Users can view own secrets
-- Use Case: User lists their stored credentials
-- Access: Only own secrets (auth.uid() = user_id)
CREATE POLICY "Users can view own secrets" ON "public"."secrets"
    FOR SELECT USING (("auth"."uid"() = "user_id"));

-- Policy 4.2: Users can create own secrets
-- Use Case: User creates new credential
-- Access: Only own secrets (auth.uid() = user_id)
CREATE POLICY "Users can create own secrets" ON "public"."secrets"
    FOR INSERT WITH CHECK (("auth"."uid"() = "user_id"));

-- Policy 4.3: Users can update own secrets
-- Use Case: User changes password or notes
-- Access: Only own secrets (auth.uid() = user_id)
-- WITH CHECK: Ensures user_id doesn't change during update
CREATE POLICY "Users can update own secrets" ON "public"."secrets"
    FOR UPDATE USING (("auth"."uid"() = "user_id"))
    WITH CHECK (("auth"."uid"() = "user_id"));

-- Policy 4.4: Users can delete own secrets
-- Use Case: User removes credential
-- Access: Only own secrets (auth.uid() = user_id)
CREATE POLICY "Users can delete own secrets" ON "public"."secrets"
    FOR DELETE USING (("auth"."uid"() = "user_id"));


-- secret_metadata table (4 policies)
-- -----------------------------------------------------------------------------

-- Policy 4.5: Users can view own secret metadata
-- Use Case: User views 2FA settings for their secret
-- Access: Only metadata for own secrets (EXISTS check)
CREATE POLICY "Users can view own secret metadata" ON "public"."secret_metadata"
    FOR SELECT USING ((EXISTS (
        SELECT 1 FROM "public"."secrets"
        WHERE (("secrets"."id" = "secret_metadata"."secret_id")
            AND ("secrets"."user_id" = "auth"."uid"()))
    )));

-- Policy 4.6: Users can create own secret metadata
-- Use Case: User enables 2FA for their secret
-- Access: Only metadata for own secrets (EXISTS check)
CREATE POLICY "Users can create own secret metadata" ON "public"."secret_metadata"
    FOR INSERT WITH CHECK ((EXISTS (
        SELECT 1 FROM "public"."secrets"
        WHERE (("secrets"."id" = "secret_metadata"."secret_id")
            AND ("secrets"."user_id" = "auth"."uid"()))
    )));

-- Policy 4.7: Users can update own secret metadata
-- Use Case: User changes 2FA settings
-- Access: Only metadata for own secrets (EXISTS check)
CREATE POLICY "Users can update own secret metadata" ON "public"."secret_metadata"
    FOR UPDATE USING ((EXISTS (
        SELECT 1 FROM "public"."secrets"
        WHERE (("secrets"."id" = "secret_metadata"."secret_id")
            AND ("secrets"."user_id" = "auth"."uid"()))
    )));

-- Policy 4.8: Users can delete own secret metadata
-- Use Case: User disables 2FA for their secret
-- Access: Only metadata for own secrets (EXISTS check)
CREATE POLICY "Users can delete own secret metadata" ON "public"."secret_metadata"
    FOR DELETE USING ((EXISTS (
        SELECT 1 FROM "public"."secrets"
        WHERE (("secrets"."id" = "secret_metadata"."secret_id")
            AND ("secrets"."user_id" = "auth"."uid"()))
    )));


-- secret_tags table (4 policies)
-- -----------------------------------------------------------------------------

-- Policy 4.9: Users can view own secret tags
-- Use Case: User sees tags for their secrets
-- Access: Only tags for own secrets (EXISTS check)
CREATE POLICY "Users can view own secret tags" ON "public"."secret_tags"
    FOR SELECT USING ((EXISTS (
        SELECT 1 FROM "public"."secrets"
        WHERE (("secrets"."id" = "secret_tags"."secret_id")
            AND ("secrets"."user_id" = "auth"."uid"()))
    )));

-- Policy 4.10: Users can create own secret tags
-- Use Case: User adds tag to their secret
-- Access: Only tags for own secrets (EXISTS check)
CREATE POLICY "Users can create own secret tags" ON "public"."secret_tags"
    FOR INSERT WITH CHECK ((EXISTS (
        SELECT 1 FROM "public"."secrets"
        WHERE (("secrets"."id" = "secret_tags"."secret_id")
            AND ("secrets"."user_id" = "auth"."uid"()))
    )));

-- Policy 4.11: Users can delete own secret tags
-- Use Case: User removes tag from their secret
-- Access: Only tags for own secrets (EXISTS check)
CREATE POLICY "Users can delete own secret tags" ON "public"."secret_tags"
    FOR DELETE USING ((EXISTS (
        SELECT 1 FROM "public"."secrets"
        WHERE (("secrets"."id" = "secret_tags"."secret_id")
            AND ("secrets"."user_id" = "auth"."uid"()))
    )));


-- secret_shares table (4 policies)
-- -----------------------------------------------------------------------------

-- Policy 4.12: Complex SELECT policy for secret shares
-- Use Case: View shares for secrets you own OR shares granted to you
-- Access: 4 conditions (OR logic):
--   1. You own the secret
--   2. Secret is shared with you (user-based)
--   3. Secret is shared with your role (role-based)
--   4. You are admin
CREATE POLICY "secret_shares_select" ON "public"."secret_shares"
    FOR SELECT USING ((
        ("secret_id" IN (
            SELECT "secrets"."id" FROM "public"."secrets"
            WHERE ("secrets"."user_id" = "auth"."uid"())
        ))
        OR ("shared_with_user_id" = "auth"."uid"())
        OR ("shared_with_role_id" IN (
            SELECT "user_roles"."role_id" FROM "public"."user_roles"
            WHERE ("user_roles"."user_id" = "auth"."uid"())
        ))
        OR (EXISTS (
            SELECT 1 FROM ("public"."user_roles" "ur"
            JOIN "public"."roles" "r" ON (("r"."id" = "ur"."role_id")))
            WHERE (("ur"."user_id" = "auth"."uid"()) AND ("r"."name" = 'admin'::"text"))
        ))
    ));

-- Policy 4.13: Users can create shares for own secrets (or admins)
-- Use Case: User shares their secret with colleague or role
-- Access: Own secrets OR admin
CREATE POLICY "secret_shares_insert" ON "public"."secret_shares"
    FOR INSERT WITH CHECK ((
        ("secret_id" IN (
            SELECT "secrets"."id" FROM "public"."secrets"
            WHERE ("secrets"."user_id" = "auth"."uid"())
        ))
        OR (EXISTS (
            SELECT 1 FROM ("public"."user_roles" "ur"
            JOIN "public"."roles" "r" ON (("r"."id" = "ur"."role_id")))
            WHERE (("ur"."user_id" = "auth"."uid"()) AND ("r"."name" = 'admin'::"text"))
        ))
    ));

-- Policy 4.14: Users can update shares for own secrets (or admins)
-- Use Case: User changes share expiration or notes
-- Access: Own secrets OR admin
CREATE POLICY "secret_shares_update" ON "public"."secret_shares"
    FOR UPDATE USING ((
        ("secret_id" IN (
            SELECT "secrets"."id" FROM "public"."secrets"
            WHERE ("secrets"."user_id" = "auth"."uid"())
        ))
        OR (EXISTS (
            SELECT 1 FROM ("public"."user_roles" "ur"
            JOIN "public"."roles" "r" ON (("r"."id" = "ur"."role_id")))
            WHERE (("ur"."user_id" = "auth"."uid"()) AND ("r"."name" = 'admin'::"text"))
        ))
    ));

-- Policy 4.15: Users can delete shares for own secrets (or admins)
-- Use Case: User revokes access to their secret
-- Access: Own secrets OR admin
CREATE POLICY "secret_shares_delete" ON "public"."secret_shares"
    FOR DELETE USING ((
        ("secret_id" IN (
            SELECT "secrets"."id" FROM "public"."secrets"
            WHERE ("secrets"."user_id" = "auth"."uid"())
        ))
        OR (EXISTS (
            SELECT 1 FROM ("public"."user_roles" "ur"
            JOIN "public"."roles" "r" ON (("r"."id" = "ur"."role_id")))
            WHERE (("ur"."user_id" = "auth"."uid"()) AND ("r"."name" = 'admin'::"text"))
        ))
    ));


-- secret_access_log table (2 policies)
-- -----------------------------------------------------------------------------

-- Policy 4.16: Anyone can insert access logs
-- Use Case: Log all secret operations (view, share, delete, etc.)
-- Access: All authenticated users (no restrictions on INSERT)
CREATE POLICY "secret_access_log_insert" ON "public"."secret_access_log"
    FOR INSERT WITH CHECK (true);

-- Policy 4.17: Users can view own logs (or admins can view all)
-- Use Case: User views audit trail for their operations
-- Access: Own logs (user_id = auth.uid()) OR admin
CREATE POLICY "secret_access_log_select" ON "public"."secret_access_log"
    FOR SELECT USING ((
        ("user_id" = "auth"."uid"())
        OR (EXISTS (
            SELECT 1 FROM ("public"."user_roles" "ur"
            JOIN "public"."roles" "r" ON (("r"."id" = "ur"."role_id")))
            WHERE (("ur"."user_id" = "auth"."uid"()) AND ("r"."name" = 'admin'::"text"))
        ))
    ));


-- ============================================================================
-- End of File: rls_policies.sql
-- ============================================================================
-- Next Migration: 20251023000014_grants.sql (Permission Grants)
-- ============================================================================
