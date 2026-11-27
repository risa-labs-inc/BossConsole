-- Runtime numeric configuration: RLS, validation, optimistic edits and audit history.
begin;
select plan(21);
insert into auth.users (id,email,email_confirmed_at) values
 ('f0260000-0000-4000-8000-000000000001','limits-admin@example.test',now()),
 ('f0260000-0000-4000-8000-000000000002','limits-user@example.test',now());
insert into public.user_roles (user_id,role_id) select 'f0260000-0000-4000-8000-000000000001',id from public.roles where name='admin';
select is((select count(*)::int from public.platform_limits),25,'every registered operational budget is seeded');
select is((select jsonb_object_agg(key,value) from public.platform_limits where key in ('orgsPerOwner','orgRequestsPerDay','marketplaceSubmissionsPerDay','inviteMailPerOrgHour','inviteMailPerActorHour','inviteMailPerEmailHour','inviteMailPerEmailDay')),
  '{"orgsPerOwner":3,"orgRequestsPerDay":5,"marketplaceSubmissionsPerDay":20,"inviteMailPerOrgHour":30,"inviteMailPerActorHour":30,"inviteMailPerEmailHour":3,"inviteMailPerEmailDay":10}'::jsonb,
  'database budgets are seeded with the constants they replace');
select ok(not has_table_privilege('authenticated','public.platform_limits','UPDATE'),'clients cannot bypass the admin RPC');
select ok(not has_function_privilege('anon','public.set_platform_limit(text,jsonb,bigint)','EXECUTE'),'anonymous cannot write');
select ok(not has_function_privilege('authenticated','public.platform_limit_number(text,integer)','EXECUTE'),'internal numeric helper is not an RPC');
set local role anon;
select is((select value from public.platform_limits where key='magicLinkPerEmail'),'[[3,900000],[10,86400000]]'::jsonb,'anonymous runtime reader sees numeric policy');
reset role;
set local role authenticated;
select set_config('request.jwt.claim.sub','f0260000-0000-4000-8000-000000000002',true);
select throws_ok($q$select public.set_platform_limit('magicLinkPerEmail','[[20,900000]]',1)$q$,'42501','Permission denied','non-admin is refused');
select set_config('request.jwt.claim.sub','f0260000-0000-4000-8000-000000000001',true);
select lives_ok($q$select public.set_platform_limit('magicLinkPerEmail','[[20,900000]]',1)$q$,'admin can raise limit');
select is((select revision::int from public.platform_limits where key='magicLinkPerEmail'),2,'revision increments');
select throws_ok($q$select public.set_platform_limit('magicLinkPerEmail','[[30,900000]]',1)$q$,'40001','limit_changed_refresh_required','stale edit cannot overwrite another admin');
select throws_ok($q$select public.set_platform_limit('magicLinkPerEmail','[]',2)$q$,'22023','invalid_limit','empty budgets cannot disable protection');
select throws_ok($q$select public.set_platform_limit('magicLinkPerEmail','[[0,900000]]',2)$q$,'22023','invalid_limit','zero counts are refused');
select throws_ok($q$select public.set_platform_limit('magicLinkPerEmail','[[20,86400001]]',2)$q$,'22023','invalid_limit','windows cannot exceed counter retention');
select throws_ok($q$select public.set_platform_limit('loginLockMs','0',1)$q$,'22023','invalid_limit','zero lock duration refused');
select throws_ok($q$select public.set_platform_limit('loginLockMs','999',1)$q$,'22023','invalid_limit','sub-second lock duration refused');
select throws_ok($q$select public.set_platform_limit('unknown','1',1)$q$,'22023','unknown_limit','unknown key refused');
select throws_ok($q$select public.set_platform_limit('orgsPerOwner','3.0',1)$q$,'22023','invalid_limit','a fractional representation cannot reach the integer readers');
select throws_ok($q$select public.set_platform_limit('magicLinkPerEmail','[[3.0,900000]]',2)$q$,'22023','invalid_limit','window counts must be plain integers');
select lives_ok($q$select public.set_platform_limit('orgsPerOwner','25',1)$q$,'database budget editable');
select ok(exists(select 1 from public.platform_settings_history(100,null) where key='limit.magicLinkPerEmail' and from_value='[[3,900000],[10,86400000]]'::jsonb and to_value='[[20,900000]]'::jsonb),'change visible in existing settings history');
reset role;
select is(public.platform_limit_number('orgsPerOwner',3),25,'database functions see new value immediately');
select * from finish();
rollback;
