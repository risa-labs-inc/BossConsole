-- pgTAP tests for 20261023110000_qa_org_polish: custom role display names and descriptions, and web BOSS pods in
-- platform_people_pods.
begin;
select plan(13);

insert into auth.users (id, email, email_confirmed_at) values
    ('c5000000-0000-4000-8000-0000000000a1', 'owner@polish.test', now()),
    ('c5000000-0000-4000-8000-0000000000a2', 'member@polish.test', now()),
    ('c5000000-0000-4000-8000-0000000000d1', 'web1@polish.test', now()),
    ('c5000000-0000-4000-8000-0000000000d2', 'web2@polish.test', now()),
    ('c5000000-0000-4000-8000-0000000000d3', 'web3@polish.test', now()),
    ('c5000000-0000-4000-8000-0000000000c1', 'platform@polish.test', now());
insert into public.user_roles (user_id, role_id)
    select 'c5000000-0000-4000-8000-0000000000c1', id from public.roles where name = 'admin';

select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select public.create_organisation_internal(p_slug => 'qa_polish_org', p_name => 'QA Polish', p_description => null,
    p_owner_id => 'c5000000-0000-4000-8000-0000000000a1', p_domain => null, p_visibility => 'private',
    p_join_policy => 'invite_only');
select set_config('request.jwt.claims', '', true);

create temp table ids (k text primary key, id uuid) on commit drop;
grant all on ids to authenticated, anon, service_role;
insert into ids select 'org', id from public.organisations where slug = 'qa_polish_org';
create function pg_temp.id(p text) returns uuid language sql stable as $$ select id from ids where k = p $$;
create function pg_temp.u(p text) returns uuid language sql immutable as $$
    select ('c5000000-0000-4000-8000-0000000000' || p)::uuid $$;
create function pg_temp.as_user(p text) returns void language sql as $$
    select set_config('request.jwt.claim.sub', pg_temp.u(p)::text, true),
           set_config('request.jwt.claims', json_build_object('role', 'authenticated', 'sub', pg_temp.u(p))::text, true);
$$;
grant execute on function pg_temp.id(text), pg_temp.u(text), pg_temp.as_user(text) to authenticated, anon, service_role;

insert into public.organisation_members (org_id, user_id, status, joined_at, join_source)
    values (pg_temp.id('org'), pg_temp.u('a2'), 'active', now(), 'admin');

-- Web BOSS requests: approved, set up, and dismissed (the last stays out of the default list).
insert into public.web_pod_requests (user_id, status, decided_at) values
    (pg_temp.u('d1'), 'provisioned', now()), (pg_temp.u('d2'), 'approved', now()), (pg_temp.u('d3'), 'dismissed', now());

-- 1. Roles
select pg_temp.as_user('a1');
insert into ids select 'qadomains', public.org_team_create_role(pg_temp.id('org'), 'qadomains',
    array['agents.domains', 'agents.access'], null, 'qaDomains');
select is((select display_name from public.org_team_roles(pg_temp.id('org')) where role_id = pg_temp.id('qadomains')),
    'qaDomains', 'the display name is kept as typed');
select is((select name from public.org_team_roles(pg_temp.id('org')) where role_id = pg_temp.id('qadomains')),
    'qa_polish_org_qadomains', 'the key stays <slug>_<suffix>');
select is((select description from public.org_team_roles(pg_temp.id('org')) where role_id = pg_temp.id('qadomains')),
    null, 'no description given: none stored (no generic filler)');
select is((select agent_permissions from public.org_team_roles(pg_temp.id('org')) where role_id = pg_temp.id('qadomains')),
    array['agents.view', 'agents.access', 'agents.domains'], 'permissions as before, View implied');

insert into ids select 'described', public.org_team_create_role(pg_temp.id('org'), 'described', array['agents.view'], 'Reads things');
select is((select description from public.org_team_roles(pg_temp.id('org')) where role_id = pg_temp.id('described')),
    'Reads things', 'a description is kept (four-argument call still works)');
select is((select display_name from public.org_team_roles(pg_temp.id('org')) where role_id = pg_temp.id('described')),
    null, 'no display name: null, the console derives it');
select throws_ok($q$ select public.org_team_create_role(pg_temp.id('org'), 'toolong', array['agents.view'], null, repeat('x', 41)) $q$,
    'P0001', 'display_name_too_long', 'a display name over 40 characters is refused');
select is((select count(*)::int from pg_proc where proname = 'org_team_create_role' and pronamespace = 'public'::regnamespace),
    1, 'one org_team_create_role signature (no ambiguous overloads)');

select pg_temp.as_user('a2');
select throws_ok($q$ select public.org_team_create_role(pg_temp.id('org'), 'sneaky', array['agents.view'], null, 'Sneaky') $q$,
    '42501', null, 'a plain member cannot create roles');
select ok(exists (select 1 from public.org_team_roles(pg_temp.id('org')) where display_name = 'qaDomains'),
    'members read the display name');

-- 2. People and pods
select pg_temp.as_user('c1');
select ok(exists (select 1 from public.platform_people_pods(null, 200) where email = 'web1@polish.test'),
    'a person whose web BOSS was set up is listed without an install or tier');
select ok(exists (select 1 from public.platform_people_pods(null, 200) where email = 'web2@polish.test')
          and not exists (select 1 from public.platform_people_pods(null, 200) where email = 'web3@polish.test'),
    'approved asks are listed; dismissed ones are not');
select is((select installs from public.platform_people_pods('web1@polish', 10)), '[]'::jsonb, 'with no agent installs');

select * from finish();
rollback;
