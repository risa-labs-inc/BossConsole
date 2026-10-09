-- pgTAP tests for the fluck.risaboss.com directory and tickets (20261006120000).
-- Run with: supabase test db
BEGIN;
SELECT plan(47);
INSERT INTO auth.users (id, email) VALUES
 ('f1c00000-0000-0000-0000-000000000001', 'fluck-web-one@example.test'),
 ('f1c00000-0000-0000-0000-000000000002', 'fluck-web-two@example.test');

-- ---- grants and function shape ----
SELECT ok(NOT has_function_privilege('anon', 'public.fluck_web_upsert_instance(text,text,text,text,text)', 'EXECUTE'), 'anon cannot heartbeat');
SELECT ok(NOT has_function_privilege('anon', 'public.fluck_web_delete_instance(text)', 'EXECUTE'), 'anon cannot delete');
SELECT ok(NOT has_function_privilege('anon', 'public.fluck_web_list_instances()', 'EXECUTE'), 'anon cannot list');
SELECT ok(NOT has_function_privilege('anon', 'public.fluck_web_mint_ticket(text)', 'EXECUTE'), 'anon cannot mint');
SELECT ok(NOT has_function_privilege('anon', 'public.fluck_web_consume_ticket(text,text)', 'EXECUTE'), 'anon cannot consume');
SELECT ok(has_function_privilege('authenticated', 'public.fluck_web_mint_ticket(text)', 'EXECUTE')
      AND has_function_privilege('authenticated', 'public.fluck_web_consume_ticket(text,text)', 'EXECUTE')
      AND has_function_privilege('authenticated', 'public.fluck_web_list_instances()', 'EXECUTE'), 'authenticated can call the RPCs');
SELECT ok(
    (SELECT bool_and(NOT prosecdef AND 'search_path=""' = ANY(proconfig)) FROM pg_proc WHERE oid IN (
        'public.fluck_web_upsert_instance(text,text,text,text,text)'::regprocedure,
        'public.fluck_web_delete_instance(text)'::regprocedure,
        'public.fluck_web_list_instances()'::regprocedure)),
    'directory RPCs are security invoker with an empty search_path');
SELECT ok(
    (SELECT bool_and(prosecdef AND 'search_path=""' = ANY(proconfig)) FROM pg_proc WHERE oid IN (
        'public.fluck_web_mint_ticket(text)'::regprocedure,
        'public.fluck_web_consume_ticket(text,text)'::regprocedure)),
    'ticket RPCs are security definer with an empty search_path');
SELECT ok(NOT has_table_privilege('anon', 'public.fluck_web_instances', 'SELECT'), 'anon cannot read instances');
SELECT ok(NOT has_table_privilege('authenticated', 'public.fluck_web_tickets', 'SELECT')
      AND NOT has_table_privilege('authenticated', 'public.fluck_web_tickets', 'INSERT')
      AND NOT has_table_privilege('authenticated', 'public.fluck_web_tickets', 'UPDATE'), 'authenticated has no direct ticket access');
SELECT ok((SELECT relrowsecurity FROM pg_class WHERE oid = 'public.fluck_web_tickets'::regclass)
      AND (SELECT relrowsecurity FROM pg_class WHERE oid = 'public.fluck_web_instances'::regclass), 'both tables enable RLS');

-- ---- heartbeat ----
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'f1c00000-0000-0000-0000-000000000001', true);
SELECT lives_ok($q$ SELECT public.fluck_web_upsert_instance('inst-a', 'Shivanshu MacBook', 'Fluck', 'https://a.trycloudflare.com/', '1.0.120') $q$, 'owner heartbeats an instance');
SELECT is((SELECT endpoint_url FROM public.fluck_web_instances WHERE instance_id = 'inst-a'), 'https://a.trycloudflare.com', 'one trailing slash is trimmed');
SELECT is((SELECT user_id::text FROM public.fluck_web_instances WHERE instance_id = 'inst-a'), 'f1c00000-0000-0000-0000-000000000001', 'row is owned by the caller');
SELECT is((SELECT count(*)::int FROM public.fluck_web_list_instances()), 1, 'owner lists the instance');
SELECT is((SELECT online FROM public.fluck_web_list_instances() WHERE instance_id = 'inst-a'), true, 'fresh heartbeat is online');

SELECT throws_ok($q$ SELECT public.fluck_web_upsert_instance('inst-b', 'x', 'Fluck', 'http://a.example', NULL) $q$, '22023', 'Invalid Fluck web instance', 'http endpoint is refused');
SELECT throws_ok($q$ SELECT public.fluck_web_upsert_instance('inst-b', 'x', 'Fluck', 'https://a.example/chat', NULL) $q$, '22023', 'Invalid Fluck web instance', 'endpoint with a path is refused');
SELECT throws_ok($q$ SELECT public.fluck_web_upsert_instance('inst-b', 'x', 'Fluck', 'https://u:p@a.example', NULL) $q$, '22023', 'Invalid Fluck web instance', 'endpoint with userinfo is refused');
SELECT throws_ok($q$ SELECT public.fluck_web_upsert_instance('inst-b', 'x', 'Fluck', 'javascript:alert(1)', NULL) $q$, '22023', 'Invalid Fluck web instance', 'non-URL endpoint is refused');
SELECT throws_ok($q$ SELECT public.fluck_web_upsert_instance('inst-b', repeat('x', 81), 'Fluck', 'https://a.example', NULL) $q$, '22023', 'Invalid Fluck web instance', 'label over 80 chars is refused');
SELECT throws_ok($q$ SELECT public.fluck_web_upsert_instance('inst-b', 'x', repeat('y', 41), 'https://a.example', NULL) $q$, '22023', 'Invalid Fluck web instance', 'agent name over 40 chars is refused');
SELECT throws_ok($q$ SELECT public.fluck_web_upsert_instance('bad id', 'x', 'Fluck', 'https://a.example', NULL) $q$, '22023', 'Invalid Fluck web instance', 'malformed instance id is refused');
SELECT lives_ok($q$ SELECT public.fluck_web_upsert_instance('inst-b', 'Tailnet Mac', 'Optimist', 'https://mac.tail1234.ts.net:8443', NULL) $q$, 'https origin with a port is accepted');

-- Backdate as the test owner, then verify heartbeat semantics.
RESET ROLE;
ALTER TABLE public.fluck_web_instances DISABLE TRIGGER fluck_web_instances_touch_on_write;
UPDATE public.fluck_web_instances SET started_at = now() - interval '1 day', last_seen_at = now() - interval '2 minutes'
 WHERE instance_id = 'inst-a';
ALTER TABLE public.fluck_web_instances ENABLE TRIGGER fluck_web_instances_touch_on_write;
SET LOCAL ROLE authenticated;
SELECT is((SELECT online FROM public.fluck_web_list_instances() WHERE instance_id = 'inst-a'), false, 'stale heartbeat is listed offline');
SELECT throws_ok($q$ SELECT public.fluck_web_mint_ticket('inst-a') $q$, 'P0001', 'instance_unavailable', 'cannot mint for an offline instance');
SELECT lives_ok($q$ SELECT public.fluck_web_upsert_instance('inst-a', 'Renamed', 'Fluck', 'https://a.trycloudflare.com', '1.0.121') $q$, 'heartbeat on an existing instance');
SELECT is((SELECT count(*)::int FROM public.fluck_web_instances WHERE instance_id = 'inst-a'), 1, 'heartbeat keeps one row');
SELECT is((SELECT started_at FROM public.fluck_web_instances WHERE instance_id = 'inst-a'), now() - interval '1 day', 'heartbeat preserves started_at');
SELECT is((SELECT last_seen_at FROM public.fluck_web_instances WHERE instance_id = 'inst-a'), now(), 'heartbeat is stamped by the server clock');
SELECT is((SELECT label FROM public.fluck_web_instances WHERE instance_id = 'inst-a'), 'Renamed', 'heartbeat updates metadata');

-- ---- tickets ----
SELECT set_config('test.ticket', public.fluck_web_mint_ticket('inst-a'), true);
SELECT ok(current_setting('test.ticket') ~ '^[A-Za-z0-9_-]{43}$', 'ticket is 32 random bytes in base64url');
RESET ROLE;
SELECT is((SELECT ticket_hash FROM public.fluck_web_tickets WHERE instance_id = 'inst-a'),
          encode(extensions.digest(current_setting('test.ticket'), 'sha256'), 'hex'), 'only the sha256 hex is stored');
SELECT ok((SELECT expires_at BETWEEN now() + interval '59 seconds' AND now() + interval '61 seconds' FROM public.fluck_web_tickets WHERE instance_id = 'inst-a'), 'ticket expires in 60 s');
SET LOCAL ROLE authenticated;

-- Another account (its own Fluck calling as itself) can never redeem it.
SELECT set_config('request.jwt.claim.sub', 'f1c00000-0000-0000-0000-000000000002', true);
SELECT throws_ok(format($q$ SELECT * FROM public.fluck_web_consume_ticket(%L, 'inst-a') $q$, current_setting('test.ticket')), 'P0001', 'ticket_invalid', 'another account cannot consume the ticket');
SELECT throws_ok($q$ SELECT public.fluck_web_mint_ticket('inst-a') $q$, 'P0001', 'instance_unavailable', 'another account cannot mint for the owner instance');
SELECT is((SELECT count(*)::int FROM public.fluck_web_list_instances()), 0, 'another account lists nothing');
SELECT is((SELECT count(*)::int FROM public.fluck_web_instances), 0, 'RLS hides the owner rows from another account');
SELECT public.fluck_web_delete_instance('inst-a');

SELECT set_config('request.jwt.claim.sub', 'f1c00000-0000-0000-0000-000000000001', true);
SELECT throws_ok(format($q$ SELECT * FROM public.fluck_web_consume_ticket(%L, 'inst-b') $q$, current_setting('test.ticket')), 'P0001', 'ticket_invalid', 'ticket is bound to its instance');
SELECT is((SELECT row(c.user_id, c.email)::text FROM public.fluck_web_consume_ticket(current_setting('test.ticket'), 'inst-a') c),
          '(f1c00000-0000-0000-0000-000000000001,fluck-web-one@example.test)', 'owner Fluck consumes once and gets id and email');
SELECT throws_ok(format($q$ SELECT * FROM public.fluck_web_consume_ticket(%L, 'inst-a') $q$, current_setting('test.ticket')), 'P0001', 'ticket_invalid', 'a used ticket cannot be replayed');
SELECT throws_ok($q$ SELECT * FROM public.fluck_web_consume_ticket('short', 'inst-a') $q$, 'P0001', 'ticket_invalid', 'malformed ticket is refused');

-- Expiry.
SELECT set_config('test.ticket2', public.fluck_web_mint_ticket('inst-a'), true);
RESET ROLE;
UPDATE public.fluck_web_tickets SET expires_at = now() - interval '1 second' WHERE used_at IS NULL;
SET LOCAL ROLE authenticated;
SELECT throws_ok(format($q$ SELECT * FROM public.fluck_web_consume_ticket(%L, 'inst-a') $q$, current_setting('test.ticket2')), 'P0001', 'ticket_invalid', 'an expired ticket is refused');

-- Deleting the instance invalidates its outstanding tickets.
SELECT set_config('test.ticket3', public.fluck_web_mint_ticket('inst-a'), true);
SELECT public.fluck_web_delete_instance('inst-a');
SELECT is((SELECT count(*)::int FROM public.fluck_web_instances WHERE instance_id = 'inst-a'), 0, 'owner deletes the instance');
SELECT throws_ok(format($q$ SELECT * FROM public.fluck_web_consume_ticket(%L, 'inst-a') $q$, current_setting('test.ticket3')), 'P0001', 'ticket_invalid', 'tickets die with their instance');

-- No identity fails closed.
SELECT set_config('request.jwt.claim.sub', '', true);
SELECT throws_ok($q$ SELECT public.fluck_web_mint_ticket('inst-b') $q$, '42501', 'Not signed in', 'mint without identity fails closed');
SELECT throws_ok($q$ SELECT * FROM public.fluck_web_list_instances() $q$, '42501', 'Not signed in', 'list without identity fails closed');
SELECT * FROM finish();
ROLLBACK;
