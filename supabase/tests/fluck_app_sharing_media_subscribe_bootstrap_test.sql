BEGIN;
SELECT plan(13);

INSERT INTO auth.users(id,email) VALUES
 ('fc000000-0000-4000-8000-000000000001','media-bootstrap@example.test');
INSERT INTO public.fluck_web_instances(user_id,instance_id,label,agent_name,endpoint_url)
VALUES('fc000000-0000-4000-8000-000000000001','fc000000-0000-4000-8000-000000000010','one','Fluck','https://one.example');

SELECT ok(has_function_privilege('service_role','public.fluck_web_begin_media_subscribe(text,uuid,uuid,uuid,text,uuid)','EXECUTE'),'service role can begin');
SELECT ok(NOT has_function_privilege('authenticated','public.fluck_web_begin_media_subscribe(text,uuid,uuid,uuid,text,uuid)','EXECUTE'),'authenticated cannot begin');
SELECT ok(has_function_privilege('service_role','public.fluck_web_commit_media_subscribe(uuid,uuid,uuid,uuid,text,uuid,text,text)','EXECUTE'),'service role can commit');
SELECT ok(NOT has_function_privilege('authenticated','public.fluck_web_commit_media_subscribe(uuid,uuid,uuid,uuid,text,uuid,text,text)','EXECUTE'),'authenticated cannot commit');

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','fc000000-0000-4000-8000-000000000001',true);
SELECT set_config('media.grant',(public.fluck_web_request_app_sharing(
 'fc000000-0000-4000-8000-000000000010','fc000000-0000-4000-8000-000000000011',
 'fc000000-0000-4000-8000-000000000012','fc000000-0000-4000-8000-000000000013')->>'grant'),true);
RESET ROLE;

SET LOCAL ROLE service_role;
SELECT set_config('request.jwt.claim.role','service_role',true);
SELECT set_config('media.bootstrap',public.fluck_web_bootstrap_app_sharing(
 current_setting('media.grant'),'fc000000-0000-4000-8000-000000000012','fc000000-0000-4000-8000-000000000013',
 'fc000000-0000-4000-8000-000000000010','fc000000-0000-4000-8000-000000000020','Fluck',
 '[{"id":"watch-boss"}]','fc000000-0000-4000-8000-000000000021',repeat('A',43),'control')::text,true);
RESET ROLE;

INSERT INTO public.app_share_media(session_id,peer_id,window_id,generation,sfu_id,publisher,mid,track_name)
VALUES('fc000000-0000-4000-8000-000000000012',
 (current_setting('media.bootstrap')::jsonb->>'host_peer_id')::uuid,'watch-boss',
 'fc000000-0000-4000-8000-000000000013','host-sfu',true,'host-mid','window-watch-boss');

SET LOCAL ROLE service_role;
SELECT set_config('request.jwt.claim.role','service_role',true);
SELECT set_config('media.begin',public.fluck_web_begin_media_subscribe(
 current_setting('media.grant'),'fc000000-0000-4000-8000-000000000012','fc000000-0000-4000-8000-000000000013',
 (current_setting('media.bootstrap')::jsonb->>'viewer_peer_id')::uuid,'watch-boss','fc000000-0000-4000-8000-000000000030')::text,true);
SELECT is((current_setting('media.begin')::jsonb->>'actor')::uuid,'fc000000-0000-4000-8000-000000000001'::uuid,'begin preserves owner authorization');
SELECT is(current_setting('media.begin')::jsonb#>>'{state,publication,track_name}','window-watch-boss','begin returns only authenticated publication state');
SELECT is((SELECT count(*)::int FROM public.app_share_media_operations WHERE operation='fc000000-0000-4000-8000-000000000030'),1,'begin holds the existing operation lock');
SELECT lives_ok($q$SELECT public.fluck_web_commit_media_subscribe(
 'fc000000-0000-4000-8000-000000000001','fc000000-0000-4000-8000-000000000012','fc000000-0000-4000-8000-000000000013',
 (current_setting('media.bootstrap')::jsonb->>'viewer_peer_id')::uuid,'watch-boss','fc000000-0000-4000-8000-000000000030','viewer-sfu','viewer-mid')$q$,'commit stores the provider result');
RESET ROLE;

SELECT is((SELECT sfu_id||':'||mid FROM public.app_share_media WHERE session_id='fc000000-0000-4000-8000-000000000012' AND NOT publisher),'viewer-sfu:viewer-mid','viewer media is committed atomically');
SELECT is((SELECT count(*)::int FROM public.app_share_media_operations WHERE operation='fc000000-0000-4000-8000-000000000030'),0,'commit releases the operation lock');

SET LOCAL ROLE service_role;
SELECT set_config('request.jwt.claim.role','service_role',true);
SELECT throws_ok($q$SELECT public.fluck_web_begin_media_subscribe(
 current_setting('media.grant'),'fc000000-0000-4000-8000-000000000012','fc000000-0000-4000-8000-000000000013',
 (current_setting('media.bootstrap')::jsonb->>'viewer_peer_id')::uuid,'watch-boss','fc000000-0000-4000-8000-000000000031')$q$,'PT409','Conflict','duplicate allocation is rejected');
RESET ROLE;

UPDATE public.fluck_web_app_sharing_grants SET revoked_at=now() WHERE user_id='fc000000-0000-4000-8000-000000000001';
SET LOCAL ROLE service_role;
SELECT set_config('request.jwt.claim.role','service_role',true);
SELECT throws_ok($q$SELECT public.fluck_web_begin_media_subscribe(
 current_setting('media.grant'),'fc000000-0000-4000-8000-000000000012','fc000000-0000-4000-8000-000000000013',
 (current_setting('media.bootstrap')::jsonb->>'viewer_peer_id')::uuid,'watch-boss','fc000000-0000-4000-8000-000000000032')$q$,'P0001','grant_invalid','revoked grant is rejected before locking');
RESET ROLE;
SELECT is((SELECT count(*)::int FROM public.app_share_media_operations WHERE operation='fc000000-0000-4000-8000-000000000032'),0,'authorization failure leaves no lock');

SELECT * FROM finish();
ROLLBACK;
