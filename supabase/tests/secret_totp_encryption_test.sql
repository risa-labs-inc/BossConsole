-- First run: python3 scripts/test/prepare-totp-migration-fixtures.py
-- Exercises a pre-migration database as well as ordinary writes. All fixtures,
-- function replacements and trigger changes are rolled back; never run on live data.
BEGIN;
SELECT no_plan();
SELECT vault.create_secret('totp-test-key-0123456789abcdef0123', 'master_encryption_key', 'pgTAP only');
INSERT INTO auth.users (id, email) VALUES
    ('d1700000-0000-4000-8000-000000000001', 'totp-owner@pgtap.test'),
    ('d1700000-0000-4000-8000-000000000002', 'totp-recipient@pgtap.test'),
    ('d1700000-0000-4000-8000-000000000003', 'totp-outsider@pgtap.test');
INSERT INTO public.secrets (id, user_id, website, username, password_encrypted) VALUES
    ('d1700000-0000-4000-8000-000000000011', 'd1700000-0000-4000-8000-000000000001', 'totp.test', 'owner', public.encrypt_text('password')),
    ('d1700000-0000-4000-8000-000000000012', 'd1700000-0000-4000-8000-000000000001', 'totp.test', 'second', public.encrypt_text('password'));

-- Reconstruct the deployed RPC and plaintext storage, then apply ONLY the new
-- migration. This catches edits to historical migrations that fresh resets hide.
\ir .migration-fixtures/20260907000000_secrets_paging_tiebreaker.inc
SELECT has_trigger('public', 'secret_metadata', 'encrypt_twofa_secret_trigger',
    'fresh migrations install the encryption trigger');
DROP TRIGGER IF EXISTS encrypt_twofa_secret_trigger ON public.secret_metadata;
INSERT INTO public.secret_metadata (secret_id, twofa_enabled, twofa_type, twofa_secret)
VALUES ('d1700000-0000-4000-8000-000000000011', true, 'app', 'JBSWY3DPEHPK3PXP');
\ir .migration-fixtures/20260909000000_encrypt_totp.inc
SELECT matches((SELECT twofa_secret FROM public.secret_metadata WHERE secret_id = 'd1700000-0000-4000-8000-000000000011'),
    '^v1:', 'backfill uses the versioned storage format');
SELECT isnt((SELECT twofa_secret FROM public.secret_metadata WHERE secret_id = 'd1700000-0000-4000-8000-000000000011'),
    'JBSWY3DPEHPK3PXP', 'upgrade removes the legacy plaintext');
SELECT is((SELECT public.safe_decrypt_twofa_secret(twofa_secret) FROM public.secret_metadata WHERE secret_id = 'd1700000-0000-4000-8000-000000000011'),
    'JBSWY3DPEHPK3PXP', 'backfilled seed round trips');
CREATE TEMP TABLE totp_before AS SELECT twofa_secret FROM public.secret_metadata WHERE secret_id = 'd1700000-0000-4000-8000-000000000011';
\ir .migration-fixtures/20260909000000_encrypt_totp.inc
SELECT is((SELECT twofa_secret FROM public.secret_metadata WHERE secret_id = 'd1700000-0000-4000-8000-000000000011'),
    (SELECT twofa_secret FROM totp_before), 'reapplying migration does not double encrypt');

SELECT ok(NOT has_function_privilege('anon', 'public.get_user_secrets_with_shared(integer,integer)', 'EXECUTE'), 'anonymous role has no shared-secret RPC grant');
SELECT ok(NOT has_function_privilege('anon', 'public.safe_decrypt_twofa_secret(text)', 'EXECUTE'), 'anonymous role cannot call helper despite default grants');
SELECT ok(NOT has_function_privilege('authenticated', 'public.safe_decrypt_twofa_secret(text)', 'EXECUTE'), 'authenticated role cannot call helper despite default grants');
SELECT ok(NOT has_function_privilege('service_role', 'public.safe_decrypt_twofa_secret(text)', 'EXECUTE'), 'service role cannot directly call helper');
SELECT is(public.safe_decrypt_twofa_secret(NULL), NULL::text, 'null stays null');
SELECT is(public.safe_decrypt_twofa_secret('plaintext'), NULL::text, 'unrecognized storage fails closed');
SELECT is(public.safe_decrypt_twofa_secret('v1:corrupt'), NULL::text, 'malformed ciphertext fails closed');

SELECT set_config('request.jwt.claims', '{"sub":"d1700000-0000-4000-8000-000000000001","role":"authenticated"}', true);
SET LOCAL ROLE authenticated;
SELECT is((SELECT metadata->>'twofa_secret' FROM public.get_user_secrets_with_shared() WHERE id = 'd1700000-0000-4000-8000-000000000011'),
    'JBSWY3DPEHPK3PXP', 'upgraded RPC returns plaintext to owner');
SELECT throws_ok($$ SELECT public.safe_decrypt_twofa_secret('v1:corrupt') $$, '42501', NULL, 'direct helper execution is denied');
SELECT throws_ok($$ INSERT INTO public.secret_metadata (secret_id, twofa_secret) VALUES ('d1700000-0000-4000-8000-000000000012', 'v1:plaintext') $$,
    '22023', NULL, 'INSERT rejects caller supplied envelopes');
INSERT INTO public.secret_metadata (secret_id, twofa_secret) VALUES ('d1700000-0000-4000-8000-000000000012', 'GEZDGNBVGY3TQOJQ');
SELECT throws_ok($$ INSERT INTO public.secret_metadata (secret_id, twofa_secret) VALUES ('d1700000-0000-4000-8000-000000000012', 'MZXW6YTBOI') ON CONFLICT (secret_id) DO UPDATE SET twofa_secret = excluded.twofa_secret $$,
    '22023', NULL, 'changed TOTP upserts are explicitly unsupported; use plain UPDATE');
SELECT is((SELECT metadata->>'twofa_secret' FROM public.get_user_secrets_with_shared() WHERE id = 'd1700000-0000-4000-8000-000000000012'),
    'GEZDGNBVGY3TQOJQ', 'authenticated insert encrypts and RPC decrypts');
UPDATE public.secret_metadata SET twofa_secret = 'MZXW6YTBOI' WHERE secret_id = 'd1700000-0000-4000-8000-000000000012';
SELECT is((SELECT metadata->>'twofa_secret' FROM public.get_user_secrets_with_shared() WHERE id = 'd1700000-0000-4000-8000-000000000012'),
    'MZXW6YTBOI', 'authenticated update replaces encrypted seed');
UPDATE public.secret_metadata SET twofa_secret = twofa_secret WHERE secret_id = 'd1700000-0000-4000-8000-000000000012';
SELECT is((SELECT metadata->>'twofa_secret' FROM public.get_user_secrets_with_shared() WHERE id = 'd1700000-0000-4000-8000-000000000012'),
    'MZXW6YTBOI', 'no-op update preserves ciphertext');
SELECT throws_ok($$ UPDATE public.secret_metadata SET twofa_secret = 'v1:plaintext' WHERE secret_id = 'd1700000-0000-4000-8000-000000000012' $$,
    '22023', NULL, 'a prefix cannot bypass encryption');
SELECT throws_ok($$ UPDATE public.secret_metadata SET twofa_secret = (SELECT twofa_secret FROM public.secret_metadata WHERE secret_id = 'd1700000-0000-4000-8000-000000000011') WHERE secret_id = 'd1700000-0000-4000-8000-000000000012' $$,
    '22023', NULL, 'a different row ciphertext cannot be imported for decryption');
UPDATE public.secret_metadata SET twofa_secret = NULL WHERE secret_id = 'd1700000-0000-4000-8000-000000000012';
SELECT is((SELECT metadata->>'twofa_secret' FROM public.get_user_secrets_with_shared() WHERE id = 'd1700000-0000-4000-8000-000000000012'), NULL::text, 'clearing a seed works');
RESET ROLE;
INSERT INTO public.secret_shares (secret_id, shared_by, shared_with_user_id, access_level)
VALUES ('d1700000-0000-4000-8000-000000000011', 'd1700000-0000-4000-8000-000000000001', 'd1700000-0000-4000-8000-000000000002', 'read');
SELECT set_config('request.jwt.claims', '{"sub":"d1700000-0000-4000-8000-000000000002","role":"authenticated"}', true);
SET LOCAL ROLE authenticated;
SELECT is((SELECT metadata->>'twofa_secret' FROM public.get_user_secrets_with_shared() WHERE id = 'd1700000-0000-4000-8000-000000000011'),
    'JBSWY3DPEHPK3PXP', 'authorized recipient receives plaintext');
RESET ROLE;
UPDATE public.secret_shares SET expires_at = now() - interval '1 second' WHERE secret_id = 'd1700000-0000-4000-8000-000000000011';
SET LOCAL ROLE authenticated;
SELECT is((SELECT count(*) FROM public.get_user_secrets_with_shared() WHERE id = 'd1700000-0000-4000-8000-000000000011'), 0::bigint, 'expired share cannot read seed');
RESET ROLE;
SELECT set_config('request.jwt.claims', '{"sub":"d1700000-0000-4000-8000-000000000003","role":"authenticated"}', true);
SET LOCAL ROLE authenticated;
SELECT is((SELECT count(*) FROM public.get_user_secrets_with_shared() WHERE id = 'd1700000-0000-4000-8000-000000000011'), 0::bigint, 'unrelated user cannot read seed');
RESET ROLE;
SELECT set_config('request.jwt.claims', '{"role":"anon"}', true);
SET LOCAL ROLE anon;
SELECT throws_ok($$ SELECT * FROM public.get_user_secrets_with_shared() $$,
    '42501', NULL, 'anonymous RPC execution is denied');
RESET ROLE;
SELECT * FROM finish();
ROLLBACK;
