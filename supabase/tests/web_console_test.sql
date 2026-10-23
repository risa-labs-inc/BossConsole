-- pgTAP tests for 20261021130000_web_console: the web_pod_self_serve flag, its public reader, and "web" reserved
-- as an agent slug. Run with: supabase test db
begin;
select plan(9);

insert into auth.users (id, email, email_confirmed_at) values
    ('c7000000-0000-4000-8000-0000000000a1', 'person@webc.test', now()),
    ('c7000000-0000-4000-8000-0000000000a2', 'platform@webc.test', now());
insert into public.user_roles (user_id, role_id)
    select 'c7000000-0000-4000-8000-0000000000a2', r.id from public.roles r where r.name = 'admin'
    on conflict do nothing;

create function pg_temp.as_user(p uuid) returns void language sql as $$
    select set_config('request.jwt.claim.sub', p::text, true),
           set_config('request.jwt.claims', json_build_object('role', 'authenticated', 'sub', p)::text, true);
$$;
grant execute on function pg_temp.as_user(uuid) to authenticated, anon, service_role;

select is((select value from public.platform_flags where key = 'web_pod_self_serve'), 'false'::jsonb, 'web_pod_self_serve exists, off');
select ok(has_function_privilege('anon', 'public.web_pod_self_serve()', 'EXECUTE'), 'anon may read the flag through its function');
select ok(exists (select 1 from public.agent_reserved_slugs where slug = 'web'), '"web" is a reserved agent slug');

set local role anon;
select set_config('request.jwt.claims', '{"role":"anon"}', true);
select is(public.web_pod_self_serve(), false, 'anon reads false');
select throws_ok($$ select count(*) from public.platform_flags $$, '42501', null, 'anon still cannot read platform_flags itself');
reset role;

-- A plain person cannot turn it on.
select pg_temp.as_user('c7000000-0000-4000-8000-0000000000a1');
set local role authenticated;
update public.platform_flags set value = 'true'::jsonb where key = 'web_pod_self_serve';
reset role;
select is((select value from public.platform_flags where key = 'web_pod_self_serve'), 'false'::jsonb, 'a plain person cannot flip it');

-- A platform admin can, and the public reader follows.
select pg_temp.as_user('c7000000-0000-4000-8000-0000000000a2');
set local role authenticated;
update public.platform_flags set value = 'true'::jsonb where key = 'web_pod_self_serve';
reset role;
select is((select value from public.platform_flags where key = 'web_pod_self_serve'), 'true'::jsonb, 'a platform admin flips it');
set local role anon;
select is(public.web_pod_self_serve(), true, 'the public reader follows the flag');
reset role;

-- Nobody can create an agent at web.<base>.
select throws_ok($$ insert into public.agents (slug, org_id, name, plugin_id)
    values ('web', (select id from public.organisations limit 1), 'Web', 'ai.rever.boss.plugin.dynamic.agent.web') $$,
    null, null, 'an agent cannot take the slug "web"');

select * from finish();
rollback;
