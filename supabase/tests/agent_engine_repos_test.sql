-- Agents built from the org's own Max: agents.github, engine repos/builds RLS, version pins, provisioner read, audit.
begin;
select plan(27);

-- a1 owner, a2 agent manager (publish, no github), a3 agent editor (view only here), b1 owner of another org, p1 provisioner.
insert into auth.users (id, email, email_confirmed_at) values
    ('ce000000-0000-4000-8000-0000000000a1', 'owner@eng.test', now()),
    ('ce000000-0000-4000-8000-0000000000a2', 'manager@eng.test', now()),
    ('ce000000-0000-4000-8000-0000000000a3', 'editor@eng.test', now()),
    ('ce000000-0000-4000-8000-0000000000b1', 'other@eng.test', now()),
    ('ce000000-0000-4000-8000-0000000000c1', 'bot@eng.test', now());
insert into public.agents_service_accounts (user_id, purpose) values ('ce000000-0000-4000-8000-0000000000c1', 'provisioner');

select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select public.create_organisation_internal(p_slug => 'enga', p_name => 'Eng A', p_description => null,
    p_owner_id => 'ce000000-0000-4000-8000-0000000000a1', p_domain => null, p_visibility => 'private', p_join_policy => 'invite_only');
select public.create_organisation_internal(p_slug => 'engb', p_name => 'Eng B', p_description => null,
    p_owner_id => 'ce000000-0000-4000-8000-0000000000b1', p_domain => null, p_visibility => 'private', p_join_policy => 'invite_only');
select set_config('request.jwt.claims', '', true);

create temp table ids (k text primary key, id uuid) on commit drop;
grant all on ids to authenticated;
insert into ids select 'org', id from public.organisations where slug = 'enga';
insert into ids select 'orgb', id from public.organisations where slug = 'engb';
insert into ids select 'manager', orl.role_id from public.organisation_roles orl
    where orl.org_id = (select id from ids where k = 'org') and orl.preset = 'agent_manager';
insert into ids select 'editor', orl.role_id from public.organisation_roles orl
    where orl.org_id = (select id from ids where k = 'org') and orl.preset = 'agent_editor';

create function pg_temp.id(p text) returns uuid language sql stable as $$ select id from ids where k = p $$;
create function pg_temp.u(p text) returns uuid language sql immutable as $$
    select ('ce000000-0000-4000-8000-0000000000' || p)::uuid $$;
create function pg_temp.as_user(p text) returns void language sql as $$
    select set_config('request.jwt.claim.sub', pg_temp.u(p)::text, true),
           set_config('request.jwt.claims', json_build_object('role', 'authenticated', 'sub', pg_temp.u(p))::text, true);
$$;
grant execute on function pg_temp.id(text), pg_temp.u(text), pg_temp.as_user(text) to authenticated;

insert into public.organisation_members (org_id, user_id, status, joined_at, join_source)
    select pg_temp.id('org'), pg_temp.u(x), 'active', now(), 'admin' from unnest(array['a2', 'a3']) x;
insert into public.user_roles (user_id, role_id) values (pg_temp.u('a2'), pg_temp.id('manager')), (pg_temp.u('a3'), pg_temp.id('editor'));
insert into public.agents (slug, org_id, name, plugin_id, created_by) values
    ('engagent', pg_temp.id('org'), 'Eng', 'ai.rever.boss.plugin.dynamic.agent.engagent', pg_temp.u('a1'));
insert into ids select 'agent', id from public.agents where slug = 'engagent';

-- What the edge function writes with the service role.
insert into public.org_github_connections (org_id, installation_id, account_login, account_id, connected_by)
    values (pg_temp.id('org'), 101, 'eng-inc', 77, pg_temp.u('a1'));
insert into public.agent_engine_repos (agent_id, org_id, repo_owner, repo_name, repo_id, created_by)
    values (pg_temp.id('agent'), pg_temp.id('org'), 'eng-inc', 'engagent-max', 4242, pg_temp.u('a1'));
update public.agent_engine_repos set state = 'ready', base_version = '1.2.33' where agent_id = pg_temp.id('agent');
insert into public.agent_engine_builds (id, agent_id, org_id, repo, commit_sha, run_id, run_url, engine_version, message,
    jar_sha256, jar_size, storage_path, imported_by)
    values ('ce000000-0000-4000-8000-00000000bb01', pg_temp.id('agent'), pg_temp.id('org'), 'eng-inc/engagent-max', repeat('a', 40), 900,
            'https://github.com/eng-inc/engagent-max/actions/runs/900', '1.2.33', 'Add a banner', repeat('c', 64), 36000000,
            pg_temp.id('org') || '/' || pg_temp.id('agent') || '/' || repeat('c', 64) || '.jar', pg_temp.u('a2')),
           ('ce000000-0000-4000-8000-00000000bb02', pg_temp.id('agent'), pg_temp.id('org'), 'eng-inc/engagent-max', repeat('b', 40), 901,
            'https://github.com/eng-inc/engagent-max/actions/runs/901', '1.2.33', 'Second', repeat('d', 64), 36000000,
            'x/y/z.jar', pg_temp.u('a2'));

-- ---- permission ----
select ok('agents.github' = any (public.agents_permission_catalog()), 'agents.github is in the catalog');
select ok(public.user_has_org_permission(pg_temp.u('a1'), pg_temp.id('org'), 'agents.github'), 'the org owner holds it');
select ok(not public.user_has_org_permission(pg_temp.u('a2'), pg_temp.id('org'), 'agents.github'), 'an agent manager does not');

-- ---- constraints ----
select throws_ok($$ insert into public.agent_engine_repos (agent_id, org_id, repo_owner, repo_name, repo_id)
    values (pg_temp.id('agent'), pg_temp.id('org'), 'eng-inc', '..', 1) $$, '23514', null, 'a dot-dot repo name is refused');
select throws_ok($$ update public.agent_engine_builds set commit_sha = 'nope' where run_id = 900 $$, '23514', null,
    'a commit must be a full sha');

-- ---- reads ----
set local role authenticated;
select pg_temp.as_user('a3');
select is((select count(*)::int from public.agent_engine_repos), 1, 'an editor (agents.view) reads the repo');
select is((select count(*)::int from public.agent_engine_builds), 2, 'and the builds');
select is((select account_login from public.org_github_connections), 'eng-inc', 'and the connection');
select throws_ok($$ insert into public.agent_engine_builds (agent_id, org_id, repo, commit_sha, run_id, run_url, engine_version,
    jar_sha256, jar_size, storage_path) values (pg_temp.id('agent'), pg_temp.id('org'), 'a/b', repeat('e', 40), 1, 'https://github.com/x',
    '1.0.0', repeat('e', 64), 1, 'p') $$, '42501', null, 'clients cannot write builds');
select throws_ok($$ select count(*) from public.github_connect_states $$, '42501', null, 'install states are service-only');
select is((public.agent_engine_overview(pg_temp.id('agent'))->'repo'->>'state'), 'ready', 'the overview shows the repo');
select is(jsonb_array_length(public.agent_engine_overview(pg_temp.id('agent'))->'builds'), 2, 'and the builds');

-- ---- pins ----
select throws_ok($$ select public.record_agent_version_engine(pg_temp.id('agent'), '1.0.1', 'ce000000-0000-4000-8000-00000000bb01') $$,
    '42501', 'Permission denied', 'an editor cannot pin');
select pg_temp.as_user('a2');
select lives_ok($$ select public.record_agent_version_engine(pg_temp.id('agent'), '1.0.1', 'ce000000-0000-4000-8000-00000000bb01') $$,
    'a manager (agents.publish) pins a build to a version');
select lives_ok($$ select public.record_agent_version_engine(pg_temp.id('agent'), '1.0.1', 'ce000000-0000-4000-8000-00000000bb01') $$,
    'pinning the same build again is a no-op');
select throws_ok($$ select public.record_agent_version_engine(pg_temp.id('agent'), '1.0.1', 'ce000000-0000-4000-8000-00000000bb02') $$,
    'P0001', 'version_engine_set', 'a version keeps its build');
select throws_ok($$ select public.record_agent_version_engine(pg_temp.id('agent'), '1.0.2', gen_random_uuid()) $$,
    'P0001', 'build_not_found', 'an unknown build is refused');
select pg_temp.as_user('b1');
select is((select count(*)::int from public.agent_engine_builds), 0, 'another org sees no builds');
select throws_ok($$ select public.agent_engine_overview(pg_temp.id('agent')) $$, '42501', 'Permission denied',
    'nor the overview');

-- ---- provisioner ----
reset role;
select throws_ok($$ update public.agents set live_version = '1.0.2', status = 'live' where id = pg_temp.id('agent') $$,
    'P0001', 'engine_pin_required', 'a direct publication cannot bypass the engine pin');
select throws_ok($$ update public.agents set pending_version = '1.0.2' where id = pg_temp.id('agent') $$,
    'P0001', 'engine_pin_required', 'review candidates need pins too');
update public.agents set live_version = '1.0.1', status = 'live'  where id = pg_temp.id('agent');
select ok((select engine_required from public.agent_engine_repos where agent_id = pg_temp.id('agent')), 'first live pinned version permanently requires a pin');
set local role authenticated;
select pg_temp.as_user('c1');
select is((select commit_sha from public.agent_live_engines(array[pg_temp.id('agent')])), repeat('a', 40),
    'the provisioner reads the live version''s pin');
select pg_temp.as_user('a1');
select throws_ok($$ select * from public.agent_live_engines(array[pg_temp.id('agent')]) $$, '42501', 'Permission denied',
    'nobody else does');

-- ---- audit ----
reset role;
select is((select count(*)::int from public.agent_audit_log where org_id = pg_temp.id('org')
           and action in ('github.connect', 'engine.repo_create', 'engine.repo_state', 'engine.build_import', 'engine.version_pin')), 6,
    'connection, repo, state, two imports and the pin are audited');
select is((select actor from public.agent_audit_log where action = 'engine.repo_create' and org_id = pg_temp.id('org')), pg_temp.u('a1'),
    'a service-role write is attributed to the person it acted for');
select is((select actor from public.agent_audit_log where action = 'engine.version_pin' and org_id = pg_temp.id('org')), pg_temp.u('a2'),
    'the pin is attributed to the publisher');

select * from finish();
rollback;
