-- ============================================================================
-- BOSS Database Schema: Performance Indexes
-- ============================================================================
-- File: 20251023000012_indexes.sql
-- Description: B-tree indexes for query performance optimization. Covers
--              foreign key lookups, filtering, searching, and JOIN operations.
-- Dependencies: Files 8-10 (all table definitions)
-- Indexes: 35 total
-- Index Type: B-tree (default PostgreSQL index type)
-- Strategy: Index foreign keys, WHERE clauses, ORDER BY columns, and partial indexes
-- ============================================================================


-- ============================================================================
-- Index Strategy Overview
-- ============================================================================
--
-- Why Indexes Matter:
--   - Speed up WHERE clause filters (e.g., WHERE user_id = '...')
--   - Optimize JOIN operations (e.g., JOIN ON role_id)
--   - Accelerate ORDER BY sorting (e.g., ORDER BY timestamp DESC)
--   - Reduce full table scans on large tables
--
-- Indexing Priorities:
--   1. Foreign key columns (for JOIN performance)
--   2. WHERE clause columns (frequently filtered fields)
--   3. ORDER BY columns (sorted queries)
--   4. Unique constraints (already indexed by PostgreSQL)
--
-- Partial Indexes:
--   - Index only subset of rows WHERE condition is met
--   - Smaller index size, faster queries on filtered data
--   - Example: WHERE active = true (only index active records)
--
-- Index Maintenance:
--   - Indexes automatically updated on INSERT/UPDATE/DELETE
--   - Overhead on write operations (acceptable tradeoff for read speed)
--   - VACUUM and ANALYZE keep indexes optimized
--
-- ============================================================================


-- ============================================================================
-- SECTION 1: Passkey Tables (13 indexes)
-- ============================================================================
-- Purpose: Optimize WebAuthn authentication flows and challenge lookups


-- completed_authentications (3 indexes)
-- -----------------------------------------------------------------------------

-- Index 1.1: Challenge lookup (authentication verification)
-- Use Case: Verify challenge during cross-device authentication
-- Query: SELECT * FROM completed_authentications WHERE challenge = '...'
CREATE INDEX "idx_completed_authentications_challenge" ON "public"."completed_authentications" USING "btree" ("challenge");

-- Index 1.2: Expiration cleanup (trigger function)
-- Use Case: DELETE FROM completed_authentications WHERE expires_at_timestamp < NOW()
-- Query: Cleanup trigger deletes expired authentications
CREATE INDEX "idx_completed_authentications_expires" ON "public"."completed_authentications" USING "btree" ("expires_at_timestamp");

-- Index 1.3: Session ID lookup (desktop polling)
-- Use Case: Desktop polls for completed authentication via session_id
-- Query: SELECT * FROM completed_authentications WHERE session_id = '...'
CREATE INDEX "idx_completed_authentications_session_id" ON "public"."completed_authentications" USING "btree" ("session_id");


-- passkey_challenges (6 indexes)
-- -----------------------------------------------------------------------------

-- Index 1.4: Challenge lookup (WebAuthn verification)
-- Use Case: Verify challenge during registration/authentication
-- Query: SELECT * FROM passkey_challenges WHERE challenge = '...'
CREATE INDEX "idx_passkey_challenges_challenge" ON "public"."passkey_challenges" USING "btree" ("challenge");

-- Index 1.5: Expiration cleanup (trigger function)
-- Use Case: DELETE FROM passkey_challenges WHERE expires_at < NOW()
-- Query: Cleanup trigger deletes expired challenges
CREATE INDEX "idx_passkey_challenges_expires_at" ON "public"."passkey_challenges" USING "btree" ("expires_at");

-- Index 1.6: Session ID lookup (cross-device flow)
-- Use Case: Mobile retrieves challenge via session_id from QR code
-- Query: SELECT * FROM passkey_challenges WHERE session_id = '...'
-- Partial: Only indexes rows with non-NULL session_id (cross-device flows)
CREATE INDEX "idx_passkey_challenges_session_id" ON "public"."passkey_challenges" USING "btree" ("session_id") WHERE ("session_id" IS NOT NULL);

-- Index 1.7: Status filtering (desktop polling)
-- Use Case: Desktop polls for status changes (pending → completed)
-- Query: SELECT * FROM passkey_challenges WHERE status = 'completed'
CREATE INDEX "idx_passkey_challenges_status" ON "public"."passkey_challenges" USING "btree" ("status");

-- Index 1.8: User email lookup (cross-device registration)
-- Use Case: Mobile looks up user by email during QR code flow
-- Query: SELECT * FROM passkey_challenges WHERE user_email = '...'
-- Partial: Only indexes rows with non-NULL user_email (cross-device flows)
CREATE INDEX "idx_passkey_challenges_user_email" ON "public"."passkey_challenges" USING "btree" ("user_email") WHERE ("user_email" IS NOT NULL);

-- Index 1.9: User ID lookup (user's challenges)
-- Use Case: Get all challenges for specific user
-- Query: SELECT * FROM passkey_challenges WHERE user_id = '...'
CREATE INDEX "idx_passkey_challenges_user_id" ON "public"."passkey_challenges" USING "btree" ("user_id");


-- user_passkeys (2 indexes)
-- -----------------------------------------------------------------------------

-- Index 1.10: Credential ID lookup (authentication)
-- Use Case: Verify passkey during authentication via credential_id
-- Query: SELECT * FROM user_passkeys WHERE credential_id = '...' AND active = true
-- Partial: Only indexes active passkeys (disabled passkeys excluded)
CREATE INDEX "idx_user_passkeys_credential_id" ON "public"."user_passkeys" USING "btree" ("credential_id") WHERE ("active" = true);

-- Index 1.11: User ID lookup (user's passkeys)
-- Use Case: List all active passkeys for user
-- Query: SELECT * FROM user_passkeys WHERE user_id = '...' AND active = true
-- Partial: Only indexes active passkeys (disabled passkeys excluded)
CREATE INDEX "idx_user_passkeys_user_id" ON "public"."user_passkeys" USING "btree" ("user_id") WHERE ("active" = true);



-- ============================================================================
-- SECTION 2: RBAC Tables (8 indexes)
-- ============================================================================
-- Purpose: Optimize role/permission queries and authorization checks


-- permissions (2 indexes)
-- -----------------------------------------------------------------------------

-- Index 2.1: System flag filtering
-- Use Case: List system vs. custom permissions
-- Query: SELECT * FROM permissions WHERE is_system = true
CREATE INDEX "idx_permissions_is_system" ON "public"."permissions" USING "btree" ("is_system");

-- Index 2.2: Permission name lookup
-- Use Case: authorize() function looks up permission by name
-- Query: SELECT * FROM permissions WHERE name = 'users.read'
CREATE INDEX "idx_permissions_name" ON "public"."permissions" USING "btree" ("name");


-- roles (2 indexes)
-- -----------------------------------------------------------------------------

-- Index 2.3: System flag filtering
-- Use Case: List system vs. custom roles
-- Query: SELECT * FROM roles WHERE is_system = true
CREATE INDEX "idx_roles_is_system" ON "public"."roles" USING "btree" ("is_system");

-- Index 2.4: Role name lookup
-- Use Case: Fast role lookup by name (used throughout RBAC functions)
-- Query: SELECT * FROM roles WHERE name = 'admin'
CREATE INDEX "idx_roles_name" ON "public"."roles" USING "btree" ("name");


-- role_permissions (2 indexes)
-- -----------------------------------------------------------------------------

-- Index 2.5: Permission ID lookup (reverse lookup)
-- Use Case: Find all roles with specific permission
-- Query: SELECT * FROM role_permissions WHERE permission_id = '...'
CREATE INDEX "idx_role_permissions_permission_id" ON "public"."role_permissions" USING "btree" ("permission_id");

-- Index 2.6: Role ID lookup (JOIN optimization)
-- Use Case: Get all permissions for role (authorize function)
-- Query: SELECT * FROM role_permissions WHERE role_id = '...'
CREATE INDEX "idx_role_permissions_role_id" ON "public"."role_permissions" USING "btree" ("role_id");


-- user_roles (2 indexes)
-- -----------------------------------------------------------------------------

-- Index 2.7: Role ID lookup (reverse lookup)
-- Use Case: Find all users with specific role
-- Query: SELECT * FROM user_roles WHERE role_id = '...'
CREATE INDEX "idx_user_roles_role_id" ON "public"."user_roles" USING "btree" ("role_id");

-- Index 2.8: User ID lookup (JOIN optimization)
-- Use Case: Get all roles for user (get_user_roles function)
-- Query: SELECT * FROM user_roles WHERE user_id = '...'
CREATE INDEX "idx_user_roles_user_id" ON "public"."user_roles" USING "btree" ("user_id");



-- ============================================================================
-- SECTION 3: Secret Tables (12 indexes)
-- ============================================================================
-- Purpose: Optimize secret retrieval, sharing, and audit logging


-- secrets (3 indexes)
-- -----------------------------------------------------------------------------

-- Index 3.1: Expiration filtering
-- Use Case: Find secrets expiring soon (password rotation reminders)
-- Query: SELECT * FROM secrets WHERE expiration_date < NOW() + INTERVAL '30 days'
-- Partial: Only indexes secrets with non-NULL expiration_date
CREATE INDEX "idx_secrets_expiration" ON "public"."secrets" USING "btree" ("expiration_date") WHERE ("expiration_date" IS NOT NULL);

-- Index 3.2: User ID lookup (JOIN optimization)
-- Use Case: Get all secrets for user (get_user_secrets function)
-- Query: SELECT * FROM secrets WHERE user_id = '...'
CREATE INDEX "idx_secrets_user_id" ON "public"."secrets" USING "btree" ("user_id");

-- Index 3.3: Website search
-- Use Case: Search secrets by website (search_user_secrets function)
-- Query: SELECT * FROM secrets WHERE website ILIKE '%github%'
CREATE INDEX "idx_secrets_website" ON "public"."secrets" USING "btree" ("website");


-- secret_metadata (1 index)
-- -----------------------------------------------------------------------------

-- Index 3.4: Secret ID lookup (JOIN optimization)
-- Use Case: Get 2FA metadata for secret (LEFT JOIN in get_user_secrets)
-- Query: SELECT * FROM secret_metadata WHERE secret_id = '...'
CREATE INDEX "idx_secret_metadata_secret_id" ON "public"."secret_metadata" USING "btree" ("secret_id");


-- secret_tags (2 indexes)
-- -----------------------------------------------------------------------------

-- Index 3.5: Secret ID lookup (JOIN optimization)
-- Use Case: Get all tags for secret (LEFT JOIN in get_user_secrets)
-- Query: SELECT * FROM secret_tags WHERE secret_id = '...'
CREATE INDEX "idx_secret_tags_secret_id" ON "public"."secret_tags" USING "btree" ("secret_id");

-- Index 3.6: Tag filtering
-- Use Case: Find all secrets with specific tag
-- Query: SELECT * FROM secret_tags WHERE tag = 'work'
CREATE INDEX "idx_secret_tags_tag" ON "public"."secret_tags" USING "btree" ("tag");


-- secret_shares (4 indexes)
-- -----------------------------------------------------------------------------

-- Index 3.7: Expiration filtering
-- Use Case: Filter expired shares (RLS policies check expires_at)
-- Query: SELECT * FROM secret_shares WHERE expires_at IS NOT NULL AND expires_at > NOW()
-- Partial: Only indexes shares with non-NULL expires_at (temporary shares)
CREATE INDEX "idx_secret_shares_expires_at" ON "public"."secret_shares" USING "btree" ("expires_at") WHERE ("expires_at" IS NOT NULL);

-- Index 3.8: Role ID lookup (role-based sharing)
-- Use Case: Find secrets shared with specific role
-- Query: SELECT * FROM secret_shares WHERE shared_with_role_id = '...'
-- Partial: Only indexes role-based shares (user-based shares have NULL role_id)
CREATE INDEX "idx_secret_shares_role_id" ON "public"."secret_shares" USING "btree" ("shared_with_role_id") WHERE ("shared_with_role_id" IS NOT NULL);

-- Index 3.9: Secret ID lookup (JOIN optimization)
-- Use Case: Get all shares for secret (get_secret_shares function)
-- Query: SELECT * FROM secret_shares WHERE secret_id = '...'
CREATE INDEX "idx_secret_shares_secret_id" ON "public"."secret_shares" USING "btree" ("secret_id");

-- Index 3.10: User ID lookup (user-based sharing)
-- Use Case: Find secrets shared with specific user
-- Query: SELECT * FROM secret_shares WHERE shared_with_user_id = '...'
-- Partial: Only indexes user-based shares (role-based shares have NULL user_id)
CREATE INDEX "idx_secret_shares_user_id" ON "public"."secret_shares" USING "btree" ("shared_with_user_id") WHERE ("shared_with_user_id" IS NOT NULL);


-- secret_access_log (4 indexes)
-- -----------------------------------------------------------------------------

-- Index 3.11: Operation filtering
-- Use Case: Find all logs for specific operation (view, share, delete)
-- Query: SELECT * FROM secret_access_log WHERE operation = 'view'
CREATE INDEX "idx_secret_access_log_operation" ON "public"."secret_access_log" USING "btree" ("operation");

-- Index 3.12: Secret ID lookup (audit trail)
-- Use Case: Get all access logs for secret
-- Query: SELECT * FROM secret_access_log WHERE secret_id = '...' ORDER BY timestamp DESC
CREATE INDEX "idx_secret_access_log_secret_id" ON "public"."secret_access_log" USING "btree" ("secret_id");

-- Index 3.13: Timestamp sorting (ORDER BY optimization)
-- Use Case: Get recent access logs (ORDER BY timestamp DESC)
-- Query: SELECT * FROM secret_access_log ORDER BY timestamp DESC LIMIT 100
-- DESC: Optimizes descending order queries (most recent first)
CREATE INDEX "idx_secret_access_log_timestamp" ON "public"."secret_access_log" USING "btree" ("timestamp" DESC);

-- Index 3.14: User ID lookup (user activity)
-- Use Case: Get all access logs for user
-- Query: SELECT * FROM secret_access_log WHERE user_id = '...'
CREATE INDEX "idx_secret_access_log_user_id" ON "public"."secret_access_log" USING "btree" ("user_id");



-- ============================================================================
-- SECTION 4: User Table (2 indexes)
-- ============================================================================
-- Purpose: Optimize user lookups and filtering


-- users (2 indexes)
-- -----------------------------------------------------------------------------

-- Index 4.1: Created at sorting
-- Use Case: List users by signup date (ORDER BY created_at)
-- Query: SELECT * FROM users ORDER BY created_at DESC LIMIT 100
CREATE INDEX "idx_users_created_at" ON "public"."users" USING "btree" ("created_at");

-- Index 4.2: Email lookup
-- Use Case: Find user by email (find_user_by_email function)
-- Query: SELECT * FROM users WHERE email = 'user@example.com'
CREATE INDEX "idx_users_email" ON "public"."users" USING "btree" ("email");


-- ============================================================================
-- End of File: indexes.sql
-- ============================================================================
-- Next Migration: 20251023000013_rls_policies.sql (RLS Policies)
-- ============================================================================
