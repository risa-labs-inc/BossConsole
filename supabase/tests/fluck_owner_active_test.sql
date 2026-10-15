-- pgTAP tests for when each Fluck's owner last used it (20261016120000).
-- Run with: supabase test db
BEGIN;
SELECT plan(7);
INSERT INTO auth.users (id, email) VALUES
 ('0a000000-0000-0000-0000-00000000000a', 'oa-alice@example.test'),
 ('0a000000-0000-0000-0000-00000000000b', 'oa-bob@example.test'),
 ('0a000000-0000-0000-0000-00000000000c', 'oa-carol@example.test');

SELECT ok(NOT has_function_privilege('anon', 'public.fluck_web_owner_active(text)', 'EXECUTE'), 'anon cannot report owner activity');
SELECT ok(has_function_privilege('authenticated', 'public.fluck_web_owner_active(text)', 'EXECUTE'), 'authenticated can');
SELECT ok((SELECT prosecdef AND 'search_path=""' = ANY(proconfig) FROM pg_proc WHERE oid = 'public.fluck_web_owner_active(text)'::regprocedure),
    'security definer with an empty search_path');

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', '0a000000-0000-0000-0000-00000000000a', true);
SELECT public.fluck_web_upsert_instance('oa-a-mac', 'Alice Mac', 'Fluck', 'https://oa-a-mac.example.test', NULL);
SELECT public.fluck_web_upsert_instance('oa-a-pod', 'Alice pod', 'Max', 'https://oa-a-pod.example.test', NULL);
SELECT set_config('request.jwt.claim.sub', '0a000000-0000-0000-0000-00000000000b', true);
SELECT public.fluck_web_upsert_instance('oa-b-mac', 'Bob Mac', 'Fluck', 'https://oa-b-mac.example.test', NULL);

-- Bob cannot mark Alice's instance; Alice marks her pod.
SELECT public.fluck_web_owner_active('oa-a-pod');
SELECT set_config('request.jwt.claim.sub', '0a000000-0000-0000-0000-00000000000a', true);
SELECT is((SELECT count(*)::int FROM public.fluck_peer_list() WHERE owner_active_at IS NOT NULL), 0, 'another user cannot mark an instance');
SELECT public.fluck_web_owner_active('oa-a-pod');
SELECT is((SELECT array_agg(instance_id) FROM public.fluck_peer_list() WHERE owner_active_at IS NOT NULL), ARRAY['oa-a-pod'], 'the owner marks their own instance');

-- Seen by an accepted connection, not by anyone else.
SELECT public.fluck_peer_request('oa-bob@example.test');
SELECT set_config('request.jwt.claim.sub', '0a000000-0000-0000-0000-00000000000b', true);
SELECT public.fluck_peer_respond((SELECT id FROM public.fluck_peer_pending() LIMIT 1), true);
SELECT is((SELECT array_agg(instance_id) FROM public.fluck_peer_list() WHERE NOT same_user AND owner_active_at IS NOT NULL), ARRAY['oa-a-pod'],
    'a connection sees when the owner last used each agent');
SELECT set_config('request.jwt.claim.sub', '0a000000-0000-0000-0000-00000000000c', true);
SELECT is((SELECT count(*)::int FROM public.fluck_peer_list() WHERE NOT same_user), 0, 'an unconnected user sees nothing');

SELECT * FROM finish();
ROLLBACK;
