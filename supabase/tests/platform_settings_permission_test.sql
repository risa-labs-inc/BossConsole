-- Settings delegation must not become platform administration or organisation privilege escalation.
begin;
select plan(56);
insert into auth.users (id, email, email_confirmed_at) values
 ('d1000000-0000-4000-8000-000000000001', 'global@settings.test', now()),
 ('d1000000-0000-4000-8000-000000000002', 'manager@settings.test', now()),
 ('d1000000-0000-4000-8000-000000000003', 'member@settings.test', now()),
 ('d1000000-0000-4000-8000-000000000004', 'owner@settings.test', now()),
 ('d1000000-0000-4000-8000-000000000005', 'other@settings.test', now());
insert into public.user_roles (user_id, role_id)
 select 'd1000000-0000-4000-8000-000000000001', id from public.roles where name='admin';
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select public.create_organisation_internal(p_slug=>'settings_test',p_name=>'Settings test',p_description=>null,
 p_owner_id=>'d1000000-0000-4000-8000-000000000004',p_domain=>'settings.test',p_visibility=>'private',p_join_policy=>'invite_only');
create function pg_temp.as_user(p uuid) returns void language sql as $$
 select set_config('request.jwt.claim.sub',coalesce(p::text,''),true),
        set_config('request.jwt.claims',json_build_object('role','authenticated','sub',p)::text,true);
$$;
grant execute on function pg_temp.as_user(uuid) to authenticated, anon, service_role;
-- Ensure an arbitrary non-UI flag and an unrelated audit record exist, without depending on prior fixtures.
insert into public.platform_flags(key,value) values ('settings_test_private','"original"') on conflict do nothing;
insert into public.platform_audit_log(actor,action,detail) values
 ('d1000000-0000-4000-8000-000000000001','platform.flag_set','{"key":"settings_test_private","from":false,"to":true}'),
 ('d1000000-0000-4000-8000-000000000001','platform.unrelated','{}');

select ok(not has_function_privilege('anon','public.platform_capabilities()','EXECUTE'),'anon cannot call capabilities');
select ok(not has_table_privilege('anon','public.platform_permission_grants','SELECT'),'anon cannot read grants');
select ok(not has_table_privilege('authenticated','public.platform_permission_grants','UPDATE'),'authenticated cannot rewrite grants or provenance');
select ok(not has_column_privilege('authenticated','public.platform_flags','key','UPDATE'),'flag keys cannot be rewritten');
select ok((select bool_and(prosecdef and 'search_path=""'=any(proconfig)) from pg_proc where oid in
 ('public.platform_capabilities()'::regprocedure,'public.platform_can_manage_settings()'::regprocedure,
 'public.platform_permission_grants_audit()'::regprocedure)), 'new privileged helpers pin empty search_path');
set local role anon;
select pg_temp.as_user(null);
select throws_ok('select public.platform_capabilities()','42501',null,'anon actual RPC denied');
select throws_ok('select * from public.platform_settings_history()','42501',null,'anon history denied');
reset role;

set local role authenticated;
select pg_temp.as_user('d1000000-0000-4000-8000-000000000003');
select is(public.platform_capabilities(),' {"admin":false,"settings_manage":false}'::jsonb,'ordinary member has neither capability');
select is_empty('select * from public.platform_permission_grants','ordinary member cannot enumerate grants');
select throws_ok($q$insert into public.platform_permission_grants(user_id,permission) values
 ('d1000000-0000-4000-8000-000000000003','platform.settings.manage')$q$,'42501',null,'ordinary member cannot self-grant');
select is_empty($q$update public.platform_flags set value='true' where key='agent_creation_open' returning key$q$,'ordinary member cannot change settings');
select throws_ok('select * from public.platform_settings_history()','42501',null,'ordinary member cannot read history');
select throws_ok($q$select public.platform_capabilities('d1000000-0000-4000-8000-000000000001'::uuid)$q$,'42883',null,'capabilities has no cross-user signature');

select pg_temp.as_user('d1000000-0000-4000-8000-000000000004');
reset role;
select ok(public.user_is_org_admin('d1000000-0000-4000-8000-000000000004',(select id from public.organisations where slug='settings_test')),'fixture is a genuine org admin');
set local role authenticated;
select is(public.platform_capabilities(),'{"admin":false,"settings_manage":false}'::jsonb,'org admin has no platform capabilities');
select throws_ok($q$insert into public.platform_permission_grants(user_id,permission) values
 ('d1000000-0000-4000-8000-000000000004','platform.settings.manage')$q$,'42501',null,'org admin cannot self-grant');
select is_empty($q$update public.platform_flags set value='true' where key='web_pod_self_serve' returning key$q$,'org admin cannot change settings');

select pg_temp.as_user('d1000000-0000-4000-8000-000000000001');
select is(public.platform_capabilities(),'{"admin":true,"settings_manage":true}'::jsonb,'global admin retains both capabilities');
select lives_ok($q$insert into public.platform_permission_grants(user_id,permission) values
 ('d1000000-0000-4000-8000-000000000002','platform.settings.manage'),
 ('d1000000-0000-4000-8000-000000000005','platform.settings.manage')$q$,'global admin can delegate');
select is((select granted_by from public.platform_permission_grants where user_id='d1000000-0000-4000-8000-000000000002'),
 'd1000000-0000-4000-8000-000000000001'::uuid,'grant provenance records caller');
select throws_ok($q$insert into public.platform_permission_grants(user_id,permission,granted_by) values
 ('d1000000-0000-4000-8000-000000000003','platform.settings.manage','d1000000-0000-4000-8000-000000000004')$q$,
 '42501',null,'even global REST insert cannot forge provenance');
select throws_ok($q$insert into public.platform_permission_grants(user_id,permission) values
 ('d1000000-0000-4000-8000-000000000003','platform.admin')$q$,'23514',null,'grant catalog permits settings only');
select ok(exists(select 1 from public.platform_audit_log where action='platform.permission_granted'
 and target_user='d1000000-0000-4000-8000-000000000002' and actor=auth.uid()),'grant is audited');

select pg_temp.as_user('d1000000-0000-4000-8000-000000000002');
select is(public.platform_capabilities(),'{"admin":false,"settings_manage":true}'::jsonb,'delegate receives settings but not admin');
select is((select count(*)::int from public.platform_permission_grants),1,'delegate sees only own grant');
select is_empty($q$select * from public.platform_permission_grants where user_id='d1000000-0000-4000-8000-000000000005'$q$,'cross-user grant read hidden');
select throws_ok($q$insert into public.platform_permission_grants(user_id,permission) values
 ('d1000000-0000-4000-8000-000000000003','platform.settings.manage')$q$,'42501',null,'delegate cannot grant another user');
select is_empty($q$delete from public.platform_permission_grants where user_id=auth.uid() returning user_id$q$,'delegate cannot delete grants');
select is_empty($q$delete from public.platform_permission_grants where user_id='d1000000-0000-4000-8000-000000000005' returning user_id$q$,'delegate cannot revoke another user');
select throws_ok($q$update public.platform_permission_grants set user_id='d1000000-0000-4000-8000-000000000003' where user_id=auth.uid()$q$,'42501',null,'delegate cannot rewrite immutable grant target');
select results_eq($q$with updated as (update public.platform_flags set value=(case when value='true'::jsonb then 'false'::jsonb else 'true'::jsonb end)
 where key in ('agent_creation_open','agent_update_review','org_creation_open','web_pod_self_serve') returning key) select key from updated order by key$q$,
 $q$values ('agent_creation_open'::text),('agent_update_review'),('org_creation_open'),('web_pod_self_serve')$q$,
 'delegate updates all four allowed existing booleans');
select throws_ok($q$update public.platform_flags set value='"true"' where key='agent_creation_open'$q$,'42501',null,'string boolean rejected');
select throws_ok($q$update public.platform_flags set value='null' where key='agent_creation_open'$q$,'42501',null,'JSON null rejected');
select throws_ok($q$update public.platform_flags set value='{"enabled":true}' where key='agent_creation_open'$q$,'42501',null,'object value rejected');
select is_empty($q$update public.platform_flags set value='"changed"' where key='settings_test_private' returning key$q$,'other flag update hidden by RLS');
select throws_ok($q$update public.platform_flags set key='settings_test_private' where key='agent_creation_open'$q$,'42501',null,'cannot rekey an allowed row');
select throws_ok($q$insert into public.platform_flags(key,value) values('new_setting','true')$q$,'42501',null,'cannot insert a flag');
select throws_ok($q$delete from public.platform_flags where key='agent_creation_open'$q$,'42501',null,'cannot delete a flag');
select ok((select count(*)>0 from public.platform_settings_history()),'delegate reads allowed history');
select is_empty($q$select * from public.platform_settings_history(100) where key not in
 ('agent_creation_open','agent_update_review','org_creation_open','web_pod_self_serve')$q$,'delegate history excludes arbitrary flags');
select is_empty('select * from public.platform_audit_log','delegate cannot read raw platform audit');
select throws_ok('select * from public.list_pending_agents()','42501',null,'delegate cannot read platform review queue');
reset role;
select ok(exists(select 1 from public.platform_audit_log where action='platform.flag_set'
 and actor='d1000000-0000-4000-8000-000000000002' and detail->>'key'='web_pod_self_serve'),'delegate flag changes retain audit');

-- A lookalike permission granted through an extensible org role must not provide this capability.
insert into public.permissions(name,description,is_system) values('platform.settings.manage','Lookalike test',false) on conflict do nothing;
insert into public.role_permissions(role_id,permission_id)
 select orl.role_id,p.id from public.organisation_roles orl join public.organisations o on o.id=orl.org_id
 cross join public.permissions p where o.slug='settings_test' and orl.kind='admin' and p.name='platform.settings.manage'
 on conflict do nothing;
set local role authenticated;
select pg_temp.as_user('d1000000-0000-4000-8000-000000000004');
select is(public.platform_capabilities(),'{"admin":false,"settings_manage":false}'::jsonb,'org role permission with identical name cannot escalate');

select pg_temp.as_user('d1000000-0000-4000-8000-000000000001');
select lives_ok($q$update public.platform_flags set value='"admin-change"' where key='settings_test_private'$q$,'global admin retains arbitrary flag updates');
select ok(exists(select 1 from public.platform_settings_history(100) where key='settings_test_private'),'global admin retains all flag history');
select lives_ok($q$delete from public.platform_permission_grants where user_id='d1000000-0000-4000-8000-000000000002'$q$,'global admin revokes grant');
select ok(exists(select 1 from public.platform_audit_log where action='platform.permission_revoked'
 and target_user='d1000000-0000-4000-8000-000000000002' and actor=auth.uid()),'revoke is audited');
select pg_temp.as_user('d1000000-0000-4000-8000-000000000002');
select is(public.platform_capabilities(),'{"admin":false,"settings_manage":false}'::jsonb,'revocation takes effect on next RPC');
select is_empty($q$update public.platform_flags set value='true' where key='agent_creation_open' returning key$q$,'revocation blocks next direct update');
select throws_ok('select * from public.platform_settings_history()','42501',null,'revocation blocks next history request');
select pg_temp.as_user('d1000000-0000-4000-8000-000000000005');
select is(public.platform_capabilities(),'{"admin":false,"settings_manage":true}'::jsonb,'revocation does not affect another delegate');
reset role;
set local role service_role;
select pg_temp.as_user(null);
select lives_ok($q$insert into public.platform_permission_grants(user_id,permission) values
 ('d1000000-0000-4000-8000-000000000003','platform.settings.manage')$q$,'service role can provision scoped grant');
select is(public.platform_capabilities(),'{"admin":false,"settings_manage":false}'::jsonb,'service role without a user has no caller capability');
select lives_ok($q$delete from public.platform_permission_grants where user_id='d1000000-0000-4000-8000-000000000003'$q$,'service role can revoke scoped grant');
reset role;
select ok(exists(select 1 from public.platform_audit_log where action='platform.permission_revoked'
 and target_user='d1000000-0000-4000-8000-000000000003' and actor is null),'service revocation audited without fabricated user');
reset role;
select * from finish();
rollback;
