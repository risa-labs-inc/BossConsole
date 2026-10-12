-- pgTAP coverage for 20261012120000_watch_boss_turn_credentials.sql.
BEGIN;
SELECT plan(25);

INSERT INTO auth.users (id, email) VALUES
 ('7a110000-0000-0000-0000-000000000001', 'turn-one@example.test'),
 ('7a110000-0000-0000-0000-000000000002', 'turn-two@example.test');

SELECT ok(has_function_privilege('authenticated', 'public.fluck_web_request_turn(text,text)', 'EXECUTE'), 'authenticated can request a ticket');
SELECT ok(NOT has_function_privilege('anon', 'public.fluck_web_request_turn(text,text)', 'EXECUTE'), 'anon cannot request a ticket');
SELECT ok(has_function_privilege('service_role', 'public.fluck_web_consume_turn_ticket(text,text,text)', 'EXECUTE'), 'service role can consume');
SELECT ok(NOT has_function_privilege('authenticated', 'public.fluck_web_consume_turn_ticket(text,text,text)', 'EXECUTE'), 'authenticated cannot consume');
SELECT ok(NOT has_table_privilege('authenticated', 'public.fluck_web_turn_tickets', 'SELECT'), 'authenticated cannot read ticket rows');
SELECT ok((SELECT relrowsecurity FROM pg_class WHERE oid = 'public.fluck_web_turn_tickets'::regclass), 'ticket table has RLS');
SELECT ok((SELECT bool_and(prosecdef AND 'search_path=""' = ANY(proconfig)) FROM pg_proc WHERE oid IN (
  'public.fluck_web_request_turn(text,text)'::regprocedure,
  'public.fluck_web_consume_turn_ticket(text,text,text)'::regprocedure)), 'ticket RPCs are hardened definers');

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', '', true);
SELECT throws_ok($q$ SELECT * FROM public.fluck_web_request_turn('inst-a', '7b7f6638-b35f-4df7-8eaf-45c30df936dc') $q$, '42501', 'Not signed in', 'identity is required');

SELECT set_config('request.jwt.claim.sub', '7a110000-0000-0000-0000-000000000001', true);
SELECT throws_ok($q$ SELECT * FROM public.fluck_web_request_turn('inst-a', NULL) $q$, '22023', 'invalid_request', 'null attempt is refused at mint');
SELECT throws_ok($q$ SELECT * FROM public.fluck_web_request_turn('inst-a', 'not-a-uuid') $q$, '22023', 'invalid_request', 'attempt must be a UUID');
SELECT throws_ok($q$ SELECT * FROM public.fluck_web_request_turn('inst-a', '7b7f6638-b35f-4df7-8eaf-45c30df936dc') $q$, 'P0001', 'instance_unavailable', 'instance must be owned and live');
SELECT public.fluck_web_upsert_instance('inst-a', 'TURN test', 'Fluck', 'https://turn-test.example', 'test');
SELECT set_config('test.turn.row', row_to_json(r)::text, true)
  FROM public.fluck_web_request_turn('inst-a', '7b7f6638-b35f-4df7-8eaf-45c30df936dc') r;
SELECT ok((current_setting('test.turn.row')::jsonb->>'ticket') ~ '^[A-Za-z0-9_-]{43}$', 'ticket is 32 random bytes base64url');
SELECT ok(((current_setting('test.turn.row')::jsonb->>'expires_at')::timestamptz BETWEEN now() + interval '59 seconds' AND now() + interval '61 seconds'), 'RPC expiry is a 60-second timestamptz');
SELECT set_config('request.jwt.claim.sub', '7a110000-0000-0000-0000-000000000002', true);
SELECT throws_ok($q$ SELECT * FROM public.fluck_web_request_turn('inst-a', '6b7f6638-b35f-4df7-8eaf-45c30df936dc') $q$, 'P0001', 'instance_unavailable', 'another owner cannot request TURN for this instance');
SELECT set_config('request.jwt.claim.sub', '7a110000-0000-0000-0000-000000000001', true);

RESET ROLE;
SELECT is((SELECT ticket_hash FROM public.fluck_web_turn_tickets LIMIT 1),
  encode(extensions.digest(current_setting('test.turn.row')::jsonb->>'ticket', 'sha256'), 'hex'), 'only the ticket hash is stored');
SELECT is((SELECT attempt_id::text FROM public.fluck_web_turn_tickets LIMIT 1), '7b7f6638-b35f-4df7-8eaf-45c30df936dc', 'ticket is bound to the attempt');

SET LOCAL ROLE service_role;
SELECT throws_ok(format($q$ SELECT public.fluck_web_consume_turn_ticket(%L, 'inst-a', NULL) $q$, current_setting('test.turn.row')::jsonb->>'ticket'), 'P0001', 'ticket_invalid', 'null attempt is refused at consume');
SELECT throws_ok(format($q$ SELECT public.fluck_web_consume_turn_ticket(%L, 'wrong', '7b7f6638-b35f-4df7-8eaf-45c30df936dc') $q$, current_setting('test.turn.row')::jsonb->>'ticket'), 'P0001', 'ticket_invalid', 'wrong instance is refused');
SELECT throws_ok(format($q$ SELECT public.fluck_web_consume_turn_ticket(%L, 'inst-a', '8b7f6638-b35f-4df7-8eaf-45c30df936dc') $q$, current_setting('test.turn.row')::jsonb->>'ticket'), 'P0001', 'ticket_invalid', 'wrong attempt is refused');
SELECT is(public.fluck_web_consume_turn_ticket(current_setting('test.turn.row')::jsonb->>'ticket', 'inst-a', '7b7f6638-b35f-4df7-8eaf-45c30df936dc')::text, '7a110000-0000-0000-0000-000000000001', 'bound ticket consumes once');
SELECT throws_ok(format($q$ SELECT public.fluck_web_consume_turn_ticket(%L, 'inst-a', '7b7f6638-b35f-4df7-8eaf-45c30df936dc') $q$, current_setting('test.turn.row')::jsonb->>'ticket'), 'P0001', 'ticket_invalid', 'replay is refused');

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', '7a110000-0000-0000-0000-000000000001', true);
SELECT throws_ok($q$ SELECT * FROM public.fluck_web_request_turn('inst-a', '7b7f6638-b35f-4df7-8eaf-45c30df936dc') $q$, 'P0001', 'duplicate_attempt', 'attempt cannot be reused after redemption');

RESET ROLE;
INSERT INTO public.fluck_web_turn_tickets (ticket_hash, user_id, instance_id, attempt_id, created_at, expires_at, used_at)
SELECT encode(extensions.digest('quota-' || n, 'sha256'), 'hex'), '7a110000-0000-0000-0000-000000000001', 'inst-a',
       ('00000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid, now(), now() + interval '60 seconds', now()
FROM generate_series(1, 19) n;
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', '7a110000-0000-0000-0000-000000000001', true);
SELECT throws_ok($q$ SELECT * FROM public.fluck_web_request_turn('inst-a', '9b7f6638-b35f-4df7-8eaf-45c30df936dc') $q$, 'P0001', 'rate_limited', 'redeemed tickets count toward the atomic rolling quota');

RESET ROLE;
UPDATE public.fluck_web_turn_tickets SET created_at = now() - interval '2 minutes';
SET LOCAL ROLE authenticated;
SELECT set_config('test.turn.expiring', row_to_json(r)::text, true)
  FROM public.fluck_web_request_turn('inst-a', '9b7f6638-b35f-4df7-8eaf-45c30df936dc') r;
SELECT ok((current_setting('test.turn.expiring')::jsonb->>'ticket') ~ '^[A-Za-z0-9_-]{43}$', 'older redeemed tickets leave the quota window');

RESET ROLE;
UPDATE public.fluck_web_turn_tickets SET expires_at = now() - interval '1 second' WHERE attempt_id = '9b7f6638-b35f-4df7-8eaf-45c30df936dc';
SET LOCAL ROLE service_role;
SELECT throws_ok(format($q$ SELECT public.fluck_web_consume_turn_ticket(%L, 'inst-a', '9b7f6638-b35f-4df7-8eaf-45c30df936dc') $q$, current_setting('test.turn.expiring')::jsonb->>'ticket'), 'P0001', 'ticket_invalid', 'expired ticket is refused');

SELECT * FROM finish();
ROLLBACK;
