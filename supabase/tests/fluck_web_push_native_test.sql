-- pgTAP tests for 20261013200000_fluck_web_push_native: APNs devices and the encryption-target keys.
-- Run with: supabase test db
BEGIN;
SELECT plan(48);
INSERT INTO auth.users (id, email) VALUES
 ('f1c00000-0000-0000-0000-0000000000a1', 'fluck-native-one@example.test'),
 ('f1c00000-0000-0000-0000-0000000000a2', 'fluck-native-two@example.test');

-- ---- grants and function shape ----
SELECT ok(NOT has_function_privilege('anon', 'public.fluck_web_push_register_native(uuid,text,text,text,text)', 'EXECUTE'), 'anon cannot register a device');
SELECT ok(NOT has_function_privilege('anon', 'public.fluck_web_push_unregister_native(uuid)', 'EXECUTE')
      AND NOT has_function_privilege('anon', 'public.fluck_web_push_native_status(uuid)', 'EXECUTE')
      AND NOT has_function_privilege('anon', 'public.fluck_web_push_keys()', 'EXECUTE'), 'anon cannot unregister, read status or read keys');
SELECT ok(has_function_privilege('authenticated', 'public.fluck_web_push_register_native(uuid,text,text,text,text)', 'EXECUTE')
      AND has_function_privilege('authenticated', 'public.fluck_web_push_unregister_native(uuid)', 'EXECUTE')
      AND has_function_privilege('authenticated', 'public.fluck_web_push_native_status(uuid)', 'EXECUTE')
      AND has_function_privilege('authenticated', 'public.fluck_web_push_keys()', 'EXECUTE'), 'authenticated can call the device RPCs');
SELECT ok(has_function_privilege('anon', 'public.fluck_web_push_redeem(text)', 'EXECUTE')
      AND has_function_privilege('anon', 'public.fluck_web_push_prune(text,uuid[],uuid[])', 'EXECUTE'), 'anon keeps the ticket RPCs');
SELECT is(to_regprocedure('public.fluck_web_push_prune(text,uuid[])')::text, NULL, 'the two-argument prune is gone');
SELECT ok((SELECT prosecdef AND 'search_path=""' = ANY(proconfig) FROM pg_proc
           WHERE oid = 'public.fluck_web_push_register_native(uuid,text,text,text,text)'::regprocedure), 'register is security definer with an empty search_path');
SELECT ok(
    (SELECT bool_and(NOT prosecdef AND 'search_path=""' = ANY(proconfig)) FROM pg_proc WHERE oid IN (
        'public.fluck_web_push_unregister_native(uuid)'::regprocedure,
        'public.fluck_web_push_native_status(uuid)'::regprocedure,
        'public.fluck_web_push_keys()'::regprocedure)),
    'unregister, status and keys are security invoker with an empty search_path');
SELECT ok(NOT has_table_privilege('anon', 'public.fluck_web_push_devices', 'SELECT'), 'anon cannot read devices');
SELECT ok((SELECT relrowsecurity FROM pg_class WHERE oid = 'public.fluck_web_push_devices'::regclass), 'devices enable RLS');
SELECT ok(NOT has_function_privilege('authenticated', 'public.fluck_web_push_devices_cap()', 'EXECUTE'), 'the cap trigger function is not callable');

-- ---- registration ----
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'f1c00000-0000-0000-0000-0000000000a1', true);
SELECT lives_ok($q$ SELECT public.fluck_web_push_register_native('10000000-0000-4000-8000-000000000001', upper(repeat('ab', 32)), 'production') $q$, 'owner registers a device (uppercase hex accepted)');
SELECT is((SELECT token FROM public.fluck_web_push_devices WHERE installation_id = '10000000-0000-4000-8000-000000000001'), repeat('ab', 32), 'token is stored lowercase');
SELECT is((SELECT token_hash FROM public.fluck_web_push_devices WHERE installation_id = '10000000-0000-4000-8000-000000000001'),
          encode(extensions.digest(repeat('ab', 32), 'sha256'), 'hex'), 'token_hash is the sha256 of the lowercase token');
SELECT is((SELECT topic FROM public.fluck_web_push_devices WHERE installation_id = '10000000-0000-4000-8000-000000000001'), 'app.fluck.ios', 'topic is the app bundle');
SELECT is((SELECT user_id::text FROM public.fluck_web_push_devices WHERE installation_id = '10000000-0000-4000-8000-000000000001'), 'f1c00000-0000-0000-0000-0000000000a1', 'row is owned by the caller');
SELECT is(public.fluck_web_push_native_status('10000000-0000-4000-8000-000000000001'), true, 'status is true for a registered installation');
SELECT is(public.fluck_web_push_native_status('10000000-0000-4000-8000-0000000000ff'), false, 'status is false for an unknown installation');
SELECT lives_ok($q$ SELECT public.fluck_web_push_register_native('10000000-0000-4000-8000-000000000001', repeat('ef', 32), 'sandbox', repeat('A', 87), repeat('B', 22)) $q$, 're-registering the installation upserts');
SELECT is((SELECT count(*)::int FROM public.fluck_web_push_devices), 1, 'upsert keeps one row');
SELECT is((SELECT environment || ':' || token FROM public.fluck_web_push_devices), 'sandbox:' || repeat('ef', 32), 'upsert replaces the token and environment');

SELECT throws_ok($q$ SELECT public.fluck_web_push_register_native('10000000-0000-4000-8000-000000000002', 'nothex', 'production') $q$, 'P0001', 'invalid_device', 'a non-hex token is refused');
SELECT throws_ok($q$ SELECT public.fluck_web_push_register_native('10000000-0000-4000-8000-000000000002', repeat('a', 63), 'production') $q$, 'P0001', 'invalid_device', 'a short token is refused');
SELECT throws_ok($q$ SELECT public.fluck_web_push_register_native('10000000-0000-4000-8000-000000000002', repeat('a', 513), 'production') $q$, 'P0001', 'invalid_device', 'a long token is refused');
SELECT throws_ok($q$ SELECT public.fluck_web_push_register_native('10000000-0000-4000-8000-000000000002', repeat('a', 64), 'staging') $q$, 'P0001', 'invalid_device', 'an unknown environment is refused');
SELECT throws_ok($q$ SELECT public.fluck_web_push_register_native('10000000-0000-4000-8000-000000000002', repeat('a', 64), 'production', repeat('A', 87), NULL) $q$, 'P0001', 'invalid_device', 'p256dh without auth is refused');
SELECT throws_ok($q$ SELECT public.fluck_web_push_register_native('10000000-0000-4000-8000-000000000002', repeat('a', 64), 'production', 'short', repeat('B', 22)) $q$, 'P0001', 'invalid_device', 'a malformed p256dh is refused');
SELECT throws_ok($q$ SELECT public.fluck_web_push_register_native(NULL, repeat('a', 64), 'production') $q$, 'P0001', 'invalid_device', 'a null installation is refused');
SELECT throws_ok($q$ INSERT INTO public.fluck_web_push_devices (installation_id, token, token_hash, environment, topic) VALUES (gen_random_uuid(), repeat('a', 64), repeat('a', 64), 'production', 'com.other.app') $q$, '23514', NULL, 'a direct insert cannot use another topic');

-- ---- keys(): the caller's rows only ----
SELECT lives_ok($q$ SELECT public.fluck_web_push_subscribe(
    jsonb_build_object('endpoint', 'https://fcm.googleapis.com/fcm/send/one', 'keys', jsonb_build_object('p256dh', repeat('C', 87), 'auth', repeat('D', 22))),
    'https://fluck.risaboss.com') $q$, 'owner stores a web subscription');
SELECT lives_ok($q$ SELECT public.fluck_web_push_register_native('10000000-0000-4000-8000-000000000003', repeat('12', 32), 'production') $q$, 'owner registers a device without keys');
SELECT is((SELECT jsonb_array_length(public.fluck_web_push_keys())), 2, 'keys lists the web subscription and the keyed device, not the keyless device');
SELECT is((SELECT count(*)::int FROM jsonb_array_elements(public.fluck_web_push_keys()) e WHERE e ->> 'kind' = 'web'
              AND e ->> 'p256dh' = repeat('C', 87) AND e ->> 'auth' = repeat('D', 22) AND e ->> 'portal_origin' = 'https://fluck.risaboss.com'), 1, 'a web row carries kind, keys and portal_origin');
SELECT is((SELECT count(*)::int FROM jsonb_array_elements(public.fluck_web_push_keys()) e WHERE e ->> 'kind' = 'apns'
              AND e ->> 'p256dh' = repeat('A', 87) AND e ->> 'auth' = repeat('B', 22) AND NOT e ? 'portal_origin' AND NOT e ? 'token'), 1, 'an apns row carries keys and never the token');

-- ---- cap: 10 per user, oldest evicted ----
RESET ROLE;
DELETE FROM public.fluck_web_push_devices;
INSERT INTO public.fluck_web_push_devices (user_id, installation_id, token, token_hash, environment, created_at)
SELECT 'f1c00000-0000-0000-0000-0000000000a1', ('20000000-0000-4000-8000-0000000000' || lpad(g::text, 2, '0'))::uuid,
       repeat(lpad(g::text, 2, '0'), 32), encode(extensions.digest(repeat(lpad(g::text, 2, '0'), 32), 'sha256'), 'hex'),
       'production', now() - make_interval(mins => 100 - g)
FROM generate_series(1, 10) g;
SELECT is((SELECT count(*)::int FROM public.fluck_web_push_devices), 10, 'ten devices are kept');
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'f1c00000-0000-0000-0000-0000000000a1', true);
SELECT lives_ok($q$ SELECT public.fluck_web_push_register_native('20000000-0000-4000-8000-000000000099', repeat('99', 32), 'production') $q$, 'an eleventh device registers');
SELECT is((SELECT count(*)::int FROM public.fluck_web_push_devices), 10, 'still ten after the eleventh');
SELECT is((SELECT count(*)::int FROM public.fluck_web_push_devices WHERE installation_id = '20000000-0000-4000-8000-000000000001'), 0, 'the oldest was evicted');
SELECT is((SELECT count(*)::int FROM public.fluck_web_push_devices WHERE installation_id = '20000000-0000-4000-8000-000000000099'), 1, 'the newest is kept');

-- ---- one device, one account ----
SELECT set_config('request.jwt.claim.sub', 'f1c00000-0000-0000-0000-0000000000a2', true);
SELECT is((SELECT count(*)::int FROM public.fluck_web_push_devices), 0, 'owner isolation: user two sees none of user one''s devices');
SELECT is((SELECT jsonb_array_length(public.fluck_web_push_keys())), 0, 'owner isolation: keys is empty for user two');
SELECT lives_ok($q$ SELECT public.fluck_web_push_register_native('30000000-0000-4000-8000-000000000001', upper(repeat('99', 32)), 'production') $q$, 'user two registers the same token');
SELECT is((SELECT count(*)::int FROM public.fluck_web_push_devices), 1, 'user two has the device');
SELECT lives_ok($q$ SELECT public.fluck_web_push_unregister_native('20000000-0000-4000-8000-000000000002') $q$, 'user two cannot delete user one''s installation (no error, no effect)');
RESET ROLE;
SELECT is((SELECT count(*)::int FROM public.fluck_web_push_devices WHERE user_id = 'f1c00000-0000-0000-0000-0000000000a1' AND token = repeat('99', 32)), 0,
          're-registering a token under another user removes it from the first');
SELECT is((SELECT count(*)::int FROM public.fluck_web_push_devices WHERE user_id = 'f1c00000-0000-0000-0000-0000000000a1'), 9, 'the first user''s other devices are untouched');

-- ---- redeem returns devices; prune drops them ----
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'f1c00000-0000-0000-0000-0000000000a2', true);
SELECT public.fluck_web_upsert_instance('inst-n', 'Phone test', 'Laya', 'https://n.trycloudflare.com', NULL);
SELECT set_config('test.ticket', public.fluck_web_push_ticket('inst-n'), true);
SET LOCAL ROLE anon;
SELECT set_config('test.redeemed', public.fluck_web_push_redeem(current_setting('test.ticket'))::text, true);
RESET ROLE;
SELECT is(current_setting('test.redeemed')::jsonb -> 'devices',
          jsonb_build_array(jsonb_build_object('id', (SELECT id FROM public.fluck_web_push_devices WHERE user_id = 'f1c00000-0000-0000-0000-0000000000a2'),
                                               'token', repeat('99', 32), 'environment', 'production', 'topic', 'app.fluck.ios')),
          'redeem returns the owner''s devices as {id, token, environment, topic}');
SELECT ok(current_setting('test.redeemed')::jsonb ?& ARRAY['instance_id', 'agent_name', 'subscriptions'], 'redeem keeps its existing fields');

SET LOCAL ROLE anon;
SELECT is(public.fluck_web_push_prune(current_setting('test.ticket'), '{}',
          ARRAY(SELECT '20000000-0000-4000-8000-000000000002'::uuid)), 0, 'prune cannot delete another owner''s device id');
SELECT is(public.fluck_web_push_prune(current_setting('test.ticket'), '{}', (SELECT array_agg(id) FROM public.fluck_web_push_devices)), 0, 'anon cannot read the table to find ids (RLS)');
RESET ROLE;
SELECT is(public.fluck_web_push_prune(current_setting('test.ticket'), '{}',
          ARRAY(SELECT id FROM public.fluck_web_push_devices WHERE user_id = 'f1c00000-0000-0000-0000-0000000000a2')), 1, 'prune removes the owner''s dead device');
SELECT is((SELECT count(*)::int FROM public.fluck_web_push_devices WHERE user_id = 'f1c00000-0000-0000-0000-0000000000a1'), 9, 'the other owner''s devices survive');
SELECT is(public.fluck_web_push_prune(repeat('x', 43), '{}', ARRAY[gen_random_uuid()]), 0, 'an unknown ticket prunes nothing');

SELECT * FROM finish();
ROLLBACK;
