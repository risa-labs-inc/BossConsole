-- pgTAP tests for Fluck-to-Fluck A2A peering: links and call tickets (20261014220000).
-- Run with: supabase test db
BEGIN;
SELECT plan(39);
INSERT INTO auth.users (id, email) VALUES
 ('a2a00000-0000-0000-0000-00000000000a', 'a2a-alice@example.test'),
 ('a2a00000-0000-0000-0000-00000000000b', 'a2a-bob@example.test'),
 ('a2a00000-0000-0000-0000-00000000000c', 'a2a-carol@example.test');

-- ---- grants and function shape ----
SELECT ok(NOT has_function_privilege('anon', 'public.fluck_peer_list()', 'EXECUTE')
      AND NOT has_function_privilege('anon', 'public.fluck_peer_request(text)', 'EXECUTE')
      AND NOT has_function_privilege('anon', 'public.fluck_a2a_mint_ticket(text,uuid,text)', 'EXECUTE')
      AND NOT has_function_privilege('anon', 'public.fluck_a2a_consume_ticket(text,text)', 'EXECUTE'), 'anon cannot call the RPCs');
SELECT ok(has_function_privilege('authenticated', 'public.fluck_peer_list()', 'EXECUTE')
      AND has_function_privilege('authenticated', 'public.fluck_a2a_consume_ticket(text,text)', 'EXECUTE'), 'authenticated can call the RPCs');
SELECT ok(
    (SELECT bool_and(prosecdef AND 'search_path=""' = ANY(proconfig)) FROM pg_proc WHERE oid IN (
        'public.fluck_peer_request(text)'::regprocedure, 'public.fluck_peer_pending()'::regprocedure,
        'public.fluck_peer_claim_notice(uuid)'::regprocedure, 'public.fluck_peer_respond(uuid,boolean)'::regprocedure,
        'public.fluck_peer_revoke(uuid)'::regprocedure, 'public.fluck_peer_links()'::regprocedure,
        'public.fluck_peer_list()'::regprocedure, 'public.fluck_a2a_mint_ticket(text,uuid,text)'::regprocedure,
        'public.fluck_a2a_consume_ticket(text,text)'::regprocedure)),
    'RPCs are security definer with an empty search_path');
SELECT ok(NOT has_table_privilege('authenticated', 'public.fluck_a2a_tickets', 'SELECT')
      AND NOT has_table_privilege('authenticated', 'public.fluck_peer_links', 'INSERT')
      AND NOT has_table_privilege('authenticated', 'public.fluck_peer_links', 'UPDATE'), 'no direct ticket access and no direct link writes');
SELECT ok((SELECT relrowsecurity FROM pg_class WHERE oid = 'public.fluck_peer_links'::regclass)
      AND (SELECT relrowsecurity FROM pg_class WHERE oid = 'public.fluck_a2a_tickets'::regclass), 'both tables enable RLS');

-- ---- each user's Flucks ----
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'a2a00000-0000-0000-0000-00000000000a', true);
SELECT public.fluck_web_upsert_instance('a-mac', 'Alice Mac', 'Fluck', 'https://a-mac.example.test', NULL);
SELECT public.fluck_web_upsert_instance('a-dgx', 'Alice DGX', 'Fluck', 'https://a-dgx.example.test', NULL);
SELECT set_config('request.jwt.claim.sub', 'a2a00000-0000-0000-0000-00000000000b', true);
SELECT public.fluck_web_upsert_instance('b-mac', 'Bob Mac', 'Fluck', 'https://b-mac.example.test', NULL);
SELECT set_config('request.jwt.claim.sub', 'a2a00000-0000-0000-0000-00000000000c', true);
SELECT public.fluck_web_upsert_instance('c-mac', 'Carol Mac', 'Fluck', 'https://c-mac.example.test', NULL);

-- ---- the same user's Flucks need no link ----
SELECT set_config('request.jwt.claim.sub', 'a2a00000-0000-0000-0000-00000000000a', true);
SELECT is((SELECT array_agg(instance_id ORDER BY instance_id) FROM public.fluck_peer_list()), ARRAY['a-dgx','a-mac'], 'alice lists only her own Flucks');
SELECT throws_ok($q$ SELECT public.fluck_a2a_mint_ticket('a-mac', 'a2a00000-0000-0000-0000-00000000000b', 'b-mac') $q$, 'P0001', 'peer_unavailable', 'no ticket to an unlinked user');
CREATE TEMP TABLE t (name text PRIMARY KEY, token text) ON COMMIT DROP;
GRANT ALL ON t TO authenticated;
INSERT INTO t SELECT 'self', public.fluck_a2a_mint_ticket('a-mac', 'a2a00000-0000-0000-0000-00000000000a', 'a-dgx');
SELECT ok((SELECT token ~ '^[A-Za-z0-9_-]{43}$' FROM t WHERE name = 'self'), 'a same-user ticket is minted');
SELECT throws_ok($q$ SELECT * FROM public.fluck_a2a_consume_ticket((SELECT token FROM t WHERE name = 'self'), 'a-mac') $q$, 'P0001', 'ticket_invalid', 'the wrong instance cannot redeem it');
SELECT set_config('request.jwt.claim.sub', 'a2a00000-0000-0000-0000-00000000000b', true);
SELECT throws_ok($q$ SELECT * FROM public.fluck_a2a_consume_ticket((SELECT token FROM t WHERE name = 'self'), 'a-dgx') $q$, 'P0001', 'ticket_invalid', 'another account cannot redeem it');
SELECT set_config('request.jwt.claim.sub', 'a2a00000-0000-0000-0000-00000000000a', true);
SELECT is((SELECT row(instance_id, same_user)::text FROM public.fluck_a2a_consume_ticket((SELECT token FROM t WHERE name = 'self'), 'a-dgx')),
    '(a-mac,t)', 'the target redeems it: the caller is alice''s Mac, same user');
SELECT throws_ok($q$ SELECT * FROM public.fluck_a2a_consume_ticket((SELECT token FROM t WHERE name = 'self'), 'a-dgx') $q$, 'P0001', 'ticket_invalid', 'a ticket works once');

-- ---- a link request ----
SELECT lives_ok($q$ SELECT public.fluck_peer_request('A2A-Bob@example.test ') $q$, 'alice asks bob (email normalised)');
SELECT lives_ok($q$ SELECT public.fluck_peer_request('nobody@example.test') $q$, 'an unknown email is a silent no-op');
SELECT lives_ok($q$ SELECT public.fluck_peer_request('a2a-alice@example.test') $q$, 'asking yourself is a silent no-op');
RESET ROLE;
SELECT is((SELECT count(*)::int FROM public.fluck_peer_links WHERE requester_id = 'a2a00000-0000-0000-0000-00000000000a'), 1, 'only the real request created a row');
SET LOCAL ROLE authenticated;
SELECT is((SELECT count(*)::int FROM public.fluck_peer_pending()), 0, 'the requester has nothing to answer');
SELECT set_config('request.jwt.claim.sub', 'a2a00000-0000-0000-0000-00000000000b', true);
SELECT is((SELECT requester_email FROM public.fluck_peer_pending()), 'a2a-alice@example.test', 'bob sees alice''s request');
INSERT INTO t SELECT 'request', id::text FROM public.fluck_peer_pending();
SELECT is(public.fluck_peer_claim_notice((SELECT token FROM t WHERE name = 'request')::uuid), true, 'the first claim wins');
SELECT is(public.fluck_peer_claim_notice((SELECT token FROM t WHERE name = 'request')::uuid), false, 'a second claim loses');
SELECT set_config('request.jwt.claim.sub', 'a2a00000-0000-0000-0000-00000000000a', true);
SELECT throws_ok($q$ SELECT public.fluck_peer_respond((SELECT token FROM t WHERE name = 'request')::uuid, true) $q$, 'P0001', 'request_invalid', 'the requester cannot accept');
SELECT set_config('request.jwt.claim.sub', 'a2a00000-0000-0000-0000-00000000000c', true);
SELECT throws_ok($q$ SELECT public.fluck_peer_respond((SELECT token FROM t WHERE name = 'request')::uuid, true) $q$, 'P0001', 'request_invalid', 'a third party cannot accept');
SELECT is((SELECT count(*)::int FROM public.fluck_peer_links), 0, 'carol reads no link rows');

-- ---- accepted ----
SELECT set_config('request.jwt.claim.sub', 'a2a00000-0000-0000-0000-00000000000b', true);
SELECT lives_ok($q$ SELECT public.fluck_peer_respond((SELECT token FROM t WHERE name = 'request')::uuid, true) $q$, 'bob accepts');
SELECT is((SELECT array_agg(instance_id ORDER BY instance_id) FROM public.fluck_peer_list() WHERE NOT same_user), ARRAY['a-dgx','a-mac'], 'bob now lists alice''s Flucks');
SELECT set_config('request.jwt.claim.sub', 'a2a00000-0000-0000-0000-00000000000c', true);
SELECT is((SELECT array_agg(instance_id) FROM public.fluck_peer_list()), ARRAY['c-mac'], 'carol still lists only her own');
SELECT set_config('request.jwt.claim.sub', 'a2a00000-0000-0000-0000-00000000000a', true);
SELECT is((SELECT row(peer_email, status, outgoing)::text FROM public.fluck_peer_links()), '(a2a-bob@example.test,accepted,t)', 'alice sees the accepted link');
INSERT INTO t SELECT 'cross', public.fluck_a2a_mint_ticket('a-mac', 'a2a00000-0000-0000-0000-00000000000b', 'b-mac');
INSERT INTO t SELECT 'unlisted', public.fluck_a2a_mint_ticket('laptop-1', 'a2a00000-0000-0000-0000-00000000000b', 'b-mac');
SELECT throws_ok($q$ SELECT public.fluck_a2a_mint_ticket('bad id', 'a2a00000-0000-0000-0000-00000000000b', 'b-mac') $q$, 'P0001', 'peer_unavailable', 'a malformed calling instance id is refused');
SELECT set_config('request.jwt.claim.sub', 'a2a00000-0000-0000-0000-00000000000c', true);
SELECT throws_ok($q$ SELECT * FROM public.fluck_a2a_consume_ticket((SELECT token FROM t WHERE name = 'cross'), 'c-mac') $q$, 'P0001', 'ticket_invalid', 'carol cannot redeem a ticket for bob');
SELECT set_config('request.jwt.claim.sub', 'a2a00000-0000-0000-0000-00000000000b', true);
SELECT is((SELECT row(email, instance_id, same_user)::text FROM public.fluck_a2a_consume_ticket((SELECT token FROM t WHERE name = 'cross'), 'b-mac')),
    '(a2a-alice@example.test,a-mac,f)', 'bob redeems it: the caller is alice, another user');
SELECT is((SELECT instance_id FROM public.fluck_a2a_consume_ticket((SELECT token FROM t WHERE name = 'unlisted'), 'b-mac')), 'laptop-1', 'an unlisted calling Fluck may call out');
INSERT INTO t SELECT 'reverse', public.fluck_a2a_mint_ticket('b-mac', 'a2a00000-0000-0000-0000-00000000000a', 'a-dgx');
SELECT set_config('request.jwt.claim.sub', 'a2a00000-0000-0000-0000-00000000000a', true);
SELECT lives_ok($q$ SELECT * FROM public.fluck_a2a_consume_ticket((SELECT token FROM t WHERE name = 'reverse'), 'a-dgx') $q$, 'the link works both ways');

-- ---- expiry and revoke ----
INSERT INTO t SELECT 'expired', public.fluck_a2a_mint_ticket('a-mac', 'a2a00000-0000-0000-0000-00000000000b', 'b-mac');
INSERT INTO t SELECT 'in-flight', public.fluck_a2a_mint_ticket('a-mac', 'a2a00000-0000-0000-0000-00000000000b', 'b-mac');
RESET ROLE;
UPDATE public.fluck_a2a_tickets SET expires_at = now() - interval '1 second'
 WHERE ticket_hash = encode(extensions.digest((SELECT token FROM t WHERE name = 'expired'), 'sha256'), 'hex');
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'a2a00000-0000-0000-0000-00000000000b', true);
SELECT throws_ok($q$ SELECT * FROM public.fluck_a2a_consume_ticket((SELECT token FROM t WHERE name = 'expired'), 'b-mac') $q$, 'P0001', 'ticket_invalid', 'an expired ticket is refused');
SELECT lives_ok($q$ SELECT public.fluck_peer_revoke('a2a00000-0000-0000-0000-00000000000a') $q$, 'bob revokes');
SELECT throws_ok($q$ SELECT * FROM public.fluck_a2a_consume_ticket((SELECT token FROM t WHERE name = 'in-flight'), 'b-mac') $q$, 'P0001', 'ticket_invalid', 'a ticket in flight dies with the link');
SELECT set_config('request.jwt.claim.sub', 'a2a00000-0000-0000-0000-00000000000a', true);
SELECT throws_ok($q$ SELECT public.fluck_a2a_mint_ticket('a-mac', 'a2a00000-0000-0000-0000-00000000000b', 'b-mac') $q$, 'P0001', 'peer_unavailable', 'no new tickets after revoke');
SELECT is((SELECT count(*)::int FROM public.fluck_peer_list() WHERE NOT same_user), 0, 'bob''s Fluck is gone from alice''s list');
SELECT lives_ok($q$ SELECT public.fluck_peer_request('a2a-bob@example.test') $q$, 'alice asks again');
SELECT set_config('request.jwt.claim.sub', 'a2a00000-0000-0000-0000-00000000000b', true);
SELECT is((SELECT count(*)::int FROM public.fluck_peer_pending()), 0, 'within the cool-down a new request does nothing');

SELECT * FROM finish();
ROLLBACK;
