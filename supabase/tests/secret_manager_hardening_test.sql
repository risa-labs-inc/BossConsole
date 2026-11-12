-- First run: python3 scripts/test/prepare-totp-migration-fixtures.py
-- 20261111190000 / 20261111190100: sealed notes, v1 output unchanged, durable
-- access log, delete logging, backfill. Rolled back; never run on live data.
BEGIN;
SELECT no_plan();

DO $fixture$
DECLARE existing uuid;
BEGIN
    SELECT id INTO existing FROM vault.secrets WHERE name = 'master_encryption_key';
    IF existing IS NULL THEN
        PERFORM vault.create_secret('smh-test-key-0123456789abcdef0123456', 'master_encryption_key', 'pgTAP only');
    ELSE
        PERFORM vault.update_secret(existing, 'smh-test-key-0123456789abcdef0123456', 'master_encryption_key', 'pgTAP only');
    END IF;
END;
$fixture$;

-- ---- Grants.
SELECT ok(NOT has_function_privilege('authenticated', 'public.secret_notes_sensitive(uuid)', 'EXECUTE'), 'helper is internal');
SELECT ok(NOT has_function_privilege('authenticated', 'public.secrets_seal_notes_trigger_fn()', 'EXECUTE'), 'seal trigger fn is internal');
SELECT ok(NOT has_function_privilege('authenticated', 'public.secret_tags_seal_notes_trigger_fn()', 'EXECUTE'), 'tag trigger fn is internal');
SELECT ok(NOT has_column_privilege('authenticated', 'public.secrets', 'notes_encrypted', 'INSERT'), 'clients cannot insert sealed notes');
SELECT ok(NOT has_column_privilege('authenticated', 'public.secrets', 'notes_encrypted', 'UPDATE'), 'clients cannot write sealed notes');
SELECT ok(NOT has_column_privilege('anon', 'public.secrets', 'notes_encrypted', 'UPDATE'), 'anon cannot write sealed notes');

-- ---- v1 signatures unchanged.
SELECT has_function('public', 'get_user_secrets', ARRAY['integer','integer'], 'v1 get_user_secrets kept');
SELECT has_function('public', 'search_user_secrets', ARRAY['text','integer','integer'], 'v1 search kept');
SELECT has_function('public', 'get_user_secrets_with_shared', ARRAY['integer','integer'], 'v1 with_shared kept');
SELECT has_function('public', 'delete_secret', ARRAY['uuid'], 'v1 delete kept');

-- ---- Fixtures: owner, reader (user share), outsider.
INSERT INTO auth.users (id, email) VALUES
    ('d1a00000-0000-4000-8000-000000000001', 'smh-owner@pgtap.test'),
    ('d1a00000-0000-4000-8000-000000000002', 'smh-reader@pgtap.test'),
    ('d1a00000-0000-4000-8000-000000000003', 'smh-outsider@pgtap.test');

SELECT set_config('request.jwt.claims', '{"sub":"d1a00000-0000-4000-8000-000000000001","role":"authenticated"}', true);
SET LOCAL ROLE authenticated;

SELECT is((public.create_secret('bank.test', 'Visa 4242', '4242424242424242',
        '{"name":"Ada Lovelace","line1":"1 Main St"}', NULL, ARRAY['kind:card','fluck-ws:w1']))->>'success',
    'true', 'card created through v1');
SELECT is((public.create_secret('mail.test', 'ada', 'pw-mail', 'plain note', NULL, ARRAY['kind:password']))->>'success',
    'true', 'login created through v1');
SELECT is((public.create_secret('fluck/w1/github/GH_TOKEN', 'token', 'ghp_secret', NULL, NULL, ARRAY['fluck','connector']))->>'success',
    'true', 'connector token created');

RESET ROLE;
CREATE TEMP TABLE ids AS
    SELECT
        (SELECT id FROM public.secrets WHERE website = 'bank.test') AS card,
        (SELECT id FROM public.secrets WHERE website = 'mail.test') AS login,
        (SELECT id FROM public.secrets WHERE website = 'fluck/w1/github/GH_TOKEN') AS token;
GRANT SELECT ON ids TO authenticated;

-- ---- Sealing at rest.
SELECT is((SELECT notes FROM public.secrets WHERE id = (SELECT card FROM ids)), NULL, 'card notes are not stored in plaintext');
SELECT isnt((SELECT notes_encrypted FROM public.secrets WHERE id = (SELECT card FROM ids)), NULL, 'card notes are sealed');
SELECT is(public.decrypt_text((SELECT notes_encrypted FROM public.secrets WHERE id = (SELECT card FROM ids))),
    '{"name":"Ada Lovelace","line1":"1 Main St"}', 'sealed notes round trip');
SELECT is((SELECT notes FROM public.secrets WHERE id = (SELECT login FROM ids)), 'plain note', 'non-sensitive notes stay plaintext');
SELECT is((SELECT notes_encrypted FROM public.secrets WHERE id = (SELECT login FROM ids)), NULL, 'non-sensitive rows have no sealed form');

-- ---- v1 output is unchanged for sealed rows.
SELECT set_config('request.jwt.claims', '{"sub":"d1a00000-0000-4000-8000-000000000001","role":"authenticated"}', true);
SET LOCAL ROLE authenticated;
SELECT is((SELECT notes FROM public.get_user_secrets() WHERE id = (SELECT card FROM ids)),
    '{"name":"Ada Lovelace","line1":"1 Main St"}', 'get_user_secrets returns sealed notes as before');
SELECT is((SELECT notes FROM public.get_user_secrets_with_shared() WHERE id = (SELECT card FROM ids)),
    '{"name":"Ada Lovelace","line1":"1 Main St"}', 'get_user_secrets_with_shared returns sealed notes as before');
SELECT is((SELECT notes FROM public.search_user_secrets('bank') WHERE id = (SELECT card FROM ids)),
    '{"name":"Ada Lovelace","line1":"1 Main St"}', 'search_user_secrets returns sealed notes as before');
SELECT is((SELECT password FROM public.get_user_secrets() WHERE id = (SELECT card FROM ids)), '4242424242424242', 'v1 password unchanged');

-- ---- update_secret keeps notes sealed, and clearing notes clears the sealed form.
SELECT is((public.update_secret((SELECT card FROM ids), 'bank.test', 'Visa 4242', '4242424242424242',
        '{"name":"Ada King"}', NULL, ARRAY['kind:card','fluck-ws:w1']))->>'success', 'true', 'card updated');
RESET ROLE;
SELECT is((SELECT notes FROM public.secrets WHERE id = (SELECT card FROM ids)), NULL, 'updated card notes stay sealed');
SELECT is(public.decrypt_text((SELECT notes_encrypted FROM public.secrets WHERE id = (SELECT card FROM ids))),
    '{"name":"Ada King"}', 'updated sealed notes hold the new value');
SELECT set_config('request.jwt.claims', '{"sub":"d1a00000-0000-4000-8000-000000000001","role":"authenticated"}', true);
SET LOCAL ROLE authenticated;

-- Adding a sensitive tag to a plaintext row seals it.
SELECT is((public.update_secret((SELECT login FROM ids), 'mail.test', 'ada', 'pw-mail', 'plain note', NULL,
        ARRAY['kind:password','notes:encrypted']))->>'success', 'true', 'login opted into sealing');
RESET ROLE;
SELECT is((SELECT notes FROM public.secrets WHERE id = (SELECT login FROM ids)), NULL, 'notes:encrypted seals an existing row');
SELECT set_config('request.jwt.claims', '{"sub":"d1a00000-0000-4000-8000-000000000001","role":"authenticated"}', true);
SET LOCAL ROLE authenticated;

-- A direct PostgREST-style write of notes on a card row is sealed too.
UPDATE public.secrets SET notes = 'direct write' WHERE id = (SELECT card FROM ids);
RESET ROLE;
SELECT is((SELECT notes FROM public.secrets WHERE id = (SELECT card FROM ids)), NULL, 'direct client write is sealed');
SELECT is(public.decrypt_text((SELECT notes_encrypted FROM public.secrets WHERE id = (SELECT card FROM ids))),
    'direct write', 'direct client write round trips');
SELECT set_config('request.jwt.claims', '{"sub":"d1a00000-0000-4000-8000-000000000001","role":"authenticated"}', true);
SET LOCAL ROLE authenticated;
SELECT throws_ok($$ UPDATE public.secrets SET notes_encrypted = 'x' WHERE id = (SELECT card FROM ids) $$,
    '42501', NULL, 'a client cannot import ciphertext into notes_encrypted');

-- ---- A share keeps working on a sealed row: the reader gets the notes through v1.
RESET ROLE;
INSERT INTO public.secret_shares (secret_id, shared_with_user_id, shared_by, access_level)
VALUES ((SELECT card FROM ids), 'd1a00000-0000-4000-8000-000000000002', 'd1a00000-0000-4000-8000-000000000001', 'read');
SELECT set_config('request.jwt.claims', '{"sub":"d1a00000-0000-4000-8000-000000000002","role":"authenticated"}', true);
SET LOCAL ROLE authenticated;
SELECT is((SELECT notes FROM public.get_user_secrets_with_shared() WHERE id = (SELECT card FROM ids)),
    'direct write', 'a share recipient reads sealed notes through v1');
SELECT set_config('request.jwt.claims', '{"sub":"d1a00000-0000-4000-8000-000000000003","role":"authenticated"}', true);
SELECT is((SELECT count(*) FROM public.get_user_secrets_with_shared() WHERE id = (SELECT card FROM ids)),
    0::bigint, 'an outsider still sees nothing');
RESET ROLE;

-- A pre-existing audit row on the card, as share_secret would write.
INSERT INTO public.secret_access_log (secret_id, user_id, operation, access_granted_via)
VALUES ((SELECT card FROM ids), 'd1a00000-0000-4000-8000-000000000001', 'share', 'owner');

-- ---- Clients still cannot write the log.
SELECT set_config('request.jwt.claims', '{"sub":"d1a00000-0000-4000-8000-000000000003","role":"authenticated"}', true);
SET LOCAL ROLE authenticated;
SELECT throws_ok($$ INSERT INTO public.secret_access_log (secret_id, user_id, operation) VALUES (gen_random_uuid(), auth.uid(), 'view') $$,
    '42501', NULL, 'clients cannot forge audit rows');

-- ---- Delete keeps the trail and logs the delete.
SELECT set_config('request.jwt.claims', '{"sub":"d1a00000-0000-4000-8000-000000000001","role":"authenticated"}', true);
SELECT is((public.delete_secret((SELECT card FROM ids)))->>'success', 'true', 'owner deletes the card');
SELECT is((public.delete_secret((SELECT card FROM ids)))->>'error', 'Secret not found or access denied', 'second delete unchanged message');
RESET ROLE;
SELECT is((SELECT count(*) FROM public.secret_access_log WHERE secret_id = (SELECT card FROM ids) AND operation = 'share'),
    1::bigint, 'earlier audit rows survive deletion of the secret');
SELECT is((SELECT count(*) FROM public.secret_access_log WHERE secret_id = (SELECT card FROM ids) AND operation = 'delete'),
    1::bigint, 'delete is logged');
SELECT ok(NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'secret_access_log_secret_id_fkey'),
    'secret_id is a plain value');

-- ---- Backfill: legacy plaintext card notes are sealed by the real migration.
ALTER TABLE public.secrets DISABLE TRIGGER secrets_seal_notes_trigger;
ALTER TABLE public.secret_tags DISABLE TRIGGER secret_tags_seal_notes_trigger;
INSERT INTO public.secrets (id, user_id, website, username, password_encrypted, notes) VALUES
    ('d1a00000-0000-4000-8000-000000000021', 'd1a00000-0000-4000-8000-000000000001', 'legacy.test', 'card', public.encrypt_text('4111111111111111'), 'legacy holder'),
    ('d1a00000-0000-4000-8000-000000000022', 'd1a00000-0000-4000-8000-000000000001', 'legacy.test', 'login', public.encrypt_text('pw'), 'legacy note');
INSERT INTO public.secret_tags (secret_id, tag) VALUES
    ('d1a00000-0000-4000-8000-000000000021', 'kind:card'),
    ('d1a00000-0000-4000-8000-000000000022', 'kind:password');
ALTER TABLE public.secrets ENABLE TRIGGER secrets_seal_notes_trigger;
ALTER TABLE public.secret_tags ENABLE TRIGGER secret_tags_seal_notes_trigger;
SELECT is((SELECT notes FROM public.secrets WHERE id = 'd1a00000-0000-4000-8000-000000000021'), 'legacy holder', 'legacy card is plaintext before backfill');
\ir .migration-fixtures/20261111190100_seal_sensitive_secret_notes.inc
SELECT is((SELECT notes FROM public.secrets WHERE id = 'd1a00000-0000-4000-8000-000000000021'), NULL, 'backfill seals legacy card notes');
SELECT is(public.decrypt_text((SELECT notes_encrypted FROM public.secrets WHERE id = 'd1a00000-0000-4000-8000-000000000021')),
    'legacy holder', 'backfilled notes round trip');
SELECT is((SELECT notes FROM public.secrets WHERE id = 'd1a00000-0000-4000-8000-000000000022'), 'legacy note', 'backfill leaves non-sensitive rows alone');
\ir .migration-fixtures/20261111190100_seal_sensitive_secret_notes.inc
SELECT is(public.decrypt_text((SELECT notes_encrypted FROM public.secrets WHERE id = 'd1a00000-0000-4000-8000-000000000021')),
    'legacy holder', 'backfill is idempotent');

SELECT * FROM finish();
ROLLBACK;
