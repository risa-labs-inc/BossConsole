BEGIN;
SELECT no_plan();

INSERT INTO auth.users (id, email) VALUES
 ('fa000000-0000-0000-0000-000000000001', 'fluck-vault-alice@example.test'),
 ('fa000000-0000-0000-0000-000000000002', 'fluck-vault-bob@example.test');

-- Fake public keys of the right shape: 32 bytes, and a 65 byte point starting 0x04.
CREATE TEMP TABLE k ON COMMIT DROP AS SELECT
  encode(decode(repeat('11', 32), 'hex'), 'base64') AS link_a,
  encode(decode('04' || repeat('22', 64), 'hex'), 'base64') AS seal_a,
  encode(decode(repeat('33', 32), 'hex'), 'base64') AS link_b,
  encode(decode('04' || repeat('44', 64), 'hex'), 'base64') AS seal_b;
GRANT SELECT ON k TO authenticated;

-- Grants
SELECT ok(NOT has_function_privilege('authenticated', 'public.fluck_vault_rotate_instance(text,uuid,text,text,text)', 'EXECUTE'), 'signed-in clients cannot rotate keys directly');
SELECT ok(NOT has_function_privilege('anon', 'public.fluck_vault_rotate_instance(text,uuid,text,text,text)', 'EXECUTE'), 'anonymous callers cannot rotate keys');
SELECT ok(NOT has_function_privilege('authenticated', 'public.fluck_vault_set_instance_issuance(text,boolean)', 'EXECUTE'), 'signed-in clients cannot approve their own install');
SELECT ok(NOT has_function_privilege('anon', 'public.fluck_vault_set_instance_issuance(text,boolean)', 'EXECUTE'), 'anonymous callers cannot approve an install');
SELECT ok(NOT has_function_privilege('authenticated', 'public.fluck_vault_instance(text)', 'EXECUTE'), 'signed-in clients cannot read install rows');
SELECT ok(has_function_privilege('service_role', 'public.fluck_vault_rotate_instance(text,uuid,text,text,text)', 'EXECUTE'), 'the service role rotates after verifying a proof');

-- Registration through the user's own session, as the plugin does.
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'fa000000-0000-0000-0000-000000000001', true);
SELECT is(public.fluck_vault_register_my_instance('inst-alice-0123456789', (SELECT link_a FROM k), (SELECT seal_a FROM k)), 'ok', 'first registration needs only a session');
SELECT is(public.fluck_vault_register_my_instance('inst-alice-0123456789', (SELECT link_a FROM k), (SELECT seal_a FROM k)), 'ok', 'the same keys again are idempotent');
SELECT is(public.fluck_vault_register_my_instance('inst-alice-0123456789', (SELECT link_b FROM k), (SELECT seal_b FROM k)), 'rotation_requires_proof', 'a session alone cannot change an existing install''s keys');
SELECT set_config('request.jwt.claim.sub', 'fa000000-0000-0000-0000-000000000002', true);
SELECT is(public.fluck_vault_register_my_instance('inst-alice-0123456789', (SELECT link_b FROM k), (SELECT seal_b FROM k)), 'conflict', 'another user cannot take the id');
RESET ROLE;

SELECT is((SELECT link_public_key FROM public.fluck_vault_instances WHERE instance_id = 'inst-alice-0123456789'), (SELECT link_a FROM k), 'the refused re-key left the keys alone');
SELECT is((SELECT issuance_approved FROM public.fluck_vault_instance('inst-alice-0123456789')), false, 'a new install is not approved to mint');

-- Issuance: an unapproved install cannot create a request row, whatever the caller.
SELECT throws_ok($$
  SELECT public.fluck_vault_create(
    '00000000-0000-4000-8000-000000000001'::uuid, 'ws-test', 'cvv', NULL, NULL, 'purchase-1',
    'shop.example', 'Visa', '4242', 48732, 'USD', now() + interval '2 minutes', 'inst-alice-0123456789')
$$, '42501', NULL, 'an unapproved install cannot mint a payment page');
SELECT is((SELECT count(*)::int FROM public.fluck_vault_requests WHERE ws = 'ws-test'), 0, 'nothing was written');

SELECT ok(public.fluck_vault_set_instance_issuance('inst-alice-0123456789', true), 'the operator approves the install');
SELECT ok(public.fluck_vault_create(
    '00000000-0000-4000-8000-000000000002'::uuid, 'ws-test', 'cvv', NULL, NULL, 'purchase-1',
    'shop.example', 'Visa', '4242', 1200, 'JPY', now() + interval '2 minutes', 'inst-alice-0123456789'),
  'an approved install mints');
SELECT ok(public.fluck_vault_create(
    '00000000-0000-4000-8000-000000000003'::uuid, 'ws-test', 'vault', 'password', 'site', NULL,
    NULL, NULL, NULL, NULL, NULL, now() + interval '2 minutes', NULL),
  'the operator''s env-key rows need no approval');
SELECT throws_ok($$
  SELECT public.fluck_vault_create(
    '00000000-0000-4000-8000-000000000004'::uuid, 'ws-test', 'cvv', NULL, NULL, 'purchase-1',
    'shop.example', 'Visa', '4242', 1000000000001, 'USD', now() + interval '2 minutes', 'inst-alice-0123456789')
$$, '23514', NULL, 'an amount beyond the exact range is refused');

-- Rotation: compare-and-swap on the current link key, with the old key recorded.
SELECT is(public.fluck_vault_rotate_instance('inst-alice-0123456789', 'fa000000-0000-0000-0000-000000000001', (SELECT link_b FROM k), (SELECT link_b FROM k), (SELECT seal_b FROM k)), 'stale', 'a proof against a key that is not current is refused');
SELECT is(public.fluck_vault_rotate_instance('inst-alice-0123456789', 'fa000000-0000-0000-0000-000000000002', (SELECT link_a FROM k), (SELECT link_b FROM k), (SELECT seal_b FROM k)), 'conflict', 'another user cannot rotate it');
SELECT is(public.fluck_vault_rotate_instance('inst-alice-0123456789', 'fa000000-0000-0000-0000-000000000001', (SELECT link_a FROM k), (SELECT link_b FROM k), (SELECT seal_b FROM k)), 'ok', 'a proven rotation applies');
SELECT is((SELECT previous_link_public_key FROM public.fluck_vault_instances WHERE instance_id = 'inst-alice-0123456789'), (SELECT link_a FROM k), 'the previous link key is recorded');
SELECT ok((SELECT rotated_at IS NOT NULL FROM public.fluck_vault_instances WHERE instance_id = 'inst-alice-0123456789'), 'the rotation time is recorded');
SELECT is(public.fluck_vault_rotate_instance('inst-alice-0123456789', 'fa000000-0000-0000-0000-000000000001', (SELECT link_a FROM k), (SELECT link_a FROM k), (SELECT seal_a FROM k)), 'stale', 'a replayed proof for the old key does nothing');
SELECT is((SELECT issuance_approved FROM public.fluck_vault_instance('inst-alice-0123456789')), true, 'a proven rotation keeps the approval');

-- A pending row now describes with the rotated keys of its own install.
SELECT is((SELECT instance_seal_public_key FROM public.fluck_vault_describe('00000000-0000-4000-8000-000000000002'::uuid)), (SELECT seal_b FROM k), 'describe follows the current key');

-- Revoking approval stops minting again.
SELECT ok(public.fluck_vault_set_instance_issuance('inst-alice-0123456789', false), 'the operator withdraws approval');
SELECT throws_ok($$
  SELECT public.fluck_vault_create(
    '00000000-0000-4000-8000-000000000005'::uuid, 'ws-test', 'cvv', NULL, NULL, 'purchase-1',
    'shop.example', 'Visa', '4242', 100, 'USD', now() + interval '2 minutes', 'inst-alice-0123456789')
$$, '42501', NULL, 'a withdrawn approval stops minting');

SELECT * FROM finish();
ROLLBACK;
