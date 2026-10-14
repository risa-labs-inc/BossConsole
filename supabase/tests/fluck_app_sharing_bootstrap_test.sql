BEGIN;
SELECT plan(15);

INSERT INTO auth.users(id,email) VALUES
 ('fb000000-0000-4000-8000-000000000001','bootstrap@example.test');
INSERT INTO public.fluck_web_instances(user_id,instance_id,label,agent_name,endpoint_url)
VALUES('fb000000-0000-4000-8000-000000000001','fb000000-0000-4000-8000-000000000010','one','Fluck','https://one.example');

SELECT ok(has_function_privilege('service_role','public.fluck_web_bootstrap_app_sharing(text,uuid,uuid,text,uuid,text,jsonb,uuid,text,text)','EXECUTE'),'service role can bootstrap');
SELECT ok(NOT has_function_privilege('authenticated','public.fluck_web_bootstrap_app_sharing(text,uuid,uuid,text,uuid,text,jsonb,uuid,text,text)','EXECUTE'),'authenticated cannot bootstrap directly');
SELECT ok(NOT has_function_privilege('anon','public.fluck_web_bootstrap_app_sharing(text,uuid,uuid,text,uuid,text,jsonb,uuid,text,text)','EXECUTE'),'anon cannot bootstrap');
SELECT ok((SELECT prosecdef AND 'search_path=""'=ANY(proconfig) FROM pg_proc WHERE oid='public.fluck_web_bootstrap_app_sharing(text,uuid,uuid,text,uuid,text,jsonb,uuid,text,text)'::regprocedure),'bootstrap is a hardened definer');

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','fb000000-0000-4000-8000-000000000001',true);
SELECT set_config('bootstrap.grant',(public.fluck_web_request_app_sharing(
 'fb000000-0000-4000-8000-000000000010',
 'fb000000-0000-4000-8000-000000000011',
 'fb000000-0000-4000-8000-000000000012',
 'fb000000-0000-4000-8000-000000000013'
)->>'grant'),true);
RESET ROLE;

SET LOCAL ROLE service_role;
SELECT set_config('request.jwt.claim.role','service_role',true);
SELECT set_config('bootstrap.result',public.fluck_web_bootstrap_app_sharing(
 current_setting('bootstrap.grant'),
 'fb000000-0000-4000-8000-000000000012',
 'fb000000-0000-4000-8000-000000000013',
 'fb000000-0000-4000-8000-000000000010',
 'fb000000-0000-4000-8000-000000000020',
 'Fluck',
 '[{"id":"watch-boss","title":"BOSS"}]',
 'fb000000-0000-4000-8000-000000000021',
 repeat('A',43),
 'control'
)::text,true);
SELECT ok((current_setting('bootstrap.result')::jsonb->>'host_peer_id')::uuid IS NOT NULL,'bootstrap returns the host peer');
SELECT ok((current_setting('bootstrap.result')::jsonb->>'viewer_peer_id')::uuid IS NOT NULL,'bootstrap returns the viewer peer');
RESET ROLE;

SELECT is((SELECT count(*)::int FROM public.app_share_sessions WHERE id='fb000000-0000-4000-8000-000000000012'),1,'bootstrap registers one session');
SELECT is((SELECT viewer_url FROM public.app_share_sessions WHERE id='fb000000-0000-4000-8000-000000000012'),'https://bridge.invalid/#k=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA','bootstrap stores only the nonsecret bridge sentinel');
SELECT is((SELECT count(*)::int FROM public.app_share_tickets WHERE session_id='fb000000-0000-4000-8000-000000000012'),0,'single-use admission ticket is consumed atomically');
SELECT is((SELECT count(*)::int FROM public.app_share_peers WHERE session_id='fb000000-0000-4000-8000-000000000012' AND actor_id='fb000000-0000-4000-8000-000000000001' AND role='control'),1,'viewer peer stays owner scoped with the requested role');

SET LOCAL ROLE service_role;
SELECT set_config('request.jwt.claim.role','service_role',true);
SELECT throws_ok($q$SELECT public.fluck_web_bootstrap_app_sharing(
 current_setting('bootstrap.grant'),'fb000000-0000-4000-8000-000000000012','fb000000-0000-4000-8000-000000000013',
 'fb000000-0000-4000-8000-000000000010','fb000000-0000-4000-8000-000000000020','Fluck','[{"id":"watch-boss"}]',
 'fb000000-0000-4000-8000-000000000021',repeat('A',43),'control')$q$,'P0001','grant_invalid','bootstrap cannot replace an existing generation');
RESET ROLE;

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','fb000000-0000-4000-8000-000000000001',true);
SELECT set_config('bootstrap.bad',(public.fluck_web_request_app_sharing(
 'fb000000-0000-4000-8000-000000000010','fb000000-0000-4000-8000-000000000031',
 'fb000000-0000-4000-8000-000000000032','fb000000-0000-4000-8000-000000000033')->>'grant'),true);
RESET ROLE;
SET LOCAL ROLE service_role;
SELECT set_config('request.jwt.claim.role','service_role',true);
SELECT throws_ok($q$SELECT public.fluck_web_bootstrap_app_sharing(
 current_setting('bootstrap.bad'),'fb000000-0000-4000-8000-000000000032','fb000000-0000-4000-8000-000000000033',
 'fb000000-0000-4000-8000-000000000010','fb000000-0000-4000-8000-000000000040','Fluck','[{"id":"watch-boss"}]',
 'fb000000-0000-4000-8000-000000000041',repeat('A',43),'owner')$q$,'22023','Invalid admission','invalid admission rolls back the whole bootstrap');
RESET ROLE;
SELECT is((SELECT count(*)::int FROM public.app_share_sessions WHERE id='fb000000-0000-4000-8000-000000000032'),0,'failed bootstrap leaves no registered session');
SELECT is((SELECT count(*)::int FROM public.app_share_tickets WHERE session_id='fb000000-0000-4000-8000-000000000032'),0,'failed bootstrap leaves no admission ticket');
SELECT is((SELECT count(*)::int FROM public.app_share_peers WHERE session_id='fb000000-0000-4000-8000-000000000032'),0,'failed bootstrap leaves no peer');

SELECT * FROM finish();
ROLLBACK;
