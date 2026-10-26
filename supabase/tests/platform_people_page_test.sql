BEGIN;
SELECT plan(23);
INSERT INTO auth.users(id,email,email_confirmed_at)
 SELECT ('e2600000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,'person'||n||'@people-page.test',now() FROM generate_series(1,305)n;
INSERT INTO public.user_roles(user_id,role_id) SELECT 'e2600000-0000-4000-8000-000000000001',id FROM public.roles WHERE name='admin';
INSERT INTO public.agents_service_accounts(user_id,purpose) VALUES('e2600000-0000-4000-8000-000000000304','provisioner');
UPDATE auth.users SET deleted_at=now() WHERE id='e2600000-0000-4000-8000-000000000305';
SELECT ok(NOT has_function_privilege('anon','public.platform_people_page(uuid,int,text)','EXECUTE'),'anonymous directory denied');
SET LOCAL ROLE anon;
SELECT throws_ok($q$SELECT public.platform_people_page()$q$,'42501',NULL,'anonymous actual directory call denied');
RESET ROLE;
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claims','{"sub":"e2600000-0000-4000-8000-000000000002","role":"authenticated"}',true);
SELECT throws_ok($q$SELECT public.platform_people_page()$q$,'42501',NULL,'ordinary user denied');
SELECT set_config('request.jwt.claims','{"sub":"e2600000-0000-4000-8000-000000000001","role":"authenticated"}',true);
CREATE TEMP TABLE pages(n int,data jsonb);
INSERT INTO pages VALUES(1,public.platform_people_page(NULL,200,'@people-page.test'));
INSERT INTO pages SELECT 2,public.platform_people_page((data->>'next_cursor')::uuid,200,'@people-page.test') FROM pages WHERE n=1;
SELECT is(jsonb_array_length((SELECT data->'people' FROM pages WHERE n=1)),200,'first page capped only per request');
SELECT is(jsonb_array_length((SELECT data->'people' FROM pages WHERE n=2)),103,'second page includes all remaining accounts');
SELECT is((SELECT data->>'next_cursor' FROM pages WHERE n=2),NULL,'final page has no cursor');
SELECT is((SELECT count(DISTINCT p->>'user_id')::int FROM pages,jsonb_array_elements(data->'people')p),303,'no duplicate or omitted accounts beyond200');
SELECT is((SELECT count(*)::int FROM pages,jsonb_array_elements(data->'people')p WHERE p->>'email'='person304@people-page.test'),0,'service account excluded');
SELECT is((SELECT count(*)::int FROM pages,jsonb_array_elements(data->'people')p WHERE p->>'email'='person305@people-page.test'),0,'deleted account excluded');
SELECT is((SELECT count(*)::int FROM pages,jsonb_array_elements(data->'people')p WHERE p->'installs'='[]'::jsonb AND p->'tier'='null'::jsonb),303,'unmanaged users visible without invented installs or tiers');
SELECT is(jsonb_array_length(public.platform_people_page(NULL,200,'PERSON303@PEOPLE-PAGE.TEST')->'people'),1,'search case insensitive');
SELECT is(jsonb_array_length(public.platform_people_page(NULL,200,'no-such-user@people-page.test')->'people'),0,'empty search valid');
SELECT is(jsonb_array_length(public.platform_people_page(NULL,0,'@people-page.test')->'people'),1,'zero limit clamped');
SELECT is(jsonb_array_length(public.platform_people_page(NULL,9999,'@people-page.test')->'people'),200,'large limit clamped');
SELECT throws_ok($q$SELECT public.platform_people_page(NULL,100,repeat('a',321))$q$,'22023','query_too_long','oversized query rejected');
RESET ROLE;
SELECT ok((SELECT prosecdef AND 'search_path=""'=ANY(proconfig) FROM pg_proc WHERE oid='public.platform_people_page(uuid,int,text)'::regprocedure),'directory search_path pinned');

-- Exercise the real one-tier-per-user join and ordered active installs, not only empty accounts.
SELECT set_config('request.jwt.claims','{"role":"service_role"}',true);
SELECT public.create_organisation_internal(p_slug=>'peoplefixtures',p_name=>'People fixtures',p_description=>NULL,p_owner_id=>'e2600000-0000-4000-8000-000000000001',p_domain=>NULL,p_visibility=>'private',p_join_policy=>'invite_only');
INSERT INTO public.agents(slug,org_id,name,plugin_id,created_by)
 SELECT slug,(SELECT id FROM organisations WHERE slug='peoplefixtures'),slug,'ai.rever.boss.plugin.dynamic.agent.'||slug,'e2600000-0000-4000-8000-000000000001'::uuid
 FROM unnest(ARRAY['peoplezz','peopleaa','peopleremoved'])slug;
INSERT INTO public.agent_installs(user_id,agent_id,instance_id,port,data_dir,state,last_seen_at)
 SELECT 'e2600000-0000-4000-8000-000000000002'::uuid,id,'people-test-'||slug,41001,'agents/'||slug,
 CASE WHEN slug='peopleremoved' THEN 'removed' ELSE 'running' END,
 CASE WHEN slug='peopleaa' THEN '2026-01-02'::timestamptz WHEN slug='peoplezz' THEN '2026-01-01'::timestamptz ELSE '2026-01-03'::timestamptz END
 FROM agents WHERE slug IN('peoplezz','peopleaa','peopleremoved');
INSERT INTO public.pod_tiers(user_id,tier,note,assigned_by) VALUES('e2600000-0000-4000-8000-000000000002','premium','fixture assignment','e2600000-0000-4000-8000-000000000001');
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claims','{"sub":"e2600000-0000-4000-8000-000000000001","role":"authenticated"}',true);
INSERT INTO pages VALUES(3,public.platform_people_page(NULL,200,'person2@people-page.test'));
SELECT is((SELECT jsonb_array_length(data->'people') FROM pages WHERE n=3),1,'tier join preserves one person');
SELECT is((SELECT data#>>'{people,0,tier}' FROM pages WHERE n=3),'premium','tier included');
SELECT is((SELECT data#>>'{people,0,tier_note}' FROM pages WHERE n=3),'fixture assignment','tier note included');
SELECT is((SELECT data#>>'{people,0,tier_assigned_by_email}' FROM pages WHERE n=3),'person1@people-page.test','assigner email resolved');
SELECT is((SELECT jsonb_array_length(data#>'{people,0,installs}') FROM pages WHERE n=3),2,'removed install excluded');
SELECT is((SELECT data#>>'{people,0,installs,0,slug}' FROM pages WHERE n=3),'peopleaa','installs ordered by slug');
SELECT is((SELECT (data#>>'{people,0,last_seen_at}')::timestamptz FROM pages WHERE n=3),'2026-01-02'::timestamptz,'last seen uses latest non-removed install');
RESET ROLE;
SELECT * FROM finish();
ROLLBACK;
