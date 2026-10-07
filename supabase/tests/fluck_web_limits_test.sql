-- pgTAP tests for 20261007090000_fluck_web_limits: instance cap, endpoint port range, ticket cap.
-- Run with: supabase test db
BEGIN;
SELECT plan(16);
INSERT INTO auth.users (id, email) VALUES
 ('f1c00000-0000-0000-0000-000000000011', 'fluck-limits-one@example.test');

SELECT ok((SELECT convalidated FROM pg_constraint WHERE conname = 'fluck_web_instances_endpoint_url_check'
           AND conrelid = 'public.fluck_web_instances'::regclass), 'endpoint CHECK is validated');

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'f1c00000-0000-0000-0000-000000000011', true);

-- ---- port range ----
SELECT lives_ok($q$ SELECT public.fluck_web_upsert_instance('port-max', 'x', 'Fluck', 'https://a.example:65535', NULL) $q$, 'port 65535 is accepted');
SELECT lives_ok($q$ SELECT public.fluck_web_upsert_instance('port-1', 'x', 'Fluck', 'https://a.example:1', NULL) $q$, 'port 1 is accepted');
SELECT throws_ok($q$ SELECT public.fluck_web_upsert_instance('port-x', 'x', 'Fluck', 'https://a.example:65536', NULL) $q$, '22023', 'Invalid Fluck web instance', 'port 65536 is refused');
SELECT throws_ok($q$ SELECT public.fluck_web_upsert_instance('port-x', 'x', 'Fluck', 'https://a.example:99999', NULL) $q$, '22023', 'Invalid Fluck web instance', 'port 99999 is refused');
SELECT throws_ok($q$ SELECT public.fluck_web_upsert_instance('port-x', 'x', 'Fluck', 'https://a.example:0', NULL) $q$, '22023', 'Invalid Fluck web instance', 'port 0 is refused');
SELECT throws_ok($q$ SELECT public.fluck_web_upsert_instance('port-x', 'x', 'Fluck', 'https://a.example:08443', NULL) $q$, '22023', 'Invalid Fluck web instance', 'a zero-padded port is refused');

-- ---- instance cap: 20 live rows per user (2 above + 18 here) ----
SELECT lives_ok($q$ SELECT public.fluck_web_upsert_instance('cap-' || g, 'x', 'Fluck', 'https://a.example', NULL) FROM generate_series(1, 18) g $q$, 'up to 20 instances are accepted');
SELECT throws_ok($q$ SELECT public.fluck_web_upsert_instance('cap-21', 'x', 'Fluck', 'https://a.example', NULL) $q$, 'P0001', 'too_many_instances', 'the 21st instance is refused');
SELECT throws_ok($q$ INSERT INTO public.fluck_web_instances (instance_id, label, agent_name, endpoint_url) VALUES ('cap-21', 'x', 'Fluck', 'https://a.example') $q$, 'P0001', 'too_many_instances', 'a direct insert is capped too');
SELECT lives_ok($q$ SELECT public.fluck_web_upsert_instance('cap-1', 'renamed', 'Fluck', 'https://a.example', NULL) $q$, 'a heartbeat for an existing instance passes at the cap');
SELECT is((SELECT count(*)::int FROM public.fluck_web_instances), 20, 'still 20 rows');

-- A row idle past the sweep window no longer counts.
RESET ROLE;
ALTER TABLE public.fluck_web_instances DISABLE TRIGGER fluck_web_instances_touch_on_write;
UPDATE public.fluck_web_instances SET last_seen_at = now() - interval '16 minutes' WHERE instance_id = 'cap-18';
ALTER TABLE public.fluck_web_instances ENABLE TRIGGER fluck_web_instances_touch_on_write;
SET LOCAL ROLE authenticated;
SELECT lives_ok($q$ SELECT public.fluck_web_upsert_instance('cap-21', 'x', 'Fluck', 'https://a.example', NULL) $q$, 'a stale row frees a slot');

-- ---- ticket cap: P0001, which PostgREST maps to 400 (fluck-web reads the message -> 429) ----
SELECT lives_ok($q$ SELECT public.fluck_web_mint_ticket('cap-1') FROM generate_series(1, 20) $q$, '20 pending tickets are allowed');
SELECT throws_ok($q$ SELECT public.fluck_web_mint_ticket('cap-1') $q$, 'P0001', 'too_many_tickets', 'the 21st pending ticket is refused with P0001');
RESET ROLE;
SELECT is((SELECT count(*)::int FROM public.fluck_web_tickets WHERE user_id = 'f1c00000-0000-0000-0000-000000000011'), 20, 'no ticket past the cap was stored');
SELECT * FROM finish();
ROLLBACK;
