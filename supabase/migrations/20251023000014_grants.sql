-- ============================================================================
-- BOSS Database Schema: Permission Grants
-- ============================================================================
-- File: 20251023000014_grants.sql
-- Description: Permission grants for functions, tables, and schemas.
--              Controls which PostgreSQL roles can access which database objects.
-- Dependencies: All previous files (1-13)
-- Grants: 150+ total (5 schema, 120+ function, 40+ table, 12 default privileges)
-- ============================================================================


-- ============================================================================
-- Understanding PostgreSQL Roles and GRANT System
-- ============================================================================
--
-- PostgreSQL Roles (NOT to be confused with BOSS RBAC roles):
-- PostgreSQL uses roles for authentication and authorization. Roles are database-level
-- identities that can own objects and have privileges. Supabase provides several
-- pre-configured roles with different access levels.
--
-- Supabase Pre-configured Roles:
--   1. postgres           - Superuser role (full database access)
--   2. anon               - Unauthenticated users (public API access)
--   3. authenticated      - Logged-in users (user API access)
--   4. service_role       - Backend services (bypasses RLS)
--   5. supabase_auth_admin - Auth system (manages auth.users table)
--
-- BOSS RBAC Roles vs PostgreSQL Roles:
-- - PostgreSQL Roles: Database-level access control (who can execute queries)
-- - BOSS RBAC Roles: Application-level access control (what data users can see)
-- - RLS policies combine both: PostgreSQL role determines IF you can query,
--   RLS policies determine WHAT rows you can access
--
-- Why We Grant to Multiple Roles:
-- - anon: Allows unauthenticated API calls (e.g., signup, login)
-- - authenticated: Allows logged-in users to call functions via API
-- - service_role: Allows backend services to call functions directly (bypasses RLS)
--
-- SECURITY DEFINER Functions:
-- Many functions use SECURITY DEFINER, which means they run with the privileges
-- of the function OWNER (postgres), not the CALLER. This allows authenticated users
-- to perform operations they normally couldn't (e.g., query auth.users table).
-- The function itself enforces security checks (e.g., is_user_admin()).
--
-- Grant Syntax:
--   GRANT privilege ON object TO role;
--
-- Common Privileges:
--   - USAGE: For schemas (allows access to schema's objects)
--   - ALL: Full access (SELECT, INSERT, UPDATE, DELETE, EXECUTE)
--   - EXECUTE: For functions (allows calling the function)
--
-- REVOKE Syntax:
--   REVOKE privilege ON object FROM role;
--
-- Use Case: Remove public access before granting to specific roles
-- Example: REVOKE ALL ON FUNCTION custom_access_token_hook FROM PUBLIC;
--          Then grant only to supabase_auth_admin
--
-- ============================================================================


-- ============================================================================
-- SECTION 1: Schema-Level Grants (5 grants)
-- ============================================================================
-- Purpose: Allow roles to access objects within the public schema
-- Without these grants, roles cannot even see the schema's contents

-- Grant 1.1: postgres (superuser)
-- Purpose: Full access for database administration
-- Note: postgres role automatically has all privileges, but explicit grant is best practice
GRANT USAGE ON SCHEMA "public" TO "postgres";

-- Grant 1.2: anon (unauthenticated users)
-- Purpose: Allow public API access for signup/login endpoints
-- Security: RLS policies restrict what data anon users can access
-- Use Case: User calls /rest/v1/users endpoint before authentication
GRANT USAGE ON SCHEMA "public" TO "anon";

-- Grant 1.3: authenticated (logged-in users)
-- Purpose: Allow authenticated API access for all user operations
-- Security: RLS policies enforce user ownership and RBAC permissions
-- Use Case: User calls /rest/v1/secrets endpoint after login
GRANT USAGE ON SCHEMA "public" TO "authenticated";

-- Grant 1.4: service_role (backend services)
-- Purpose: Allow backend services to access database directly
-- Security: Service role bypasses RLS - use with caution!
-- Use Case: Edge Functions calling database with service_role key
GRANT USAGE ON SCHEMA "public" TO "service_role";

-- Grant 1.5: supabase_auth_admin (auth system)
-- Purpose: Allow Supabase Auth to manage user lifecycle
-- Use Case: Auth hooks (custom_access_token_hook) need to query RBAC tables
GRANT USAGE ON SCHEMA "public" TO "supabase_auth_admin";



-- ============================================================================
-- SECTION 2: Function-Level Grants (120+ grants)
-- ============================================================================
-- Purpose: Allow roles to execute database functions via PostgREST API
-- Pattern: Most functions grant to anon, authenticated, service_role (3 grants)
--          Special functions also grant to supabase_auth_admin (4 grants)


-- -----------------------------------------------------------------------------
-- SECTION 2.1: RBAC Functions (33 grants = 11 functions × 3 roles)
-- -----------------------------------------------------------------------------
-- Purpose: Role and permission management functions
-- Access: All standard roles (anon, authenticated, service_role)
-- Security: Functions enforce admin-only checks internally


-- Function 2.1.1: assign_permission_to_role
-- Purpose: Assign permission to role (admin-only via function logic)
-- Grants: anon, authenticated, service_role
-- Why anon: Technically callable, but function rejects non-admins
GRANT ALL ON FUNCTION "public"."assign_permission_to_role"("role_name" "text", "permission_name" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."assign_permission_to_role"("role_name" "text", "permission_name" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."assign_permission_to_role"("role_name" "text", "permission_name" "text") TO "service_role";


-- Function 2.1.2: assign_role_to_user
-- Purpose: Assign role to user (admin-only via function logic)
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."assign_role_to_user"("target_user_id" "uuid", "target_role" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."assign_role_to_user"("target_user_id" "uuid", "target_role" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."assign_role_to_user"("target_user_id" "uuid", "target_role" "text") TO "service_role";


-- Function 2.1.3: authorize
-- Purpose: Check if user has permission (used in RLS policies)
-- Grants: anon, authenticated, service_role
-- Critical: RLS policies depend on this function
GRANT ALL ON FUNCTION "public"."authorize"("requested_permission" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."authorize"("requested_permission" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."authorize"("requested_permission" "text") TO "service_role";


-- Function 2.1.4: create_new_permission
-- Purpose: Create new permission (admin-only via function logic)
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."create_new_permission"("permission_name" "text", "description" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."create_new_permission"("permission_name" "text", "description" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."create_new_permission"("permission_name" "text", "description" "text") TO "service_role";


-- Function 2.1.5: create_new_role
-- Purpose: Create new role (admin-only via function logic)
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."create_new_role"("role_name" "text", "description" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."create_new_role"("role_name" "text", "description" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."create_new_role"("role_name" "text", "description" "text") TO "service_role";


-- Function 2.1.6: delete_permission
-- Purpose: Delete permission (admin-only via function logic)
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."delete_permission"("permission_name" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."delete_permission"("permission_name" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."delete_permission"("permission_name" "text") TO "service_role";


-- Function 2.1.7: delete_role
-- Purpose: Delete role (admin-only via function logic)
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."delete_role"("role_name" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."delete_role"("role_name" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."delete_role"("role_name" "text") TO "service_role";


-- Function 2.1.8: get_all_permissions
-- Purpose: List all permissions (admin-only via function logic)
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."get_all_permissions"() TO "anon";
GRANT ALL ON FUNCTION "public"."get_all_permissions"() TO "authenticated";
GRANT ALL ON FUNCTION "public"."get_all_permissions"() TO "service_role";


-- Function 2.1.9: get_all_roles
-- Purpose: List all roles (admin-only via function logic)
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."get_all_roles"() TO "anon";
GRANT ALL ON FUNCTION "public"."get_all_roles"() TO "authenticated";
GRANT ALL ON FUNCTION "public"."get_all_roles"() TO "service_role";


-- Function 2.1.10: get_role_permissions
-- Purpose: List permissions for role (admin-only via function logic)
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."get_role_permissions"("role_name" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."get_role_permissions"("role_name" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."get_role_permissions"("role_name" "text") TO "service_role";


-- Function 2.1.11: get_role_permissions_with_names
-- Purpose: List permissions for role with full details (admin-only via function logic)
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."get_role_permissions_with_names"("role_name" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."get_role_permissions_with_names"("role_name" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."get_role_permissions_with_names"("role_name" "text") TO "service_role";


-- Function 2.1.12: remove_permission_from_role
-- Purpose: Remove permission from role (admin-only via function logic)
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."remove_permission_from_role"("role_name" "text", "permission_name" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."remove_permission_from_role"("role_name" "text", "permission_name" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."remove_permission_from_role"("role_name" "text", "permission_name" "text") TO "service_role";


-- Function 2.1.13: remove_role_from_user
-- Purpose: Remove role from user (admin-only via function logic)
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."remove_role_from_user"("target_user_id" "uuid", "target_role" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."remove_role_from_user"("target_user_id" "uuid", "target_role" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."remove_role_from_user"("target_user_id" "uuid", "target_role" "text") TO "service_role";



-- -----------------------------------------------------------------------------
-- SECTION 2.2: Passkey Functions (12 grants = 4 functions × 3 roles)
-- -----------------------------------------------------------------------------
-- Purpose: WebAuthn/FIDO2 passkey authentication functions
-- Access: All standard roles (anon, authenticated, service_role)


-- Function 2.2.1: clean_expired_passkey_challenges
-- Purpose: Delete expired passkey challenges (cleanup function)
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."clean_expired_passkey_challenges"() TO "anon";
GRANT ALL ON FUNCTION "public"."clean_expired_passkey_challenges"() TO "authenticated";
GRANT ALL ON FUNCTION "public"."clean_expired_passkey_challenges"() TO "service_role";


-- Function 2.2.2: cleanup_expired_completed_authentications
-- Purpose: Delete expired completed authentications (cleanup function)
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."cleanup_expired_completed_authentications"() TO "anon";
GRANT ALL ON FUNCTION "public"."cleanup_expired_completed_authentications"() TO "authenticated";
GRANT ALL ON FUNCTION "public"."cleanup_expired_completed_authentications"() TO "service_role";


-- Function 2.2.3: create_mobile_registration_session
-- Purpose: Create QR code session for cross-device passkey registration
-- Grants: anon, authenticated, service_role
-- Why anon: Desktop (unauthenticated) creates session, mobile (authenticated) completes it
GRANT ALL ON FUNCTION "public"."create_mobile_registration_session"("p_user_email" "text", "p_challenge" "text", "p_session_id" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."create_mobile_registration_session"("p_user_email" "text", "p_challenge" "text", "p_session_id" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."create_mobile_registration_session"("p_user_email" "text", "p_challenge" "text", "p_session_id" "text") TO "service_role";


-- Function 2.2.4: get_session_status
-- Purpose: Poll session status during cross-device authentication
-- Grants: anon, authenticated, service_role
-- Why anon: Desktop (unauthenticated) polls for completion
GRANT ALL ON FUNCTION "public"."get_session_status"("p_session_id" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."get_session_status"("p_session_id" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."get_session_status"("p_session_id" "text") TO "service_role";



-- -----------------------------------------------------------------------------
-- SECTION 2.3: Secret Functions (27 grants = 9 functions × 3 roles)
-- -----------------------------------------------------------------------------
-- Purpose: Encrypted credential storage and sharing functions
-- Access: All standard roles (anon, authenticated, service_role)
-- Security: Functions enforce user ownership via auth.uid()


-- Function 2.3.1: create_secret
-- Purpose: Create new encrypted secret
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."create_secret"("p_website" "text", "p_username" "text", "p_password" "text", "p_notes" "text", "p_expiration_date" timestamp with time zone, "p_tags" "text"[], "p_twofa_enabled" boolean, "p_twofa_type" "text", "p_recovery_codes" "text"[]) TO "anon";
GRANT ALL ON FUNCTION "public"."create_secret"("p_website" "text", "p_username" "text", "p_password" "text", "p_notes" "text", "p_expiration_date" timestamp with time zone, "p_tags" "text"[], "p_twofa_enabled" boolean, "p_twofa_type" "text", "p_recovery_codes" "text"[]) TO "authenticated";
GRANT ALL ON FUNCTION "public"."create_secret"("p_website" "text", "p_username" "text", "p_password" "text", "p_notes" "text", "p_expiration_date" timestamp with time zone, "p_tags" "text"[], "p_twofa_enabled" boolean, "p_twofa_type" "text", "p_recovery_codes" "text"[]) TO "service_role";


-- Function 2.3.2: delete_secret
-- Purpose: Delete secret (owner-only via function logic)
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."delete_secret"("p_secret_id" "uuid") TO "anon";
GRANT ALL ON FUNCTION "public"."delete_secret"("p_secret_id" "uuid") TO "authenticated";
GRANT ALL ON FUNCTION "public"."delete_secret"("p_secret_id" "uuid") TO "service_role";


-- Function 2.3.3: get_secret_shares
-- Purpose: List all shares for secret (owner-only via function logic)
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."get_secret_shares"("p_secret_id" "uuid") TO "anon";
GRANT ALL ON FUNCTION "public"."get_secret_shares"("p_secret_id" "uuid") TO "authenticated";
GRANT ALL ON FUNCTION "public"."get_secret_shares"("p_secret_id" "uuid") TO "service_role";


-- Function 2.3.4: get_user_secrets
-- Purpose: Get user's own secrets only
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."get_user_secrets"("p_limit" integer, "p_offset" integer) TO "anon";
GRANT ALL ON FUNCTION "public"."get_user_secrets"("p_limit" integer, "p_offset" integer) TO "authenticated";
GRANT ALL ON FUNCTION "public"."get_user_secrets"("p_limit" integer, "p_offset" integer) TO "service_role";


-- Function 2.3.5: get_user_secrets_with_shared
-- Purpose: Get user's secrets + secrets shared with them
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."get_user_secrets_with_shared"("p_limit" integer, "p_offset" integer) TO "anon";
GRANT ALL ON FUNCTION "public"."get_user_secrets_with_shared"("p_limit" integer, "p_offset" integer) TO "authenticated";
GRANT ALL ON FUNCTION "public"."get_user_secrets_with_shared"("p_limit" integer, "p_offset" integer) TO "service_role";


-- Function 2.3.6: search_user_secrets
-- Purpose: Search user's secrets by website/username
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."search_user_secrets"("p_query" "text", "p_limit" integer, "p_offset" integer) TO "anon";
GRANT ALL ON FUNCTION "public"."search_user_secrets"("p_query" "text", "p_limit" integer, "p_offset" integer) TO "authenticated";
GRANT ALL ON FUNCTION "public"."search_user_secrets"("p_query" "text", "p_limit" integer, "p_offset" integer) TO "service_role";


-- Function 2.3.7: share_secret
-- Purpose: Share secret with user or role
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."share_secret"("p_secret_id" "uuid", "p_target_user_id" "uuid", "p_target_role_id" "uuid", "p_notes" "text", "p_expires_at" timestamp with time zone) TO "anon";
GRANT ALL ON FUNCTION "public"."share_secret"("p_secret_id" "uuid", "p_target_user_id" "uuid", "p_target_role_id" "uuid", "p_notes" "text", "p_expires_at" timestamp with time zone) TO "authenticated";
GRANT ALL ON FUNCTION "public"."share_secret"("p_secret_id" "uuid", "p_target_user_id" "uuid", "p_target_role_id" "uuid", "p_notes" "text", "p_expires_at" timestamp with time zone) TO "service_role";


-- Function 2.3.8: unshare_secret
-- Purpose: Remove share from user or role
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."unshare_secret"("p_secret_id" "uuid", "p_target_user_id" "uuid", "p_target_role_id" "uuid") TO "anon";
GRANT ALL ON FUNCTION "public"."unshare_secret"("p_secret_id" "uuid", "p_target_user_id" "uuid", "p_target_role_id" "uuid") TO "authenticated";
GRANT ALL ON FUNCTION "public"."unshare_secret"("p_secret_id" "uuid", "p_target_user_id" "uuid", "p_target_role_id" "uuid") TO "service_role";


-- Function 2.3.9: update_secret
-- Purpose: Update existing secret (owner-only via function logic)
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."update_secret"("p_secret_id" "uuid", "p_website" "text", "p_username" "text", "p_password" "text", "p_notes" "text", "p_expiration_date" timestamp with time zone, "p_tags" "text"[], "p_twofa_enabled" boolean, "p_twofa_type" "text", "p_recovery_codes" "text"[]) TO "anon";
GRANT ALL ON FUNCTION "public"."update_secret"("p_secret_id" "uuid", "p_website" "text", "p_username" "text", "p_password" "text", "p_notes" "text", "p_expiration_date" timestamp with time zone, "p_tags" "text"[], "p_twofa_enabled" boolean, "p_twofa_type" "text", "p_recovery_codes" "text"[]) TO "authenticated";
GRANT ALL ON FUNCTION "public"."update_secret"("p_secret_id" "uuid", "p_website" "text", "p_username" "text", "p_password" "text", "p_notes" "text", "p_expiration_date" timestamp with time zone, "p_tags" "text"[], "p_twofa_enabled" boolean, "p_twofa_type" "text", "p_recovery_codes" "text"[]) TO "service_role";



-- -----------------------------------------------------------------------------
-- SECTION 2.4: Encryption Functions (12 grants = 4 functions × 3 roles)
-- -----------------------------------------------------------------------------
-- Purpose: AES-256 encryption/decryption helper functions
-- Access: All standard roles (anon, authenticated, service_role)
-- Security: Functions use Supabase Vault for master key


-- Function 2.4.1: decrypt_text
-- Purpose: Decrypt AES-256 encrypted text
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."decrypt_text"("ciphertext" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."decrypt_text"("ciphertext" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."decrypt_text"("ciphertext" "text") TO "service_role";


-- Function 2.4.2: encrypt_text
-- Purpose: Encrypt plaintext with AES-256
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."encrypt_text"("plaintext" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."encrypt_text"("plaintext" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."encrypt_text"("plaintext" "text") TO "service_role";


-- Function 2.4.3: get_encryption_key
-- Purpose: Retrieve master encryption key from Supabase Vault
-- Grants: anon, authenticated, service_role
-- Security: SECURITY DEFINER allows function to access vault
GRANT ALL ON FUNCTION "public"."get_encryption_key"() TO "anon";
GRANT ALL ON FUNCTION "public"."get_encryption_key"() TO "authenticated";
GRANT ALL ON FUNCTION "public"."get_encryption_key"() TO "service_role";


-- Function 2.4.4: safe_decrypt_recovery_codes
-- Purpose: Safely decrypt recovery codes with error handling
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."safe_decrypt_recovery_codes"("encrypted_data" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."safe_decrypt_recovery_codes"("encrypted_data" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."safe_decrypt_recovery_codes"("encrypted_data" "text") TO "service_role";



-- -----------------------------------------------------------------------------
-- SECTION 2.5: User Functions (15 grants = 5 functions × 3 roles)
-- -----------------------------------------------------------------------------
-- Purpose: Core user management functions
-- Access: All standard roles (anon, authenticated, service_role)


-- Function 2.5.1: delete_user
-- Purpose: Delete user account (admin-only via function logic)
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."delete_user"("target_user_id" "uuid") TO "anon";
GRANT ALL ON FUNCTION "public"."delete_user"("target_user_id" "uuid") TO "authenticated";
GRANT ALL ON FUNCTION "public"."delete_user"("target_user_id" "uuid") TO "service_role";


-- Function 2.5.2: find_user_by_email
-- Purpose: Look up user by email address
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."find_user_by_email"("p_email" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."find_user_by_email"("p_email" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."find_user_by_email"("p_email" "text") TO "service_role";


-- Function 2.5.3: get_user_roles
-- Purpose: Get user's role IDs
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."get_user_roles"("check_user_id" "uuid") TO "anon";
GRANT ALL ON FUNCTION "public"."get_user_roles"("check_user_id" "uuid") TO "authenticated";
GRANT ALL ON FUNCTION "public"."get_user_roles"("check_user_id" "uuid") TO "service_role";


-- Function 2.5.4: get_user_roles_with_names
-- Purpose: Get user's roles with full details
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."get_user_roles_with_names"("target_user_id" "uuid") TO "anon";
GRANT ALL ON FUNCTION "public"."get_user_roles_with_names"("target_user_id" "uuid") TO "authenticated";
GRANT ALL ON FUNCTION "public"."get_user_roles_with_names"("target_user_id" "uuid") TO "service_role";


-- Function 2.5.5: user_has_role
-- Purpose: Check if user has specific role
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."user_has_role"("check_user_id" "uuid", "check_role" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."user_has_role"("check_user_id" "uuid", "check_role" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."user_has_role"("check_user_id" "uuid", "check_role" "text") TO "service_role";



-- -----------------------------------------------------------------------------
-- SECTION 2.6: Helper Functions (27 grants = 9 functions × 3 roles, +2 special)
-- -----------------------------------------------------------------------------
-- Purpose: RBAC query helpers, auth hooks, lifecycle triggers, cleanup triggers
-- Access: Most grant to standard roles, auth hooks also grant to supabase_auth_admin


-- Function 2.6.1: custom_access_token_hook (SPECIAL - 4 grants)
-- Purpose: Inject RBAC claims into JWT during login/token refresh
-- Grants: anon, authenticated, service_role, supabase_auth_admin
-- Special: REVOKE FROM PUBLIC first (removes default public access)
-- Why supabase_auth_admin: Auth system calls this hook during token generation
-- Critical: This function MUST be accessible to supabase_auth_admin or auth will fail
REVOKE ALL ON FUNCTION "public"."custom_access_token_hook"("event" "jsonb") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."custom_access_token_hook"("event" "jsonb") TO "anon";
GRANT ALL ON FUNCTION "public"."custom_access_token_hook"("event" "jsonb") TO "authenticated";
GRANT ALL ON FUNCTION "public"."custom_access_token_hook"("event" "jsonb") TO "service_role";
GRANT ALL ON FUNCTION "public"."custom_access_token_hook"("event" "jsonb") TO "supabase_auth_admin";


-- Function 2.6.2: get_user_roles_for_hook (SPECIAL - 4 grants)
-- Purpose: Get user roles for auth hook (used by custom_access_token_hook)
-- Grants: anon, authenticated, service_role, supabase_auth_admin
-- Why supabase_auth_admin: Auth hook calls this function during token generation
GRANT ALL ON FUNCTION "public"."get_user_roles_for_hook"("check_user_id" "uuid") TO "anon";
GRANT ALL ON FUNCTION "public"."get_user_roles_for_hook"("check_user_id" "uuid") TO "authenticated";
GRANT ALL ON FUNCTION "public"."get_user_roles_for_hook"("check_user_id" "uuid") TO "service_role";
GRANT ALL ON FUNCTION "public"."get_user_roles_for_hook"("check_user_id" "uuid") TO "supabase_auth_admin";


-- Function 2.6.3: handle_new_user
-- Purpose: Trigger function - create user record on signup
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."handle_new_user"() TO "anon";
GRANT ALL ON FUNCTION "public"."handle_new_user"() TO "authenticated";
GRANT ALL ON FUNCTION "public"."handle_new_user"() TO "service_role";


-- Function 2.6.4: handle_user_email_update
-- Purpose: Trigger function - sync email changes from auth.users to public.users
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."handle_user_email_update"() TO "anon";
GRANT ALL ON FUNCTION "public"."handle_user_email_update"() TO "authenticated";
GRANT ALL ON FUNCTION "public"."handle_user_email_update"() TO "service_role";


-- Function 2.6.5: is_user_admin
-- Purpose: Check if user has admin role
-- Grants: anon, authenticated, service_role
-- Critical: Used throughout RBAC functions for admin checks
GRANT ALL ON FUNCTION "public"."is_user_admin"("check_user_id" "uuid") TO "anon";
GRANT ALL ON FUNCTION "public"."is_user_admin"("check_user_id" "uuid") TO "authenticated";
GRANT ALL ON FUNCTION "public"."is_user_admin"("check_user_id" "uuid") TO "service_role";


-- Function 2.6.6: trigger_cleanup_expired_challenges
-- Purpose: Trigger function - cleanup expired passkey challenges
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."trigger_cleanup_expired_challenges"() TO "anon";
GRANT ALL ON FUNCTION "public"."trigger_cleanup_expired_challenges"() TO "authenticated";
GRANT ALL ON FUNCTION "public"."trigger_cleanup_expired_challenges"() TO "service_role";


-- Function 2.6.7: trigger_cleanup_expired_completed_auths
-- Purpose: Trigger function - cleanup expired completed authentications
-- Grants: anon, authenticated, service_role
GRANT ALL ON FUNCTION "public"."trigger_cleanup_expired_completed_auths"() TO "anon";
GRANT ALL ON FUNCTION "public"."trigger_cleanup_expired_completed_auths"() TO "authenticated";
GRANT ALL ON FUNCTION "public"."trigger_cleanup_expired_completed_auths"() TO "service_role";



-- ============================================================================
-- SECTION 3: Table-Level Grants (42 grants = 13 tables × 3 roles, +2 special)
-- ============================================================================
-- Purpose: Allow roles to query and modify tables via PostgREST API
-- Pattern: Most tables grant to anon, authenticated, service_role (3 grants)
--          Special tables also grant to supabase_auth_admin (4 grants)
-- Security: RLS policies restrict which rows each user can access


-- -----------------------------------------------------------------------------
-- SECTION 3.1: Passkey Tables (12 grants = 4 tables × 3 roles)
-- -----------------------------------------------------------------------------
-- Purpose: WebAuthn/FIDO2 credential storage
-- Access: All standard roles (anon, authenticated, service_role)


-- Table 3.1.1: user_passkeys
-- Purpose: Store user passkey credentials
-- Grants: anon, authenticated, service_role
-- RLS: Users can only see own passkeys
GRANT ALL ON TABLE "public"."user_passkeys" TO "anon";
GRANT ALL ON TABLE "public"."user_passkeys" TO "authenticated";
GRANT ALL ON TABLE "public"."user_passkeys" TO "service_role";


-- Table 3.1.2: active_user_passkeys (VIEW)
-- Purpose: View of active passkeys only (active = true)
-- Grants: anon, authenticated, service_role
-- Note: This is a VIEW, not a table
GRANT ALL ON TABLE "public"."active_user_passkeys" TO "anon";
GRANT ALL ON TABLE "public"."active_user_passkeys" TO "authenticated";
GRANT ALL ON TABLE "public"."active_user_passkeys" TO "service_role";


-- Table 3.1.3: completed_authentications
-- Purpose: Store completed authentication results for cross-device flow
-- Grants: anon, authenticated, service_role
-- RLS: Desktop polls by session_id (short-lived - 5 minutes)
GRANT ALL ON TABLE "public"."completed_authentications" TO "anon";
GRANT ALL ON TABLE "public"."completed_authentications" TO "authenticated";
GRANT ALL ON TABLE "public"."completed_authentications" TO "service_role";


-- Table 3.1.4: passkey_challenges
-- Purpose: Store temporary WebAuthn challenges
-- Grants: anon, authenticated, service_role
-- RLS: Session-based access (challenges expire after 5 minutes)
GRANT ALL ON TABLE "public"."passkey_challenges" TO "anon";
GRANT ALL ON TABLE "public"."passkey_challenges" TO "authenticated";
GRANT ALL ON TABLE "public"."passkey_challenges" TO "service_role";



-- -----------------------------------------------------------------------------
-- SECTION 3.2: RBAC Tables (15 grants = 5 tables × 3 roles, +2 special)
-- -----------------------------------------------------------------------------
-- Purpose: Role-based access control system
-- Access: Most tables grant to standard roles
--         Special tables also grant to supabase_auth_admin (auth hooks need access)


-- Table 3.2.1: permissions
-- Purpose: Store available permissions
-- Grants: anon, authenticated, service_role
-- RLS: Admin-only access via RLS policies
GRANT ALL ON TABLE "public"."permissions" TO "anon";
GRANT ALL ON TABLE "public"."permissions" TO "authenticated";
GRANT ALL ON TABLE "public"."permissions" TO "service_role";


-- Table 3.2.2: roles (SPECIAL - 4 grants)
-- Purpose: Store available roles
-- Grants: anon, authenticated, service_role, supabase_auth_admin
-- Why supabase_auth_admin: Auth hooks need to query roles table for JWT claims
GRANT ALL ON TABLE "public"."roles" TO "anon";
GRANT ALL ON TABLE "public"."roles" TO "authenticated";
GRANT ALL ON TABLE "public"."roles" TO "service_role";
GRANT ALL ON TABLE "public"."roles" TO "supabase_auth_admin";


-- Table 3.2.3: role_permissions
-- Purpose: Store role-permission assignments
-- Grants: anon, authenticated, service_role
-- RLS: Admin-only access via RLS policies
GRANT ALL ON TABLE "public"."role_permissions" TO "anon";
GRANT ALL ON TABLE "public"."role_permissions" TO "authenticated";
GRANT ALL ON TABLE "public"."role_permissions" TO "service_role";


-- Table 3.2.4: user_roles (SPECIAL - 4 grants)
-- Purpose: Store user-role assignments
-- Grants: anon, authenticated, service_role, supabase_auth_admin
-- Why supabase_auth_admin: Auth hooks need to query user_roles for JWT claims
GRANT ALL ON TABLE "public"."user_roles" TO "anon";
GRANT ALL ON TABLE "public"."user_roles" TO "authenticated";
GRANT ALL ON TABLE "public"."user_roles" TO "service_role";
GRANT ALL ON TABLE "public"."user_roles" TO "supabase_auth_admin";


-- Table 3.2.5: users
-- Purpose: Mirror auth.users table in public schema
-- Grants: anon, authenticated, service_role
-- RLS: Users can read own profile, admins can read all
GRANT ALL ON TABLE "public"."users" TO "anon";
GRANT ALL ON TABLE "public"."users" TO "authenticated";
GRANT ALL ON TABLE "public"."users" TO "service_role";



-- -----------------------------------------------------------------------------
-- SECTION 3.3: Secret Tables (15 grants = 5 tables × 3 roles)
-- -----------------------------------------------------------------------------
-- Purpose: Encrypted credential storage and sharing
-- Access: All standard roles (anon, authenticated, service_role)


-- Table 3.3.1: secrets
-- Purpose: Store encrypted passwords and credentials
-- Grants: anon, authenticated, service_role
-- RLS: User ownership + sharing access
GRANT ALL ON TABLE "public"."secrets" TO "anon";
GRANT ALL ON TABLE "public"."secrets" TO "authenticated";
GRANT ALL ON TABLE "public"."secrets" TO "service_role";


-- Table 3.3.2: secret_metadata
-- Purpose: Store 2FA settings for secrets
-- Grants: anon, authenticated, service_role
-- RLS: Inherits secret ownership via foreign key
GRANT ALL ON TABLE "public"."secret_metadata" TO "anon";
GRANT ALL ON TABLE "public"."secret_metadata" TO "authenticated";
GRANT ALL ON TABLE "public"."secret_metadata" TO "service_role";


-- Table 3.3.3: secret_tags
-- Purpose: Store tags for organizing secrets
-- Grants: anon, authenticated, service_role
-- RLS: Inherits secret ownership via foreign key
GRANT ALL ON TABLE "public"."secret_tags" TO "anon";
GRANT ALL ON TABLE "public"."secret_tags" TO "authenticated";
GRANT ALL ON TABLE "public"."secret_tags" TO "service_role";


-- Table 3.3.4: secret_shares
-- Purpose: Store secret sharing permissions
-- Grants: anon, authenticated, service_role
-- RLS: Share participants can read/modify
GRANT ALL ON TABLE "public"."secret_shares" TO "anon";
GRANT ALL ON TABLE "public"."secret_shares" TO "authenticated";
GRANT ALL ON TABLE "public"."secret_shares" TO "service_role";


-- Table 3.3.5: secret_access_log
-- Purpose: Audit log for secret access
-- Grants: anon, authenticated, service_role
-- RLS: Users can read own logs, secret owners can read all logs for their secrets
GRANT ALL ON TABLE "public"."secret_access_log" TO "anon";
GRANT ALL ON TABLE "public"."secret_access_log" TO "authenticated";
GRANT ALL ON TABLE "public"."secret_access_log" TO "service_role";



-- ============================================================================
-- SECTION 4: Default Privileges (12 grants = 3 object types × 4 roles)
-- ============================================================================
-- Purpose: Automatically grant privileges to future objects created by postgres role
-- Why Needed: When postgres role creates new tables/functions/sequences,
--             these grants ensure anon/authenticated/service_role can access them
-- Scope: Only applies to objects created by postgres role in public schema


-- -----------------------------------------------------------------------------
-- SECTION 4.1: Sequence Default Privileges (4 grants)
-- -----------------------------------------------------------------------------
-- Purpose: Auto-grant privileges for future sequences (e.g., SERIAL columns)
-- Use Case: If we add SERIAL id column, anon/authenticated/service_role get access automatically

ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON SEQUENCES TO "postgres";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON SEQUENCES TO "anon";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON SEQUENCES TO "authenticated";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON SEQUENCES TO "service_role";


-- -----------------------------------------------------------------------------
-- SECTION 4.2: Function Default Privileges (4 grants)
-- -----------------------------------------------------------------------------
-- Purpose: Auto-grant privileges for future functions
-- Use Case: If we add new function, anon/authenticated/service_role get access automatically

ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON FUNCTIONS TO "postgres";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON FUNCTIONS TO "anon";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON FUNCTIONS TO "authenticated";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON FUNCTIONS TO "service_role";


-- -----------------------------------------------------------------------------
-- SECTION 4.3: Table Default Privileges (4 grants)
-- -----------------------------------------------------------------------------
-- Purpose: Auto-grant privileges for future tables
-- Use Case: If we add new table, anon/authenticated/service_role get access automatically

ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON TABLES TO "postgres";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON TABLES TO "anon";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON TABLES TO "authenticated";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON TABLES TO "service_role";



-- ============================================================================
-- End of File: grants.sql
-- ============================================================================
-- Migration Complete! All 14 files created successfully.
--
-- Next Steps:
--   1. Delete old baseline: rm supabase/migrations/20251023_complete_baseline.sql
--   2. Test locally: supabase db reset --local
--   3. Verify all objects created:
--      - 13 tables (11 tables + 2 junction tables)
--      - 1 view (active_user_passkeys)
--      - 42 functions (11 RBAC + 4 passkey + 9 secret + 4 encryption + 5 user + 9 helper)
--      - 35 indexes (13 passkey + 8 RBAC + 12 secret + 2 user)
--      - 47 constraints (18 PK + 11 UNIQUE + 18 FK)
--      - 4 triggers (2 lifecycle + 2 cleanup)
--      - 50+ RLS policies (14 RBAC + 3 user + 11 passkey + 20 secret)
--      - 150+ grants (5 schema + 120+ function + 40+ table + 12 default)
--   4. If local testing succeeds: supabase db reset --linked (remote reset)
--   5. Run seed data: supabase db seed --linked
-- ============================================================================
