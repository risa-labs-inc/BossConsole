-- ============================================================================
-- BOSS Database Schema: Authenticated, Non-Deterministic Secret Encryption
-- ============================================================================
-- File: 20260913000000_authenticated_secret_encryption.sql
-- Description: Replaces the encrypt_text / decrypt_text construction, which had
--              three defects that all came out of one call to the 3-arg
--              extensions.encrypt(plaintext, key, 'aes'):
--
--   1. Deterministic ciphertext. The 3-arg form uses an all-zero IV, so
--      encryption was a pure function of the plaintext under the one global key.
--      A plain GROUP BY over password_encrypted named every set of users sharing
--      a password, and because create_secret() encrypts server-side and RLS then
--      lets a user read their own password_encrypted back, any signed-in user was
--      an encryption oracle: candidate-password to ciphertext pairs recovered
--      plaintext by equality join, with no access to the vault key. This is the
--      serious defect - it holds against a dump alone.
--
--   2. Half the master key was discarded. encryption_key::bytea took the 64 ASCII
--      characters of the documented `openssl rand -hex 32` key rather than
--      hex-decoding them to 32 binary bytes, and pgcrypto's rijndael keys on at
--      most 32 bytes - so only the first 32 hex characters were used: 128 bits of
--      entropy, not the documented 256.
--
--   3. No integrity. CBC with no MAC leaves stored ciphertext malleable, and a
--      flipped bit surfaced only later as a UTF-8 decode error, never as an
--      authentication failure.
--
-- Fix: a versioned 'v2:' envelope carrying a per-row random IV and an
-- encrypt-then-MAC HMAC-SHA256 tag, keyed by the hex-decoded 32-byte master key
-- (true AES-256-CBC). decrypt_text reads both the new 'v2:' envelope and legacy
-- rows (raw base64, zero IV, ASCII key bytes) so existing data keeps decrypting;
-- the backfill at the end re-encrypts every affected row into the v2 form.
--
-- Envelope layout (bytes, before base64): iv(16) || hmac(32) || ciphertext.
-- The MAC covers iv || ciphertext (encrypt-then-MAC) and uses a key derived from
-- the master key by a domain-separated HMAC, so the AES key and the MAC key are
-- never the same bytes.
--
-- This is complementary to 20260909120000_close_client_crypto_access.sql (PR
-- #423), which revoked client execute on these functions and the rotation ops
-- script. That work closes the direct oracle but does not change the
-- construction: create_secret() still encrypts server-side and hands the owner
-- their row back, and supabase/ops/rotate_master_encryption_key.sql re-encrypts
-- through encrypt_text, so rotation preserved the determinism. Both are fixed
-- here at the source. #547 (ALTER DEFAULT PRIVILEGES inheritance) is unrelated.
--
-- Operational scope (same caveats as 20260909000000_encrypt_totp.sql): an UPDATE
-- rewrites visible rows, not old physical bytes. Dead tuples, WAL and existing
-- backups / PITR may retain the deterministic ciphertext. This is not secure
-- erasure; backup retention and physical storage reclamation are separate
-- operator decisions. Logical reads after commit see the v2 storage.
--
-- Breaking change: the key is now hex-decoded and must be exactly 32 bytes
-- (64 hex characters, i.e. the documented `openssl rand -hex 32`). This lands
-- together with the re-encryption backfill precisely because it changes the key
-- derivation for new writes; legacy rows are still read with the old ASCII-bytes
-- key until the backfill rewrites them in this same migration.
-- ============================================================================

-- Function 2.1 (replaces 20251023000005): encrypt_text
-- -----------------------------------------------------------------------------
-- AES-256-CBC with a per-call random IV, then HMAC-SHA256 over iv || ciphertext.
-- Output: 'v2:' || base64(iv || mac || ciphertext).
CREATE OR REPLACE FUNCTION "public"."encrypt_text"("plaintext" "text") RETURNS "text"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public, pg_catalog, extensions'
    AS $$
DECLARE
    encryption_key TEXT;
    enc_key        bytea;
    mac_key        bytea;
    iv             bytea;
    ciphertext     bytea;
    mac            bytea;
BEGIN
    IF plaintext IS NULL THEN
        RETURN NULL;
    END IF;

    -- Step 1: master key from the vault, hex-decoded to 32 raw bytes (256 bits).
    encryption_key := public.get_encryption_key();
    enc_key := pg_catalog.decode(encryption_key, 'hex');
    IF pg_catalog.octet_length(enc_key) <> 32 THEN
        RAISE EXCEPTION 'master_encryption_key must be 32 bytes (64 hex characters, e.g. `openssl rand -hex 32`); got % bytes',
            pg_catalog.octet_length(enc_key)
            USING ERRCODE = '22023';
    END IF;

    -- Step 2: derive a distinct MAC key so the AES key is never reused for MAC.
    mac_key := extensions.hmac('BOSS-secret-mac-v2'::bytea, enc_key, 'sha256'::text);

    -- Step 3: encrypt under a fresh random IV (AES-256-CBC, PKCS padding).
    iv := extensions.gen_random_bytes(16);
    ciphertext := extensions.encrypt_iv(plaintext::bytea, enc_key, iv, 'aes'::text);

    -- Step 4: encrypt-then-MAC over iv || ciphertext.
    mac := extensions.hmac(iv || ciphertext, mac_key, 'sha256'::text);

    -- Step 5: versioned, base64-encoded envelope.
    RETURN 'v2:' || pg_catalog.encode(iv || mac || ciphertext, 'base64'::text);
END;
$$;

ALTER FUNCTION "public"."encrypt_text"("plaintext" "text") OWNER TO "postgres";
COMMENT ON FUNCTION "public"."encrypt_text"("plaintext" "text") IS
    'Encrypt text as a versioned v2 envelope: AES-256-CBC under a random IV, encrypt-then-MAC HMAC-SHA256, hex-decoded 32-byte master key.';


-- Function 2.2 (replaces 20251023000005): decrypt_text
-- -----------------------------------------------------------------------------
-- Reads a 'v2:' envelope (verifying the HMAC before decrypting) and, for
-- backward compatibility, legacy rows written by the old construction (raw
-- base64 of the zero-IV AES output keyed by the ASCII bytes of the hex key).
CREATE OR REPLACE FUNCTION "public"."decrypt_text"("ciphertext" "text") RETURNS "text"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public, pg_catalog, extensions'
    AS $$
DECLARE
    encryption_key TEXT;
    enc_key        bytea;
    mac_key        bytea;
    raw            bytea;
    iv             bytea;
    mac            bytea;
    body           bytea;
BEGIN
    IF ciphertext IS NULL THEN
        RETURN NULL;
    END IF;

    encryption_key := public.get_encryption_key();

    IF pg_catalog.left(ciphertext, 3) = 'v2:' THEN
        -- New envelope: hex-decoded 32-byte key, verify HMAC, then decrypt.
        enc_key := pg_catalog.decode(encryption_key, 'hex');
        mac_key := extensions.hmac('BOSS-secret-mac-v2'::bytea, enc_key, 'sha256'::text);

        raw := pg_catalog.decode(pg_catalog.substring(ciphertext, 4), 'base64');
        IF pg_catalog.octet_length(raw) < 48 THEN
            RAISE EXCEPTION 'ciphertext envelope is too short to contain an IV and MAC'
                USING ERRCODE = '22023';
        END IF;
        iv   := pg_catalog.substring(raw, 1, 16);
        mac  := pg_catalog.substring(raw, 17, 32);
        body := pg_catalog.substring(raw, 49);

        IF extensions.hmac(iv || body, mac_key, 'sha256'::text) <> mac THEN
            RAISE EXCEPTION 'ciphertext failed integrity verification'
                USING ERRCODE = '22023';
        END IF;

        RETURN pg_catalog.convert_from(
            extensions.decrypt_iv(body, enc_key, iv, 'aes'::text),
            'utf8'::name
        );
    END IF;

    -- Legacy path: the old 3-arg encrypt() used an all-zero IV and keyed on the
    -- ASCII bytes of the hex string (first 32 bytes). Read those rows verbatim so
    -- the backfill below - and any row it has not reached yet - still decrypts.
    RETURN pg_catalog.convert_from(
        extensions.decrypt(
            pg_catalog.decode(ciphertext, 'base64'::text),
            encryption_key::bytea,
            'aes'::text
        ),
        'utf8'::name
    );
END;
$$;

ALTER FUNCTION "public"."decrypt_text"("ciphertext" "text") OWNER TO "postgres";
COMMENT ON FUNCTION "public"."decrypt_text"("ciphertext" "text") IS
    'Decrypt a v2 envelope (HMAC verified before decrypt) or a legacy zero-IV row; the legacy branch preserves rows written before 20260913000000.';

-- Re-issue the client revokes from 20260909120000_close_client_crypto_access.sql.
-- CREATE OR REPLACE preserves an existing ACL, and the enforce_explicit_anon_grants
-- event trigger (20260908000000) already keeps public/anon out of a replacement,
-- so these are belt-and-braces: they guarantee this replacement cannot widen
-- access to the key material or the decryption oracle under any path. service_role
-- keeps its access, exactly as #423 left it (it revoked only these three roles).
REVOKE ALL ON FUNCTION "public"."encrypt_text"("text") FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION "public"."decrypt_text"("text") FROM PUBLIC, anon, authenticated;


-- Function 2.3 (new): rekey_secret_envelope
-- -----------------------------------------------------------------------------
-- Re-encrypt one stored value from an old key to a new key, in a single
-- expression, for supabase/ops/rotate_master_encryption_key.sql. Rotation
-- decrypts under the old key and encrypts under the new one BEFORE the vault
-- swap, so both keys are in play at once and neither can come from the vault -
-- which is why the two functions above are not usable here and this takes both
-- keys as parameters. It exists so rotation shares one construction with
-- encrypt_text / decrypt_text instead of re-implementing pgcrypto raw (which is
-- what regressed rotation to the deterministic zero-IV form before this):
--   * decrypt is version-aware - a 'v2:' body is HMAC-verified and decrypted
--     under the hex-decoded old key; anything else is read as a legacy zero-IV
--     row under the old key's ASCII bytes, exactly as decrypt_text does;
--   * encrypt always emits a fresh 'v2:' envelope under the hex-decoded new key,
--     so a rotation upgrades legacy rows and never produces deterministic output.
-- `prefix` is the storage marker the rotation map carries (for example TOTP's
-- 'v1:'); it is stripped before decrypt and re-applied after encrypt. Plaintext
-- lives only inside this call - never in a column or temp - preserving the
-- rotation script's no-materialization guarantee. Keys are parameters, so this
-- is not a vault oracle; it is still revoked from every client role and left to
-- the owner that runs rotation.
CREATE OR REPLACE FUNCTION "public"."rekey_secret_envelope"(
    "stored" "text", "prefix" "text", "old_key" "text", "new_key" "text"
) RETURNS "text"
    LANGUAGE "plpgsql"
    SET "search_path" TO 'public, pg_catalog, extensions'
    AS $$
DECLARE
    body_in text;
    plain   bytea;
    old_enc bytea;
    new_enc bytea;
    raw     bytea;
    iv      bytea;
    mac     bytea;
    ct      bytea;
    new_iv  bytea;
    new_ct  bytea;
    new_mac bytea;
BEGIN
    IF stored IS NULL THEN
        RETURN NULL;
    END IF;
    body_in := pg_catalog.substr(stored, pg_catalog.length(COALESCE(prefix, '')) + 1);

    -- Decrypt under the old key, version-aware (mirrors decrypt_text).
    IF pg_catalog.left(body_in, 3) = 'v2:' THEN
        old_enc := pg_catalog.decode(old_key, 'hex');
        raw := pg_catalog.decode(pg_catalog.substring(body_in, 4), 'base64');
        IF pg_catalog.octet_length(raw) < 48 THEN
            RAISE EXCEPTION 'stored envelope is too short to rekey' USING ERRCODE = '22023';
        END IF;
        iv  := pg_catalog.substring(raw, 1, 16);
        mac := pg_catalog.substring(raw, 17, 32);
        ct  := pg_catalog.substring(raw, 49);
        IF extensions.hmac(iv || ct, extensions.hmac('BOSS-secret-mac-v2'::bytea, old_enc, 'sha256'::text), 'sha256'::text) <> mac THEN
            RAISE EXCEPTION 'stored ciphertext failed integrity verification during rekey' USING ERRCODE = '22023';
        END IF;
        plain := extensions.decrypt_iv(ct, old_enc, iv, 'aes'::text);
    ELSE
        plain := extensions.decrypt(pg_catalog.decode(body_in, 'base64'::text), old_key::bytea, 'aes'::text);
    END IF;

    -- Re-encrypt under the new key as a fresh v2 envelope.
    new_enc := pg_catalog.decode(new_key, 'hex');
    IF pg_catalog.octet_length(new_enc) <> 32 THEN
        RAISE EXCEPTION 'new master key must be 32 bytes (64 hex characters, e.g. `openssl rand -hex 32`); got % bytes',
            pg_catalog.octet_length(new_enc)
            USING ERRCODE = '22023';
    END IF;
    new_iv  := extensions.gen_random_bytes(16);
    new_ct  := extensions.encrypt_iv(plain, new_enc, new_iv, 'aes'::text);
    new_mac := extensions.hmac(new_iv || new_ct, extensions.hmac('BOSS-secret-mac-v2'::bytea, new_enc, 'sha256'::text), 'sha256'::text);
    RETURN COALESCE(prefix, '') || 'v2:' || pg_catalog.encode(new_iv || new_mac || new_ct, 'base64'::text);
END;
$$;

ALTER FUNCTION "public"."rekey_secret_envelope"("text", "text", "text", "text") OWNER TO "postgres";
COMMENT ON FUNCTION "public"."rekey_secret_envelope"("text", "text", "text", "text") IS
    'Re-encrypt one stored value from old_key to new_key as a v2 envelope, for key rotation. Version-aware read, always writes v2. Keys are parameters, not the vault.';
REVOKE ALL ON FUNCTION "public"."rekey_secret_envelope"("text", "text", "text", "text") FROM PUBLIC, anon, authenticated, service_role;


-- ============================================================================
-- Backfill: rewrite every deterministically encrypted row into the v2 envelope.
-- ============================================================================
-- Each UPDATE decrypts through the legacy branch and re-encrypts through the new
-- one. Guards make every statement idempotent: a v2 row is skipped, so replaying
-- this migration is a no-op. Ordering note: decrypt_text and encrypt_text are the
-- new definitions above by the time these run. Only the columns this repo's
-- migrations create are touched; the qbo_token_state / google_token_state columns
-- named in supabase/ops/rotate_master_encryption_key.sql are created outside this
-- repo, so they are left for the rotation path (decrypt_text reads their legacy
-- rows unchanged in the meantime).

-- 1. Passwords (secrets.password_encrypted).
UPDATE public.secrets
SET password_encrypted = public.encrypt_text(public.decrypt_text(password_encrypted))
WHERE password_encrypted IS NOT NULL
  AND pg_catalog.left(password_encrypted, 3) <> 'v2:';

-- 2. Recovery codes (secret_metadata.recovery_codes_encrypted).
UPDATE public.secret_metadata
SET recovery_codes_encrypted = public.encrypt_text(public.decrypt_text(recovery_codes_encrypted))
WHERE recovery_codes_encrypted IS NOT NULL
  AND pg_catalog.left(recovery_codes_encrypted, 3) <> 'v2:';

-- 3. TOTP seeds (secret_metadata.twofa_secret), stored as 'v1:' || encrypt_text(seed)
--    since 20260909000000. The 'v1:' prefix is the TOTP storage marker; the inner
--    payload is what encrypt_text produced, so an already-migrated row reads
--    'v1:v2:...'. The encrypt_twofa_secret_trigger rejects any value LIKE 'v1:%'
--    as a caller-supplied envelope, so - exactly as the TOTP migration did for its
--    own backfill - drop the trigger, rewrite the inner payload, then restore it.
DROP TRIGGER IF EXISTS encrypt_twofa_secret_trigger ON public.secret_metadata;

UPDATE public.secret_metadata
SET twofa_secret = 'v1:' || public.encrypt_text(public.decrypt_text(pg_catalog.substring(twofa_secret, 4)))
WHERE twofa_secret LIKE 'v1:%'
  AND pg_catalog.substring(twofa_secret, 4, 3) <> 'v2:';

CREATE TRIGGER encrypt_twofa_secret_trigger
    BEFORE INSERT OR UPDATE OF twofa_secret ON public.secret_metadata
    FOR EACH ROW EXECUTE FUNCTION public.encrypt_twofa_secret_trigger_fn();

-- ============================================================================
-- End of File: 20260913000000_authenticated_secret_encryption.sql
-- ============================================================================
