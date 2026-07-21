-- ============================================================================
-- BOSS Database Schema: Encryption Functions
-- ============================================================================
-- File: 20251023000005_encryption_functions.sql
-- Description: Core encryption/decryption functions for securing sensitive data.
--              All encryption uses AES-256 with base64 encoding (NOT PGP).
--              Master encryption key stored securely in Supabase Vault.
-- Dependencies:
--   - File 1: extensions_and_types.sql (pgcrypto extension)
--   - Supabase Vault: master_encryption_key secret must be created
-- Functions: 4 total
-- Encryption Method: AES-256 + base64 encoding
-- Key Storage: Supabase Vault (vault.decrypted_secrets table)
-- ============================================================================


-- ============================================================================
-- SECTION 1: Key Management (1 function)
-- ============================================================================
-- Purpose: Retrieve master encryption key from Supabase Vault


-- Function 1.1: get_encryption_key
-- -----------------------------------------------------------------------------
-- Purpose: Retrieve the master encryption key from Supabase Vault
-- Use Case: Called internally by encrypt_text() and decrypt_text()
-- Security Model:
--   - Key stored in Supabase Vault (vault.decrypted_secrets table)
--   - Never hardcoded in codebase or environment variables
--   - SECURITY DEFINER allows access to vault schema
--   - Key name: 'master_encryption_key'
--
-- First-Time Setup (required before using encryption):
--   SELECT vault.create_secret(
--     '<your-32-byte-hex-key>',
--     'master_encryption_key',
--     'Master key for encrypting user secrets'
--   );
--
-- Key Generation Best Practice:
--   Generate a secure 256-bit (32-byte) key:
--     openssl rand -hex 32
--
-- Returns: TEXT - The master encryption key (32-byte hex string)
-- Raises: EXCEPTION if key not found (provides helpful setup instructions)
-- Performance: Fast lookup via indexed vault.decrypted_secrets.name
-- Security: SECURITY DEFINER to read from vault schema
-- Usage:
--   -- Internal usage (called by encrypt_text/decrypt_text)
--   encryption_key := public.get_encryption_key();
CREATE OR REPLACE FUNCTION "public"."get_encryption_key"() RETURNS "text"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public, vault'
    AS $$
DECLARE
    encryption_key TEXT;
BEGIN
    -- Retrieve encryption key from Supabase Vault
    -- This ensures the key is never hardcoded in the codebase
    SELECT decrypted_secret INTO encryption_key
    FROM vault.decrypted_secrets
    WHERE name = 'master_encryption_key';

    IF encryption_key IS NULL THEN
        RAISE EXCEPTION 'Encryption key not found in vault. Please run: SELECT vault.create_secret(''<your-key>'', ''master_encryption_key'', ''Master key for encrypting user secrets'');';
    END IF;

    RETURN encryption_key;
END;
$$;

ALTER FUNCTION "public"."get_encryption_key"() OWNER TO "postgres";



-- ============================================================================
-- SECTION 2: Core Encryption Operations (2 functions)
-- ============================================================================
-- Purpose: Encrypt and decrypt text data using AES-256 + base64 encoding


-- Function 2.1: encrypt_text
-- -----------------------------------------------------------------------------
-- Purpose: Encrypt plaintext using AES-256 and encode as base64 string
-- Use Case: Encrypting sensitive data before storing in database
--   - User passwords in secrets table (password_encrypted column)
--   - Recovery codes in secret_metadata table (recovery_codes_encrypted column)
--   - Any sensitive text data that needs encryption at rest
--
-- Encryption Flow:
--   1. Retrieve master key from vault (get_encryption_key)
--   2. Convert plaintext string to bytea
--   3. Encrypt bytea using AES with master key (pgcrypto.encrypt)
--   4. Encode encrypted bytea to base64 string (safe for TEXT columns)
--   5. Return base64-encoded ciphertext
--
-- Visual Flow:
--   Plaintext → bytea → AES Encrypt → base64 Encode → Ciphertext
--   "password123" → bytea → encrypted bytes → "a9f2b8c3..." → stored in DB
--
-- Parameters:
--   - plaintext: The text to encrypt (password, recovery codes, etc.)
-- Returns: TEXT - Base64-encoded ciphertext (safe for storage in TEXT columns)
-- Encryption Algorithm: AES-256 (via pgcrypto extension)
-- Encoding: base64 (for safe storage in TEXT columns)
-- Key Source: Supabase Vault (via get_encryption_key)
-- Security: SECURITY DEFINER to access vault and pgcrypto functions
-- Performance: Fast for typical password/secret lengths (<1KB)
-- Usage:
--   -- Encrypt a password before storing
--   INSERT INTO secrets (password_encrypted)
--   VALUES (encrypt_text('my-secure-password'));
--
--   -- Encrypt recovery codes (JSON array)
--   UPDATE secret_metadata
--   SET recovery_codes_encrypted = encrypt_text('["code1", "code2"]'::text)
--   WHERE secret_id = '...';
CREATE OR REPLACE FUNCTION "public"."encrypt_text"("plaintext" "text") RETURNS "text"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public, pg_catalog, extensions'
    AS $$
DECLARE
    encryption_key TEXT;
BEGIN
    -- Step 1: Retrieve master encryption key from Supabase Vault
    encryption_key := public.get_encryption_key();

    -- Step 2-4: Encrypt and encode
    -- - Convert plaintext to bytea
    -- - Encrypt with AES using master key (pgcrypto.encrypt)
    -- - Encode result as base64 for safe storage in TEXT column
    RETURN pg_catalog.encode(
        extensions.encrypt(  -- <-- Fully qualified to use pgcrypto extension
            plaintext::bytea,
            encryption_key::bytea,
            'aes'::text
        ),
        'base64'::text
    );
END;
$$;

ALTER FUNCTION "public"."encrypt_text"("plaintext" "text") OWNER TO "postgres";



-- Function 2.2: decrypt_text
-- -----------------------------------------------------------------------------
-- Purpose: Decrypt base64-encoded ciphertext back to plaintext
-- Use Case: Decrypting sensitive data when retrieving from database
--   - Decrypt passwords when user views/copies secret
--   - Decrypt recovery codes for 2FA setup
--   - Decrypt any encrypted text field
--
-- Decryption Flow:
--   1. Check if ciphertext is NULL → return NULL (no decryption needed)
--   2. Retrieve master key from vault (get_encryption_key)
--   3. Decode base64 string to encrypted bytea
--   4. Decrypt bytea using AES with master key (pgcrypto.decrypt)
--   5. Convert decrypted bytea to UTF-8 string
--   6. Return plaintext
--
-- Visual Flow:
--   Ciphertext → base64 Decode → AES Decrypt → bytea → UTF-8 → Plaintext
--   "a9f2b8c3..." (from DB) → encrypted bytes → bytea → "password123"
--
-- Parameters:
--   - ciphertext: Base64-encoded encrypted text (from database TEXT column)
-- Returns: TEXT - Decrypted plaintext string, or NULL if input is NULL
-- Decryption Algorithm: AES-256 (via pgcrypto extension)
-- Decoding: base64 → bytea → UTF-8 text
-- Key Source: Supabase Vault (via get_encryption_key)
-- Security: SECURITY DEFINER to access vault and pgcrypto functions
-- NULL Handling: Returns NULL if ciphertext is NULL (safe for optional fields)
-- Error Handling: Will raise exception if:
--   - Ciphertext is corrupt (invalid base64 or encrypted data)
--   - Wrong encryption key used
--   - Key not found in vault
-- Performance: Fast for typical password/secret lengths (<1KB)
-- Usage:
--   -- Decrypt password when retrieving secret
--   SELECT
--     website,
--     username,
--     decrypt_text(password_encrypted) AS password
--   FROM secrets
--   WHERE id = '...';
--
--   -- Decrypt recovery codes (returns JSON string)
--   SELECT decrypt_text(recovery_codes_encrypted)::jsonb
--   FROM secret_metadata
--   WHERE secret_id = '...';
CREATE OR REPLACE FUNCTION "public"."decrypt_text"("ciphertext" "text") RETURNS "text"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public, pg_catalog, extensions'
    AS $$
DECLARE
    encryption_key TEXT;
BEGIN
    -- Step 1: NULL check (no decryption needed for NULL values)
    IF ciphertext IS NULL THEN
        RETURN NULL;
    END IF;

    -- Step 2: Retrieve master encryption key from Supabase Vault
    encryption_key := public.get_encryption_key();

    -- Step 3-5: Decode and decrypt
    -- - Decode base64 string to bytea
    -- - Decrypt bytea with AES using master key (pgcrypto.decrypt)
    -- - Convert decrypted bytea to UTF-8 text
    RETURN pg_catalog.convert_from(
        extensions.decrypt(  -- <-- Fully qualified to use pgcrypto extension
            pg_catalog.decode(ciphertext, 'base64'::text),
            encryption_key::bytea,
            'aes'::text
        ),
        'utf8'::name
    );
END;
$$;

ALTER FUNCTION "public"."decrypt_text"("ciphertext" "text") OWNER TO "postgres";



-- ============================================================================
-- SECTION 3: Safe Decryption with Error Handling (1 function)
-- ============================================================================
-- Purpose: Decrypt with graceful failure for optional/legacy data


-- Function 3.1: safe_decrypt_recovery_codes
-- -----------------------------------------------------------------------------
-- Purpose: Safely decrypt recovery codes JSON array with error handling
-- Use Case: Decrypt recovery codes for 2FA without crashing on corrupt data
--   - Used in get_user_secrets and get_user_secrets_with_shared functions
--   - Prevents query failures when recovery codes are corrupt or key changed
--   - Returns empty array [] instead of raising exception on failure
--
-- Why Safe Decryption Needed:
--   Problem: If recovery codes are corrupt (wrong key, data corruption, etc.),
--            normal decrypt_text() would raise exception and crash entire query
--   Solution: Wrap decrypt_text() in BEGIN...EXCEPTION...END block to catch
--             errors and return safe default value (empty array)
--
-- Failure Scenarios Handled:
--   1. Corrupt encrypted data (invalid base64 or AES ciphertext)
--   2. Wrong encryption key (key rotation without re-encrypting data)
--   3. NULL encrypted data (no recovery codes set)
--   4. Invalid JSON after decryption (data corruption)
--
-- Error Handling Flow:
--   1. Check if encrypted_data is NULL → return [] (no codes to decrypt)
--   2. Try to decrypt using decrypt_text() and cast to jsonb
--   3. If successful → return decrypted JSON array
--   4. If exception raised → catch error and return [] (safe fallback)
--
-- Visual Flow (Success):
--   encrypted_data → decrypt_text() → "[\"code1\", \"code2\"]" → jsonb array
--
-- Visual Flow (Failure):
--   encrypted_data → decrypt_text() → ERROR → CATCH → [] (empty array)
--
-- Parameters:
--   - encrypted_data: Base64-encoded encrypted JSON array of recovery codes
-- Returns: JSONB - Decrypted recovery codes array, or [] on any failure
-- Decryption Method: Uses decrypt_text() internally (AES + base64)
-- Error Handling: BEGIN...EXCEPTION...END block catches ALL errors
-- Fallback Value: Empty JSON array [] (safe for application logic)
-- Security: SECURITY DEFINER to call decrypt_text()
-- Performance: Fast for typical recovery codes (<1KB)
-- NULL Safety: Returns [] if input is NULL (no codes set)
-- Usage:
--   -- Used internally by get_user_secrets functions
--   SELECT
--     jsonb_build_object(
--       'recovery_codes', safe_decrypt_recovery_codes(sm.recovery_codes_encrypted)
--     ) AS metadata
--   FROM secret_metadata sm
--   WHERE secret_id = '...';
--
--   -- Returns [] instead of crashing if decryption fails:
--   SELECT safe_decrypt_recovery_codes('corrupt-data');  -- Returns: []
--   SELECT safe_decrypt_recovery_codes(NULL);            -- Returns: []
CREATE OR REPLACE FUNCTION "public"."safe_decrypt_recovery_codes"("encrypted_data" "text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    AS $$
BEGIN
    -- NULL check: Return empty array if no encrypted data
    IF encrypted_data IS NULL THEN
        RETURN '[]'::jsonb;
    END IF;

    -- Try to decrypt and parse as JSON
    BEGIN
        -- Use decrypt_text which handles AES + base64 decoding
        -- Cast result to jsonb (validates JSON format)
        RETURN (public.decrypt_text(encrypted_data)::text)::jsonb;
    EXCEPTION
        WHEN OTHERS THEN
            -- If decryption fails (corrupt data, wrong key, invalid JSON, etc.),
            -- return empty array instead of crashing the query
            -- This allows secrets queries to succeed even if recovery codes are corrupt
            RETURN '[]'::jsonb;
    END;
END;
$$;

ALTER FUNCTION "public"."safe_decrypt_recovery_codes"("encrypted_data" "text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."safe_decrypt_recovery_codes"("encrypted_data" "text") IS 'Safely decrypt recovery codes, returning empty array on failure';


-- ============================================================================
-- End of File: encryption_functions.sql
-- ============================================================================
-- Next Migration: 20251023000006_user_functions.sql (User Functions)
-- ============================================================================
