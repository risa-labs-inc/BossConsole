-- ============================================================================
-- BOSS Database Schema: authenticated encryption for the secrets vault
-- ============================================================================
-- File: 20260923171000_secret_authenticated_encryption_pgp.sql
-- Fixes: BossConsole#618 (defect 3 of 3, the last one; see that issue and the
-- "Deliberately NOT touched here" list in 20260914000000).
--
-- Problem: the v2 envelope is unauthenticated. encrypt_text stores
-- 'v2:' || base64(iv || AES-CBC(key, iv, plaintext)): pgcrypto's encrypt_iv
-- 'aes' type is raw AES-CBC with PKCS padding and no integrity tag, so a
-- flipped byte in a stored value is never detected as tampering. Classic CBC
-- bit-flipping applies: flipping bit n of block b corrupts block b and flips
-- the same bit of block b+1, so an attacker who can modify a stored column
-- can steer what decrypt_text returns for every block after the first. The
-- only "protection" is that the corrupted first block usually fails UTF-8
-- conversion - a decoding accident, not an integrity check. The v2
-- migration's own header records this as defect 3 and deliberately left it
-- open because it is a wire-format decision that deserves its own review.
-- This is that review.
--
-- Fix (the house-preferred shape): the v3 envelope is PGP symmetric
-- encryption, extensions.pgp_sym_encrypt with cipher-algo=aes256:
--   * a fresh RANDOM SESSION KEY per value, itself wrapped under an
--     S2K-iterated key derived from the master key with a per-message random
--     salt, so identical plaintexts encrypt to different ciphertexts (the v2
--     pattern-leakage invariant, preserved by construction);
--   * an OpenPGP Modification Detection Code inside the integrity-protected
--     packet: pgp_sym_decrypt verifies it BEFORE returning and raises
--     SQLSTATE 39000 ("Wrong key or corrupt data") on any flipped, truncated
--     or spliced byte - the tampered value refuses to decrypt instead of
--     surfacing attacker-influenced plaintext. Verified: mid-message flips,
--     tail flips, truncation, garbage bodies and wrong keys all raise 39000;
--   * S2K consumes the ENTIRE master-key string, so all 256 bits of a
--     64-hex-character key take part in key derivation. The legacy
--     encryption_key::bytea path used only the first 32 bytes of the string's
--     own characters (32 hex chars = 128 bits) - #618 defect 2, mitigated for
--     every v3 write without touching the deployed key in the Vault. The v2
--     and legacy DECRYPT paths keep ::bytea because they must keep reading
--     what was written before; only new ciphertext changes hands.
--
-- Wire format: 'v3:' || base64(pgp_sym_encrypt(...)). decrypt_text dispatches
-- on the prefix: v3 (PGP), then v2 (random-IV AES-CBC), then the original
-- zero-IV legacy layout, so every row written since 20251023000005 keeps
-- decrypting and this deploys without a flag day.
--
-- The backfill below re-encrypts the same three user-secret fields
-- 20260914000000 covered (secrets.password_encrypted,
-- secret_metadata.recovery_codes_encrypted, and the inner payload of
-- secret_metadata.twofa_secret), reusing its proven shape: one DO block
-- (atomic even under psql without -1), the rotation advisory lock (423, 1200)
-- and ACCESS EXCLUSIVE locks on both tables, an ephemeral-HMAC fingerprint of
-- every row's plaintext and application-reader output taken BEFORE the swap
-- and re-verified after, refusal before any mutation if a row is unreadable,
-- and v3 framing required of every stored value at verification time. Broker
-- columns (qbo_token_state, google_token_state) stay out of scope exactly as
-- in 20260914000000: their schemas are operated outside this repository, and
-- rotation re-encrypts them under their existing shape.
-- ops/rotate_master_encryption_key.sql is taught the v3 shape in this same
-- change so rotation keeps its "same shape, new key" contract for rows the
-- backfill upgrades.
--
-- The function bodies below are full replacements carrying the repo-wide
-- invariant that every SECURITY DEFINER function pins SET search_path TO ''
-- (the direction of the 20260916130000 part-1..part-4 family): every reference
-- is schema-qualified. CREATE OR REPLACE preserves owners and the client
-- revocations from 20260909120000_close_client_crypto_access.sql.
-- ============================================================================

CREATE OR REPLACE FUNCTION public.encrypt_text(plaintext text)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER SET search_path TO ''
AS $$
DECLARE
    encryption_key text;
BEGIN
    -- Parity with every prior body: NULL in, NULL out.
    IF plaintext IS NULL THEN
        RETURN NULL;
    END IF;

    encryption_key := public.get_encryption_key();

    -- PGP symmetric encryption: fresh random session key per message,
    -- wrapped under an S2K-iterated key (salted per message), AES-256
    -- cipher, compression off so short secrets carry no zlib header, and the
    -- OpenPGP MDC on (default; never pass disable-mdc). decrypt reads the
    -- algorithms from the packet headers, so only encrypt needs the options
    -- string.
    RETURN 'v3:' || pg_catalog.encode(
        extensions.pgp_sym_encrypt(
            plaintext,
            encryption_key,
            'cipher-algo=aes256, compress-algo=0, s2k-digest-algo=sha256'::text
        ),
        'base64'::text
    );
END;
$$;

ALTER FUNCTION public.encrypt_text(text) OWNER TO postgres;

CREATE OR REPLACE FUNCTION public.decrypt_text(ciphertext text)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER SET search_path TO ''
AS $$
DECLARE
    encryption_key text;
    envelope bytea;
    iv bytea;
    body bytea;
BEGIN
    IF ciphertext IS NULL THEN
        RETURN NULL;
    END IF;

    encryption_key := public.get_encryption_key();

    IF ciphertext LIKE 'v3:%' THEN
        -- PGP symmetric decrypt verifies the MDC before returning: a
        -- flipped, truncated or spliced value raises SQLSTATE 39000 instead
        -- of returning attacker-influenced plaintext.
        RETURN extensions.pgp_sym_decrypt(
            pg_catalog.decode(substring(ciphertext from 4), 'base64'::text),
            encryption_key
        );
    END IF;

    IF ciphertext LIKE 'v2:%' THEN
        -- Random-IV AES-CBC written by 20260914000000's encrypt_text.
        -- Unauthenticated bytes: no MDC exists for them, so this path stays
        -- a format dispatcher only; the backfill below migrates stored rows
        -- off it. substring(x FROM y [FOR z]) is SQL-standard trailing
        -- syntax the parser recognizes only on the bare function name -
        -- schema-qualifying it (pg_catalog.substring(...)) makes FROM a
        -- syntax error, since it is then parsed as an ordinary call instead.
        -- The parser resolves this SQL-standard form to pg_catalog.substring;
        -- it does not rely on search_path (20260914000000 did the same).
        envelope := pg_catalog.decode(substring(ciphertext from 4), 'base64'::text);
        iv := substring(envelope from 1 for 16);
        body := substring(envelope from 17);
        RETURN pg_catalog.convert_from(
            extensions.decrypt_iv(body, encryption_key::bytea, iv, 'aes'::text),
            'utf8'::name
        );
    END IF;

    -- Legacy path, unchanged: a zero-IV ciphertext written before
    -- 20260914000000, or any row the backfills have not reached yet.
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

ALTER FUNCTION public.decrypt_text(text) OWNER TO postgres;

-- ---- Backfill: upgrade every stored user-secret value to the authenticated
-- v3 envelope. Guarded by NOT LIKE 'v3:%' so a re-run (or a row already
-- migrated by a prior partial run) is a no-op, matching the idempotency
-- contract of 20260914000000 and 20260909000000. The twofa_secret input
-- trigger is disabled for the inner-payload rewrite, exactly as
-- 20260914000000 did, because it rejects any value that already carries the
-- 'v1:' storage envelope - correct for application writes, fatal here.
-- Keep the trigger-disabled window atomic even under psql without -1.
DO $upgrade$
DECLARE
    field record;
    stored record;
    unreadable bigint;
    failures text := '';
    fingerprint_key bytea := extensions.gen_random_bytes(32);
    plain text;
    app_plain text;
    expected bigint;
    actual bigint;
    mismatched bigint;
BEGIN
    -- Match rotation's lock order and serialize with its key-management operation.
    PERFORM pg_catalog.pg_advisory_xact_lock(423, 1200);
    LOCK TABLE public.secret_metadata, public.secrets IN ACCESS EXCLUSIVE MODE;
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_trigger
        WHERE tgrelid = 'public.secret_metadata'::regclass
          AND tgname = 'encrypt_twofa_secret_trigger' AND tgenabled = 'O') THEN
        RAISE EXCEPTION 'Authenticated encryption upgrade requires enabled encrypt_twofa_secret_trigger; restore the TOTP input trigger first';
    END IF;

    -- An ephemeral HMAC key prevents stored fingerprints being password hashes.
    CREATE TEMP TABLE v3_upgrade_fp (
        tbl text, col text, pk text, fp bytea, app_fp bytea,
        PRIMARY KEY (tbl, col, pk)
    ) ON COMMIT DROP;
    FOR field IN SELECT * FROM (VALUES
        ('secrets', 'password_encrypted', 'id', 1, 'public.decrypt_text'),
        ('secret_metadata', 'recovery_codes_encrypted', 'secret_id', 1, 'public.safe_decrypt_recovery_codes'),
        ('secret_metadata', 'twofa_secret', 'secret_id', 4, 'public.safe_decrypt_twofa_secret')
    ) AS fields(tbl, col, pk, start_at, reader) LOOP
        unreadable := 0;
        FOR stored IN EXECUTE pg_catalog.format(
            'SELECT %1$I::text AS pk, %2$I AS value FROM public.%3$I WHERE %2$I IS NOT NULL',
            field.pk, field.col, field.tbl) LOOP
            BEGIN
                plain := public.decrypt_text(pg_catalog.substr(stored.value, field.start_at));
                EXECUTE pg_catalog.format('SELECT %s($1)::text', field.reader)
                    INTO app_plain USING stored.value;
                IF plain IS NULL OR app_plain IS NULL THEN
                    RAISE EXCEPTION 'Unreadable value';
                END IF;
                INSERT INTO pg_temp.v3_upgrade_fp VALUES (
                    field.tbl, field.col, stored.pk,
                    extensions.hmac(pg_catalog.convert_to(plain, 'utf8'), fingerprint_key, 'sha256'),
                    extensions.hmac(pg_catalog.convert_to(app_plain, 'utf8'), fingerprint_key, 'sha256'));
            EXCEPTION WHEN OTHERS THEN
                unreadable := unreadable + 1;
            END;
        END LOOP;
        IF unreadable > 0 THEN
            failures := failures || pg_catalog.format('%s unreadable rows in public.%s; ',
                unreadable, field.tbl || '.' || field.col);
        END IF;
    END LOOP;
    IF failures <> '' THEN
        RAISE EXCEPTION 'Authenticated encryption upgrade refused: %recover these rows with the correct key before retrying', failures;
    END IF;

UPDATE public.secrets
SET password_encrypted = public.encrypt_text(public.decrypt_text(password_encrypted))
WHERE password_encrypted IS NOT NULL
  AND password_encrypted NOT LIKE 'v3:%';

UPDATE public.secret_metadata
SET recovery_codes_encrypted = public.encrypt_text(public.decrypt_text(recovery_codes_encrypted))
WHERE recovery_codes_encrypted IS NOT NULL
  AND recovery_codes_encrypted NOT LIKE 'v3:%';

-- twofa_secret carries its own 'v1:' envelope around an encrypt_text()
-- payload (20260909000000). Re-encrypt the inner payload and keep the outer
-- 'v1:' framing exactly as-is, since safe_decrypt_twofa_secret and the
-- insert/update trigger both key off it.
ALTER TABLE public.secret_metadata DISABLE TRIGGER encrypt_twofa_secret_trigger;

UPDATE public.secret_metadata
SET twofa_secret = 'v1:' || public.encrypt_text(public.decrypt_text(substring(twofa_secret from 4)))
WHERE twofa_secret LIKE 'v1:%'
  AND substring(twofa_secret from 4) NOT LIKE 'v3:%';

ALTER TABLE public.secret_metadata ENABLE TRIGGER encrypt_twofa_secret_trigger;
-- Verify every non-null cell, including already-v3 values and application
-- adapters which can return NULL or [] instead of throwing on bad framing.
FOR field IN SELECT * FROM (VALUES
    ('secrets', 'password_encrypted', 'id', 1, 'public.decrypt_text'),
    ('secret_metadata', 'recovery_codes_encrypted', 'secret_id', 1, 'public.safe_decrypt_recovery_codes'),
    ('secret_metadata', 'twofa_secret', 'secret_id', 4, 'public.safe_decrypt_twofa_secret')
) AS fields(tbl, col, pk, start_at, reader) LOOP
    SELECT count(*) INTO expected FROM pg_temp.v3_upgrade_fp f
        WHERE f.tbl = field.tbl AND f.col = field.col;
    actual := 0;
    mismatched := 0;
    FOR stored IN EXECUTE pg_catalog.format(
        'SELECT %1$I::text AS pk, %2$I AS value FROM public.%3$I WHERE %2$I IS NOT NULL',
        field.pk, field.col, field.tbl) LOOP
        actual := actual + 1;
        BEGIN
            plain := public.decrypt_text(pg_catalog.substr(stored.value, field.start_at));
            EXECUTE pg_catalog.format('SELECT %s($1)::text', field.reader)
                INTO app_plain USING stored.value;
            IF pg_catalog.substr(stored.value, field.start_at) NOT LIKE 'v3:%' OR NOT EXISTS (SELECT 1 FROM pg_temp.v3_upgrade_fp f
                WHERE f.tbl = field.tbl AND f.col = field.col AND f.pk = stored.pk
                  AND f.fp = extensions.hmac(pg_catalog.convert_to(plain, 'utf8'), fingerprint_key, 'sha256')
                  AND f.app_fp = extensions.hmac(pg_catalog.convert_to(app_plain, 'utf8'), fingerprint_key, 'sha256')) THEN
                mismatched := mismatched + 1;
            END IF;
        EXCEPTION WHEN OTHERS THEN
            mismatched := mismatched + 1;
        END;
    END LOOP;
    IF actual <> expected OR mismatched > 0 THEN
        RAISE EXCEPTION 'Authenticated encryption upgrade verification failed for public.%: expected % rows, found %, mismatched %; rolling back',
            field.tbl || '.' || field.col, expected, actual, mismatched;
    END IF;
END LOOP;
DROP TABLE pg_temp.v3_upgrade_fp;
END;
$upgrade$;
