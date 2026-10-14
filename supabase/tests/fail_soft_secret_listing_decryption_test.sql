-- BossConsole#620: a single undecryptable secret must not abort the whole
-- listing. All migrations are already applied by the time this file runs.
BEGIN;
SELECT no_plan();

DO $fixture$
DECLARE existing uuid;
BEGIN
    SELECT id INTO existing FROM vault.secrets WHERE name = 'master_encryption_key';
    IF existing IS NULL THEN
        PERFORM vault.create_secret('failsoft-test-key-0123456789abcdef012', 'master_encryption_key', 'pgTAP only');
    ELSE
        PERFORM vault.update_secret(existing, 'failsoft-test-key-0123456789abcdef012', 'master_encryption_key', 'pgTAP only');
    END IF;
END;
$fixture$;

-- ---- try_decrypt_text: NULL on damaged data, real plaintext otherwise.
SELECT is(public.try_decrypt_text(NULL), NULL::text, 'NULL input stays NULL');
SELECT is(public.try_decrypt_text('not even base64!!'), NULL::text, 'garbage input fails closed instead of raising');
SELECT is(public.try_decrypt_text('v2:dGVzdA=='), NULL::text, 'a well-formed but undecryptable v2 envelope fails closed instead of raising');
SELECT is(
    public.try_decrypt_text(public.encrypt_text('round-trip-me')),
    'round-trip-me',
    'a genuinely encrypted value still round trips'
);

-- ---- try_decrypt_text is locked down the same way safe_decrypt_twofa_secret is.
-- has_function_privilege(role, ...) checks the named role's grants directly, so
-- these do not depend on which role this connection currently runs as.
SELECT ok(NOT has_function_privilege('anon', 'public.try_decrypt_text(text)', 'EXECUTE'), 'anon cannot call the helper directly');
SELECT ok(NOT has_function_privilege('authenticated', 'public.try_decrypt_text(text)', 'EXECUTE'), 'authenticated cannot call the helper directly');
SELECT ok(NOT has_function_privilege('service_role', 'public.try_decrypt_text(text)', 'EXECUTE'), 'service_role cannot call the helper directly either');

-- ---- The listing RPCs: one corrupt row must not take down the whole page.
INSERT INTO auth.users (id, email) VALUES
    ('d1900000-0000-4000-8000-000000000001', 'failsoft-owner@pgtap.test');

-- The two corrupt values below are the same ones already proven to fail
-- try_decrypt_text above: not-base64 (fails at decode) and a well-formed but
-- too-short v2 envelope (fails at the IV/AES stage). Reusing them here keeps
-- this fixture from resting on a third, unverified failure mode.
INSERT INTO public.secrets (id, user_id, website, username, password_encrypted) VALUES
    ('d1900000-0000-4000-8000-000000000011', 'd1900000-0000-4000-8000-000000000001', 'failsoft.test', 'good', public.encrypt_text('good-password')),
    ('d1900000-0000-4000-8000-000000000012', 'd1900000-0000-4000-8000-000000000001', 'failsoft.test', 'bad', 'not even base64!!');

INSERT INTO public.secret_metadata (secret_id, recovery_codes_encrypted) VALUES
    ('d1900000-0000-4000-8000-000000000011', public.encrypt_text('["code-a","code-b"]')),
    ('d1900000-0000-4000-8000-000000000012', 'v2:dGVzdA==');

SELECT set_config('request.jwt.claims', '{"sub":"d1900000-0000-4000-8000-000000000001","role":"authenticated"}', true);
SET LOCAL ROLE authenticated;

SELECT throws_ok($$ SELECT public.try_decrypt_text('anything') $$, '42501', NULL, 'direct execution as authenticated is denied');

-- get_user_secrets
SELECT lives_ok($$ SELECT * FROM public.get_user_secrets() $$, 'get_user_secrets does not raise with a corrupt row in the page');
SELECT is((SELECT count(*) FROM public.get_user_secrets()), 2::bigint, 'get_user_secrets still returns both rows');
SELECT is((SELECT password FROM public.get_user_secrets() WHERE id = 'd1900000-0000-4000-8000-000000000011'), 'good-password', 'the good row still decrypts');
SELECT is((SELECT password FROM public.get_user_secrets() WHERE id = 'd1900000-0000-4000-8000-000000000012'), ''::text, 'the corrupt row is blanked, not raised');
SELECT is((SELECT metadata->'recovery_codes' FROM public.get_user_secrets() WHERE id = 'd1900000-0000-4000-8000-000000000012'), '[]'::jsonb, 'corrupt recovery codes blank to an empty array');
SELECT is((SELECT metadata->'recovery_codes' FROM public.get_user_secrets() WHERE id = 'd1900000-0000-4000-8000-000000000011'), '["code-a","code-b"]'::jsonb, 'the good row''s recovery codes still decrypt');

-- search_user_secrets
SELECT lives_ok($$ SELECT * FROM public.search_user_secrets('failsoft') $$, 'search_user_secrets does not raise with a corrupt row in the results');
SELECT is((SELECT count(*) FROM public.search_user_secrets('failsoft')), 2::bigint, 'search_user_secrets still returns both rows');
SELECT is((SELECT password FROM public.search_user_secrets('failsoft') WHERE id = 'd1900000-0000-4000-8000-000000000012'), ''::text, 'the corrupt row is blanked in search results too');

-- get_user_secrets_with_shared
SELECT lives_ok($$ SELECT * FROM public.get_user_secrets_with_shared() $$, 'get_user_secrets_with_shared does not raise with a corrupt row in the page');
SELECT is((SELECT count(*) FROM public.get_user_secrets_with_shared()), 2::bigint, 'get_user_secrets_with_shared still returns both rows');
SELECT is((SELECT password FROM public.get_user_secrets_with_shared() WHERE id = 'd1900000-0000-4000-8000-000000000011'), 'good-password', 'the good row still decrypts via the shared RPC');
SELECT is((SELECT password FROM public.get_user_secrets_with_shared() WHERE id = 'd1900000-0000-4000-8000-000000000012'), ''::text, 'the corrupt row is blanked via the shared RPC, not raised');

RESET ROLE;
-- Missing keys and systemic errors must not turn the entire page into blanks.
CREATE OR REPLACE FUNCTION public.get_encryption_key() RETURNS text
LANGUAGE plpgsql SECURITY DEFINER AS $$ BEGIN
    RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = 'synthetic unavailable key';
END $$;
SELECT throws_ok($$SELECT * FROM public.get_user_secrets()$$, 'P0001', NULL::text,
    'missing key propagates through the listing');
CREATE OR REPLACE FUNCTION public.get_encryption_key() RETURNS text
LANGUAGE sql SECURITY DEFINER AS $$ SELECT ''::text $$;
SELECT throws_ok($$SELECT public.try_decrypt_text('invalid')$$, '22023', NULL::text,
    'empty key propagates');
CREATE OR REPLACE FUNCTION public.get_encryption_key() RETURNS text
LANGUAGE sql SECURITY DEFINER AS $$ SELECT 'synthetic-test-key'::text $$;
CREATE OR REPLACE FUNCTION public.decrypt_text(ciphertext text) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER AS $$ BEGIN
    RAISE EXCEPTION USING ERRCODE = ciphertext, MESSAGE = 'synthetic failure';
END $$;
SELECT throws_ok($$SELECT public.try_decrypt_text('42501')$$, '42501', NULL::text, 'privilege errors propagate');
SELECT throws_ok($$SELECT public.try_decrypt_text('42883')$$, '42883', NULL::text, 'missing decoder propagates');
SELECT throws_ok($$SELECT public.try_decrypt_text('XX000')$$, 'XX000', NULL::text, 'internal errors propagate');
SELECT is(public.try_decrypt_text('39000'), NULL::text, 'pgcrypto failures blank only the field');
SELECT is(public.try_decrypt_text('22P05'), NULL::text, 'untranslatable UTF-8 blanks only the field');
SELECT * FROM finish();
ROLLBACK;
