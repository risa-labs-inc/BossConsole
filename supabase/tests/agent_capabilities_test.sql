-- Per-agent capabilities: validated column, agents.capabilities permission, audit trail.
begin;
select plan(20);

-- a1 owner, a2 agent manager, a3 member with a custom capabilities role.
insert into auth.users (id, email, email_confirmed_at) values
    ('c9000000-0000-4000-8000-0000000000a1', 'owner@cap.test', now()),
    ('c9000000-0000-4000-8000-0000000000a2', 'manager@cap.test', now()),
    ('c9000000-0000-4000-8000-0000000000a3', 'capper@cap.test', now());

select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select public.create_organisation_internal(p_slug => 'capa', p_name => 'Cap A', p_description => null,
    p_owner_id => 'c9000000-0000-4000-8000-0000000000a1', p_domain => null, p_visibility => 'private',
    p_join_policy => 'invite_only');
select set_config('request.jwt.claims', '', true);

create temp table ids (k text primary key, id uuid) on commit drop;
grant all on ids to authenticated;
insert into ids select 'org', id from public.organisations where slug = 'capa';
insert into ids select 'manager', orl.role_id from public.organisation_roles orl
    where orl.org_id = (select id from ids where k = 'org') and orl.preset = 'agent_manager';

create function pg_temp.id(p text) returns uuid language sql stable as $$ select id from ids where k = p $$;
create function pg_temp.u(p text) returns uuid language sql immutable as $$
    select ('c9000000-0000-4000-8000-0000000000' || p)::uuid $$;
create function pg_temp.as_user(p text) returns void language sql as $$
    select set_config('request.jwt.claim.sub', pg_temp.u(p)::text, true),
           set_config('request.jwt.claims', json_build_object('role', 'authenticated', 'sub', pg_temp.u(p))::text, true);
$$;
grant execute on function pg_temp.id(text), pg_temp.u(text), pg_temp.as_user(text) to authenticated;

insert into public.organisation_members (org_id, user_id, status, joined_at, join_source)
    select pg_temp.id('org'), pg_temp.u(x), 'active', now(), 'admin' from unnest(array['a2', 'a3']) x;
insert into public.user_roles (user_id, role_id) values (pg_temp.u('a2'), pg_temp.id('manager'));
insert into public.agents (slug, org_id, name, plugin_id, created_by) values
    ('capagent', pg_temp.id('org'), 'Cap', 'ai.rever.boss.plugin.dynamic.agent.capagent', pg_temp.u('a1'));
insert into ids select 'agent', id from public.agents where slug = 'capagent';

-- ---- column ----
select is((select capabilities from public.agents where id = pg_temp.id('agent')), '{}'::jsonb,
    'a new agent starts with everything on');
select ok(public.agent_capabilities_valid('{"evolve":false,"own_connectors":true,"tabs":{"memory":false,"vault":true}}'),
    'the full shape is valid');
select ok(not public.agent_capabilities_valid('{"evolve":"no"}'), 'a non-boolean is invalid');
select ok(not public.agent_capabilities_valid('{"shell":false}'), 'an unknown key is invalid');
select ok(not public.agent_capabilities_valid('{"tabs":{"files":false}}'), 'an unknown tab is invalid');
select ok(not public.agent_capabilities_valid('{"tabs":[]}'), 'tabs must be an object');
select ok(not public.agent_capabilities_valid('[]'), 'the column must be an object');
select throws_ok($$ update public.agents set capabilities = '{"evolve":1}' where slug = 'capagent' $$, '23514', null,
    'the check constraint rejects a bad value');

-- ---- permission ----
select ok('agents.capabilities' = any (public.agents_permission_catalog()), 'the permission is in the catalog');
select is((select is_system from public.permissions where name = 'agents.capabilities'), true, 'it is a system permission');

set local role authenticated;
select pg_temp.as_user('a1');
select ok('agents.capabilities' = any (public.my_org_permissions(pg_temp.id('org'))), 'the owner holds it');
select lives_ok($$ update public.agents set capabilities = '{"evolve":false,"tabs":{"memory":false}}' where slug = 'capagent' $$,
    'the owner turns evolve and the Memory tab off');

select pg_temp.as_user('a2');
select ok(not ('agents.capabilities' = any (public.my_org_permissions(pg_temp.id('org')))),
    'the agent manager preset does not hold it');
select throws_ok($$ update public.agents set capabilities = '{}' where slug = 'capagent' $$, '42501',
    'Permission denied: agents.capabilities', 'a manager cannot change capabilities');
select lives_ok($$ update public.agents set name = 'Cap renamed' where slug = 'capagent' $$,
    'a manager still edits other settings');

select pg_temp.as_user('a1');
select lives_ok($$ select public.org_team_create_role(pg_temp.id('org'), 'cappers', array['agents.capabilities'], 'Cappers') $$,
    'a custom role can grant it');
reset role;
insert into public.user_roles (user_id, role_id)
    select pg_temp.u('a3'), orl.role_id from public.organisation_roles orl
    join public.roles r on r.id = orl.role_id
    where orl.org_id = pg_temp.id('org') and orl.kind = 'custom' and orl.preset is null;
set local role authenticated;
select pg_temp.as_user('a3');
select lives_ok($$ update public.agents set capabilities = '{"evolve":false}' where slug = 'capagent' $$,
    'a member with the custom role changes capabilities');
select throws_ok($$ update public.agents set name = 'Nope' where slug = 'capagent' $$, '42501',
    'Permission denied: agents.edit', 'but not other settings');

-- ---- audit ----
reset role;
select is((select detail -> 'capability_keys' from public.agent_audit_log
           where agent_id = pg_temp.id('agent') and action = 'agent.settings_change' and actor = pg_temp.u('a1')
             and detail -> 'fields' ? 'capabilities'),
    '["evolve", "tabs.memory"]'::jsonb, 'the audit entry names the changed switches');
select is((select detail -> 'capabilities' from public.agent_audit_log
           where agent_id = pg_temp.id('agent') and action = 'agent.settings_change' and actor = pg_temp.u('a3')),
    '{"evolve": false}'::jsonb, 'and records the new value');

select * from finish();
rollback;
