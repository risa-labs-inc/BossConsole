-- ============================================================================
-- BOSS Database Schema: Triggers and Constraints
-- ============================================================================
-- File: 20251023000011_triggers_and_constraints.sql
-- Description: Primary keys, unique constraints, foreign keys, and triggers.
--              Enforces data integrity and automates user lifecycle events.
-- Dependencies:
--   - File 7: helper_functions.sql (trigger functions)
--   - Files 8-10: All table definitions
-- Constraints: 47 total (18 PRIMARY KEY, 11 UNIQUE, 18 FOREIGN KEY)
-- Triggers: 4 total (user lifecycle, automatic cleanup)
-- ============================================================================


-- ============================================================================
-- SECTION 1: PRIMARY KEY Constraints (18 constraints)
-- ============================================================================
-- Purpose: Enforce uniqueness and provide table identity

ALTER TABLE ONLY "public"."completed_authentications"
    ADD CONSTRAINT "completed_authentications_pkey" PRIMARY KEY ("id");

ALTER TABLE ONLY "public"."passkey_challenges"
    ADD CONSTRAINT "passkey_challenges_pkey" PRIMARY KEY ("id");

ALTER TABLE ONLY "public"."permissions"
    ADD CONSTRAINT "permissions_pkey" PRIMARY KEY ("id");

ALTER TABLE ONLY "public"."role_permissions"
    ADD CONSTRAINT "role_permissions_new_pkey" PRIMARY KEY ("id");

ALTER TABLE ONLY "public"."roles"
    ADD CONSTRAINT "roles_pkey" PRIMARY KEY ("id");

ALTER TABLE ONLY "public"."secret_access_log"
    ADD CONSTRAINT "secret_access_log_pkey" PRIMARY KEY ("id");

ALTER TABLE ONLY "public"."secret_metadata"
    ADD CONSTRAINT "secret_metadata_pkey" PRIMARY KEY ("id");

ALTER TABLE ONLY "public"."secret_shares"
    ADD CONSTRAINT "secret_shares_pkey" PRIMARY KEY ("id");

ALTER TABLE ONLY "public"."secret_tags"
    ADD CONSTRAINT "secret_tags_pkey" PRIMARY KEY ("id");

ALTER TABLE ONLY "public"."secrets"
    ADD CONSTRAINT "secrets_pkey" PRIMARY KEY ("id");

ALTER TABLE ONLY "public"."user_passkeys"
    ADD CONSTRAINT "user_passkeys_pkey" PRIMARY KEY ("id");

ALTER TABLE ONLY "public"."user_roles"
    ADD CONSTRAINT "user_roles_new_pkey" PRIMARY KEY ("id");

ALTER TABLE ONLY "public"."users"
    ADD CONSTRAINT "users_pkey" PRIMARY KEY ("id");



-- ============================================================================
-- SECTION 2: UNIQUE Constraints (11 constraints)
-- ============================================================================
-- Purpose: Prevent duplicate entries and enforce business rules


-- Passkey Tables
-- -----------------------------------------------------------------------------

-- completed_authentications: Challenge must be unique (prevent replay attacks)
ALTER TABLE ONLY "public"."completed_authentications"
    ADD CONSTRAINT "completed_authentications_challenge_key" UNIQUE ("challenge");

-- user_passkeys: Credential ID must be unique across all users
-- Why: WebAuthn credential_id is globally unique per device+user+RP
ALTER TABLE ONLY "public"."user_passkeys"
    ADD CONSTRAINT "user_passkeys_credential_id_key" UNIQUE ("credential_id");


-- RBAC Tables
-- -----------------------------------------------------------------------------

-- permissions: Permission name must be unique (e.g., "users.read")
ALTER TABLE ONLY "public"."permissions"
    ADD CONSTRAINT "permissions_name_key" UNIQUE ("name");

-- roles: Role name must be unique (e.g., "admin", "editor")
ALTER TABLE ONLY "public"."roles"
    ADD CONSTRAINT "roles_name_key" UNIQUE ("name");

-- role_permissions: Role can only have permission once (prevent duplicates)
-- Composite unique: (role_id, permission_id)
ALTER TABLE ONLY "public"."role_permissions"
    ADD CONSTRAINT "role_permissions_new_role_id_permission_id_key" UNIQUE ("role_id", "permission_id");

-- user_roles: User can only have role once (prevent duplicate assignments)
-- Composite unique: (user_id, role_id)
ALTER TABLE ONLY "public"."user_roles"
    ADD CONSTRAINT "user_roles_new_user_id_role_id_key" UNIQUE ("user_id", "role_id");

-- users: Email must be unique
-- Why: Synced from auth.users which enforces email uniqueness
ALTER TABLE ONLY "public"."users"
    ADD CONSTRAINT "users_email_key" UNIQUE ("email");


-- Secret Tables
-- -----------------------------------------------------------------------------

-- secret_metadata: Secret can only have one metadata record (1:1 relationship)
ALTER TABLE ONLY "public"."secret_metadata"
    ADD CONSTRAINT "secret_metadata_secret_id_key" UNIQUE ("secret_id");

-- secret_shares: Secret can only be shared once per role (prevent duplicate shares)
-- NULL values are allowed (for user-based shares)
ALTER TABLE ONLY "public"."secret_shares"
    ADD CONSTRAINT "unique_role_share" UNIQUE ("secret_id", "shared_with_role_id");

-- secret_shares: Secret can only be shared once per user (prevent duplicate shares)
-- NULL values are allowed (for role-based shares)
ALTER TABLE ONLY "public"."secret_shares"
    ADD CONSTRAINT "unique_user_share" UNIQUE ("secret_id", "shared_with_user_id");

-- secret_tags: Secret can only have tag once (prevent duplicate tags)
-- Composite unique: (secret_id, tag)
ALTER TABLE ONLY "public"."secret_tags"
    ADD CONSTRAINT "unique_secret_tag" UNIQUE ("secret_id", "tag");

-- secrets: User can only have one credential per website+username combination
-- Composite unique: (user_id, website, username)
-- Example: User can't have two "github.com" + "user@email.com" entries
ALTER TABLE ONLY "public"."secrets"
    ADD CONSTRAINT "unique_user_website_username" UNIQUE ("user_id", "website", "username");



-- ============================================================================
-- SECTION 3: FOREIGN KEY Constraints (18 constraints)
-- ============================================================================
-- Purpose: Maintain referential integrity and enable cascading deletes


-- Passkey Tables
-- -----------------------------------------------------------------------------

-- passkey_challenges: Challenge belongs to user (auth.users)
-- ON DELETE CASCADE: Delete challenges when user deleted
ALTER TABLE ONLY "public"."passkey_challenges"
    ADD CONSTRAINT "passkey_challenges_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;

-- user_passkeys: Passkey belongs to user (auth.users)
-- ON DELETE CASCADE: Delete passkeys when user deleted
ALTER TABLE ONLY "public"."user_passkeys"
    ADD CONSTRAINT "user_passkeys_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;


-- RBAC Tables
-- -----------------------------------------------------------------------------

-- role_permissions: Permission assignment references permission table
-- ON DELETE CASCADE: Delete role_permissions when permission deleted
ALTER TABLE ONLY "public"."role_permissions"
    ADD CONSTRAINT "role_permissions_new_permission_id_fkey" FOREIGN KEY ("permission_id") REFERENCES "public"."permissions"("id") ON DELETE CASCADE;

-- role_permissions: Permission assignment references role table
-- ON DELETE CASCADE: Delete role_permissions when role deleted
ALTER TABLE ONLY "public"."role_permissions"
    ADD CONSTRAINT "role_permissions_new_role_id_fkey" FOREIGN KEY ("role_id") REFERENCES "public"."roles"("id") ON DELETE CASCADE;

-- user_roles: User assignment references auth.users
-- ON DELETE CASCADE: Delete user_roles when user deleted
ALTER TABLE ONLY "public"."user_roles"
    ADD CONSTRAINT "user_roles_new_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;

-- user_roles: Role assignment references roles table
-- ON DELETE CASCADE: Delete user_roles when role deleted
ALTER TABLE ONLY "public"."user_roles"
    ADD CONSTRAINT "user_roles_new_role_id_fkey" FOREIGN KEY ("role_id") REFERENCES "public"."roles"("id") ON DELETE CASCADE;

-- user_roles: Assigned by admin (nullable - system assignments have NULL)
-- ON DELETE SET NULL: Keep assignment but clear assigned_by when admin deleted
ALTER TABLE ONLY "public"."user_roles"
    ADD CONSTRAINT "user_roles_new_assigned_by_fkey" FOREIGN KEY ("assigned_by") REFERENCES "auth"."users"("id") ON DELETE SET NULL;

-- users: Mirror auth.users table
-- ON DELETE CASCADE: Delete public.users when auth.users deleted
ALTER TABLE ONLY "public"."users"
    ADD CONSTRAINT "users_id_fkey" FOREIGN KEY ("id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;


-- Secret Tables
-- -----------------------------------------------------------------------------

-- secrets: Secret belongs to user (auth.users)
-- ON DELETE CASCADE: Delete secrets when user deleted
ALTER TABLE ONLY "public"."secrets"
    ADD CONSTRAINT "secrets_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;

-- secret_metadata: Metadata belongs to secret
-- ON DELETE CASCADE: Delete metadata when secret deleted
ALTER TABLE ONLY "public"."secret_metadata"
    ADD CONSTRAINT "secret_metadata_secret_id_fkey" FOREIGN KEY ("secret_id") REFERENCES "public"."secrets"("id") ON DELETE CASCADE;

-- secret_tags: Tag belongs to secret
-- ON DELETE CASCADE: Delete tags when secret deleted
ALTER TABLE ONLY "public"."secret_tags"
    ADD CONSTRAINT "secret_tags_secret_id_fkey" FOREIGN KEY ("secret_id") REFERENCES "public"."secrets"("id") ON DELETE CASCADE;

-- secret_shares: Share references secret
-- ON DELETE CASCADE: Delete shares when secret deleted
ALTER TABLE ONLY "public"."secret_shares"
    ADD CONSTRAINT "secret_shares_secret_id_fkey" FOREIGN KEY ("secret_id") REFERENCES "public"."secrets"("id") ON DELETE CASCADE;

-- secret_shares: Share references sharing user (who granted access)
-- NO CASCADE: Keep share record but shared_by becomes invalid (audit trail)
ALTER TABLE ONLY "public"."secret_shares"
    ADD CONSTRAINT "secret_shares_shared_by_fkey" FOREIGN KEY ("shared_by") REFERENCES "auth"."users"("id");

-- secret_shares: Share references shared user (who receives access)
-- ON DELETE CASCADE: Delete share when recipient user deleted
ALTER TABLE ONLY "public"."secret_shares"
    ADD CONSTRAINT "secret_shares_shared_with_user_id_fkey" FOREIGN KEY ("shared_with_user_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;

-- secret_shares: Share references shared role (role that receives access)
-- ON DELETE CASCADE: Delete share when role deleted
ALTER TABLE ONLY "public"."secret_shares"
    ADD CONSTRAINT "secret_shares_shared_with_role_id_fkey" FOREIGN KEY ("shared_with_role_id") REFERENCES "public"."roles"("id") ON DELETE CASCADE;

-- secret_access_log: Log references secret
-- ON DELETE CASCADE: Delete logs when secret deleted
ALTER TABLE ONLY "public"."secret_access_log"
    ADD CONSTRAINT "secret_access_log_secret_id_fkey" FOREIGN KEY ("secret_id") REFERENCES "public"."secrets"("id") ON DELETE CASCADE;

-- secret_access_log: Log references user who performed operation
-- ON DELETE CASCADE: Delete logs when user deleted
ALTER TABLE ONLY "public"."secret_access_log"
    ADD CONSTRAINT "secret_access_log_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;



-- ============================================================================
-- SECTION 4: Triggers (4 triggers)
-- ============================================================================
-- Purpose: Automate user lifecycle and cleanup operations


-- Trigger 4.1: on_auth_user_created
-- -----------------------------------------------------------------------------
-- Purpose: Automatically create user record and assign default role on signup
-- Fires: AFTER INSERT ON auth.users
-- Function: handle_new_user() (defined in File 7: helper_functions.sql)
-- Actions:
--   1. INSERT INTO public.users (mirror auth.users record)
--   2. INSERT INTO user_roles (assign default 'user' role)
-- Why Needed: Supabase Auth creates users in auth.users, but we need:
--   - Mirror record in public.users for foreign keys and RLS
--   - Default role assignment for RBAC system
-- Example:
--   User signs up → auth.users INSERT → trigger fires → public.users INSERT + user_roles INSERT
CREATE TRIGGER "on_auth_user_created"
    AFTER INSERT ON "auth"."users"
    FOR EACH ROW EXECUTE FUNCTION "public"."handle_new_user"();

-- Note: Cannot add COMMENT on auth.users triggers (not owner of auth schema)



-- Trigger 4.2: on_auth_user_updated
-- -----------------------------------------------------------------------------
-- Purpose: Sync email changes from auth.users to public.users
-- Fires: AFTER UPDATE ON auth.users
-- Function: handle_user_email_update() (defined in File 7: helper_functions.sql)
-- Actions:
--   1. UPDATE public.users SET email = NEW.email WHERE id = NEW.id
-- Why Needed: Email stored in both auth.users and public.users must stay consistent
-- Example:
--   User changes email → auth.users UPDATE → trigger fires → public.users UPDATE
CREATE TRIGGER "on_auth_user_updated"
    AFTER UPDATE ON "auth"."users"
    FOR EACH ROW EXECUTE FUNCTION "public"."handle_user_email_update"();

-- Note: Cannot add COMMENT on auth.users triggers (not owner of auth schema)



-- Trigger 4.3: trigger_cleanup_expired_challenges_on_insert
-- -----------------------------------------------------------------------------
-- Purpose: Probabilistically clean up expired passkey challenges
-- Fires: AFTER INSERT ON passkey_challenges
-- Function: trigger_cleanup_expired_challenges() (defined in File 7: helper_functions.sql)
-- Probability: 10% (reduces overhead while keeping table clean)
-- Actions:
--   1. IF random() < 0.1 THEN DELETE FROM passkey_challenges WHERE expires_at < NOW()
-- Why Probabilistic: Not every insert needs cleanup - occasional cleanup is sufficient
-- Cleanup Rules:
--   - Delete challenges where expires_at < NOW() (hard expiration)
--   - Challenges expire after 5 minutes (standard WebAuthn timeout)
-- Example:
--   Desktop creates challenge → passkey_challenges INSERT → 10% chance of cleanup
CREATE TRIGGER "trigger_cleanup_expired_challenges_on_insert"
    AFTER INSERT ON "public"."passkey_challenges"
    FOR EACH ROW EXECUTE FUNCTION "public"."trigger_cleanup_expired_challenges"();

COMMENT ON TRIGGER "trigger_cleanup_expired_challenges_on_insert" ON "public"."passkey_challenges" IS 'Probabilistically cleans up expired challenges on insert (10% chance)';



-- Trigger 4.4: trigger_cleanup_expired_completed_auths_on_insert
-- -----------------------------------------------------------------------------
-- Purpose: Probabilistically clean up expired completed authentications
-- Fires: AFTER INSERT ON completed_authentications
-- Function: trigger_cleanup_expired_completed_auths() (defined in File 7: helper_functions.sql)
-- Probability: 10% (reduces overhead while keeping table clean)
-- Actions:
--   1. IF random() < 0.1 THEN DELETE FROM completed_authentications WHERE expires_at_timestamp < NOW()
-- Why Probabilistic: Authentication results are short-lived (2 minutes), occasional cleanup sufficient
-- Cleanup Rules:
--   - Delete authentication results where expires_at_timestamp < NOW()
--   - Results expire after 5 minutes (from creation)
-- Example:
--   Mobile completes auth → completed_authentications INSERT → 10% chance of cleanup
CREATE TRIGGER "trigger_cleanup_expired_completed_auths_on_insert"
    AFTER INSERT ON "public"."completed_authentications"
    FOR EACH ROW EXECUTE FUNCTION "public"."trigger_cleanup_expired_completed_auths"();

COMMENT ON TRIGGER "trigger_cleanup_expired_completed_auths_on_insert" ON "public"."completed_authentications" IS 'Probabilistically cleans up expired completed authentications on insert (10% chance)';


-- ============================================================================
-- End of File: triggers_and_constraints.sql
-- ============================================================================
-- Next Migration: 20251023000012_indexes.sql (Indexes)
-- ============================================================================
