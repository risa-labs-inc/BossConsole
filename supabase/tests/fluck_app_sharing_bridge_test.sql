BEGIN;
SELECT plan(28);

INSERT INTO auth.users(id,email) VALUES
 ('fa110000-0000-0000-0000-000000000001','bridge-one@example.test'),
 ('fa110000-0000-0000-0000-000000000002','bridge-two@example.test');
INSERT INTO public.fluck_web_instances(user_id,instance_id,label,agent_name,endpoint_url)
VALUES('fa110000-0000-0000-0000-000000000001','fa110000-0000-4000-8000-000000000010','one','Fluck','https://one.example');

SELECT ok(has_function_privilege('authenticated','public.fluck_web_request_app_sharing(text,uuid,uuid,uuid)','EXECUTE'),'authenticated can mint');
SELECT ok(NOT has_function_privilege('anon','public.fluck_web_request_app_sharing(text,uuid,uuid,uuid)','EXECUTE'),'anon cannot mint');
SELECT ok(has_function_privilege('authenticated','public.fluck_web_revoke_app_sharing(text,uuid)','EXECUTE'),'authenticated can revoke its grant');
SELECT ok(NOT has_function_privilege('authenticated','public.fluck_web_authorize_app_sharing(text,uuid,uuid,text,text)','EXECUTE'),'authenticated cannot authorize');
SELECT ok(has_function_privilege('service_role','public.fluck_web_authorize_app_sharing(text,uuid,uuid,text,text)','EXECUTE'),'service role can authorize');
SELECT ok(NOT has_table_privilege('authenticated','public.fluck_web_app_sharing_grants','SELECT'),'grant rows are hidden');
SELECT ok((SELECT relrowsecurity FROM pg_class WHERE oid='public.fluck_web_app_sharing_grants'::regclass),'grant table has RLS');
SELECT ok((SELECT bool_and(prosecdef AND 'search_path=""'=ANY(proconfig)) FROM pg_proc WHERE oid IN(
 'public.fluck_web_request_app_sharing(text,uuid,uuid,uuid)'::regprocedure,
 'public.fluck_web_revoke_app_sharing(text,uuid)'::regprocedure,
 'public.fluck_web_authorize_app_sharing(text,uuid,uuid,text,text)'::regprocedure)),'RPCs are hardened definers');

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','fa110000-0000-0000-0000-000000000001',true);
SELECT throws_ok($q$SELECT public.fluck_web_request_app_sharing(NULL,'fa110000-0000-4000-8000-000000000011','fa110000-0000-4000-8000-000000000012','fa110000-0000-4000-8000-000000000013')$q$,'22023','invalid_request','null instance refused');
SELECT throws_ok($q$SELECT public.fluck_web_request_app_sharing('not-a-uuid','fa110000-0000-4000-8000-000000000011','fa110000-0000-4000-8000-000000000012','fa110000-0000-4000-8000-000000000013')$q$,'22023','invalid_request','instance must match register UUID type');
SELECT throws_ok($q$SELECT public.fluck_web_request_app_sharing('fa110000-0000-4000-8000-000000000099','fa110000-0000-4000-8000-000000000011','fa110000-0000-4000-8000-000000000012','fa110000-0000-4000-8000-000000000013')$q$,'P0001','instance_unavailable','instance must be live and owned');
SELECT set_config('bridge.first',(public.fluck_web_request_app_sharing('fa110000-0000-4000-8000-000000000010','fa110000-0000-4000-8000-000000000011','fa110000-0000-4000-8000-000000000012','fa110000-0000-4000-8000-000000000013')->>'grant'),true);
SELECT is(length(current_setting('bridge.first')),43,'grant is 32 random bytes base64url');
RESET ROLE;
SELECT ok((SELECT expires_at BETWEEN now()+interval '9 minutes 55 seconds' AND now()+interval '10 minutes 5 seconds' FROM public.fluck_web_app_sharing_grants WHERE revoked_at IS NULL),'grant lasts ten minutes');
SELECT is((SELECT count(*)::int FROM public.fluck_web_app_sharing_grants),1,'first mint creates one quota row');
SELECT ok((SELECT bool_and(grant_hash<>current_setting('bridge.first') AND length(grant_hash)=64) FROM public.fluck_web_app_sharing_grants),'only grant hashes are stored');

SET LOCAL ROLE service_role;
SELECT is(public.fluck_web_authorize_app_sharing(current_setting('bridge.first'),'fa110000-0000-4000-8000-000000000012','fa110000-0000-4000-8000-000000000013','register','fa110000-0000-4000-8000-000000000010')::text,'fa110000-0000-0000-0000-000000000001','service role resolves the owner');
SELECT throws_ok($q$SELECT public.fluck_web_authorize_app_sharing(current_setting('bridge.first'),'fa110000-0000-4000-8000-000000000012','fa110000-0000-4000-8000-000000000013','list',NULL)$q$,'P0001','grant_invalid','account-wide actions refused');
SELECT throws_ok($q$SELECT public.fluck_web_authorize_app_sharing(current_setting('bridge.first'),'fa110000-0000-4000-8000-000000000099','fa110000-0000-4000-8000-000000000013','register','fa110000-0000-4000-8000-000000000010')$q$,'P0001','grant_invalid','scope cannot name another session');
SELECT throws_ok($q$SELECT public.fluck_web_authorize_app_sharing(current_setting('bridge.first'),'fa110000-0000-4000-8000-000000000012','fa110000-0000-4000-8000-000000000013','register','fa110000-0000-4000-8000-000000000099')$q$,'P0001','grant_invalid','register instance is pinned');
RESET ROLE;

INSERT INTO public.app_share_sessions(id,owner_id,generation,device_id,instance_id,name,windows,viewer_url,key_epoch,host_public_key,expires_at) VALUES
 ('fa110000-0000-4000-8000-000000000012','fa110000-0000-0000-0000-000000000001','fa110000-0000-4000-8000-000000000013','fa110000-0000-4000-8000-000000000020','fa110000-0000-4000-8000-000000000010','Fluck','[{"id":"boss"}]','https://bridge.invalid/#k=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA','fa110000-0000-4000-8000-000000000021',repeat('A',43),now()+interval '90 seconds'),
 ('fa110000-0000-4000-8000-000000000099','fa110000-0000-0000-0000-000000000001','fa110000-0000-4000-8000-000000000098','fa110000-0000-4000-8000-000000000020','fa110000-0000-4000-8000-000000000010','Native','[{"id":"native"}]','https://native.example/#k=BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB','fa110000-0000-4000-8000-000000000022',repeat('B',43),now()+interval '90 seconds');
SET LOCAL ROLE service_role;
SELECT is(public.fluck_web_authorize_app_sharing(current_setting('bridge.first'),'fa110000-0000-4000-8000-000000000012','fa110000-0000-4000-8000-000000000013','heartbeat',NULL)::text,'fa110000-0000-0000-0000-000000000001','registered bound session remains authorized');
SELECT throws_ok($q$SELECT public.fluck_web_authorize_app_sharing(current_setting('bridge.first'),'fa110000-0000-4000-8000-000000000099','fa110000-0000-4000-8000-000000000098','heartbeat',NULL)$q$,'P0001','grant_invalid','grant cannot impersonate an unrelated native session');
RESET ROLE;

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','fa110000-0000-0000-0000-000000000001',true);
SELECT set_config('bridge.second',(public.fluck_web_request_app_sharing('fa110000-0000-4000-8000-000000000010','fa110000-0000-4000-8000-000000000011','fa110000-0000-4000-8000-000000000012','fa110000-0000-4000-8000-000000000013')->>'grant'),true);
RESET ROLE;
SELECT is((SELECT count(*)::int FROM public.fluck_web_app_sharing_grants),2,'refresh is counted as another quota row');
SET LOCAL ROLE service_role;
SELECT throws_ok($q$SELECT public.fluck_web_authorize_app_sharing(current_setting('bridge.first'),'fa110000-0000-4000-8000-000000000012','fa110000-0000-4000-8000-000000000013','register','fa110000-0000-4000-8000-000000000010')$q$,'P0001','grant_invalid','rotated grant cannot be replayed');
RESET ROLE;

UPDATE public.fluck_web_app_sharing_grants SET expires_at=now()-interval '1 second' WHERE revoked_at IS NULL;
SET LOCAL ROLE service_role;
SELECT throws_ok($q$SELECT public.fluck_web_authorize_app_sharing(current_setting('bridge.second'),'fa110000-0000-4000-8000-000000000012','fa110000-0000-4000-8000-000000000013','register','fa110000-0000-4000-8000-000000000010')$q$,'P0001','grant_invalid','expired grant refused');
RESET ROLE;

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','fa110000-0000-0000-0000-000000000001',true);
SELECT set_config('bridge.third',(public.fluck_web_request_app_sharing('fa110000-0000-4000-8000-000000000010','fa110000-0000-4000-8000-000000000011','fa110000-0000-4000-8000-000000000012','fa110000-0000-4000-8000-000000000013')->>'grant'),true);
SELECT ok(public.fluck_web_revoke_app_sharing('fa110000-0000-4000-8000-000000000010','fa110000-0000-4000-8000-000000000011'),'owner revokes the active grant');
RESET ROLE;
SET LOCAL ROLE service_role;
SELECT throws_ok($q$SELECT public.fluck_web_authorize_app_sharing(current_setting('bridge.third'),'fa110000-0000-4000-8000-000000000012','fa110000-0000-4000-8000-000000000013','register','fa110000-0000-4000-8000-000000000010')$q$,'P0001','grant_invalid','revoked grant refused');
RESET ROLE;

INSERT INTO public.fluck_web_app_sharing_grants(grant_hash,user_id,instance_id,attempt_id,session_id,generation,expires_at,revoked_at)
SELECT encode(extensions.digest('quota-'||n,'sha256'),'hex'),'fa110000-0000-0000-0000-000000000001','fa110000-0000-4000-8000-000000000010',gen_random_uuid(),'fa110000-0000-4000-8000-000000000012','fa110000-0000-4000-8000-000000000013',now()+interval '10 minutes',now()
FROM generate_series(1,17) n;
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','fa110000-0000-0000-0000-000000000001',true);
SELECT throws_ok($q$SELECT public.fluck_web_request_app_sharing('fa110000-0000-4000-8000-000000000010','fa110000-0000-4000-8000-000000000015','fa110000-0000-4000-8000-000000000012','fa110000-0000-4000-8000-000000000013')$q$,'P0001','rate_limited','rotated and revoked grants count toward the atomic quota');
RESET ROLE;

ALTER TABLE public.fluck_web_instances DISABLE TRIGGER fluck_web_instances_touch_on_write;
UPDATE public.fluck_web_instances SET last_seen_at=now()-interval '91 seconds';
ALTER TABLE public.fluck_web_instances ENABLE TRIGGER fluck_web_instances_touch_on_write;
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','fa110000-0000-0000-0000-000000000001',true);
SELECT throws_ok($q$SELECT public.fluck_web_request_app_sharing('fa110000-0000-4000-8000-000000000010','fa110000-0000-4000-8000-000000000014','fa110000-0000-4000-8000-000000000012','fa110000-0000-4000-8000-000000000013')$q$,'P0001','instance_unavailable','stale instance cannot refresh');

SELECT * FROM finish();
ROLLBACK;
