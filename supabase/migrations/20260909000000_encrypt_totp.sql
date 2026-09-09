-- ============================================================================
-- BOSS Database Schema: Encrypt Plaintext TOTP Secrets
-- ============================================================================
-- File: 20260909000000_encrypt_totp.sql
-- Description: Secures twofa_secret by storing it encrypted at rest using a
--              'v1:' prefix to guarantee idempotence.
--              Implements safe decryption and auto-encrypt triggers.
-- ============================================================================

-- Function 1: safe_decrypt_twofa_secret
-- Safely attempts to decrypt the twofa_secret, returning NULL on failure
CREATE OR REPLACE FUNCTION "public"."safe_decrypt_twofa_secret"("encrypted_data" "text") RETURNS "text"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    IF encrypted_data IS NULL THEN
        RETURN NULL;
    END IF;

    IF encrypted_data LIKE 'v1:%' THEN
        BEGIN
            RETURN public.decrypt_text(substring(encrypted_data from 4));
        EXCEPTION
            WHEN OTHERS THEN
                RETURN NULL;
        END;
    END IF;

    -- Unrecognized format (e.g., plaintext if not migrated, or corrupted)
    RETURN NULL;
END;
$$;

ALTER FUNCTION "public"."safe_decrypt_twofa_secret"("encrypted_data" "text") OWNER TO "postgres";
COMMENT ON FUNCTION "public"."safe_decrypt_twofa_secret"("encrypted_data" "text") IS 'Safely decrypt v1: prefixed 2FA TOTP secret, returning NULL on failure';

-- Prevent this from acting as an open generic decryption oracle for authenticated users
REVOKE EXECUTE ON FUNCTION "public"."safe_decrypt_twofa_secret"("encrypted_data" "text") FROM PUBLIC;
GRANT EXECUTE ON FUNCTION "public"."safe_decrypt_twofa_secret"("encrypted_data" "text") TO "postgres";

-- Function 2: Trigger function to intercept and encrypt plaintext inserts/updates
CREATE OR REPLACE FUNCTION "public"."encrypt_twofa_secret_trigger_fn"() RETURNS trigger
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    -- Only process if twofa_secret is provided and not already v1: prefixed
    IF NEW.twofa_secret IS NOT NULL AND NEW.twofa_secret NOT LIKE 'v1:%' THEN
        -- Safely prefix with v1: and encrypt the plaintext
        NEW.twofa_secret := 'v1:' || public.encrypt_text(NEW.twofa_secret);
    END IF;
    RETURN NEW;
END;
$$;

ALTER FUNCTION "public"."encrypt_twofa_secret_trigger_fn"() OWNER TO "postgres";

DROP TRIGGER IF EXISTS "encrypt_twofa_secret_trigger" ON "public"."secret_metadata";
CREATE TRIGGER "encrypt_twofa_secret_trigger"
    BEFORE INSERT OR UPDATE OF "twofa_secret" ON "public"."secret_metadata"
    FOR EACH ROW
    EXECUTE FUNCTION "public"."encrypt_twofa_secret_trigger_fn"();

-- Data Migration: Encrypt existing plaintext rows and apply v1: prefix
UPDATE public.secret_metadata
SET twofa_secret = 'v1:' || public.encrypt_text(twofa_secret)
WHERE twofa_secret IS NOT NULL
AND twofa_secret NOT LIKE 'v1:%';
