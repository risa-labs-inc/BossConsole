-- ============================================================================
-- BOSS Database Schema: Secrets Management Functions
-- ============================================================================
-- File: 20251023000004_secret_functions.sql
-- Description: Encrypted credential storage, retrieval, sharing, and search.
--              All passwords encrypted with AES+base64 (NOT PGP).
--              Tags stored in separate secret_tags table (many-to-many).
--              Sharing supports user OR role-based access with expiration.
-- Dependencies:
--   - File 1: extensions_and_types.sql (Extensions)
--   - File 5: encryption_functions.sql (encrypt_text, decrypt_text) - created after
--   - Note: Tables will be created in File 10 (secret_tables.sql)
-- Functions: 9 total
-- Encryption: AES-256 + base64 encoding via encrypt_text()
-- Database Tables Used:
--   - secrets: Main credential storage
--   - secret_metadata: 2FA configuration (optional, one-to-one)
--   - secret_tags: Tags for organization (many-to-many)
--   - secret_shares: Sharing relationships (user OR role)
--   - secret_access_log: Audit trail
-- ============================================================================


-- ============================================================================
-- SECTION 1: CRUD Operations (3 functions)
-- ============================================================================
-- Purpose: Create, update, and delete encrypted secrets with optional 2FA


-- Function 1.1: create_secret
-- -----------------------------------------------------------------------------
-- Purpose: Create a new encrypted secret with optional tags and 2FA metadata
-- Parameters:
--   - p_website: Website URL or name (e.g., 'github.com', 'AWS Console')
--   - p_username: Username or email for login
--   - p_password: Plaintext password (will be encrypted)
--   - p_notes: Optional notes (NOT encrypted, for non-sensitive info)
--   - p_expiration_date: Optional password expiration reminder
--   - p_tags: Optional array of tags ['work', 'personal', 'banking']
--   - p_twofa_enabled: Whether secret has 2FA enabled
--   - p_twofa_type: Type of 2FA ('app' | 'sms' | 'email' | 'hardware')
--   - p_recovery_codes: Optional array of 2FA recovery codes
--
-- Returns: JSONB with structure:
--   Success: {"success": true, "secret_id": "uuid", "message": "..."}
--   Error: {"success": false, "error": "error message"}
--
-- Encryption Flow:
--   1. Password: encrypt_text(plaintext) → AES encrypt → base64 encode
--   2. Recovery codes: JSON.stringify(array) → encrypt_text() → stored
--   3. Notes: NOT encrypted (use for non-sensitive reminders)
--
-- Database Operations:
--   1. INSERT into secrets table (main record)
--   2. If 2FA enabled: INSERT into secret_metadata
--   3. If tags provided: INSERT into secret_tags (one row per tag)
--
-- Unique Constraint: (user_id, website, username)
--   - One credential per website+username combination per user
--   - Duplicate attempt returns unique_violation error
--
-- Security: SECURITY DEFINER with search_path set for safe execution
-- Usage:
--   SELECT create_secret(
--     'github.com',
--     'user@example.com',
--     'plaintextPassword123',
--     'Personal account',
--     NULL,  -- no expiration
--     ARRAY['work', 'development'],
--     true,  -- 2FA enabled
--     'app',  -- authenticator app
--     ARRAY['code1', 'code2', 'code3']  -- recovery codes
--   );
CREATE OR REPLACE FUNCTION "public"."create_secret"("p_website" "text", "p_username" "text", "p_password" "text", "p_notes" "text" DEFAULT NULL::"text", "p_expiration_date" timestamp with time zone DEFAULT NULL::timestamp with time zone, "p_tags" "text"[] DEFAULT NULL::"text"[], "p_twofa_enabled" boolean DEFAULT false, "p_twofa_type" "text" DEFAULT NULL::"text", "p_recovery_codes" "text"[] DEFAULT NULL::"text"[]) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public, pg_catalog, auth'
    AS $$
DECLARE
    v_secret_id UUID;
    v_encrypted_password TEXT;
    v_encrypted_codes TEXT;
BEGIN
    -- Encrypt password using AES + base64
    v_encrypted_password := public.encrypt_text(p_password);

    -- Insert main secret record
    INSERT INTO public.secrets (
        user_id, website, username, password_encrypted, notes, expiration_date
    )
    VALUES (
        auth.uid(), p_website, p_username, v_encrypted_password, p_notes, p_expiration_date
    )
    RETURNING id INTO v_secret_id;

    -- Handle 2FA metadata (optional)
    IF p_twofa_enabled THEN
        -- Encrypt recovery codes if provided
        IF p_recovery_codes IS NOT NULL AND array_length(p_recovery_codes, 1) > 0 THEN
            -- Convert array to JSON string, then encrypt
            v_encrypted_codes := public.encrypt_text(array_to_json(p_recovery_codes)::text);
        END IF;

        -- Insert 2FA metadata (one-to-one with secret)
        INSERT INTO public.secret_metadata (secret_id, twofa_enabled, twofa_type, recovery_codes_encrypted)
        VALUES (v_secret_id, p_twofa_enabled, p_twofa_type, v_encrypted_codes);
    END IF;

    -- Handle tags (many-to-many)
    IF p_tags IS NOT NULL AND array_length(p_tags, 1) > 0 THEN
        -- Insert one row per tag using unnest()
        INSERT INTO public.secret_tags (secret_id, tag)
        SELECT v_secret_id, unnest(p_tags);
    END IF;

    RETURN jsonb_build_object('success', true, 'secret_id', v_secret_id, 'message', 'Secret created successfully');
EXCEPTION
    WHEN unique_violation THEN
        RETURN jsonb_build_object('success', false, 'error', 'A secret for this website and username already exists');
    WHEN OTHERS THEN
        RETURN jsonb_build_object('success', false, 'error', SQLERRM);
END;
$$;

ALTER FUNCTION "public"."create_secret"("p_website" "text", "p_username" "text", "p_password" "text", "p_notes" "text", "p_expiration_date" timestamp with time zone, "p_tags" "text"[], "p_twofa_enabled" boolean, "p_twofa_type" "text", "p_recovery_codes" "text"[]) OWNER TO "postgres";



-- Function 1.2: update_secret
-- -----------------------------------------------------------------------------
-- Purpose: Update an existing secret (owner only)
-- Parameters: Same as create_secret, plus p_secret_id
-- Security: Only owner can update (WHERE user_id = auth.uid())
-- Behavior:
--   - Replaces ALL fields (not a patch operation)
--   - If 2FA disabled: Deletes from secret_metadata
--   - If 2FA enabled: UPSERT into secret_metadata
--   - Tags: Deletes all existing, inserts new set
-- Usage: SELECT update_secret('secret-uuid', 'github.com', ...);
CREATE OR REPLACE FUNCTION "public"."update_secret"("p_secret_id" "uuid", "p_website" "text", "p_username" "text", "p_password" "text", "p_notes" "text" DEFAULT NULL::"text", "p_expiration_date" timestamp with time zone DEFAULT NULL::timestamp with time zone, "p_tags" "text"[] DEFAULT NULL::"text"[], "p_twofa_enabled" boolean DEFAULT false, "p_twofa_type" "text" DEFAULT NULL::"text", "p_recovery_codes" "text"[] DEFAULT NULL::"text"[]) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public, pg_catalog, auth'
    AS $$
DECLARE
    v_encrypted_password TEXT;
    v_encrypted_codes TEXT;
BEGIN
    -- Check ownership before proceeding
    IF NOT EXISTS (SELECT 1 FROM public.secrets WHERE id = p_secret_id AND user_id = auth.uid()) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Secret not found or access denied');
    END IF;

    -- Encrypt new password
    v_encrypted_password := public.encrypt_text(p_password);

    -- Update main secret record
    UPDATE public.secrets
    SET website = p_website, username = p_username, password_encrypted = v_encrypted_password,
        notes = p_notes, expiration_date = p_expiration_date, updated_at = NOW()
    WHERE id = p_secret_id;

    -- Handle 2FA metadata update
    IF p_twofa_enabled THEN
        -- Encrypt new recovery codes if provided
        IF p_recovery_codes IS NOT NULL AND array_length(p_recovery_codes, 1) > 0 THEN
            v_encrypted_codes := public.encrypt_text(array_to_json(p_recovery_codes)::text);
        END IF;

        -- UPSERT 2FA metadata (insert or update)
        INSERT INTO public.secret_metadata (secret_id, twofa_enabled, twofa_type, recovery_codes_encrypted)
        VALUES (p_secret_id, p_twofa_enabled, p_twofa_type, v_encrypted_codes)
        ON CONFLICT (secret_id) DO UPDATE
        SET twofa_enabled = p_twofa_enabled, twofa_type = p_twofa_type,
            recovery_codes_encrypted = v_encrypted_codes, updated_at = NOW();
    ELSE
        -- 2FA disabled: delete metadata if exists
        DELETE FROM public.secret_metadata WHERE secret_id = p_secret_id;
    END IF;

    -- Replace tags (delete all, insert new)
    DELETE FROM public.secret_tags WHERE secret_id = p_secret_id;
    IF p_tags IS NOT NULL AND array_length(p_tags, 1) > 0 THEN
        INSERT INTO public.secret_tags (secret_id, tag)
        SELECT p_secret_id, unnest(p_tags);
    END IF;

    RETURN jsonb_build_object('success', true, 'message', 'Secret updated successfully');
EXCEPTION
    WHEN unique_violation THEN
        RETURN jsonb_build_object('success', false, 'error', 'A secret for this website and username already exists');
    WHEN OTHERS THEN
        RETURN jsonb_build_object('success', false, 'error', SQLERRM);
END;
$$;

ALTER FUNCTION "public"."update_secret"("p_secret_id" "uuid", "p_website" "text", "p_username" "text", "p_password" "text", "p_notes" "text", "p_expiration_date" timestamp with time zone, "p_tags" "text"[], "p_twofa_enabled" boolean, "p_twofa_type" "text", "p_recovery_codes" "text"[]) OWNER TO "postgres";



-- Function 1.3: delete_secret
-- -----------------------------------------------------------------------------
-- Purpose: Delete a secret (owner only)
-- Parameters:
--   - p_secret_id: UUID of secret to delete
-- Security: Only owner can delete (WHERE user_id = auth.uid())
-- Cascade: Automatically deletes:
--   - secret_metadata (one-to-one FK)
--   - secret_tags (many-to-many FK)
--   - secret_shares (sharing records)
--   - secret_access_log entries (audit trail)
-- Returns: JSONB with success/error message
-- Usage: SELECT delete_secret('secret-uuid');
CREATE OR REPLACE FUNCTION "public"."delete_secret"("p_secret_id" "uuid") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public, pg_catalog, auth'
    AS $$
BEGIN
    -- Delete secret (cascades to related tables via FK constraints)
    DELETE FROM public.secrets WHERE id = p_secret_id AND user_id = auth.uid();

    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Secret not found or access denied');
    END IF;

    RETURN jsonb_build_object('success', true, 'message', 'Secret deleted successfully');
EXCEPTION
    WHEN OTHERS THEN
        RETURN jsonb_build_object('success', false, 'error', SQLERRM);
END;
$$;

ALTER FUNCTION "public"."delete_secret"("p_secret_id" "uuid") OWNER TO "postgres";



-- ============================================================================
-- SECTION 2: Retrieval Functions (3 functions)
-- ============================================================================
-- Purpose: Query secrets with decryption, aggregation, and pagination


-- Function 2.1: get_user_secrets
-- -----------------------------------------------------------------------------
-- Purpose: Get user's owned secrets with decryption and aggregation
-- Parameters:
--   - p_limit: Max secrets to return (default: 50)
--   - p_offset: Offset for pagination (default: 0)
-- Returns: TABLE with decrypted secrets
-- Decryption: decrypt_text(password_encrypted) → plaintext password
-- Aggregation:
--   - Tags: JSONB array from secret_tags table
--   - Metadata: JSONB object from secret_metadata table
-- Ordering: Created date DESC (newest first)
-- Security: Only returns secrets WHERE user_id = auth.uid()
-- Usage: SELECT * FROM get_user_secrets(50, 0);
CREATE OR REPLACE FUNCTION "public"."get_user_secrets"("p_limit" integer DEFAULT 50, "p_offset" integer DEFAULT 0) RETURNS TABLE("id" "uuid", "website" "text", "username" "text", "password" "text", "notes" "text", "expiration_date" timestamp with time zone, "tags" "jsonb", "metadata" "jsonb", "created_at" timestamp with time zone, "updated_at" timestamp with time zone)
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public, pg_catalog, auth'
    AS $$
BEGIN
    RETURN QUERY
    SELECT
        s.id, s.website, s.username,
        -- Decrypt password using AES + base64
        public.decrypt_text(s.password_encrypted) AS password,
        s.notes, s.expiration_date,
        -- Aggregate tags from secret_tags table
        COALESCE((SELECT jsonb_agg(st.tag) FROM public.secret_tags st WHERE st.secret_id = s.id), '[]'::jsonb) AS tags,
        -- Aggregate 2FA metadata from secret_metadata table
        COALESCE((
            SELECT jsonb_build_object(
                'twofa_enabled', sm.twofa_enabled,
                'twofa_type', sm.twofa_type,
                'recovery_codes', CASE WHEN sm.recovery_codes_encrypted IS NOT NULL
                    THEN public.decrypt_text(sm.recovery_codes_encrypted)::jsonb ELSE '[]'::jsonb END
            )
            FROM public.secret_metadata sm WHERE sm.secret_id = s.id
        ), '{}'::jsonb) AS metadata,
        s.created_at, s.updated_at
    FROM public.secrets s
    WHERE s.user_id = auth.uid()  -- Only user's own secrets
    ORDER BY s.created_at DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$;

ALTER FUNCTION "public"."get_user_secrets"("p_limit" integer, "p_offset" integer) OWNER TO "postgres";



-- Function 2.2: get_user_secrets_with_shared
-- -----------------------------------------------------------------------------
-- Purpose: Get user's owned secrets + secrets shared with them
-- Parameters: Same as get_user_secrets
-- Returns: TABLE with additional columns:
--   - is_owner: Boolean (true if owner, false if shared)
--   - shared_by_email: Email of sharer (NULL if owner)
--   - access_level: 'owner' | 'read' | 'write'
--
-- Logic:
--   1. UNION three sources of secrets:
--      a) User's own secrets (is_owner = true)
--      b) Secrets shared directly with user
--      c) Secrets shared via role membership
--   2. Remove duplicates with DISTINCT ON (id)
--   3. Prioritize ownership (ORDER BY is_owner DESC)
--
-- Deduplication Example:
--   If user shares their own secret with themselves:
--   - Row 1: id=123, is_owner=TRUE, access_level='owner'
--   - Row 2: id=123, is_owner=FALSE, access_level='read'
--   - Result: Keep only Row 1 (ownership takes precedence)
--
-- Expiration Check: Filters out expired shares (expires_at < NOW())
-- Performance: Uses indexes on secret_shares.shared_with_user_id and role_id
-- Usage: SELECT * FROM get_user_secrets_with_shared(50, 0);
CREATE OR REPLACE FUNCTION "public"."get_user_secrets_with_shared"("p_limit" integer DEFAULT 50, "p_offset" integer DEFAULT 0) RETURNS TABLE("id" "uuid", "website" "text", "username" "text", "password" "text", "notes" "text", "expiration_date" timestamp with time zone, "tags" "jsonb", "metadata" "jsonb", "created_at" timestamp with time zone, "updated_at" timestamp with time zone, "is_owner" boolean, "shared_by_email" "text", "access_level" "text")
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public, pg_catalog, auth'
    AS $$
BEGIN
    RETURN QUERY
    WITH accessible_secrets AS (
        -- Source 1: User's own secrets
        SELECT
            s.id,
            s.user_id,
            TRUE as is_owner,
            NULL::TEXT as shared_by_email,
            'owner'::TEXT as access_level
        FROM public.secrets s
        WHERE s.user_id = auth.uid()

        UNION

        -- Source 2: Secrets shared directly with user
        SELECT
            s.id,
            s.user_id,
            FALSE as is_owner,
            u.email as shared_by_email,
            ss.access_level
        FROM public.secrets s
        JOIN public.secret_shares ss ON ss.secret_id = s.id
        JOIN auth.users u ON u.id = ss.shared_by
        WHERE ss.shared_with_user_id = auth.uid()
          AND (ss.expires_at IS NULL OR ss.expires_at > NOW())  -- Check expiration

        UNION

        -- Source 3: Secrets shared via role membership
        SELECT
            s.id,
            s.user_id,
            FALSE as is_owner,
            u.email as shared_by_email,
            ss.access_level
        FROM public.secrets s
        JOIN public.secret_shares ss ON ss.secret_id = s.id
        JOIN auth.users u ON u.id = ss.shared_by
        WHERE ss.shared_with_role_id IN (
            SELECT role_id FROM public.user_roles WHERE user_id = auth.uid()
        )
        AND (ss.expires_at IS NULL OR ss.expires_at > NOW())  -- Check expiration
    ),
    unique_secrets AS (
        -- Remove duplicates: if same secret appears as both owner and shared,
        -- keep only the owner entry (ownership takes precedence)
        SELECT DISTINCT ON (a.id)
            a.id,
            a.user_id,
            a.is_owner,
            a.shared_by_email,
            a.access_level
        FROM accessible_secrets a
        ORDER BY a.id, a.is_owner DESC  -- Owner (TRUE) sorts before shared (FALSE)
    )
    SELECT
        s.id,
        s.website,
        s.username,
        -- Decrypt password
        public.decrypt_text(s.password_encrypted) AS password,
        s.notes,
        s.expiration_date,
        -- Aggregate tags
        COALESCE(
            (SELECT jsonb_agg(st.tag) FROM public.secret_tags st WHERE st.secret_id = s.id),
            '[]'::jsonb
        ) AS tags,
        -- Aggregate 2FA metadata with safe decryption
        COALESCE(
            (
                SELECT jsonb_build_object(
                    'twofa_enabled', sm.twofa_enabled,
                    'twofa_type', sm.twofa_type,
                    'twofa_secret', sm.twofa_secret,
                    'recovery_codes', CASE
                        WHEN sm.recovery_codes_encrypted IS NOT NULL
                        THEN public.decrypt_text(sm.recovery_codes_encrypted)::jsonb
                        ELSE '[]'::jsonb
                    END
                )
                FROM public.secret_metadata sm WHERE sm.secret_id = s.id
            ),
            '{}'::jsonb
        ) AS metadata,
        s.created_at,
        s.updated_at,
        u.is_owner,
        u.shared_by_email,
        u.access_level
    FROM unique_secrets u
    JOIN public.secrets s ON s.id = u.id
    ORDER BY s.created_at DESC
    LIMIT p_limit
    OFFSET p_offset;
END;
$$;

ALTER FUNCTION "public"."get_user_secrets_with_shared"("p_limit" integer, "p_offset" integer) OWNER TO "postgres";

COMMENT ON FUNCTION "public"."get_user_secrets_with_shared"("p_limit" integer, "p_offset" integer) IS 'Returns user''s own secrets + secrets shared with them - No duplicates (ownership takes precedence)';



-- Function 2.3: search_user_secrets
-- -----------------------------------------------------------------------------
-- Purpose: Search user's secrets by website or username
-- Parameters:
--   - p_query: Search term (case-insensitive)
--   - p_limit: Max results (default: 50)
--   - p_offset: Offset for pagination (default: 0)
-- Search: ILIKE '%query%' on website and username columns
-- Security: Only searches user's own secrets (WHERE user_id = auth.uid())
-- Performance: Uses index on (website, username) for speed
-- Usage: SELECT * FROM search_user_secrets('github', 50, 0);
CREATE OR REPLACE FUNCTION "public"."search_user_secrets"("p_query" "text", "p_limit" integer DEFAULT 50, "p_offset" integer DEFAULT 0) RETURNS TABLE("id" "uuid", "website" "text", "username" "text", "password" "text", "notes" "text", "expiration_date" timestamp with time zone, "tags" "jsonb", "metadata" "jsonb", "created_at" timestamp with time zone, "updated_at" timestamp with time zone)
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public, pg_catalog, auth'
    AS $$
BEGIN
    RETURN QUERY
    SELECT
        s.id, s.website, s.username,
        public.decrypt_text(s.password_encrypted) AS password,
        s.notes, s.expiration_date,
        COALESCE((SELECT jsonb_agg(st.tag) FROM public.secret_tags st WHERE st.secret_id = s.id), '[]'::jsonb) AS tags,
        COALESCE((
            SELECT jsonb_build_object(
                'twofa_enabled', sm.twofa_enabled,
                'twofa_type', sm.twofa_type,
                'recovery_codes', CASE WHEN sm.recovery_codes_encrypted IS NOT NULL
                    THEN public.decrypt_text(sm.recovery_codes_encrypted)::jsonb ELSE '[]'::jsonb END
            )
            FROM public.secret_metadata sm WHERE sm.secret_id = s.id
        ), '{}'::jsonb) AS metadata,
        s.created_at, s.updated_at
    FROM public.secrets s
    WHERE s.user_id = auth.uid()
    AND (s.website ILIKE '%' || p_query || '%' OR s.username ILIKE '%' || p_query || '%')  -- Case-insensitive search
    ORDER BY s.created_at DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$;

ALTER FUNCTION "public"."search_user_secrets"("p_query" "text", "p_limit" integer, "p_offset" integer) OWNER TO "postgres";



-- ============================================================================
-- SECTION 3: Sharing Functions (3 functions)
-- ============================================================================
-- Purpose: Share secrets with users or roles, revoke access, list shares


-- Function 3.1: share_secret
-- -----------------------------------------------------------------------------
-- Purpose: Share a secret with a user OR role (mutually exclusive)
-- Parameters:
--   - p_secret_id: UUID of secret to share
--   - p_target_user_id: UUID of user to share with (NULL if role share)
--   - p_target_role_id: UUID of role to share with (NULL if user share)
--   - p_notes: Optional sharing notes (e.g., "Read-only access for audit")
--   - p_expires_at: Optional expiration timestamp
--
-- Authorization:
--   - Owner: User owns the secret (secret.user_id = auth.uid())
--   - Admin: User has 'admin' role (can share any secret)
--
-- Validation:
--   - Must specify EITHER user_id OR role_id, not both or neither
--   - Target user/role must exist
--
-- Behavior:
--   - ON CONFLICT: Updates expires_at and notes (idempotent)
--   - Logs to secret_access_log for audit trail
--   - Access level: Always 'read' (write access future enhancement)
--
-- Returns: JSONB with success/error and target info
-- Usage:
--   -- Share with user
--   SELECT share_secret('secret-uuid', 'user-uuid', NULL, 'Read access', '2025-12-31'::timestamptz);
--   -- Share with role
--   SELECT share_secret('secret-uuid', NULL, 'role-uuid', 'Team access', NULL);
CREATE OR REPLACE FUNCTION "public"."share_secret"("p_secret_id" "uuid", "p_target_user_id" "uuid" DEFAULT NULL::"uuid", "p_target_role_id" "uuid" DEFAULT NULL::"uuid", "p_notes" "text" DEFAULT NULL::"text", "p_expires_at" timestamp with time zone DEFAULT NULL::timestamp with time zone) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    AS $$
DECLARE
    v_is_owner BOOLEAN;
    v_is_admin BOOLEAN;
    v_target_email TEXT;
    v_target_role_name TEXT;
    v_secret_website TEXT;
BEGIN
    -- Check if user is owner or admin
    SELECT EXISTS(
        SELECT 1 FROM public.secrets
        WHERE id = p_secret_id AND user_id = auth.uid()
    ) INTO v_is_owner;

    SELECT EXISTS(
        SELECT 1 FROM public.user_roles ur
        JOIN public.roles r ON r.id = ur.role_id
        WHERE ur.user_id = auth.uid() AND r.name = 'admin'
    ) INTO v_is_admin;

    IF NOT v_is_owner AND NOT v_is_admin THEN
        RETURN jsonb_build_object(
            'success', false,
            'error', 'Unauthorized: You must be the owner or admin to share this secret'
        );
    END IF;

    -- Validate target (must be user XOR role, not both or neither)
    IF (p_target_user_id IS NULL AND p_target_role_id IS NULL) OR
       (p_target_user_id IS NOT NULL AND p_target_role_id IS NOT NULL) THEN
        RETURN jsonb_build_object(
            'success', false,
            'error', 'Must specify either target_user_id OR target_role_id, not both or neither'
        );
    END IF;

    -- Get secret website for logging
    SELECT website INTO v_secret_website
    FROM public.secrets
    WHERE id = p_secret_id;

    -- Handle user share
    IF p_target_user_id IS NOT NULL THEN
        -- Get user email
        SELECT email INTO v_target_email
        FROM auth.users
        WHERE id = p_target_user_id;

        IF v_target_email IS NULL THEN
            RETURN jsonb_build_object(
                'success', false,
                'error', 'User not found'
            );
        END IF;

        -- Insert or update user share (ON CONFLICT updates expires_at and notes)
        INSERT INTO public.secret_shares (
            secret_id,
            shared_with_user_id,
            shared_with_role_id,
            shared_by,
            access_level,
            expires_at,
            notes
        ) VALUES (
            p_secret_id,
            p_target_user_id,
            NULL,
            auth.uid(),
            'read',
            p_expires_at,
            p_notes
        )
        ON CONFLICT (secret_id, shared_with_user_id)
        DO UPDATE SET
            expires_at = EXCLUDED.expires_at,
            notes = EXCLUDED.notes,
            created_at = NOW();
    END IF;

    -- Handle role share
    IF p_target_role_id IS NOT NULL THEN
        -- Get role name
        SELECT name INTO v_target_role_name
        FROM public.roles
        WHERE id = p_target_role_id;

        IF v_target_role_name IS NULL THEN
            RETURN jsonb_build_object(
                'success', false,
                'error', 'Role not found'
            );
        END IF;

        -- Insert or update role share
        INSERT INTO public.secret_shares (
            secret_id,
            shared_with_user_id,
            shared_with_role_id,
            shared_by,
            access_level,
            expires_at,
            notes
        ) VALUES (
            p_secret_id,
            NULL,
            p_target_role_id,
            auth.uid(),
            'read',
            p_expires_at,
            p_notes
        )
        ON CONFLICT (secret_id, shared_with_role_id)
        DO UPDATE SET
            expires_at = EXCLUDED.expires_at,
            notes = EXCLUDED.notes,
            created_at = NOW();
    END IF;

    -- Log the share action for audit trail
    INSERT INTO public.secret_access_log (
        secret_id,
        user_id,
        operation,
        access_granted_via,
        metadata
    ) VALUES (
        p_secret_id,
        auth.uid(),
        'share',
        CASE WHEN v_is_owner THEN 'owner' ELSE 'admin_override' END,
        jsonb_build_object(
            'target_user_id', p_target_user_id,
            'target_role_id', p_target_role_id,
            'target_email', v_target_email,
            'target_role_name', v_target_role_name,
            'secret_website', v_secret_website,
            'expires_at', p_expires_at,
            'notes', p_notes
        )
    );

    RETURN jsonb_build_object(
        'success', true,
        'message', 'Secret shared successfully',
        'target_email', v_target_email,
        'target_role', v_target_role_name
    );
END;
$$;

ALTER FUNCTION "public"."share_secret"("p_secret_id" "uuid", "p_target_user_id" "uuid", "p_target_role_id" "uuid", "p_notes" "text", "p_expires_at" timestamp with time zone) OWNER TO "postgres";



-- Function 3.2: unshare_secret
-- -----------------------------------------------------------------------------
-- Purpose: Revoke access to a shared secret (owner/admin only)
-- Parameters: Same as share_secret (user_id XOR role_id)
-- Authorization: Owner or admin only
-- Behavior:
--   - Deletes from secret_shares table
--   - Logs to secret_access_log for audit
--   - Returns revoked_count (number of shares removed)
-- Returns: JSONB with success/error and revoked_count
-- Usage: SELECT unshare_secret('secret-uuid', 'user-uuid', NULL);
CREATE OR REPLACE FUNCTION "public"."unshare_secret"("p_secret_id" "uuid", "p_target_user_id" "uuid" DEFAULT NULL::"uuid", "p_target_role_id" "uuid" DEFAULT NULL::"uuid") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    AS $$
DECLARE
    v_is_owner BOOLEAN;
    v_is_admin BOOLEAN;
    v_deleted_count INTEGER;
BEGIN
    -- Check authorization
    SELECT EXISTS(
        SELECT 1 FROM public.secrets
        WHERE id = p_secret_id AND user_id = auth.uid()
    ) INTO v_is_owner;

    SELECT EXISTS(
        SELECT 1 FROM public.user_roles ur
        JOIN public.roles r ON r.id = ur.role_id
        WHERE ur.user_id = auth.uid() AND r.name = 'admin'
    ) INTO v_is_admin;

    IF NOT v_is_owner AND NOT v_is_admin THEN
        RETURN jsonb_build_object(
            'success', false,
            'error', 'Unauthorized: You must be the owner or admin to revoke access'
        );
    END IF;

    -- Delete share record(s)
    DELETE FROM public.secret_shares
    WHERE secret_id = p_secret_id
    AND (
        (p_target_user_id IS NOT NULL AND shared_with_user_id = p_target_user_id)
        OR
        (p_target_role_id IS NOT NULL AND shared_with_role_id = p_target_role_id)
    );

    GET DIAGNOSTICS v_deleted_count = ROW_COUNT;

    IF v_deleted_count = 0 THEN
        RETURN jsonb_build_object(
            'success', false,
            'error', 'No matching share found to revoke'
        );
    END IF;

    -- Log the unshare action
    INSERT INTO public.secret_access_log (
        secret_id,
        user_id,
        operation,
        access_granted_via,
        metadata
    ) VALUES (
        p_secret_id,
        auth.uid(),
        'unshare',
        CASE WHEN v_is_owner THEN 'owner' ELSE 'admin_override' END,
        jsonb_build_object(
            'target_user_id', p_target_user_id,
            'target_role_id', p_target_role_id
        )
    );

    RETURN jsonb_build_object(
        'success', true,
        'message', 'Access revoked successfully',
        'revoked_count', v_deleted_count
    );
END;
$$;

ALTER FUNCTION "public"."unshare_secret"("p_secret_id" "uuid", "p_target_user_id" "uuid", "p_target_role_id" "uuid") OWNER TO "postgres";



-- Function 3.3: get_secret_shares
-- -----------------------------------------------------------------------------
-- Purpose: List all shares for a secret (owner/admin only)
-- Parameters:
--   - p_secret_id: UUID of secret to query
-- Returns: TABLE with share details:
--   - share_id: UUID of share record
--   - shared_with_user_id: UUID if user share, NULL if role share
--   - shared_with_user_email: Email if user share, NULL if role share
--   - shared_with_role_id: UUID if role share, NULL if user share
--   - shared_with_role_name: Role name if role share, NULL if user share
--   - access_level: 'read' | 'write'
--   - shared_by_email: Email of user who shared
--   - created_at: When share was created
--   - expires_at: When share expires (NULL if permanent)
--   - notes: Optional sharing notes
-- Authorization: Owner or admin only
-- Ordering: Created date DESC (newest first)
-- Usage: SELECT * FROM get_secret_shares('secret-uuid');
CREATE OR REPLACE FUNCTION "public"."get_secret_shares"("p_secret_id" "uuid") RETURNS TABLE("share_id" "uuid", "shared_with_user_id" "uuid", "shared_with_user_email" "text", "shared_with_role_id" "uuid", "shared_with_role_name" "text", "access_level" "text", "shared_by_email" "text", "created_at" timestamp with time zone, "expires_at" timestamp with time zone, "notes" "text")
    LANGUAGE "plpgsql" SECURITY DEFINER
    AS $$
BEGIN
    -- Check if user is owner or admin
    IF NOT EXISTS(
        SELECT 1 FROM public.secrets
        WHERE id = p_secret_id AND user_id = auth.uid()
    ) AND NOT EXISTS(
        SELECT 1 FROM public.user_roles ur
        JOIN public.roles r ON r.id = ur.role_id
        WHERE ur.user_id = auth.uid() AND r.name = 'admin'
    ) THEN
        RAISE EXCEPTION 'Unauthorized: You must be the owner or admin to view shares';
    END IF;

    RETURN QUERY
    SELECT
        ss.id as share_id,
        ss.shared_with_user_id,
        u.email::text as shared_with_user_email,
        ss.shared_with_role_id,
        r.name::text as shared_with_role_name,
        ss.access_level,
        sb.email::text as shared_by_email,
        ss.created_at,
        ss.expires_at,
        ss.notes
    FROM public.secret_shares ss
    LEFT JOIN auth.users u ON u.id = ss.shared_with_user_id
    LEFT JOIN public.roles r ON r.id = ss.shared_with_role_id
    LEFT JOIN auth.users sb ON sb.id = ss.shared_by
    WHERE ss.secret_id = p_secret_id
    ORDER BY ss.created_at DESC;
END;
$$;

ALTER FUNCTION "public"."get_secret_shares"("p_secret_id" "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."get_secret_shares"("p_secret_id" "uuid") IS 'List all shares for a secret (owner/admin only) - Fixed type casting';


-- ============================================================================
-- End of File: secret_functions.sql
-- ============================================================================
-- Next Migration: 20251023000005_encryption_functions.sql (Encryption)
-- ============================================================================
