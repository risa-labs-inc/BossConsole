begin;
select plan(46);
insert into auth.users(id,email,email_confirmed_at) values
 ('e1000000-0000-4000-8000-000000000001','admin@inventory.test',now()),
 ('e1000000-0000-4000-8000-000000000002','bot@inventory.test',now()),
 ('e1000000-0000-4000-8000-000000000003','owner@inventory.test',now()),
 ('e1000000-0000-4000-8000-000000000004','member@inventory.test',now());
insert into public.user_roles(user_id,role_id) select 'e1000000-0000-4000-8000-000000000001',id from public.roles where name='admin';
insert into public.agents_service_accounts(user_id,purpose) values('e1000000-0000-4000-8000-000000000002','provisioner');
create function pg_temp.as_user(p uuid) returns void language sql as $$
 select set_config('request.jwt.claim.sub',coalesce(p::text,''),true),set_config('request.jwt.claims',json_build_object('role','authenticated','sub',p)::text,true);
$$;
create function pg_temp.payload(n integer default 2) returns jsonb language sql as $$
 select jsonb_build_object('runtime_available',true,'builtins',jsonb_build_object('optimist',true,'max',true),'instances',
 coalesce((select jsonb_agg(jsonb_build_object('instance_id','instance-'||i,'state','active','capacity',1,'image','registry/boss@sha256:test','tier','standard',
 'runtime',jsonb_build_object('state','running','health','healthy','started_at',1000,'restart_count',0),'users',
 case when i=1 then '[{"email":"OWNER@inventory.test","boss_user_id":"e1000000-0000-4000-8000-000000000003","state":"assigned"}]'::jsonb else '[]'::jsonb end) order by i) from generate_series(1,n)i),'[]'::jsonb));
$$;
create temp table t(at timestamptz);insert into t values(clock_timestamp()-interval '60 seconds');grant select on t to authenticated,anon,service_role;
grant execute on all functions in schema pg_temp to authenticated,anon,service_role;
create temp table original_counts as select (select count(*) from public.agents) agents,(select count(*) from public.agent_installs) installs,
 (select count(*) from public.pod_tiers) tiers,(select count(*) from public.web_pod_requests) requests;

select ok(not has_function_privilege('anon','public.platform_inventory()','execute'),'anon cannot read inventory RPC');
select ok(not has_function_privilege('anon','public.sync_platform_inventory(text,uuid,timestamptz,jsonb)','execute'),'anon cannot sync');
select ok(not has_table_privilege('authenticated','public.platform_pod_inventory','INSERT'),'authenticated direct inventory inserts denied');
select ok(not has_table_privilege('service_role','public.platform_pod_inventory','INSERT'),'service direct inventory writes denied; sync is purpose-gated');
select ok((select bool_and(prosecdef and 'search_path=""'=any(proconfig)) from pg_proc where oid in
 ('public.platform_inventory()'::regprocedure,'public.sync_platform_inventory(text,uuid,timestamptz,jsonb)'::regprocedure)), 'RPCs pin definer search_path');
set local role anon;
select throws_ok('select public.platform_inventory()','42501',null,'anon actual read denied');
reset role;
set local role authenticated;
select pg_temp.as_user('e1000000-0000-4000-8000-000000000004');
select throws_ok('select public.platform_inventory()','42501',null,'member read denied');
select throws_ok($q$select public.sync_platform_inventory('test',gen_random_uuid(),now(),pg_temp.payload())$q$,'42501',null,'member sync denied');
select is_empty('select * from public.platform_inventory_sources','member direct sources hidden');
select is_empty('select * from public.platform_pod_inventory','member direct pods hidden');
select is_empty('select * from public.platform_pod_owners','member direct owners hidden');

select pg_temp.as_user('e1000000-0000-4000-8000-000000000001');
select is(public.platform_inventory()->'instances','[]'::jsonb,'no snapshot distinguished as empty');
select is(public.platform_inventory()->'stale','true'::jsonb,'no snapshot is stale');
select is(public.platform_inventory()->'synced_at','null'::jsonb,'no fabricated sync time');
select throws_ok($q$select public.sync_platform_inventory('test',gen_random_uuid(),now(),pg_temp.payload())$q$,'42501',null,'global admin cannot impersonate sync bot');

select pg_temp.as_user('e1000000-0000-4000-8000-000000000002');
select is(public.sync_platform_inventory('test','e2000000-0000-4000-8000-000000000001',(select at from t),pg_temp.payload()),
 '{"accepted":true,"instances":2}'::jsonb,'provisioner syncs complete assigned and spare inventory');
select throws_ok('select public.platform_inventory()','42501',null,'bot cannot read platform-admin projection');
select throws_ok($q$insert into public.platform_pod_inventory(deployment,instance_id,state,capacity,tier,runtime_state,health,started_at)
 values('test','bypass','active',1,'standard','running','healthy',0)$q$,'42501',null,'bot cannot bypass sync validation with direct insert');
select is(public.sync_platform_inventory('test','e2000000-0000-4000-8000-000000000001',(select at from t),pg_temp.payload(0))->>'reason','duplicate','retry is idempotent, cannot replace body');
select is(public.sync_platform_inventory('test',gen_random_uuid(),(select at-interval '1 second' from t),pg_temp.payload(0))->>'reason','stale','older snapshot cannot delete newer rows');
select is(public.sync_platform_inventory('other',gen_random_uuid(),(select at from t),pg_temp.payload(1))->>'accepted','true','separate deployment supports same owner/instance ids');

select pg_temp.as_user('e1000000-0000-4000-8000-000000000001');
select is(jsonb_array_length(public.platform_inventory()->'instances'),3,'all deployment rows returned');
select is(public.platform_inventory()->'builtins','{"optimist":true,"max":true}'::jsonb,'configured builtins retained separately');
select is((select count(*)::int from public.platform_pod_owners where deployment='test'),1,'spare has no invented owner');
select is((select x#>>'{users,0,email}' from jsonb_array_elements(public.platform_inventory()->'instances')x where x->>'deployment'='test' and x->>'instance_id'='instance-1'),
 'owner@inventory.test','owner email comes from authoritative Auth record');
select is(public.platform_inventory()->'stale','false'::jsonb,'fresh healthy snapshot is not stale');

select pg_temp.as_user('e1000000-0000-4000-8000-000000000002');
select is(public.sync_platform_inventory('test',gen_random_uuid(),(select at+interval '1 second' from t),jsonb_set(pg_temp.payload(0),'{runtime_available}','false'))->>'reason',
 'runtime_unavailable','runtime failure recorded without claiming a successful snapshot');
select is(public.sync_platform_inventory('test',gen_random_uuid(),(select at from t),pg_temp.payload(0))->>'reason','stale','older healthy response cannot overwrite a newer failure');
select pg_temp.as_user('e1000000-0000-4000-8000-000000000001');
select is((select count(*)::int from public.platform_pod_inventory where deployment='test'),2,'runtime failure cannot erase rows');
select is((select observed_at from public.platform_inventory_sources where deployment='test'),(select at from t),'runtime failure preserves last good observation time');
select is(public.platform_inventory()->'stale','true'::jsonb,'failed runtime explicitly stale');
select is(public.platform_inventory()->'runtime_available','false'::jsonb,'failed runtime not marked available');

select pg_temp.as_user('e1000000-0000-4000-8000-000000000002');
select throws_ok($q$select public.sync_platform_inventory('test',gen_random_uuid(),(select at+interval '2 seconds' from t),
 jsonb_set(pg_temp.payload(),'{instances,1,users}','[{"email":"wrong@inventory.test","boss_user_id":"e1000000-0000-4000-8000-000000000003","state":"assigned"}]'))$q$,
 '22023','inventory_owner_mismatch','mid-batch invalid owner rejects whole snapshot');
select pg_temp.as_user('e1000000-0000-4000-8000-000000000001');
select is((select count(*)::int from public.platform_pod_inventory where deployment='test'),2,'mid-batch rollback preserves prior instances');
select is((select last_attempt_at from public.platform_inventory_sources where deployment='test'),(select at+interval '1 second' from t),'mid-batch rollback preserves attempt ordering too');
select pg_temp.as_user('e1000000-0000-4000-8000-000000000002');
select throws_ok($q$select public.sync_platform_inventory('test',gen_random_uuid(),(select at+interval '2 seconds' from t),jsonb_set(pg_temp.payload(),'{instances,0,webchat_secret}','"secret"'))$q$,
 '22023','invalid_inventory_instance','unexpected credential fields rejected rather than persisted');
select throws_ok($q$select public.sync_platform_inventory('test',gen_random_uuid(),now()+interval '1 hour',pg_temp.payload())$q$,
 '22023','invalid_inventory_snapshot','far-future snapshot cannot lock out legitimate updates');
select throws_ok($q$select public.sync_platform_inventory('test',gen_random_uuid(),(select at+interval '2 seconds' from t),
 jsonb_set(pg_temp.payload(),'{instances,0,users,0,boss_user_id}','"e1000000-0000-4000-8000-000000000099"'))$q$,
 '22023','inventory_owner_mismatch','unknown Auth user cannot be invented');
select is(public.sync_platform_inventory('test',gen_random_uuid(),(select at+interval '2 seconds' from t),pg_temp.payload(0))->>'accepted','true','healthy empty snapshot deletes only its deployment');
select pg_temp.as_user('e1000000-0000-4000-8000-000000000001');
select is((select count(*)::int from public.platform_pod_inventory where deployment='test'),0,'removed instances disappear');
select is((select count(*)::int from public.platform_pod_owners where deployment='test'),0,'removed instance owners cascade');
select is((select count(*)::int from public.platform_pod_inventory where deployment='other'),1,'other deployment not deleted');

select pg_temp.as_user('e1000000-0000-4000-8000-000000000002');
select is(public.sync_platform_inventory('many',gen_random_uuid(),(select at from t),pg_temp.payload(121))->>'instances','121','snapshot supports more than100instances');
select pg_temp.as_user('e1000000-0000-4000-8000-000000000001');
select is(jsonb_array_length(public.platform_inventory()->'instances'),122,'admin read is complete, no100row truncation');
reset role;
update public.platform_inventory_sources set observed_at=now()-interval '5 minutes' where deployment='many';
set local role authenticated;
select is(public.platform_inventory()->'stale','true'::jsonb,'missed periodic sync becomes stale by age');
reset role;
select ok((select agents=(select count(*) from public.agents) and installs=(select count(*) from public.agent_installs)
 and tiers=(select count(*) from public.pod_tiers) and requests=(select count(*) from public.web_pod_requests) from original_counts),
 'inventory sync never creates agents, installs, tier assignments or requests');
select * from finish();
rollback;
