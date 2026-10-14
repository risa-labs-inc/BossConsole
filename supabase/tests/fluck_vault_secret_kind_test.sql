BEGIN;
SELECT no_plan();

INSERT INTO auth.users (id, email) VALUES
 ('fb000000-0000-0000-0000-000000000001', 'fluck-vault-secret@example.test');

CREATE TEMP TABLE k ON COMMIT DROP AS SELECT
  encode(decode(repeat('55', 32), 'hex'), 'base64') AS link_a,
  encode(decode('04' || repeat('66', 64), 'hex'), 'base64') AS seal_a,
  encode(decode(repeat('77', 32), 'hex'), 'base64') AS link_b,
  encode(decode('04' || repeat('88', 64), 'hex'), 'base64') AS seal_b,
  decode('01' || repeat('ab', 120), 'hex') AS blob;
GRANT SELECT ON k TO authenticated;

-- Grants
SELECT ok(NOT has_function_privilege('authenticated', 'public.fluck_vault_create(uuid,text,text,text,text,text,text,text,text,bigint,text,timestamp with time zone,text,text,text)', 'EXECUTE'), 'signed-in clients cannot create requests');
SELECT ok(NOT has_function_privilege('anon', 'public.fluck_vault_claim(text,text)', 'EXECUTE'), 'anonymous callers cannot claim');
SELECT ok(has_function_privilege('service_role', 'public.fluck_vault_create(uuid,text,text,text,text,text,text,text,text,bigint,text,timestamp with time zone,text,text,text)', 'EXECUTE'), 'the service role creates requests');

-- Minting a secret on the env key (no install).
SELECT ok(public.fluck_vault_create(
    'b0000000-0000-4000-8000-000000000001'::uuid, 'ws-secret', 'vault', 'secret', NULL, NULL,
    NULL, NULL, NULL, NULL, NULL, now() + interval '5 minutes', NULL),
  'a thirteen-argument positional call still resolves, for a secret');
SELECT ok(public.fluck_vault_create(
    'b0000000-0000-4000-8000-000000000002'::uuid, 'ws-secret', 'vault', 'secret', NULL, NULL,
    NULL, NULL, NULL, NULL, NULL, now() + interval '5 minutes', NULL, 'notion', 'NOTION_TOKEN'),
  'a secret carries a connector and an env name');
SELECT is((SELECT connector FROM public.fluck_vault_describe('b0000000-0000-4000-8000-000000000002')), 'notion', 'describe returns the connector');
SELECT is((SELECT connector FROM public.fluck_vault_describe('b0000000-0000-4000-8000-000000000001')), NULL, 'describe returns no connector when none was set');

-- Metadata is per kind: only a secret carries it, the cvv row (kind NULL) included.
SELECT throws_ok($$
  SELECT public.fluck_vault_create(
    'b0000000-0000-4000-8000-000000000010'::uuid, 'ws-secret', 'cvv', NULL, NULL, 'purchase-1',
    'shop.example', 'Visa', '4242', 1200, 'USD', now() + interval '2 minutes', NULL, 'notion', NULL)
$$, '23514', NULL, 'a cvv row with a connector is refused');
SELECT throws_ok($$
  SELECT public.fluck_vault_create(
    'b0000000-0000-4000-8000-000000000011'::uuid, 'ws-secret', 'cvv', NULL, NULL, 'purchase-1',
    'shop.example', 'Visa', '4242', 1200, 'USD', now() + interval '2 minutes', NULL, NULL, 'NOTION_TOKEN')
$$, '23514', NULL, 'a cvv row with an env name is refused');
SELECT throws_ok($$
  SELECT public.fluck_vault_create(
    'b0000000-0000-4000-8000-000000000012'::uuid, 'ws-secret', 'vault', 'password', 'site', NULL,
    NULL, NULL, NULL, NULL, NULL, now() + interval '5 minutes', NULL, 'github', NULL)
$$, '23514', NULL, 'a password row with a connector is refused');
SELECT throws_ok($$
  SELECT public.fluck_vault_create(
    'b0000000-0000-4000-8000-000000000013'::uuid, 'ws-secret', 'vault', 'card', NULL, NULL,
    NULL, NULL, NULL, NULL, NULL, now() + interval '5 minutes', NULL, NULL, 'CARD_NUMBER')
$$, '23514', NULL, 'a card row with an env name is refused');
SELECT throws_ok($$
  SELECT public.fluck_vault_create(
    'b0000000-0000-4000-8000-000000000014'::uuid, 'ws-secret', 'vault', NULL, NULL, NULL,
    NULL, NULL, NULL, NULL, NULL, now() + interval '5 minutes', NULL)
$$, '23514', NULL, 'a vault row without a kind is refused');
SELECT throws_ok($$
  SELECT public.fluck_vault_create(
    'b0000000-0000-4000-8000-000000000015'::uuid, 'ws-secret', 'vault', 'secret', NULL, NULL,
    NULL, NULL, NULL, NULL, NULL, now() + interval '5 minutes', NULL, 'slack', NULL)
$$, '23514', NULL, 'a connector outside the vocabulary is refused');
SELECT throws_ok($$
  SELECT public.fluck_vault_create(
    'b0000000-0000-4000-8000-000000000016'::uuid, 'ws-secret', 'vault', 'secret', NULL, NULL,
    NULL, NULL, NULL, NULL, NULL, now() + interval '5 minutes', NULL, NULL, 'notion_token')
$$, '23514', NULL, 'a lower-case env name is refused');
SELECT throws_ok($$
  SELECT public.fluck_vault_create(
    'b0000000-0000-4000-8000-000000000017'::uuid, 'ws-secret', 'vault', 'secret', NULL, NULL,
    NULL, NULL, NULL, NULL, NULL, now() + interval '5 minutes', NULL, NULL, E'NOTION_TOKEN\nFOO')
$$, '23514', NULL, 'an env name with a newline is refused');
SELECT ok(public.fluck_vault_create(
    'b0000000-0000-4000-8000-000000000018'::uuid, 'ws-secret', 'cvv', NULL, NULL, 'purchase-1',
    'shop.example', 'Visa', '4242', 1200, 'USD', now() + interval '2 minutes', NULL),
  'a cvv row without metadata still mints');

-- Store and claim carry the metadata for a secret, and nothing for a password.
SELECT ok(public.fluck_vault_create(
    'b0000000-0000-4000-8000-000000000020'::uuid, 'ws-secret', 'vault', 'password', 'site', NULL,
    NULL, NULL, NULL, NULL, NULL, now() + interval '5 minutes', NULL),
  'a password mints');
SELECT is(public.fluck_vault_store('b0000000-0000-4000-8000-000000000002', (SELECT blob FROM k), 'cookie'), 'secret', 'a secret stores');
SELECT is(public.fluck_vault_store('b0000000-0000-4000-8000-000000000020', (SELECT blob FROM k), 'cookie'), 'password', 'a password stores');
SELECT is(public.fluck_vault_store('b0000000-0000-4000-8000-000000000002', (SELECT blob FROM k), 'cookie'), NULL, 'a secret link is single use');
SELECT is((SELECT env FROM public.fluck_vault_inbox WHERE jti = 'b0000000-0000-4000-8000-000000000002'), 'NOTION_TOKEN', 'the staged blob carries the env name');
SELECT throws_ok($$
  UPDATE public.fluck_vault_inbox SET connector = 'notion'
  WHERE jti = 'b0000000-0000-4000-8000-000000000020'
$$, '23514', NULL, 'a staged password cannot carry a connector');

CREATE TEMP TABLE claimed ON COMMIT DROP AS
  SELECT * FROM public.fluck_vault_claim('ws-secret', NULL);
SELECT is((SELECT count(*)::int FROM claimed), 2, 'both staged blobs are claimed');
SELECT is((SELECT connector || ':' || env FROM claimed WHERE kind = 'secret'), 'notion:NOTION_TOKEN', 'a claimed secret carries its connector and env name');
SELECT ok((SELECT connector IS NULL AND env IS NULL FROM claimed WHERE kind = 'password'), 'a claimed password carries no metadata');
SELECT is((SELECT count(*)::int FROM public.fluck_vault_claim('ws-secret', NULL)), 0, 'a claim drains the queue');

-- #1830's install rules apply to a secret row: issuance, rotation expiry and revocation.
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'fb000000-0000-0000-0000-000000000001', true);
SELECT is(public.fluck_vault_register_my_instance('inst-secret-0123456789', (SELECT link_a FROM k), (SELECT seal_a FROM k)), 'ok', 'the install registers');
RESET ROLE;

SELECT throws_ok($$
  SELECT public.fluck_vault_create(
    'b0000000-0000-4000-8000-000000000030'::uuid, 'ws-secret', 'vault', 'secret', NULL, NULL,
    NULL, NULL, NULL, NULL, NULL, now() + interval '5 minutes', 'inst-secret-0123456789', 'github', 'GH_TOKEN')
$$, '42501', NULL, 'an unapproved install cannot mint a secret');

SELECT ok(public.fluck_vault_set_instance_issuance('inst-secret-0123456789', true), 'the operator approves the install');
SELECT ok(public.fluck_vault_create(
    'b0000000-0000-4000-8000-000000000031'::uuid, 'ws-secret', 'vault', 'secret', NULL, NULL,
    NULL, NULL, NULL, NULL, NULL, now() + interval '5 minutes', 'inst-secret-0123456789', 'github', 'GH_TOKEN'),
  'an approved install mints a secret');
SELECT is((SELECT connector FROM public.fluck_vault_describe('b0000000-0000-4000-8000-000000000031')), 'github', 'the install''s secret link describes');

SELECT is(public.fluck_vault_rotate_instance('inst-secret-0123456789', 'fb000000-0000-0000-0000-000000000001', (SELECT link_a FROM k), (SELECT link_b FROM k), (SELECT seal_b FROM k)), 'ok', 'the install rotates');
SELECT is((SELECT count(*)::int FROM public.fluck_vault_describe('b0000000-0000-4000-8000-000000000031')), 0, 'a rotation expires the open secret link');
SELECT is(public.fluck_vault_store('b0000000-0000-4000-8000-000000000031', (SELECT blob FROM k), 'cookie'), NULL, 'the expired secret link cannot be consumed');
SELECT throws_ok($$
  SELECT public.fluck_vault_create(
    'b0000000-0000-4000-8000-000000000032'::uuid, 'ws-secret', 'vault', 'secret', NULL, NULL,
    NULL, NULL, NULL, NULL, NULL, now() + interval '5 minutes', 'inst-secret-0123456789', 'github', 'GH_TOKEN')
$$, '42501', NULL, 'a rotated install cannot mint a secret until approved again');

SELECT ok(public.fluck_vault_set_instance_issuance('inst-secret-0123456789', true), 'the operator approves the new keys');
SELECT ok(public.fluck_vault_create(
    'b0000000-0000-4000-8000-000000000033'::uuid, 'ws-secret', 'vault', 'secret', NULL, NULL,
    NULL, NULL, NULL, NULL, NULL, now() + interval '5 minutes', 'inst-secret-0123456789', 'notion', NULL),
  'the re-approved install mints a secret');
SELECT is(public.fluck_vault_store('b0000000-0000-4000-8000-000000000033', (SELECT blob FROM k), 'cookie'), 'secret', 'the secret is staged');

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'fb000000-0000-0000-0000-000000000001', true);
SELECT is(public.fluck_vault_revoke_my_instance('inst-secret-0123456789'), 'ok', 'the owner revokes the install');
RESET ROLE;

SELECT is((SELECT count(*)::int FROM public.fluck_vault_inbox WHERE jti = 'b0000000-0000-4000-8000-000000000033'), 0, 'revocation drops the staged secret');
SELECT is((SELECT count(*)::int FROM public.fluck_vault_claim('ws-secret', 'inst-secret-0123456789')), 0, 'nothing is claimable after revocation');

SELECT * FROM finish();
ROLLBACK;
