-- ============================================================================
-- BOSS Database Schema: Secret Management Tables
-- ============================================================================
-- File: 20251023000010_secret_tables.sql
-- Description: Encrypted credential storage with 2FA metadata, tagging,
--              sharing (user OR role-based), and audit logging. Passwords
--              encrypted with AES-256 + base64 encoding (NOT PGP).
-- Dependencies:
--   - File 1: extensions_and_types.sql (pgcrypto, uuid-ossp)
--   - File 5: encryption_functions.sql (encrypt_text, decrypt_text)
--   - File 8: rbac_tables.sql (users, roles)
-- Tables: 5 total
-- Encryption Method: AES-256 + base64 encoding
-- Sharing Model: User OR role-based with expiration support
-- ============================================================================


-- ============================================================================
-- Secret Management Architecture Overview
-- ============================================================================
--
-- This schema implements an encrypted credential manager with:
--   1. AES-256 encrypted password storage (base64-encoded)
--   2. 2FA metadata storage (TOTP secrets, recovery codes)
--   3. Tag-based organization (many-to-many via secret_tags)
--   4. Flexible sharing (user-based OR role-based with expiration)
--   5. Comprehensive audit logging (all operations logged)
--
-- Table Relationships:
--   secrets (core) ←→ secret_metadata (1:1, optional)
--   secrets ←→ secret_tags (1:many)
--   secrets ←→ secret_shares (1:many)
--   secrets ←→ secret_access_log (1:many, audit trail)
--
-- Data Flow Examples:
--
--   A. Create Secret with 2FA:
--      1. User enters website, username, password, 2FA codes
--      2. App encrypts password → INSERT INTO secrets (password_encrypted)
--      3. App encrypts recovery codes → INSERT INTO secret_metadata (recovery_codes_encrypted)
--      4. App adds tags → INSERT INTO secret_tags (tag) for each tag
--      5. Log operation → INSERT INTO secret_access_log (operation='create')
--
--   B. Share Secret with User:
--      1. Owner shares secret with colleague@company.com
--      2. App looks up user_id from email
--      3. App → INSERT INTO secret_shares (shared_with_user_id, shared_by, access_level='read')
--      4. Log operation → INSERT INTO secret_access_log (operation='share', access_granted_via='user_share')
--      5. Colleague can now view secret (via get_user_secrets_with_shared function)
--
--   C. Share Secret with Role:
--      1. Owner shares secret with 'developer' role
--      2. App → INSERT INTO secret_shares (shared_with_role_id, shared_by, access_level='read')
--      3. Log operation → INSERT INTO secret_access_log (operation='share', access_granted_via='role_share')
--      4. All users with 'developer' role can now view secret
--
-- Encryption Details:
--   - Passwords: AES-256 + base64 encoding (via encrypt_text function)
--   - Recovery codes: AES-256 + base64 encoding (JSON array)
--   - Master key: Stored in Supabase Vault (vault.decrypted_secrets)
--   - NOT PGP encryption (uses pgcrypto's encrypt/decrypt functions)
--
-- ============================================================================


-- ============================================================================
-- SECTION 1: Core Secret Storage (1 table)
-- ============================================================================
-- Purpose: Store encrypted credentials (website + username + password)


-- Table 1.1: secrets
-- -----------------------------------------------------------------------------
-- Purpose: Core encrypted credential storage
-- Why Needed: Store website credentials securely with encryption
-- Use Case: Password manager, credential vault, secret storage
--
-- Credential Model:
--   - website: Domain or URL (e.g., "github.com", "https://app.example.com")
--   - username: Username or email for the website
--   - password_encrypted: AES-256 encrypted password (base64-encoded)
--   - notes: Optional plaintext notes (NOT encrypted - for non-sensitive info)
--   - expiration_date: Optional password rotation reminder
--
-- Encryption:
--   - Passwords encrypted via encrypt_text() function
--   - Encryption method: AES-256 + base64 encoding
--   - Master key retrieved from Supabase Vault
--   - Decryption via decrypt_text() function
--
-- Columns:
--   - id: Secret UUID (PRIMARY KEY, auto-generated via gen_random_uuid)
--   - user_id: Foreign key to users table (who owns this secret)
--   - website: Website domain or URL (plaintext for search)
--   - username: Username/email for the website (plaintext for display)
--   - password_encrypted: AES-256 encrypted password (base64 text)
--   - notes: Optional plaintext notes (NOT encrypted)
--   - expiration_date: Optional password rotation date (for reminders)
--   - created_at: When secret was created
--   - updated_at: Last modification timestamp (updated on password change)
--
-- Relationships:
--   - ONE secret → ONE secret_metadata (optional, for 2FA data)
--   - ONE secret → MANY secret_tags (tags for organization)
--   - ONE secret → MANY secret_shares (shared with users/roles)
--   - ONE secret → MANY secret_access_log (audit trail)
--
-- Secret Lifecycle:
--   1. Create → INSERT INTO secrets (encrypt password via encrypt_text)
--   2. View → SELECT * FROM secrets, decrypt via decrypt_text
--   3. Update → UPDATE secrets, re-encrypt password via encrypt_text
--   4. Share → INSERT INTO secret_shares (user OR role)
--   5. Delete → DELETE FROM secrets (cascades to metadata, tags, shares, logs)
--
-- Password Encryption Flow:
--   Plaintext → encrypt_text() → AES-256 → base64 encode → password_encrypted
--   Example: "myPassword123" → "a9f2b8c3d4e5..." (base64 ciphertext)
--
-- Password Decryption Flow:
--   password_encrypted → decrypt_text() → base64 decode → AES-256 decrypt → Plaintext
--   Example: "a9f2b8c3d4e5..." → "myPassword123"
--
-- Search Optimization:
--   - website and username stored as plaintext for fast search
--   - Passwords encrypted for security (can't search encrypted fields)
--   - search_user_secrets() function uses ILIKE on website/username
--
-- RLS Policies:
--   - Users can only access their own secrets (user_id = auth.uid())
--   - OR secrets shared with them (via secret_shares table)
--   - OR secrets shared with their roles (via secret_shares + user_roles)
--
-- Usage:
--   -- Create secret (via function)
--   SELECT create_secret(
--     'github.com', 'username@email.com', 'myPassword123',
--     'Personal GitHub account', NULL, ARRAY['work', 'important'],
--     true, 'app', ARRAY['code1', 'code2']
--   );
--
--   -- Get user's secrets (decrypted)
--   SELECT * FROM get_user_secrets(50, 0);
--   -- Returns: id, website, username, password (decrypted), notes, tags, metadata
--
--   -- Search secrets
--   SELECT * FROM search_user_secrets('github', 50, 0);
--   -- Returns: Secrets matching "github" in website or username
CREATE TABLE IF NOT EXISTS "public"."secrets" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "user_id" "uuid" NOT NULL,
    "website" "text" NOT NULL,
    "username" "text" NOT NULL,
    "password_encrypted" "text" NOT NULL,
    "notes" "text",
    "expiration_date" timestamp with time zone,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);

ALTER TABLE "public"."secrets" OWNER TO "postgres";

COMMENT ON TABLE "public"."secrets" IS 'Encrypted credential storage for website:username combinations';

COMMENT ON COLUMN "public"."secrets"."website" IS 'Website domain or URL';

COMMENT ON COLUMN "public"."secrets"."username" IS 'Username/email for the website';

COMMENT ON COLUMN "public"."secrets"."password_encrypted" IS 'Password encrypted with pgcrypto';

COMMENT ON COLUMN "public"."secrets"."notes" IS 'Optional notes about the credential';

COMMENT ON COLUMN "public"."secrets"."expiration_date" IS 'When the password should be rotated';



-- ============================================================================
-- SECTION 2: 2FA Metadata Storage (1 table)
-- ============================================================================
-- Purpose: Store 2FA/MFA metadata for secrets


-- Table 2.1: secret_metadata
-- -----------------------------------------------------------------------------
-- Purpose: Store 2FA/MFA metadata and recovery codes for secrets
-- Why Needed: Many credentials require 2FA - store TOTP secrets and backup codes
-- Relationship: ONE secret → ONE secret_metadata (optional, 1:1)
--
-- 2FA Types Supported:
--   - app: Authenticator app (Google Authenticator, Authy, 1Password)
--   - sms: SMS-based 2FA (phone number receives codes)
--   - email: Email-based 2FA (email receives codes)
--   - hardware: Hardware security key (YubiKey, Titan Key)
--
-- Columns:
--   - id: Metadata UUID (PRIMARY KEY, auto-generated via gen_random_uuid)
--   - secret_id: Foreign key to secrets table (which secret has 2FA)
--   - twofa_enabled: Boolean flag (true if 2FA is configured)
--   - twofa_type: 2FA method ('app', 'sms', 'email', 'hardware')
--   - twofa_secret: Encrypted TOTP secret (for authenticator apps)
--   - recovery_codes_encrypted: Encrypted JSON array of backup codes (AES + base64)
--   - created_at: When 2FA was configured
--   - updated_at: Last modification timestamp
--
-- Recovery Codes Format:
--   - Stored as encrypted JSON array: '["code1", "code2", "code3", ...]'
--   - Encrypted via encrypt_text() function (AES-256 + base64)
--   - Decrypted via safe_decrypt_recovery_codes() function (returns JSONB)
--
-- TOTP Secret Storage:
--   - twofa_secret stores the base32-encoded TOTP secret
--   - NOT encrypted in current implementation (TODO: encrypt this field)
--   - Used to generate 6-digit TOTP codes
--
-- Table Constraints:
--   - valid_twofa_type CHECK: Ensures twofa_type is one of: 'app', 'sms', 'email', 'hardware'
--   - twofa_type can be NULL if twofa_enabled = false
--
-- Metadata Lifecycle:
--   1. User enables 2FA → INSERT INTO secret_metadata (twofa_enabled=true, type)
--   2. User saves recovery codes → UPDATE recovery_codes_encrypted (encrypt via encrypt_text)
--   3. User views secret → Decrypt recovery codes via safe_decrypt_recovery_codes()
--   4. User disables 2FA → UPDATE twofa_enabled=false (or DELETE metadata)
--
-- Encryption Flow (Recovery Codes):
--   JSON array → JSON.stringify() → encrypt_text() → AES-256 → base64 → recovery_codes_encrypted
--   Example: ["A1B2", "C3D4"] → '["A1B2", "C3D4"]' → "x9y8z7..." (encrypted)
--
-- Decryption Flow (Recovery Codes):
--   recovery_codes_encrypted → safe_decrypt_recovery_codes() → base64 decode → AES decrypt → JSON parse → Array
--   Example: "x9y8z7..." → '["A1B2", "C3D4"]' → ["A1B2", "C3D4"]
--
-- Safe Decryption:
--   - safe_decrypt_recovery_codes() handles errors gracefully
--   - Returns [] (empty array) on decryption failure (corrupt data, wrong key)
--   - Prevents query crashes when recovery codes are corrupt
--
-- Usage:
--   -- Enable 2FA for secret (via create_secret or update_secret)
--   SELECT update_secret(
--     '<secret-id>', 'github.com', 'user@email.com', 'password',
--     NULL, NULL, NULL, true, 'app', ARRAY['A1B2', 'C3D4']
--   );
--
--   -- Query secret with 2FA metadata
--   SELECT
--     s.website,
--     s.username,
--     sm.twofa_enabled,
--     sm.twofa_type,
--     safe_decrypt_recovery_codes(sm.recovery_codes_encrypted) AS recovery_codes
--   FROM secrets s
--   LEFT JOIN secret_metadata sm ON sm.secret_id = s.id
--   WHERE s.id = '<secret-id>';
CREATE TABLE IF NOT EXISTS "public"."secret_metadata" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "secret_id" "uuid" NOT NULL,
    "twofa_enabled" boolean DEFAULT false NOT NULL,
    "twofa_type" "text",
    "twofa_secret" "text",
    "recovery_codes_encrypted" "text",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "valid_twofa_type" CHECK ((("twofa_type" IS NULL) OR ("twofa_type" = ANY (ARRAY['app'::"text", 'sms'::"text", 'email'::"text", 'hardware'::"text"]))))
);

ALTER TABLE "public"."secret_metadata" OWNER TO "postgres";

COMMENT ON TABLE "public"."secret_metadata" IS '2FA and recovery code information for secrets';

COMMENT ON COLUMN "public"."secret_metadata"."twofa_enabled" IS 'Whether 2FA is enabled for this credential';

COMMENT ON COLUMN "public"."secret_metadata"."twofa_type" IS 'Type of 2FA: app, sms, email, hardware';

COMMENT ON COLUMN "public"."secret_metadata"."twofa_secret" IS 'Encrypted TOTP secret for authenticator apps';

COMMENT ON COLUMN "public"."secret_metadata"."recovery_codes_encrypted" IS 'Encrypted JSON array of backup codes';



-- ============================================================================
-- SECTION 3: Organization and Categorization (1 table)
-- ============================================================================
-- Purpose: Tag secrets for organization and search


-- Table 3.1: secret_tags
-- -----------------------------------------------------------------------------
-- Purpose: Tag secrets for organization and categorization
-- Why Needed: Users need to organize secrets (work, personal, important, etc.)
-- Relationship: ONE secret → MANY tags (many-to-many via separate rows)
--
-- Tag Examples:
--   - "work" - Work-related credentials
--   - "personal" - Personal accounts
--   - "important" - Critical credentials
--   - "banking" - Financial accounts
--   - "social" - Social media accounts
--   - "development" - Development/staging credentials
--
-- Columns:
--   - id: Tag UUID (PRIMARY KEY, auto-generated via gen_random_uuid)
--   - secret_id: Foreign key to secrets table (which secret is tagged)
--   - tag: Tag name (e.g., "work", "personal", "important")
--   - created_at: When tag was added
--
-- Tag Model:
--   - Many-to-many relationship via separate rows (NOT array column)
--   - Each tag is a separate row in secret_tags table
--   - Same tag can be used across multiple secrets
--   - Allows efficient querying: "Find all secrets tagged 'work'"
--
-- Tag Lifecycle:
--   1. Create secret with tags → INSERT multiple rows in secret_tags
--   2. Add tag → INSERT INTO secret_tags (secret_id, tag)
--   3. Remove tag → DELETE FROM secret_tags WHERE secret_id = '...' AND tag = '...'
--   4. Delete secret → CASCADE DELETE all secret_tags rows
--
-- Query Patterns:
--   -- Get all tags for a secret (aggregated into JSON array)
--   SELECT
--     s.id,
--     s.website,
--     jsonb_agg(st.tag) AS tags
--   FROM secrets s
--   LEFT JOIN secret_tags st ON st.secret_id = s.id
--   WHERE s.id = '<secret-id>'
--   GROUP BY s.id;
--   -- Returns: {"id": "...", "website": "github.com", "tags": ["work", "important"]}
--
--   -- Find secrets with specific tag
--   SELECT DISTINCT s.*
--   FROM secrets s
--   JOIN secret_tags st ON st.secret_id = s.id
--   WHERE st.tag = 'work' AND s.user_id = auth.uid();
--
-- Tag Aggregation (in secret functions):
--   - get_user_secrets() aggregates tags via:
--     SELECT jsonb_agg(st.tag) FROM secret_tags st WHERE st.secret_id = s.id
--   - Returns empty [] if no tags (via COALESCE)
--
-- Usage:
--   -- Create secret with tags (via function)
--   SELECT create_secret(
--     'github.com', 'user@email.com', 'password',
--     NULL, NULL, ARRAY['work', 'development'],  -- Tags array
--     false, NULL, NULL
--   );
--   -- Creates 2 rows in secret_tags: ('work'), ('development')
--
--   -- Add tag to existing secret
--   INSERT INTO secret_tags (secret_id, tag)
--   VALUES ('<secret-id>', 'important');
--
--   -- Remove tag
--   DELETE FROM secret_tags
--   WHERE secret_id = '<secret-id>' AND tag = 'development';
CREATE TABLE IF NOT EXISTS "public"."secret_tags" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "secret_id" "uuid" NOT NULL,
    "tag" "text" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL
);

ALTER TABLE "public"."secret_tags" OWNER TO "postgres";

COMMENT ON TABLE "public"."secret_tags" IS 'Tags/categories for organizing secrets';

COMMENT ON COLUMN "public"."secret_tags"."tag" IS 'Tag name (e.g., "work", "personal", "important")';



-- ============================================================================
-- SECTION 4: Secret Sharing (1 table)
-- ============================================================================
-- Purpose: Share secrets with users OR roles with optional expiration


-- Table 4.1: secret_shares
-- -----------------------------------------------------------------------------
-- Purpose: Share secrets with specific users OR roles (not both)
-- Why Needed: Team collaboration, credential sharing, role-based access
-- Sharing Model: User-based OR role-based (mutually exclusive via CHECK constraint)
--
-- Sharing Types:
--   A. User-Based Sharing:
--      - Owner shares secret with specific user (via email lookup)
--      - shared_with_user_id = UUID of recipient
--      - shared_with_role_id = NULL
--
--   B. Role-Based Sharing:
--      - Owner shares secret with all users in a role
--      - shared_with_role_id = UUID of role (e.g., 'developer', 'admin')
--      - shared_with_user_id = NULL
--      - All users with that role gain access (dynamic membership)
--
-- Columns:
--   - id: Share UUID (PRIMARY KEY, auto-generated via gen_random_uuid)
--   - secret_id: Foreign key to secrets table (which secret is shared)
--   - shared_with_user_id: User who gets access (NULL if role-based)
--   - shared_with_role_id: Role that gets access (NULL if user-based)
--   - shared_by: UUID of user who granted access (audit trail)
--   - access_level: Access level (default 'read', future: 'write', 'admin')
--   - created_at: When share was created
--   - expires_at: Optional expiration date (NULL = no expiration)
--   - notes: Optional reason for sharing (e.g., "For Q3 project")
--
-- Table Constraints:
--   - share_target_check: Ensures exactly ONE of (user_id, role_id) is set
--     CHECK: (user_id IS NOT NULL AND role_id IS NULL) OR (user_id IS NULL AND role_id IS NOT NULL)
--     This prevents: Both NULL, or both set
--
-- Access Levels:
--   - 'read': View-only access (current implementation)
--   - 'write': Can edit secret (future feature)
--   - 'admin': Can edit + reshare (future feature)
--
-- Expiration Logic:
--   - expires_at = NULL → permanent access (never expires)
--   - expires_at = timestamp → temporary access (expires at specified time)
--   - RLS policies check: expires_at IS NULL OR expires_at > NOW()
--
-- Sharing Lifecycle:
--   1. Share with user → INSERT INTO secret_shares (shared_with_user_id, access_level='read')
--   2. Share with role → INSERT INTO secret_shares (shared_with_role_id, access_level='read')
--   3. Set expiration → UPDATE expires_at = NOW() + INTERVAL '30 days'
--   4. Revoke access → DELETE FROM secret_shares WHERE id = '<share-id>'
--   5. Auto-expire → RLS policies filter expired shares (no active cleanup needed)
--
-- Role-Based Sharing Example:
--   -- Share secret with all developers
--   INSERT INTO secret_shares (secret_id, shared_with_role_id, shared_by, access_level)
--   VALUES (
--     '<secret-id>',
--     (SELECT id FROM roles WHERE name = 'developer'),
--     auth.uid(),
--     'read'
--   );
--   -- Now all users with 'developer' role can view the secret
--
-- User-Based Sharing Example:
--   -- Share secret with specific user
--   INSERT INTO secret_shares (secret_id, shared_with_user_id, shared_by, access_level)
--   VALUES (
--     '<secret-id>',
--     (SELECT id FROM users WHERE email = 'colleague@company.com'),
--     auth.uid(),
--     'read'
--   );
--   -- Now colleague@company.com can view the secret
--
-- Query Patterns:
--   -- Get all shares for a secret
--   SELECT * FROM get_secret_shares('<secret-id>');
--   -- Returns: share_id, shared_with_user_email, shared_with_role_name, access_level, etc.
--
--   -- Get secrets shared with current user (via function)
--   SELECT * FROM get_user_secrets_with_shared(50, 0);
--   -- Returns: User's own secrets + secrets shared with user + secrets shared with user's roles
--
-- Usage:
--   -- Share with user (via function)
--   SELECT share_secret(
--     '<secret-id>',
--     '<user-id>',
--     NULL,  -- role_id = NULL (user-based sharing)
--     'Shared for project collaboration',
--     NOW() + INTERVAL '30 days'  -- Expires in 30 days
--   );
--
--   -- Share with role (via function)
--   SELECT share_secret(
--     '<secret-id>',
--     NULL,  -- user_id = NULL (role-based sharing)
--     '<role-id>',
--     'All developers need access',
--     NULL  -- No expiration
--   );
--
--   -- Revoke access (via function)
--   SELECT unshare_secret('<secret-id>', '<user-id>', NULL);
CREATE TABLE IF NOT EXISTS "public"."secret_shares" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "secret_id" "uuid" NOT NULL,
    "shared_with_user_id" "uuid",
    "shared_with_role_id" "uuid",
    "shared_by" "uuid" NOT NULL,
    "access_level" "text" DEFAULT 'read'::"text" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "expires_at" timestamp with time zone,
    "notes" "text",
    CONSTRAINT "share_target_check" CHECK (((("shared_with_user_id" IS NOT NULL) AND ("shared_with_role_id" IS NULL)) OR (("shared_with_user_id" IS NULL) AND ("shared_with_role_id" IS NOT NULL))))
);

ALTER TABLE "public"."secret_shares" OWNER TO "postgres";

COMMENT ON TABLE "public"."secret_shares" IS 'Secret sharing: assigns view access to users or roles';

COMMENT ON COLUMN "public"."secret_shares"."secret_id" IS 'The secret being shared';

COMMENT ON COLUMN "public"."secret_shares"."shared_with_user_id" IS 'User who gets access (NULL if role-based)';

COMMENT ON COLUMN "public"."secret_shares"."shared_with_role_id" IS 'Role that gets access (NULL if user-based)';

COMMENT ON COLUMN "public"."secret_shares"."shared_by" IS 'User who granted the access';

COMMENT ON COLUMN "public"."secret_shares"."access_level" IS 'Access level: read (view-only), future: write, admin';

COMMENT ON COLUMN "public"."secret_shares"."expires_at" IS 'Optional expiration date for temporary access';

COMMENT ON COLUMN "public"."secret_shares"."notes" IS 'Why this secret was shared';



-- ============================================================================
-- SECTION 5: Audit Logging (1 table)
-- ============================================================================
-- Purpose: Comprehensive audit trail for all secret operations


-- Table 5.1: secret_access_log
-- -----------------------------------------------------------------------------
-- Purpose: Audit log for all secret access and sharing operations
-- Why Needed: Security compliance, forensics, access tracking
-- Logged Operations: view, create, update, delete, share, unshare
--
-- Operation Types:
--   - view: User viewed secret (copied password)
--   - create: User created new secret
--   - update: User modified secret (changed password, notes, etc.)
--   - delete: User deleted secret
--   - share: User shared secret with another user/role
--   - unshare: User revoked access to secret
--
-- Access Grant Methods (access_granted_via):
--   - owner: User is the owner of the secret
--   - user_share: Access via direct user sharing
--   - role_share: Access via role membership
--   - admin_override: Admin accessed secret (not owner or shared)
--
-- Columns:
--   - id: Log entry UUID (PRIMARY KEY, auto-generated via gen_random_uuid)
--   - secret_id: Foreign key to secrets table (which secret was accessed)
--   - user_id: Foreign key to users table (who performed the operation)
--   - operation: Operation type ('view', 'create', 'update', 'delete', 'share', 'unshare')
--   - access_granted_via: How access was granted ('owner', 'user_share', 'role_share', 'admin_override')
--   - role_name: If accessed via role, which role provided access
--   - ip_address: IP address of client (for forensics)
--   - user_agent: Browser/device user agent string
--   - timestamp: When operation occurred (defaults to NOW())
--   - metadata: Additional context as JSONB (flexible for future fields)
--
-- Audit Lifecycle:
--   1. User views secret → INSERT INTO secret_access_log (operation='view', access_granted_via='owner')
--   2. User shares secret → INSERT INTO secret_access_log (operation='share', metadata={shared_with: 'user@email.com'})
--   3. User deletes secret → INSERT INTO secret_access_log (operation='delete')
--   4. Admin reviews logs → SELECT * FROM secret_access_log WHERE secret_id = '...' ORDER BY timestamp DESC
--
-- Metadata Examples (JSONB):
--   - Share operation: {"shared_with": "user@email.com", "access_level": "read", "expires_at": "2024-12-31"}
--   - Update operation: {"fields_changed": ["password", "notes"], "reason": "Password rotation"}
--   - View operation: {"source": "mobile_app", "copied_to_clipboard": true}
--
-- Query Patterns:
--   -- Get audit trail for specific secret
--   SELECT
--     timestamp,
--     operation,
--     u.email AS performed_by,
--     access_granted_via,
--     ip_address
--   FROM secret_access_log sal
--   JOIN users u ON u.id = sal.user_id
--   WHERE sal.secret_id = '<secret-id>'
--   ORDER BY timestamp DESC;
--
--   -- Find all secrets accessed by specific user
--   SELECT DISTINCT secret_id
--   FROM secret_access_log
--   WHERE user_id = '<user-id>' AND operation = 'view'
--   ORDER BY timestamp DESC;
--
--   -- Detect suspicious access patterns
--   SELECT
--     user_id,
--     COUNT(*) AS access_count,
--     COUNT(DISTINCT ip_address) AS unique_ips
--   FROM secret_access_log
--   WHERE timestamp > NOW() - INTERVAL '1 hour'
--   GROUP BY user_id
--   HAVING COUNT(*) > 100;  -- Flag users with >100 accesses in 1 hour
--
-- Retention Policy:
--   - Logs retained indefinitely (no automatic cleanup)
--   - For GDPR compliance, implement manual cleanup for deleted users
--   - Consider partitioning table by timestamp for large datasets
--
-- Usage:
--   -- Log secret view (manual logging in client)
--   INSERT INTO secret_access_log (
--     secret_id, user_id, operation, access_granted_via,
--     ip_address, user_agent
--   ) VALUES (
--     '<secret-id>', auth.uid(), 'view', 'owner',
--     inet_client_addr(), 'Mozilla/5.0...'
--   );
--
--   -- Log share operation (via share_secret function)
--   INSERT INTO secret_access_log (
--     secret_id, user_id, operation, access_granted_via,
--     role_name, metadata
--   ) VALUES (
--     '<secret-id>', auth.uid(), 'share', 'role_share',
--     'developer', '{"shared_with_role": "developer", "expires_at": "2024-12-31"}'::jsonb
--   );
CREATE TABLE IF NOT EXISTS "public"."secret_access_log" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "secret_id" "uuid" NOT NULL,
    "user_id" "uuid" NOT NULL,
    "operation" "text" NOT NULL,
    "access_granted_via" "text",
    "role_name" "text",
    "ip_address" "inet",
    "user_agent" "text",
    "timestamp" timestamp with time zone DEFAULT "now"() NOT NULL,
    "metadata" "jsonb"
);

ALTER TABLE "public"."secret_access_log" OWNER TO "postgres";

COMMENT ON TABLE "public"."secret_access_log" IS 'Audit log for all secret access and sharing operations';

COMMENT ON COLUMN "public"."secret_access_log"."operation" IS 'Operation type: view, share, unshare, delete, create, update';

COMMENT ON COLUMN "public"."secret_access_log"."access_granted_via" IS 'How access was granted: owner, user_share, role_share, admin_override';

COMMENT ON COLUMN "public"."secret_access_log"."role_name" IS 'If accessed via role, which role provided access';

COMMENT ON COLUMN "public"."secret_access_log"."metadata" IS 'Additional context (JSON)';


-- ============================================================================
-- End of File: secret_tables.sql
-- ============================================================================
-- Next Migration: 20251023000011_triggers_and_constraints.sql (Triggers & Constraints)
-- ============================================================================
