-- BossConsole#618 (defect 1): encrypt_text/decrypt_text must no longer produce
-- deterministic ciphertext. All migrations are already applied by the time
-- this file runs, so the 20260914000000 backfill had nothing to convert on a
-- fresh database; the last block below re-runs 20260923171000_secret_
-- authenticated_encryption_pgp.sql's exact UPDATE statement against a row
-- inserted in the pre-v2 format to prove that statement's logic directly,
-- rather than only trusting that it ran once at migration time. The live
-- envelope is v3 (authenticated PGP) since 20260923171000; the deeper
-- adversarial invariants live in secret_authenticated_encryption_test.sql.
BEGIN;
SELECT no_plan();
DO $fixture$
DECLARE existing uuid;
BEGIN
    SELECT id INTO existing FROM vault.secrets WHERE name = 'master_encryption_key';
    IF existing IS NULL THEN
        PERFORM vault.create_secret('iv-test-key-0123456789abcdef01234567', 'master_encryption_key', 'pgTAP only');
    ELSE
        PERFORM vault.update_secret(existing, 'iv-test-key-0123456789abcdef01234567', 'master_encryption_key', 'pgTAP only');
    END IF;
END;
$fixture$;

-- ---- Determinism is broken: two encryptions of the same plaintext differ.
SELECT isnt(
    public.encrypt_text('same-password'),
    public.encrypt_text('same-password'),
    'two encryptions of the same plaintext produce different ciphertext'
);

-- ---- New envelope round-trips.
SELECT matches(public.encrypt_text('round-trip-me'), '^v3:', 'new ciphertext carries the v3 envelope');
SELECT is(
    public.decrypt_text(public.encrypt_text('round-trip-me')),
    'round-trip-me',
    'a v3-encrypted value round trips'
);

-- ---- Legacy (pre-migration, zero-IV) ciphertext still decrypts. Constructed
-- with the exact expression the original encrypt_text used, not through the
-- (now-replaced) function - this is what every row written before this
-- migration actually looks like on disk.
DO $$
DECLARE
    legacy_ciphertext text;
BEGIN
    legacy_ciphertext := pg_catalog.encode(
        extensions.encrypt('legacy-password'::bytea, public.get_encryption_key()::bytea, 'aes'::text),
        'base64'::text
    );
    PERFORM set_config('pgtap.legacy_ciphertext', legacy_ciphertext, true);
END;
$$;
SELECT is(
    public.decrypt_text(current_setting('pgtap.legacy_ciphertext')),
    'legacy-password',
    'a zero-IV ciphertext written before this migration still decrypts'
);
SELECT ok(
    current_setting('pgtap.legacy_ciphertext') NOT LIKE 'v2:%'
        AND current_setting('pgtap.legacy_ciphertext') NOT LIKE 'v3:%',
    'the legacy fixture really is in the old, unversioned format'
);

-- ---- The backfill statement converts a legacy row straight to the v3
-- envelope, and is idempotent (a second run leaves an already-migrated row
-- unchanged).
INSERT INTO auth.users (id, email) VALUES
    ('d1800000-0000-4000-8000-000000000001', 'iv-owner@pgtap.test');
INSERT INTO public.secrets (id, user_id, website, username, password_encrypted) VALUES
    ('d1800000-0000-4000-8000-000000000011', 'd1800000-0000-4000-8000-000000000001', 'iv.test', 'owner',
     pg_catalog.encode(extensions.encrypt('shared-password'::bytea, public.get_encryption_key()::bytea, 'aes'::text), 'base64'::text));

UPDATE public.secrets
SET password_encrypted = public.encrypt_text(public.decrypt_text(password_encrypted))
WHERE id = 'd1800000-0000-4000-8000-000000000011'
  AND password_encrypted NOT LIKE 'v3:%';

SELECT matches(
    (SELECT password_encrypted FROM public.secrets WHERE id = 'd1800000-0000-4000-8000-000000000011'),
    '^v3:',
    'the backfill statement upgrades a legacy row to the v3 envelope'
);
SELECT is(
    public.decrypt_text((SELECT password_encrypted FROM public.secrets WHERE id = 'd1800000-0000-4000-8000-000000000011')),
    'shared-password',
    'the upgraded row still decrypts to its original plaintext'
);

CREATE TEMP TABLE iv_backfill_before AS
    SELECT password_encrypted FROM public.secrets WHERE id = 'd1800000-0000-4000-8000-000000000011';

UPDATE public.secrets
SET password_encrypted = public.encrypt_text(public.decrypt_text(password_encrypted))
WHERE id = 'd1800000-0000-4000-8000-000000000011'
  AND password_encrypted NOT LIKE 'v3:%';

SELECT is(
    (SELECT password_encrypted FROM public.secrets WHERE id = 'd1800000-0000-4000-8000-000000000011'),
    (SELECT password_encrypted FROM iv_backfill_before),
    'the backfill guard is idempotent - an already-migrated row is left untouched'
);

SELECT * FROM finish();
ROLLBACK;
