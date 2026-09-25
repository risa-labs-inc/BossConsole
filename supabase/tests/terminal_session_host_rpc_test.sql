BEGIN;
SELECT plan(28);
INSERT INTO auth.users (id, email) VALUES
 ('aabbccdd-0000-0000-0000-000000000001', 'terminal-rpc-one@example.test'),
 ('aabbccdd-0000-0000-0000-000000000002', 'terminal-rpc-two@example.test');
SELECT ok(NOT has_function_privilege('anon', 'public.upsert_terminal_session(uuid,jsonb)', 'EXECUTE'), 'anon cannot publish');
SELECT ok(NOT has_function_privilege('anon', 'public.delete_terminal_session(uuid,text)', 'EXECUTE'), 'anon cannot delete');
SELECT ok(NOT has_function_privilege('anon', 'public.list_terminal_sessions(uuid,timestamptz)', 'EXECUTE'), 'anon cannot list');
SELECT ok(
    (SELECT bool_and(NOT prosecdef AND 'search_path=""' = ANY(proconfig))
     FROM pg_proc WHERE oid IN (
        'public.upsert_terminal_session(uuid,jsonb)'::regprocedure,
        'public.delete_terminal_session(uuid,text)'::regprocedure,
        'public.list_terminal_sessions(uuid,timestamptz)'::regprocedure
     )), 'all RPCs use invoker rights and an empty search path'
);
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'aabbccdd-0000-0000-0000-000000000001', true);
SELECT lives_ok($q$ SELECT public.upsert_terminal_session('aabbccdd-0000-0000-0000-000000000001',
 '{"user_id":"aabbccdd-0000-0000-0000-000000000002","share_id":"0123456789abcdef","device_name":"test","scope":"ALL","view_url":"https://example.test/?t=view","control_url":"https://example.test/?t=account#k=secret"}') $q$, 'publish derives ownership from host session');
SELECT is((SELECT user_id::text FROM public.terminal_sessions WHERE share_id='0123456789abcdef'), 'aabbccdd-0000-0000-0000-000000000001', 'payload cannot impersonate another account');
SELECT is(jsonb_array_length(public.list_terminal_sessions('aabbccdd-0000-0000-0000-000000000001', now()-interval '90 seconds')), 1, 'owner sees their live session');
-- The server stamps heartbeats, so discovery must not trust a device's clock.
SELECT is(jsonb_array_length(public.list_terminal_sessions('aabbccdd-0000-0000-0000-000000000001', now()+interval '1 day')), 1, 'fast client clock does not hide live sessions');
SELECT is(jsonb_array_length(public.list_terminal_sessions('aabbccdd-0000-0000-0000-000000000001', NULL)), 1, 'null cutoff uses server freshness');

-- Backdate fixture metadata as the test owner, then verify heartbeat semantics.
RESET ROLE;
ALTER TABLE public.terminal_sessions DISABLE TRIGGER terminal_sessions_touch_on_write;
UPDATE public.terminal_sessions SET started_at=now()-interval '1 day', last_seen_at=now()-interval '10 seconds'
WHERE user_id='aabbccdd-0000-0000-0000-000000000001' AND share_id='0123456789abcdef';
ALTER TABLE public.terminal_sessions ENABLE TRIGGER terminal_sessions_touch_on_write;
SET LOCAL ROLE authenticated;
SELECT lives_ok($q$ SELECT public.upsert_terminal_session('aabbccdd-0000-0000-0000-000000000001',
 '{"share_id":"0123456789abcdef","device_name":"updated","scope":"ALL","view_url":"https://example.test/?t=view","control_url":"https://example.test/?t=account#k=secret","started_at":"2100-01-01","last_seen_at":"2100-01-01"}') $q$, 'heartbeat accepts the same share without a duplicate');
SELECT is((SELECT count(*)::integer FROM public.terminal_sessions WHERE share_id='0123456789abcdef'), 1, 'heartbeat keeps one row');
SELECT is((SELECT started_at FROM public.terminal_sessions WHERE share_id='0123456789abcdef'), now()-interval '1 day', 'heartbeat preserves the original start time');
SELECT is((SELECT last_seen_at FROM public.terminal_sessions WHERE share_id='0123456789abcdef'), now(), 'heartbeat uses database time, ignoring client timestamps');
SELECT is((SELECT device_name FROM public.terminal_sessions WHERE share_id='0123456789abcdef'), 'updated', 'heartbeat updates metadata');

-- Existing BossTerm direct-table clients interoperate in both directions.
SELECT lives_ok($q$ INSERT INTO public.terminal_sessions(user_id,share_id,device_name,scope,view_url,control_url)
 VALUES ('aabbccdd-0000-0000-0000-000000000001','abcdef0123456789','BossTerm','TAB','https://example.test/view','https://example.test/control') $q$, 'standalone direct insert remains supported');
SELECT is(jsonb_array_length(public.list_terminal_sessions('aabbccdd-0000-0000-0000-000000000001', now()-interval '90 seconds')), 2, 'RPC discovers the standalone BossTerm row');
SELECT is((SELECT count(*)::integer FROM public.terminal_sessions WHERE user_id=auth.uid()), 2, 'direct SELECT sees RPC and standalone shares');
RESET ROLE;
ALTER TABLE public.terminal_sessions DISABLE TRIGGER terminal_sessions_touch_on_write;
UPDATE public.terminal_sessions SET last_seen_at=now()-interval '2 minutes'
WHERE user_id='aabbccdd-0000-0000-0000-000000000001' AND share_id='abcdef0123456789';
ALTER TABLE public.terminal_sessions ENABLE TRIGGER terminal_sessions_touch_on_write;
SET LOCAL ROLE authenticated;
SELECT is(jsonb_array_length(public.list_terminal_sessions('aabbccdd-0000-0000-0000-000000000001', now()-interval '1 year')), 1, 'old client cutoff cannot revive stale sessions');
SELECT throws_ok($q$ SELECT public.upsert_terminal_session('aabbccdd-0000-0000-0000-000000000001',
 '{"share_id":"0123456789abcdef","device_name":"test","scope":"INVALID","view_url":"https://example.test/view","control_url":"https://example.test/?t=account#k=secret"}') $q$,
 '22023', 'Invalid terminal session payload', 'invalid row returns a generic validation error');
SELECT throws_ok($q$ SELECT public.upsert_terminal_session('aabbccdd-0000-0000-0000-000000000001',
 '{"share_id":"0123456789abcdef","device_name":"test","scope":"ALL","view_url":"https://example.test/view","control_url":"https://example.test/?t=account#k=secret","secure":"not-boolean"}') $q$,
 '22023', 'Invalid terminal session payload', 'invalid boolean returns a generic validation error');

-- Assert DETAIL is empty too: a generic message alone would not hide a failing row.
DO $$
DECLARE detail text;
BEGIN
    BEGIN
        PERFORM public.upsert_terminal_session('aabbccdd-0000-0000-0000-000000000001',
            '{"share_id":"0123456789abcdef","device_name":"test","scope":"INVALID","view_url":"https://example.test/view","control_url":"https://example.test/?t=account#k=secret"}');
    EXCEPTION WHEN SQLSTATE '22023' THEN
        GET STACKED DIAGNOSTICS detail = PG_EXCEPTION_DETAIL;
        PERFORM set_config('test.terminal_rpc_error_detail', detail, true);
    END;
END;
$$;
SELECT is(current_setting('test.terminal_rpc_error_detail', true), '', 'validation error DETAIL cannot leak bearer links');

SELECT set_config('request.jwt.claim.sub', 'aabbccdd-0000-0000-0000-000000000002', true);
SELECT is(jsonb_array_length(public.list_terminal_sessions('aabbccdd-0000-0000-0000-000000000002', now()-interval '90 seconds')), 0, 'other account cannot discover bearer links');
SELECT throws_ok($q$ SELECT public.upsert_terminal_session('aabbccdd-0000-0000-0000-000000000001', '{}') $q$, '42501', 'Terminal account changed', 'stale publish rejected after account switch');
SELECT throws_ok($q$ SELECT public.list_terminal_sessions('aabbccdd-0000-0000-0000-000000000001', now()) $q$, '42501', 'Terminal account changed', 'stale list rejected');
SELECT throws_ok($q$ SELECT public.delete_terminal_session('aabbccdd-0000-0000-0000-000000000001', '0123456789abcdef') $q$, '42501', 'Terminal account changed', 'stale deletion rejected');
SELECT public.delete_terminal_session('aabbccdd-0000-0000-0000-000000000002', '0123456789abcdef');
SELECT set_config('request.jwt.claim.sub', 'aabbccdd-0000-0000-0000-000000000001', true);
SELECT is((SELECT count(*)::integer FROM public.terminal_sessions WHERE share_id='0123456789abcdef'), 1, 'another account cannot delete an owner row even with its share id');
SELECT public.delete_terminal_session('aabbccdd-0000-0000-0000-000000000001', '0123456789abcdef');
SELECT is((SELECT count(*)::integer FROM public.terminal_sessions WHERE share_id='0123456789abcdef'), 0, 'owner can remove own session');
SELECT set_config('request.jwt.claim.sub', '', true);
SELECT throws_ok($q$ SELECT public.upsert_terminal_session('aabbccdd-0000-0000-0000-000000000001', '{}') $q$, '42501', 'Terminal account changed', 'missing identity fails closed');
SELECT * FROM finish();
ROLLBACK;
