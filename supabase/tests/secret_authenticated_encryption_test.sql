-- BossConsole#618 (defect 3): the secrets vault's encryption must be
-- authenticated. All migrations are applied before this file runs, so
-- encrypt_text emits the v3 PGP envelope (fresh session key + S2K salt per
-- value, OpenPGP MDC verified on decrypt) and decrypt_text dispatches
-- v3 -> v2 -> legacy zero-IV. This suite pins the adversarial invariants:
--   * identical plaintexts encrypt to different ciphertexts;
--   * a tampered ciphertext REFUSES to decrypt (SQLSTATE 39000) instead of
--     returning attacker-influenced plaintext - the CBC bit-flip is dead;
--   * a tamper failure leaks no plaintext, ciphertext or key material into
--     its error message;
--   * v2-era and zero-IV legacy ciphertexts keep decrypting (no flag day);
--   * fail-soft readers still swallow tampering (blank, not an abort);
--   * the backfill statements upgrade a stored v2 row to v3, idempotently.
BEGIN;
SELECT no_plan();
DO $fixture$
DECLARE existing uuid;
BEGIN
    SELECT id INTO existing FROM vault.secrets WHERE name = 'master_encryption_key';
    IF existing IS NULL THEN
        PERFORM vault.create_secret('authenc-test-key-0123456789abcdef01234567', 'master_encryption_key', 'pgTAP only');
    ELSE
        PERFORM vault.update_secret(existing, 'authenc-test-key-0123456789abcdef01234567', 'master_encryption_key', 'pgTAP only');
    END IF;
END;
$fixture$;

-- ---- Envelope and round-trip.
SELECT matches(public.encrypt_text('round-trip-me'), '^v3:', 'new ciphertext carries the v3 envelope');
SELECT is(
    public.decrypt_text(public.encrypt_text('round-trip-me')),
    'round-trip-me',
    'a v3-encrypted value round trips'
);
SELECT is(public.decrypt_text(public.encrypt_text('')), '', 'the empty string round trips');
SELECT is(
    public.decrypt_text(public.encrypt_text($q$a\back\slash and 雪, 300+ bytes of secret: $q$ || repeat('x', 300))),
    $q$a\back\slash and 雪, 300+ bytes of secret: $q$ || repeat('x', 300),
    'backslashes, Unicode and multi-KB values round trip'
);

-- ---- Determinism stays broken: fresh session key and salt per value.
SELECT isnt(
    public.encrypt_text('same-password'),
    public.encrypt_text('same-password'),
    'two encryptions of the same plaintext produce different ciphertext'
);
SELECT ok(
    (SELECT count(DISTINCT c) = 8 FROM
        (SELECT public.encrypt_text('dup') AS c FROM generate_series(1, 8)) s),
    'eight encryptions of one plaintext are eight distinct ciphertexts'
);

-- ---- Tampering refuses to decrypt: every flip position raises, none can
-- steer plaintext through the MDC.
DO $tamper$
DECLARE
    msg text;
    env bytea;
    tam bytea;
BEGIN
    msg := public.encrypt_text('attacker-controlled-plaintext');
    env := pg_catalog.decode(substring(msg from 4), 'base64');

    -- 1. Flip the last byte: inside the MDC hash.
    tam := pg_catalog.set_byte(env, pg_catalog.octet_length(env) - 1,
        255 - pg_catalog.get_byte(env, pg_catalog.octet_length(env) - 1));
    PERFORM set_config('pgtap.tam_tail', 'v3:' || pg_catalog.encode(tam, 'base64'), true);

    -- 2. Flip a middle byte: the classic CBC bit-flip position. Under the v2
    -- envelope this corrupted one block and silently flipped bits in the
    -- next; under v3 the MDC must reject it.
    tam := pg_catalog.set_byte(env, 20, 255 - pg_catalog.get_byte(env, 20));
    PERFORM set_config('pgtap.tam_mid', 'v3:' || pg_catalog.encode(tam, 'base64'), true);

    -- 3. Truncate the message, MDC packet gone.
    PERFORM set_config('pgtap.tam_trunc',
        'v3:' || pg_catalog.encode(substring(env from 1 for pg_catalog.octet_length(env) - 24), 'base64'), true);

    -- 4. Splice in random garbage as the whole body.
    PERFORM set_config('pgtap.tam_garbage',
        'v3:' || pg_catalog.encode(extensions.gen_random_bytes(32), 'base64'), true);
END
$tamper$;

SELECT throws_ok($$ SELECT public.decrypt_text(current_setting('pgtap.tam_tail')) $$, '39000', NULL,
    'flipping a byte of the MDC refuses to decrypt');
SELECT throws_ok($$ SELECT public.decrypt_text(current_setting('pgtap.tam_mid')) $$, '39000', NULL,
    'a mid-message bit flip, the CBC bit-flip position, refuses to decrypt');
SELECT throws_ok($$ SELECT public.decrypt_text(current_setting('pgtap.tam_trunc')) $$, '39000', NULL,
    'a truncated ciphertext refuses to decrypt');
SELECT throws_ok($$ SELECT public.decrypt_text(current_setting('pgtap.tam_garbage')) $$, '39000', NULL,
    'garbage bytes inside the v3 envelope refuse to decrypt');
SELECT throws_ok(
    $$ SELECT extensions.pgp_sym_decrypt(
           pg_catalog.decode(substring(public.encrypt_text('probe') from 4), 'base64'),
           'not-the-master-key') $$,
    '39000', NULL,
    'the wrong key refuses to decrypt');

-- ---- Fail-soft readers still swallow tampering: NULL, not an abort, and no
-- plaintext or key anywhere near the reason.
SELECT is(public.try_decrypt_text(current_setting('pgtap.tam_mid')), NULL::text,
    'try_decrypt_text returns NULL for a tampered v3 value');

DO $leak$
DECLARE err text := '';
BEGIN
    BEGIN
        PERFORM public.decrypt_text(current_setting('pgtap.tam_tail'));
    EXCEPTION WHEN OTHERS THEN
        err := SQLERRM;
    END;
    PERFORM set_config('pgtap.tamper_err', err, true);
END
$leak$;
SELECT ok(
    position('attacker-controlled-plaintext' in current_setting('pgtap.tamper_err')) = 0,
    'the tamper failure message leaks no plaintext');
SELECT ok(
    position(public.get_encryption_key() in current_setting('pgtap.tamper_err')) = 0,
    'the tamper failure message leaks no key material');
SELECT matches(current_setting('pgtap.tamper_err'), 'Wrong key or corrupt data',
    'the tamper failure message is pgcrypto''s fixed refusal string');

-- ---- Backward compatibility: v2 and legacy rows keep decrypting.
DO $v2fix$
DECLARE
    iv bytea := extensions.gen_random_bytes(16);
    ct bytea;
    legacy text;
BEGIN
    ct := extensions.encrypt_iv(pg_catalog.convert_to('v2-era-value', 'utf8'),
        public.get_encryption_key()::bytea, iv, 'aes');
    PERFORM set_config('pgtap.v2_fixture', 'v2:' || pg_catalog.encode(iv || ct, 'base64'), true);
    legacy := pg_catalog.encode(
        extensions.encrypt('legacy-password'::bytea, public.get_encryption_key()::bytea, 'aes'),
        'base64');
    PERFORM set_config('pgtap.legacy_fixture', legacy, true);
END
$v2fix$;
SELECT is(
    public.decrypt_text(current_setting('pgtap.v2_fixture')),
    'v2-era-value',
    'a v2-era random-IV AES-CBC ciphertext still decrypts');
SELECT ok(current_setting('pgtap.v2_fixture') NOT LIKE 'v3:%',
    'the v2 fixture really is v2-framed, not re-misread as v3');
SELECT is(
    public.decrypt_text(current_setting('pgtap.legacy_fixture')),
    'legacy-password',
    'a zero-IV ciphertext written before 20260914000000 still decrypts');

-- ---- The TOTP storage path: the v1 wrapper around a v3 payload round trips
-- through the application reader.
SELECT is(
    public.safe_decrypt_twofa_secret('v1:' || public.encrypt_text('JBSWY3DPEHPK3PXP')),
    'JBSWY3DPEHPK3PXP',
    'a v1-wrapped v3 TOTP secret round trips through the application reader');

-- ---- NULL handling parity with every prior body.
SELECT is(public.encrypt_text(NULL), NULL, 'encrypt_text(NULL) is NULL');
SELECT is(public.decrypt_text(NULL), NULL, 'decrypt_text(NULL) is NULL');

-- ---- The backfill statements upgrade a stored v2 row to the v3 envelope
-- and are idempotent. (Replicated inline, the same approach as
-- secret_encryption_iv_randomization_test.sql, because pg_prove mounts test
-- files in isolation and cannot \ir a migration.)
INSERT INTO auth.users (id, email) VALUES
    ('d1800000-0000-4000-8000-000000000031', 'authenc-owner@pgtap.test');
INSERT INTO public.secrets (id, user_id, website, username, password_encrypted) VALUES
    ('d1800000-0000-4000-8000-000000000032', 'd1800000-0000-4000-8000-000000000031', 'authenc.test', 'owner',
     current_setting('pgtap.v2_fixture'));

UPDATE public.secrets
SET password_encrypted = public.encrypt_text(public.decrypt_text(password_encrypted))
WHERE password_encrypted IS NOT NULL
  AND password_encrypted NOT LIKE 'v3:%';

SELECT matches(
    (SELECT password_encrypted FROM public.secrets WHERE id = 'd1800000-0000-4000-8000-000000000032'),
    '^v3:',
    'the backfill statement upgrades a v2 row to the v3 envelope'
);
SELECT is(
    public.decrypt_text((SELECT password_encrypted FROM public.secrets WHERE id = 'd1800000-0000-4000-8000-000000000032')),
    'v2-era-value',
    'the upgraded row still decrypts to its original plaintext'
);

CREATE TEMP TABLE authenc_backfill_before AS
    SELECT password_encrypted FROM public.secrets WHERE id = 'd1800000-0000-4000-8000-000000000032';

UPDATE public.secrets
SET password_encrypted = public.encrypt_text(public.decrypt_text(password_encrypted))
WHERE password_encrypted IS NOT NULL
  AND password_encrypted NOT LIKE 'v3:%';

SELECT is(
    (SELECT password_encrypted FROM public.secrets WHERE id = 'd1800000-0000-4000-8000-000000000032'),
    (SELECT password_encrypted FROM authenc_backfill_before),
    'the backfill guard is idempotent - an already-migrated row is left untouched'
);

-- ---- End to end: a tampered STORED row surfaces as a blank password through
-- the fail-soft listing RPC, never as attacker-influenced plaintext, and
-- does not abort the listing for the row's other fields.
UPDATE public.secrets
SET password_encrypted = current_setting('pgtap.tam_mid')
WHERE id = 'd1800000-0000-4000-8000-000000000032';

SELECT set_config('request.jwt.claims',
    '{"sub":"d1800000-0000-4000-8000-000000000031","role":"authenticated"}', true);
SELECT is(
    (SELECT password FROM public.get_user_secrets(50, 0) WHERE website = 'authenc.test'),
    '',
    'a tampered stored row yields a blank password, not attacker-influenced plaintext'
);
SELECT is(
    (SELECT username FROM public.get_user_secrets(50, 0) WHERE website = 'authenc.test'),
    'owner',
    'the tampered row keeps its authorized row and other fields in the listing'
);

SELECT * FROM finish();
ROLLBACK;
