-- ============================================================================
-- BOSS Database Schema: Randomize the IV for encrypt_text/decrypt_text
-- ============================================================================
-- File: 20260914000000_randomize_secret_encryption_iv.sql
-- Fixes: BossConsole#618 (defect 1 of 3 - see that issue for the other two).
--
-- encrypt_text() called the 3-arg extensions.encrypt(plaintext, key, 'aes'),
-- which pgcrypto documents as using an all-zero IV. Encryption was therefore a
-- pure function of (plaintext, key): two equal passwords produced byte-identical
-- ciphertext. Because create_secret() encrypts server-side and RLS lets a user
-- read their own password_encrypted back, any signed-in user was an encryption
-- oracle - `SELECT password_encrypted, count(*) FROM secrets GROUP BY 1 HAVING
-- count(*) > 1` names every set of users sharing a password, from a dump alone,
-- with no key required. The same defect applied to recovery_codes_encrypted and
-- (#417) twofa_secret, wherever two rows happened to hold equal plaintext.
--
-- Fix: encrypt_text now generates a fresh random 16-byte IV per call
-- (extensions.gen_random_bytes) and uses extensions.encrypt_iv/decrypt_iv,
-- storing 'v2:' || base64(iv || ciphertext) - the same versioned-envelope
-- convention 20260909000000_encrypt_totp.sql established for this exact
-- purpose. decrypt_text dispatches on the 'v2:' prefix and falls back to the
-- original zero-IV path for anything not yet migrated, so this deploys without
-- a flag day: existing ciphertext keeps decrypting right up until the backfill
-- below converts it, and nothing but encrypt_text/decrypt_text's own behavior
-- changes for any caller.
--
-- Plaintext encoding also changes from plaintext::bytea (which interprets
-- backslash/octal escapes) to convert_to(plaintext, 'utf8'), preserving literal
-- backslashes. Legacy bytes must decode as UTF-8: an old octal-escaped value
-- containing invalid UTF-8 refuses this upgrade and must be recovered first.
-- The backfill locks both core tables, blocking secret reads and writes for its
-- duration. Per-row HMACs verify raw plaintext and application-reader results
-- before commit; diagnostics contain only column names and counts.
--
-- Deliberately NOT touched here, so nobody assumes more was fixed than was:
--   - Defect 2 (encryption_key::bytea truncates the key to its first 32
--     *bytes of the string's own characters* - 32 hex characters is 128 bits,
--     not the documented 256). ops/rotate_master_encryption_key.sql's own
--     comment records that a deployed key may be hex OR base64, and this
--     migration cannot tell which is live in any given project's Vault - an
--     incorrect guess here would make get_encryption_key()'s result unusable
--     and break encryption/decryption for every row immediately on deploy.
--     Fixing defect 2 needs an operator who knows the deployed key's actual
--     encoding, not a guess baked into a migration. Left as GitHub#618's
--     still-open second defect.
--   - Defect 3 (no MAC/AEAD - a flipped ciphertext byte surfaces only as a
--     later UTF8 decode error, never as an authentication failure). Adding one
--     is a wire-format decision (where the tag lives, what key derives it)
--     that deserves its own review, not a rider on this fix.
-- ops/rotate_master_encryption_key.sql's re-encrypt loop operated on raw
-- ciphertext bytes directly (old key -> new key) and assumed the fixed,
-- no-IV, no-prefix legacy layout for every mapped column - after the backfill
-- below, the three columns above are 'v2:'-framed instead, so that script has
-- been updated in the same change as this migration to re-encrypt either
-- shape under its own envelope (a v2 row gets a fresh IV; a legacy row, such
-- as the still-unmigrated qbo_token_state/google_token_state columns, stays
-- zero-IV). Existing broker rows are NOT upgraded by this migration or by
-- rotation; only later application writes change their format. Their schemas
-- are operated outside this repository and need an explicit operator-owned
-- backfill. This change is scoped to the three user-secret fields in #618.
-- The inner v2 encryption version is independent of TOTP's outer v1 marker.
-- See that script's own comment for the detail.
-- ============================================================================

CREATE OR REPLACE FUNCTION "public"."encrypt_text"("plaintext" "text") RETURNS "text"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public, pg_catalog, extensions'
    AS $$
DECLARE
    encryption_key TEXT;
    iv bytea;
    ciphertext bytea;
BEGIN
    encryption_key := public.get_encryption_key();
    -- 16 bytes: pgcrypto's AES block size, and what decrypt below expects to
    -- split back off the front of the stored envelope.
    iv := extensions.gen_random_bytes(16);
    ciphertext := extensions.encrypt_iv(
        pg_catalog.convert_to(plaintext, 'utf8'),
        encryption_key::bytea,
        iv,
        'aes'::text
    );
    RETURN 'v2:' || pg_catalog.encode(iv || ciphertext, 'base64'::text);
END;
$$;

ALTER FUNCTION "public"."encrypt_text"("plaintext" "text") OWNER TO "postgres";

CREATE OR REPLACE FUNCTION "public"."decrypt_text"("ciphertext" "text") RETURNS "text"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public, pg_catalog, extensions'
    AS $$
DECLARE
    encryption_key TEXT;
    envelope bytea;
    iv bytea;
    body bytea;
BEGIN
    IF ciphertext IS NULL THEN
        RETURN NULL;
    END IF;

    encryption_key := public.get_encryption_key();

    IF ciphertext LIKE 'v2:%' THEN
        -- substring(x FROM y [FOR z]) is SQL-standard trailing syntax the parser recognizes only
        -- on the bare function name - schema-qualifying it (pg_catalog.substring(...)) makes FROM
        -- a syntax error, since it is then parsed as an ordinary call instead. The parser
        -- resolves this SQL-standard form to pg_catalog.substring; it does not rely
        -- on search_path (20260909000000_encrypt_totp.sql does the same).
        envelope := pg_catalog.decode(substring(ciphertext from 4), 'base64'::text);
        iv := substring(envelope from 1 for 16);
        body := substring(envelope from 17);
        RETURN pg_catalog.convert_from(
            extensions.decrypt_iv(body, encryption_key::bytea, iv, 'aes'::text),
            'utf8'::name
        );
    END IF;

    -- Legacy path, unchanged: a zero-IV ciphertext written before this
    -- migration, or any row the backfill below has not reached yet.
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

-- ---- Backfill: convert every existing zero-IV ciphertext to the versioned,
-- random-IV envelope. Guarded by "NOT LIKE 'v2:%'" so re-running this
-- migration (or a row already migrated by a prior partial run) is a no-op,
-- matching 20260909000000_encrypt_totp.sql's own idempotency contract.

-- Keep the trigger-disabled window atomic even under psql without -1.
DO $backfill$
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
        RAISE EXCEPTION 'IV upgrade requires enabled encrypt_twofa_secret_trigger; restore the TOTP input trigger first';
    END IF;

    -- An ephemeral HMAC key prevents stored fingerprints being password hashes.
    CREATE TEMP TABLE iv_upgrade_fp (
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
                INSERT INTO pg_temp.iv_upgrade_fp VALUES (
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
        RAISE EXCEPTION 'IV upgrade refused: %recover these rows with the correct key and valid UTF-8 before retrying', failures;
    END IF;

UPDATE public.secrets
SET password_encrypted = public.encrypt_text(public.decrypt_text(password_encrypted))
WHERE password_encrypted IS NOT NULL
  AND password_encrypted NOT LIKE 'v2:%';

UPDATE public.secret_metadata
SET recovery_codes_encrypted = public.encrypt_text(public.decrypt_text(recovery_codes_encrypted))
WHERE recovery_codes_encrypted IS NOT NULL
  AND recovery_codes_encrypted NOT LIKE 'v2:%';

-- twofa_secret carries its own 'v1:' envelope around an encrypt_text() payload
-- (20260909000000_encrypt_totp.sql). Re-encrypt the inner payload and keep the
-- outer 'v1:' framing exactly as-is, since safe_decrypt_twofa_secret and the
-- insert/update trigger both key off it. The trigger rejects any UPDATE whose
-- new value already carries a 'v1:' prefix ("TOTP input must be plaintext"),
-- which is correct for application writes and fatal for this backfill -
-- disable it for the duration, exactly as
-- ops/rotate_master_encryption_key.sql already does for the same reason.
ALTER TABLE public.secret_metadata DISABLE TRIGGER encrypt_twofa_secret_trigger;

UPDATE public.secret_metadata
SET twofa_secret = 'v1:' || public.encrypt_text(public.decrypt_text(substring(twofa_secret from 4)))
WHERE twofa_secret LIKE 'v1:%'
  AND substring(twofa_secret from 4) NOT LIKE 'v2:%';

ALTER TABLE public.secret_metadata ENABLE TRIGGER encrypt_twofa_secret_trigger;
-- Verify every non-null cell, including already-v2 values and application
-- adapters which can return NULL or [] instead of throwing on bad framing.
FOR field IN SELECT * FROM (VALUES
    ('secrets', 'password_encrypted', 'id', 1, 'public.decrypt_text'),
    ('secret_metadata', 'recovery_codes_encrypted', 'secret_id', 1, 'public.safe_decrypt_recovery_codes'),
    ('secret_metadata', 'twofa_secret', 'secret_id', 4, 'public.safe_decrypt_twofa_secret')
) AS fields(tbl, col, pk, start_at, reader) LOOP
    SELECT count(*) INTO expected FROM pg_temp.iv_upgrade_fp f
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
            IF pg_catalog.substr(stored.value, field.start_at) NOT LIKE 'v2:%' OR NOT EXISTS (SELECT 1 FROM pg_temp.iv_upgrade_fp f
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
        RAISE EXCEPTION 'IV upgrade verification failed for public.%: expected % rows, found %, mismatched %; rolling back',
            field.tbl || '.' || field.col, expected, actual, mismatched;
    END IF;
END LOOP;
DROP TABLE pg_temp.iv_upgrade_fp;
END;
$backfill$;
