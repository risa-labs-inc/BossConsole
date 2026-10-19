-- pgTAP tests for 20261019140000_agents_rbac: org-scoped agents.* permissions, built-in agent roles,
-- the swapped agents gates, Team RPCs, email-bound invites and the role/permission audit trail.
-- Run with: supabase test db
begin;
select plan(160);

-- a1 owner A, a2 second admin of A, a3 agent manager, a4 agent editor (also a plain member of B),
-- a5 agent analyst, a6 plain member, a7 applicant, a8 invitee, a9 someone else, b1 owner B, c1 platform admin.
insert into auth.users (id, email, email_confirmed_at) values
    ('c4000000-0000-4000-8000-0000000000a1', 'owner-a@rbac.test', now()),
    ('c4000000-0000-4000-8000-0000000000a2', 'admin-a@rbac.test', now()),
    ('c4000000-0000-4000-8000-0000000000a3', 'manager@rbac.test', now()),
    ('c4000000-0000-4000-8000-0000000000a4', 'editor@rbac.test', now()),
    ('c4000000-0000-4000-8000-0000000000a5', 'analyst@rbac.test', now()),
    ('c4000000-0000-4000-8000-0000000000a6', 'member@rbac.test', now()),
    ('c4000000-0000-4000-8000-0000000000a7', 'applicant@rbac.test', now()),
    ('c4000000-0000-4000-8000-0000000000a8', 'invitee@rbac.test', now()),
    ('c4000000-0000-4000-8000-0000000000a9', 'someone@rbac.test', now()),
    ('c4000000-0000-4000-8000-0000000000b1', 'owner-b@rbac.test', now()),
    ('c4000000-0000-4000-8000-0000000000c1', 'platform@rbac.test', now());
insert into public.user_roles (user_id, role_id)
    select 'c4000000-0000-4000-8000-0000000000c1', id from public.roles where name = 'admin';

select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select public.create_organisation_internal(p_slug => 'rbaca', p_name => 'RBAC A', p_description => null,
    p_owner_id => 'c4000000-0000-4000-8000-0000000000a1', p_domain => null, p_visibility => 'private',
    p_join_policy => 'request_to_join');
select public.create_organisation_internal(p_slug => 'rbacb', p_name => 'RBAC B', p_description => null,
    p_owner_id => 'c4000000-0000-4000-8000-0000000000b1', p_domain => null, p_visibility => 'private',
    p_join_policy => 'invite_only');
select set_config('request.jwt.claims', '', true);

create temp table ids (k text primary key, id uuid) on commit drop;
grant all on ids to authenticated, anon, service_role;
insert into ids select 'orga', id from public.organisations where slug = 'rbaca';
insert into ids select 'orgb', id from public.organisations where slug = 'rbacb';
insert into ids select 'a_' || coalesce(orl.preset, orl.kind), orl.role_id from public.organisation_roles orl
    where orl.org_id = (select id from ids where k = 'orga');
insert into ids select 'b_' || coalesce(orl.preset, orl.kind), orl.role_id from public.organisation_roles orl
    where orl.org_id = (select id from ids where k = 'orgb');

create function pg_temp.id(p text) returns uuid language sql stable as $$ select id from ids where k = p $$;
create function pg_temp.u(p text) returns uuid language sql immutable as $$
    select ('c4000000-0000-4000-8000-0000000000' || p)::uuid $$;
create function pg_temp.as_user(p text) returns void language sql as $$
    select set_config('request.jwt.claim.sub', pg_temp.u(p)::text, true),
           set_config('request.jwt.claims', json_build_object('role', 'authenticated', 'sub', pg_temp.u(p))::text, true);
$$;
grant execute on function pg_temp.id(text), pg_temp.u(text), pg_temp.as_user(text) to authenticated, anon, service_role;

-- Members of A with their roles; the editor is also a plain member of B.
insert into public.organisation_members (org_id, user_id, status, joined_at, join_source)
    select pg_temp.id('orga'), pg_temp.u(x), 'active', now(), 'admin' from unnest(array['a2','a3','a4','a5','a6']) x;
insert into public.organisation_members (org_id, user_id, status, joined_at, join_source)
    values (pg_temp.id('orgb'), pg_temp.u('a4'), 'active', now(), 'admin');
insert into public.user_roles (user_id, role_id) values
    (pg_temp.u('a2'), pg_temp.id('a_admin')),
    (pg_temp.u('a3'), pg_temp.id('a_agent_manager')),
    (pg_temp.u('a4'), pg_temp.id('a_agent_editor')),
    (pg_temp.u('a5'), pg_temp.id('a_agent_analyst')),
    (pg_temp.u('a6'), pg_temp.id('a_user')),
    (pg_temp.u('a4'), pg_temp.id('b_user'));

insert into public.agents (slug, org_id, name, plugin_id, created_by) values
    ('racme', pg_temp.id('orga'), 'Acme', 'ai.rever.boss.plugin.dynamic.agent.racme', pg_temp.u('a1')),
    ('rbeta', pg_temp.id('orgb'), 'Beta', 'ai.rever.boss.plugin.dynamic.agent.rbeta', pg_temp.u('b1'));
insert into ids select 'acme', id from public.agents where slug = 'racme';
insert into ids select 'beta', id from public.agents where slug = 'rbeta';
insert into public.agent_installs (user_id, agent_id, instance_id, port, data_dir, state)
    values (pg_temp.u('a9'), pg_temp.id('acme'), 'rbac-i1', 41001, 'agents/racme/a9', 'running');

-- ---- catalog, seed, grants ----
select is((select count(*)::int from public.permissions where name like 'agents.%' and is_system), 6, 'six agents.* system permissions');
select is((public.register_plugin_permission('agents.admin', null, 'x.y'))->>'success', 'false', 'the agents domain is reserved against plugin permissions');
select is((select array_agg(preset order by preset) from public.organisation_roles where org_id = pg_temp.id('orga') and preset is not null),
    array['agent_analyst', 'agent_editor', 'agent_manager'], 'a new org gets the three built-in agent roles');
select ok((select bool_and(kind = 'custom') from public.organisation_roles where preset is not null), 'built-in roles are ordinary custom roles');
select is((select count(*)::int from public.organisations o
           where (select count(*) from public.organisation_roles orl where orl.org_id = o.id and orl.preset is not null) <> 3),
    0, 'every org, existing ones included, has exactly three built-in roles');
select is((select array_agg(p.name order by p.name) from public.role_permissions rp join public.permissions p on p.id = rp.permission_id
           where rp.role_id = pg_temp.id('a_agent_manager')),
    array['agents.access', 'agents.domains', 'agents.edit', 'agents.publish', 'agents.view', 'organisation.read'], 'agent manager permissions');
select is((select array_agg(p.name order by p.name) from public.role_permissions rp join public.permissions p on p.id = rp.permission_id
           where rp.role_id = pg_temp.id('a_agent_editor')),
    array['agents.edit', 'agents.view', 'organisation.read'], 'agent editor permissions');
select is((select array_agg(p.name order by p.name) from public.role_permissions rp join public.permissions p on p.id = rp.permission_id
           where rp.role_id = pg_temp.id('a_agent_analyst')),
    array['agents.view', 'organisation.read'], 'agent analyst permissions');
select is((select count(*)::int from public.role_permissions rp join public.permissions p on p.id = rp.permission_id
           where rp.role_id = pg_temp.id('a_admin') and p.name like 'agents.%'), 6, 'the admin-kind role carries all six');
select is((select count(*)::int from public.role_hierarchy where parent_role_id = pg_temp.id('a_admin')
           and child_role_id in (pg_temp.id('a_agent_manager'), pg_temp.id('a_agent_editor'), pg_temp.id('a_agent_analyst'))),
    3, 'built-in roles sit below the admin role');
select is((select count(*)::int from public.role_hierarchy where child_role_id = pg_temp.id('a_user')
           and parent_role_id in (pg_temp.id('a_agent_manager'), pg_temp.id('a_agent_editor'), pg_temp.id('a_agent_analyst'))),
    3, 'and above the member role');
select is((select count(*)::int from public.agent_audit_log where org_id = pg_temp.id('orga') and action like 'role.%'),
    0, 'seeding writes no role audit entries');
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select is((select (public.get_organisation_detail(pg_temp.id('orga'), pg_temp.u('a1')))->'data'->>'custom_role_count'), '0',
    'built-in roles do not count as custom roles (max_custom_roles quota)');
select set_config('request.jwt.claims', '', true);
select ok(has_function_privilege('authenticated', 'public.has_org_permission(uuid,text)', 'EXECUTE')
      and has_function_privilege('authenticated', 'public.my_org_permissions(uuid)', 'EXECUTE')
      and has_function_privilege('authenticated', 'public.has_agent_permission(uuid,text)', 'EXECUTE'), 'self checks are callable by authenticated');
select ok(not has_function_privilege('anon', 'public.has_org_permission(uuid,text)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.my_org_permissions(uuid)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.org_team_members(uuid,integer,integer)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.org_team_invite(uuid,text,uuid,integer)', 'EXECUTE'), 'anon gets none of them');
select ok(not has_function_privilege('authenticated', 'public.user_has_org_permission(uuid,uuid,text)', 'EXECUTE')
      and not has_function_privilege('authenticated', 'public.user_has_agent_permission(uuid,uuid,text)', 'EXECUTE')
      and has_function_privilege('service_role', 'public.user_has_org_permission(uuid,uuid,text)', 'EXECUTE')
      and not has_function_privilege('authenticated', 'public.seed_org_agent_roles(uuid)', 'EXECUTE'),
      'arbitrary-subject checks and the seed are not callable by authenticated');
select ok((select bool_and(prosecdef and 'search_path=""' = any(proconfig)) from pg_proc where oid in (
    'public.user_has_org_permission(uuid,uuid,text)'::regprocedure, 'public.has_org_permission(uuid,text)'::regprocedure,
    'public.my_org_permissions(uuid)'::regprocedure, 'public.org_team_invite(uuid,text,uuid,integer)'::regprocedure,
    'public.org_team_update_role(uuid,uuid,text[],text)'::regprocedure, 'public.remove_agent_install(uuid,uuid)'::regprocedure)),
    'new RPCs are security definer with an empty search_path');

-- ---- the matrix: each role holds exactly its scope ----
select is(public.user_has_org_permission(pg_temp.u('a1'), pg_temp.id('orga'), 'agents.view'), true, 'service-side check answers for the owner');
select is(public.user_has_org_permission(pg_temp.u('a1'), pg_temp.id('orga'), 'ai.optimist'), false, 'non-agents permissions are never answered (fail closed)');
select is(public.user_has_org_permission(null, pg_temp.id('orga'), 'agents.view'), false, 'no subject, no permission');
select is(public.user_has_org_permission(pg_temp.u('a1'), pg_temp.id('orga'), null), false, 'null permission is false, not null');

set local role authenticated;
select pg_temp.as_user('a1');
select is(public.my_org_permissions(pg_temp.id('orga')),
    array['agents.view','agents.edit','agents.publish','agents.evolve','agents.access','agents.domains','organisation.admin'], 'owner: everything');
select pg_temp.as_user('a2');
select is(public.my_org_permissions(pg_temp.id('orga')),
    array['agents.view','agents.edit','agents.publish','agents.evolve','agents.access','agents.domains','organisation.admin'], 'admin-kind holder: everything');
select pg_temp.as_user('c1');
select is(public.my_org_permissions(pg_temp.id('orga')),
    array['agents.view','agents.edit','agents.publish','agents.evolve','agents.access','agents.domains','organisation.admin'], 'global admin: everything');
select pg_temp.as_user('a3');
select is(public.my_org_permissions(pg_temp.id('orga')),
    array['agents.view','agents.edit','agents.publish','agents.access','agents.domains'], 'agent manager: no evolve, no team');
select pg_temp.as_user('a4');
select is(public.my_org_permissions(pg_temp.id('orga')), array['agents.view','agents.edit'], 'agent editor: view and edit');
select pg_temp.as_user('a5');
select is(public.my_org_permissions(pg_temp.id('orga')), array['agents.view'], 'agent analyst: view only');
select pg_temp.as_user('a6');
select is(public.my_org_permissions(pg_temp.id('orga')), array[]::text[], 'plain member: nothing');
select pg_temp.as_user('b1');
select is(public.my_org_permissions(pg_temp.id('orga')), array[]::text[], 'another org''s owner: nothing in A');
select pg_temp.as_user('a4');
select is(public.my_org_permissions(pg_temp.id('orgb')), array[]::text[], 'A''s editor holds nothing in B, where they are a plain member');
select is(public.has_org_permission(pg_temp.id('orga'), 'agents.evolve'), false, 'has_org_permission: editor cannot evolve');
select is(public.has_agent_permission(pg_temp.id('acme'), 'agents.edit'), true, 'has_agent_permission resolves the agent''s org');
select is(public.has_agent_permission(pg_temp.id('beta'), 'agents.edit'), false, 'and not another org''s agent');

-- ---- editor: saves brand and settings, nothing else ----
select lives_ok($q$ update public.agents set name = 'Acme Edited', brand = '{"name":"Acme","accent":"#112233"}' where slug = 'racme' $q$,
    'editor saves name and brand');
select is((select name from public.agents where slug = 'racme'), 'Acme Edited', 'the edit landed');
select throws_ok($q$ update public.agents set signup_policy = '{"mode":"invite"}' where slug = 'racme' $q$, '42501', null,
    'editor cannot change the access policy');
select throws_ok($q$ select public.publish_agent(pg_temp.id('acme'), '1.0.0') $q$, '42501', null, 'editor cannot publish_agent');
select throws_ok($q$ insert into public.agent_domains (agent_id, hostname) values (pg_temp.id('acme'), 'chat.rbac-acme.com') $q$,
    '42501', null, 'editor cannot add a domain');
select throws_ok($q$ insert into public.agent_invites (agent_id, email) values (pg_temp.id('acme'), 'x@rbac.test') $q$,
    '42501', null, 'editor cannot add an agent invite');
select throws_ok($q$ select public.remove_agent_install(pg_temp.id('acme'), pg_temp.u('a9')) $q$, '42501', null, 'editor cannot remove an install');
select throws_ok($q$ select public.org_team_invite(pg_temp.id('orga'), 'new@rbac.test') $q$, '42501', null, 'editor cannot invite to the org');
select throws_ok($q$ select public.org_team_assign_role(pg_temp.id('orga'), pg_temp.u('a6'), pg_temp.id('a_agent_editor')) $q$,
    '42501', null, 'editor cannot assign roles');
select lives_ok($q$ insert into public.agents (slug, org_id, name, created_by) values ('redit', pg_temp.id('orga'), 'Ed', pg_temp.u('a4')) $q$,
    'editor creates an agent');
select throws_ok($q$ insert into public.agents (slug, org_id, name, created_by, signup_policy)
                    values ('redit2', pg_temp.id('orga'), 'Ed2', pg_temp.u('a4'), '{"mode":"invite"}') $q$, '42501', null,
    'editor cannot create an agent with a non-default access policy');
select is(public.is_agent_folder_admin(pg_temp.id('orga')::text || '/' || pg_temp.id('acme')::text || '/logo.png'), true,
    'editor may write brand assets');
select lives_ok($q$ insert into public.agent_org_defaults (org_id, default_brand) values (pg_temp.id('orga'), '{"accent":"#000000"}') $q$,
    'editor saves the org default brand');
select is((select (public.org_overview(pg_temp.id('orga')))->'agents_by_status'->>'draft'), '2', 'editor reads the overview');

-- ---- analyst: reads aggregates, writes nothing ----
select pg_temp.as_user('a5');
select is((select count(*)::int from public.agents where org_id = pg_temp.id('orga')), 2, 'analyst sees the org''s agents');
select is((select count(*)::int from public.agents where org_id = pg_temp.id('orgb')), 0, 'but not another org''s');
select lives_ok($q$ select public.org_overview(pg_temp.id('orga')) $q$, 'analyst reads org_overview');
select lives_ok($q$ select * from public.org_people(pg_temp.id('orga')) $q$, 'analyst reads org_people');
select lives_ok($q$ select * from public.org_usage(pg_temp.id('orga'), 7) $q$, 'analyst reads org_usage');
select lives_ok($q$ select * from public.org_audit_log(pg_temp.id('orga')) $q$, 'analyst reads the audit log');
update public.agents set name = 'Analyst Was Here' where slug = 'racme';
select is((select name from public.agents where slug = 'racme'), 'Acme Edited', 'analyst updates touch no row');
select throws_ok($q$ insert into public.agents (slug, org_id, name, created_by) values ('rana', pg_temp.id('orga'), 'A', pg_temp.u('a5')) $q$,
    '42501', null, 'analyst cannot create agents');
select is(public.is_agent_folder_admin(pg_temp.id('orga')::text || '/org/logo.png'), false, 'analyst may not write brand assets');
select throws_ok($q$ select public.publish_agent(pg_temp.id('acme'), '1.0.0') $q$, '42501', null, 'analyst cannot publish');
update public.agent_org_defaults set default_brand = '{}' where org_id = pg_temp.id('orga');
select is((select default_brand->>'accent' from public.agent_org_defaults where org_id = pg_temp.id('orga')), '#000000',
    'analyst cannot change the default brand');

-- ---- plain member and outsiders see nothing ----
select pg_temp.as_user('a6');
select is((select count(*)::int from public.agents where org_id = pg_temp.id('orga')), 0, 'plain member sees no agents');
select throws_ok($q$ select public.org_overview(pg_temp.id('orga')) $q$, '42501', null, 'plain member cannot read the overview');
select is((select count(*)::int from public.list_agent_models()), 0, 'plain member gets no model list');
select pg_temp.as_user('b1');
select is((select count(*)::int from public.agents where org_id = pg_temp.id('orga')), 0, 'B''s owner sees none of A''s agents');
select throws_ok($q$ select public.publish_agent(pg_temp.id('acme'), '1.0.0') $q$, '42501', null, 'B''s owner cannot publish A''s agent');
select throws_ok($q$ select * from public.org_audit_log(pg_temp.id('orga')) $q$, '42501', null, 'B''s owner cannot read A''s audit log');
select throws_ok($q$ select * from public.org_team_members(pg_temp.id('orga')) $q$, '42501', null, 'B''s owner cannot list A''s team');
select throws_ok($q$ select public.org_team_update_role(pg_temp.id('orga'), pg_temp.id('a_agent_editor'), array['agents.publish']) $q$,
    '42501', null, 'B''s owner cannot edit A''s roles');

-- ---- manager: publish, access, domains; no team, no evolve ----
select pg_temp.as_user('a3');
select is(public.publish_agent(pg_temp.id('acme'), '1.0.0')::text, 'pending_review', 'manager publishes');
select lives_ok($q$ update public.agents set signup_policy = '{"mode":"invite"}' where slug = 'racme' $q$, 'manager sets the access policy');
select lives_ok($q$ insert into public.agent_invites (agent_id, email) values (pg_temp.id('acme'), 'guest@rbac.test') $q$, 'manager adds an agent invite');
select lives_ok($q$ insert into public.agent_domains (agent_id, hostname) values (pg_temp.id('acme'), 'chat.rbac-acme.com') $q$, 'manager adds a domain');
select is(public.remove_agent_install(pg_temp.id('acme'), pg_temp.u('a9')), true, 'manager removes a person''s install');
reset role;
select is((select state from public.agent_installs where user_id = pg_temp.u('a9')), 'removed', 'the install is marked removed');
set local role authenticated;
select throws_ok($q$ select public.org_team_invite(pg_temp.id('orga'), 'new@rbac.test') $q$, '42501', null, 'manager cannot invite to the org');
select throws_ok($q$ select public.org_team_assign_role(pg_temp.id('orga'), pg_temp.u('a6'), pg_temp.id('a_agent_editor')) $q$,
    '42501', null, 'manager cannot assign roles');
select throws_ok($q$ select public.org_team_update_role(pg_temp.id('orga'), pg_temp.id('a_agent_manager'), array['agents.evolve']) $q$,
    '42501', null, 'manager cannot give their own role evolve');
select is((public.grant_organisation_role_permission(pg_temp.id('orga'), pg_temp.id('a_agent_manager'), 'agents.evolve'))->>'error',
    'Permission denied', 'nor through the existing org RPC');
select throws_ok($q$ select public.org_team_remove(pg_temp.id('orga'), pg_temp.u('a6')) $q$, '42501', null, 'manager cannot remove members');
select is(public.has_org_permission(pg_temp.id('orga'), 'agents.evolve'), false, 'manager cannot evolve');
insert into ids select 'dom', id from public.agent_domains where hostname = 'chat.rbac-acme.com';
reset role;
select throws_ok($q$ select public.agent_domain_record_check(pg_temp.id('dom'), true, true, pg_temp.u('a4')) $q$, '42501', null,
    'a domain check on behalf of an editor is refused');
select throws_ok($q$ select public.agent_domain_record_check(pg_temp.id('dom'), true, true, null) $q$, '42501', null,
    'and on behalf of nobody');
select is(public.agent_domain_record_check(pg_temp.id('dom'), true, false, pg_temp.u('a3')), 'verified', 'on behalf of the manager it records');
set local role authenticated;
select pg_temp.as_user('a5');
select is((select count(*)::int from public.agent_domains where agent_id = pg_temp.id('acme')), 1, 'analyst can see the domain');
delete from public.agent_domains where agent_id = pg_temp.id('acme');
select is((select count(*)::int from public.agent_domains where agent_id = pg_temp.id('acme')), 1, 'but deleting it touches nothing');
select pg_temp.as_user('a3');
delete from public.agent_domains where agent_id = pg_temp.id('acme');
select is((select count(*)::int from public.agent_domains where agent_id = pg_temp.id('acme')), 0, 'manager deletes it');

-- ---- owner/admin imply all, independent of what the admin role carries ----
reset role;
delete from public.role_permissions where role_id = pg_temp.id('a_admin')
    and permission_id = (select id from public.permissions where name = 'agents.evolve');
set local role authenticated;
select pg_temp.as_user('a2');
select is(public.has_org_permission(pg_temp.id('orga'), 'agents.evolve'), true, 'an admin keeps evolve after it is taken off the admin role');
select pg_temp.as_user('a1');
select is(public.has_org_permission(pg_temp.id('orga'), 'agents.domains'), true, 'the owner holds domains');

-- ---- org scoping: a grant to the global user role leaks into no org ----
reset role;
insert into public.role_permissions (role_id, permission_id)
    select r.id, p.id from public.roles r, public.permissions p where r.name = 'user' and p.name = 'agents.view';
select is(public.user_has_org_permission(pg_temp.u('a6'), pg_temp.id('orga'), 'agents.view'), false,
    'agents.view on the global user role gives an org member nothing');
delete from public.role_permissions where role_id = (select id from public.roles where name = 'user')
    and permission_id = (select id from public.permissions where name = 'agents.view');
-- A pending member holding a role gets nothing until approved.
set local role authenticated;
select pg_temp.as_user('a7');
select is((public.request_organisation_membership(pg_temp.id('orga'), 'hi'))->>'status', 'pending', 'applicant applies');
reset role;
insert into public.user_roles (user_id, role_id) values (pg_temp.u('a7'), pg_temp.id('a_agent_analyst'));
select is(public.user_has_org_permission(pg_temp.u('a7'), pg_temp.id('orga'), 'agents.view'), false, 'a pending member''s role grants nothing');
delete from public.user_roles where user_id = pg_temp.u('a7') and role_id = pg_temp.id('a_agent_analyst');

-- ---- Team: listing ----
set local role authenticated;
select pg_temp.as_user('a1');
select is((select status from public.org_team_members(pg_temp.id('orga')) where user_id = pg_temp.u('a7')), 'pending_approval',
    'admins see a pending applicant');
select is((select display_name || '|' || is_owner::text || '|' || is_admin::text from public.org_team_members(pg_temp.id('orga'))
           where user_id = pg_temp.u('a1')), 'owner-a|true|true', 'owner row: display name, owner, admin');
select is((select roles->0->>'preset' from public.org_team_members(pg_temp.id('orga')) where user_id = pg_temp.u('a3')),
    'agent_manager', 'roles carry their preset');
select is((select array_agg(coalesce(preset, kind) order by coalesce(preset, kind)) from public.org_team_roles(pg_temp.id('orga'))),
    array['admin', 'agent_analyst', 'agent_editor', 'agent_manager', 'user'], 'roles list: admin, the three built-ins, member');
select is((select agent_permissions from public.org_team_roles(pg_temp.id('orga')) where preset = 'agent_editor'),
    array['agents.view', 'agents.edit'], 'effective agent permissions per role');
select is((select agent_permissions from public.org_team_roles(pg_temp.id('orga')) where kind = 'admin'),
    public.agents_permission_catalog(), 'the admin role shows all six');
select is((select grantable from public.org_team_roles(pg_temp.id('orga')) where kind = 'admin'), true, 'the owner may grant the admin role');
select pg_temp.as_user('a2');
select is((select grantable from public.org_team_roles(pg_temp.id('orga')) where kind = 'admin'), false, 'another admin may not');
select is((select grantable from public.org_team_roles(pg_temp.id('orga')) where preset = 'agent_editor'), true, 'but may grant the built-ins');
select pg_temp.as_user('a6');
select is((select count(*)::int from public.org_team_members(pg_temp.id('orga')) where status <> 'active'), 0,
    'a plain member sees the active roster only');
select throws_ok($q$ select * from public.org_team_invites(pg_temp.id('orga')) $q$, '42501', null, 'and no invites');

-- ---- Team: approve, deny, remove ----
select pg_temp.as_user('a1');
select lives_ok($q$ select public.org_team_approve(pg_temp.id('orga'), pg_temp.u('a7')) $q$, 'admin approves the applicant');
select is((select status from public.org_team_members(pg_temp.id('orga')) where user_id = pg_temp.u('a7')), 'active', 'now active');
select throws_ok($q$ select public.org_team_deny(pg_temp.id('orga'), pg_temp.u('a7')) $q$, 'P0001', null, 'an active member cannot be denied');
select throws_ok($q$ select public.org_team_remove(pg_temp.id('orga'), pg_temp.u('a1')) $q$, 'P0001',
    'The organisation owner cannot be removed -- transfer ownership first', 'the owner cannot be removed');

-- ---- Team: role assignment rules ----
select pg_temp.as_user('a2');
select throws_ok($q$ select public.org_team_assign_role(pg_temp.id('orga'), pg_temp.u('a2'), pg_temp.id('a_agent_manager')) $q$,
    '42501', 'cannot_change_own_roles', 'no self-assignment');
select throws_ok($q$ select public.org_team_assign_role(pg_temp.id('orga'), pg_temp.u('a4'), pg_temp.id('a_admin')) $q$,
    '42501', 'role_not_grantable', 'a non-owner admin cannot make admins');
select throws_ok($q$ select public.org_team_assign_role(pg_temp.id('orga'), pg_temp.u('a4'), pg_temp.id('b_agent_manager')) $q$,
    '42501', 'role_not_grantable', 'another org''s role is refused');
select throws_ok($q$ select public.org_team_remove_role(pg_temp.id('orga'), pg_temp.u('a1'), pg_temp.id('a_admin')) $q$,
    '42501', 'role_not_grantable', 'a non-owner admin cannot take the owner''s admin role');
select lives_ok($q$ select public.org_team_assign_role(pg_temp.id('orga'), pg_temp.u('a6'), pg_temp.id('a_agent_analyst')) $q$,
    'admin grants a built-in role');
select pg_temp.as_user('a6');
select is(public.my_org_permissions(pg_temp.id('orga')), array['agents.view'], 'the grant applies at once');
select pg_temp.as_user('a1');
select throws_ok($q$ select public.org_team_remove_role(pg_temp.id('orga'), pg_temp.u('a1'), pg_temp.id('a_admin')) $q$,
    'P0001', 'The organisation owner must keep the administrator role', 'the owner keeps the admin role');
select lives_ok($q$ select public.org_team_remove_role(pg_temp.id('orga'), pg_temp.u('a2'), pg_temp.id('a_admin')) $q$,
    'the owner demotes the second admin');
select pg_temp.as_user('a2');
select is(public.my_org_permissions(pg_temp.id('orga')), array[]::text[], 'the demoted admin loses everything at once');
select pg_temp.as_user('b1');
select throws_ok($q$ select public.org_team_remove_role(pg_temp.id('orgb'), pg_temp.u('b1'), pg_temp.id('b_admin')) $q$,
    'P0001', 'The organisation owner must keep the administrator role', 'the last admin (the owner) keeps the role');
select is((select count(*)::int from public.user_roles where role_id = pg_temp.id('b_admin')), 1, 'B still has its one admin');

-- ---- revocation takes effect in the next statement ----
select pg_temp.as_user('a4');
select is(public.has_org_permission(pg_temp.id('orga'), 'agents.edit'), true, 'editor can edit before the revocation');
select pg_temp.as_user('a1');
select lives_ok($q$ select public.org_team_remove_role(pg_temp.id('orga'), pg_temp.u('a4'), pg_temp.id('a_agent_editor')) $q$,
    'admin takes the editor role away');
select pg_temp.as_user('a4');
update public.agents set name = 'Too Late' where slug = 'racme';
select is((select count(*)::int from public.agents where slug = 'racme'), 0, 'the former editor no longer sees the agent');
select throws_ok($q$ insert into public.agents (slug, org_id, name, created_by) values ('rlate', pg_temp.id('orga'), 'L', pg_temp.u('a4')) $q$,
    '42501', null, 'nor creates one');
select pg_temp.as_user('a5');
select is(public.has_org_permission(pg_temp.id('orga'), 'agents.view'), true, 'analyst can view before the role is emptied');
select pg_temp.as_user('a1');
select lives_ok($q$ select public.org_team_update_role(pg_temp.id('orga'), pg_temp.id('a_agent_analyst'), array[]::text[]) $q$,
    'admin empties the analyst role');
select pg_temp.as_user('a5');
select throws_ok($q$ select public.org_overview(pg_temp.id('orga')) $q$, '42501', null, 'the analyst is locked out at once');

-- ---- custom roles limited to agents.* ----
select pg_temp.as_user('a1');
select throws_ok($q$ select public.org_team_create_role(pg_temp.id('orga'), 'shippers', array['agents.publish', 'secret.read']) $q$,
    'P0001', 'invalid_permission:secret.read', 'custom roles take agents.* only');
select throws_ok($q$ select public.org_team_update_role(pg_temp.id('orga'), pg_temp.id('a_admin'), array['agents.view']) $q$,
    'P0001', 'admin_role_has_all_permissions', 'the admin role is not editable');
select isnt(public.org_team_create_role(pg_temp.id('orga'), 'shippers', array['agents.publish'], 'Ship it'), null, 'admin creates a custom role');
insert into ids select 'a_shippers', role_id from public.organisation_roles orl join public.roles r on r.id = orl.role_id where r.name = 'rbaca_shippers';
select is((select agent_permissions from public.org_team_roles(pg_temp.id('orga')) where role_id = pg_temp.id('a_shippers')),
    array['agents.view', 'agents.publish'], 'publish implies view');
select is((select description from public.org_team_roles(pg_temp.id('orga')) where role_id = pg_temp.id('a_shippers')), 'Ship it', 'description kept');
select lives_ok($q$ select public.org_team_update_role(pg_temp.id('orga'), pg_temp.id('a_shippers'), array['agents.domains'], 'Domains') $q$,
    'admin edits it');
select is((select permissions from public.org_team_roles(pg_temp.id('orga')) where role_id = pg_temp.id('a_shippers')),
    array['agents.domains', 'agents.view', 'organisation.read'], 'exactly the new agents set; organisation.read untouched');
select lives_ok($q$ select public.org_team_assign_role(pg_temp.id('orga'), pg_temp.u('a7'), pg_temp.id('a_shippers')) $q$, 'assigned');
select lives_ok($q$ select public.org_team_delete_role(pg_temp.id('orga'), pg_temp.id('a_shippers')) $q$, 'and deleted');
select is((select count(*)::int from public.user_roles where role_id = pg_temp.id('a_shippers')), 0, 'deleting it strips holders');
select throws_ok($q$ select public.org_team_delete_role(pg_temp.id('orga'), pg_temp.id('a_admin')) $q$, 'P0001', null, 'the admin role cannot be deleted');

-- ---- invites: email-bound, single use, existing flow ----
select throws_ok($q$ select public.org_team_invite(pg_temp.id('orga'), 'not-an-email') $q$, 'P0001', 'invalid_email', 'email is validated');
select throws_ok($q$ select public.org_team_invite(pg_temp.id('orga'), 'member@rbac.test') $q$, 'P0001', 'already_member', 'members are not invited');
select throws_ok($q$ select public.org_team_invite(pg_temp.id('orga'), 'invitee@rbac.test', pg_temp.id('a_admin')) $q$, 'P0001',
    'An invite link cannot grant the administrator role -- assign it explicitly after they join', 'an invite never carries the admin role');
create temp table inv on commit drop as
    select public.org_team_invite(pg_temp.id('orga'), ' Invitee@RBAC.test ', pg_temp.id('a_agent_editor')) as r;
grant all on inv to authenticated;
select is((select r->>'email' from inv), 'invitee@rbac.test', 'invite is for the normalised email');
select ok((select r->>'token' like 'boss_inv_%' from inv), 'and returns the one-time token');
select is((select count(*)::int from public.org_team_invites(pg_temp.id('orga')) where email = 'invitee@rbac.test'), 1, 'listed as pending');
select is((select max_uses from public.org_team_invites(pg_temp.id('orga')) where email = 'invitee@rbac.test'), 1, 'single use');
create temp table inv2 on commit drop as
    select public.org_team_invite(pg_temp.id('orga'), 'invitee@rbac.test', pg_temp.id('a_agent_editor')) as r;
grant all on inv2 to authenticated;
select is((select count(*)::int from public.org_team_invites(pg_temp.id('orga')) where email = 'invitee@rbac.test'), 1,
    're-inviting replaces the live invite');
select pg_temp.as_user('a9');
select is((public.redeem_organisation_invite((select r->>'token' from inv2)))->>'error', 'Invite link is invalid or expired',
    'someone else cannot redeem an email-bound invite');
select pg_temp.as_user('a8');
select is((public.redeem_organisation_invite((select r->>'token' from inv)))->>'success', 'false', 'the replaced invite is dead');
select is((public.redeem_organisation_invite((select r->>'token' from inv2)))->>'status', 'active', 'the invitee redeems');
select is(public.my_org_permissions(pg_temp.id('orga')), array['agents.view', 'agents.edit'], 'and holds the invited role');
select pg_temp.as_user('a1');
create temp table inv3 on commit drop as select public.org_team_invite(pg_temp.id('orga'), 'later@rbac.test') as r;
select lives_ok($q$ select public.org_team_revoke_invite(pg_temp.id('orga'), (select (r->>'invite_id')::uuid from inv3)) $q$, 'admin revokes an invite');
select is((select count(*)::int from public.org_team_invites(pg_temp.id('orga')) where email = 'later@rbac.test'), 0, 'gone from pending');
select pg_temp.as_user('b1');
select throws_ok($q$ select public.org_team_revoke_invite(pg_temp.id('orgb'), (select (r->>'invite_id')::uuid from inv2)) $q$,
    'P0001', 'Invite not found', 'another org cannot touch A''s invites');

-- ---- store gate for agent adapter plugins (plugin-store calls this with the service role) ----
reset role;
select is(public.agent_plugin_publish_authz(pg_temp.u('a3'), 'ai.rever.boss.plugin.dynamic.agent.racme'),
    jsonb_build_object('agent', true, 'org_id', pg_temp.id('orga'), 'allowed', true), 'a manager may publish the agent''s plugin');
select is(public.agent_plugin_publish_authz(pg_temp.u('a5'), 'ai.rever.boss.plugin.dynamic.agent.racme')->'allowed', 'false'::jsonb,
    'an analyst may not');
select is(public.agent_plugin_publish_authz(pg_temp.u('b1'), 'ai.rever.boss.plugin.dynamic.agent.racme')->'allowed', 'false'::jsonb,
    'another org''s owner may not');
select is(public.agent_plugin_publish_authz(pg_temp.u('a1'), 'ai.rever.boss.plugin.dynamic.agent.nobody'),
    jsonb_build_object('agent', true, 'org_id', null, 'allowed', false), 'an id no agent uses is refused');
select is(public.agent_plugin_publish_authz(pg_temp.u('a1'), 'ai.rever.boss.plugin.dynamic.agentadaptertemplate'),
    '{"agent": false}'::jsonb, 'ids outside the namespace are not agent plugins');
select ok(not has_function_privilege('authenticated', 'public.agent_plugin_publish_authz(uuid,text)', 'EXECUTE')
      and has_function_privilege('service_role', 'public.agent_plugin_publish_authz(uuid,text)', 'EXECUTE'), 'service role only');
delete from public.user_roles where user_id = pg_temp.u('a3') and role_id = pg_temp.id('a_agent_manager');
select is(public.agent_plugin_publish_authz(pg_temp.u('a3'), 'ai.rever.boss.plugin.dynamic.agent.racme')->'allowed', 'false'::jsonb,
    'revoking the manager role stops the next publish');
set local role authenticated;

-- ---- audit ----
select pg_temp.as_user('a1');
select ok((select array_agg(distinct action) from public.org_audit_log(pg_temp.id('orga'), 200)) @> array[
    'member.role_assign', 'member.role_remove', 'role.permission_grant', 'role.permission_revoke', 'role.create', 'role.delete',
    'team.invite_add', 'team.invite_revoke', 'team.member_approve', 'install.remove'], 'role, permission and team changes are audited');
select is((select detail->>'permission' from public.org_audit_log(pg_temp.id('orga'), 200)
           where action = 'role.permission_revoke' and detail->>'role' = 'rbaca_agent_analyst' limit 1), 'agents.view',
    'a revoke names the permission and the role');
select is((select actor_email || '|' || (detail->>'email') from public.org_audit_log(pg_temp.id('orga'), 200)
           where action = 'member.role_remove' and detail->>'role' = 'rbaca_agent_editor' and detail->>'user_id' = pg_temp.u('a4')::text),
    'owner-a@rbac.test|editor@rbac.test', 'a role removal names the actor and the member');
select is((select count(*)::int from public.org_audit_log(pg_temp.id('orga'), 200)
           where action = 'member.role_assign' and detail->>'role' = 'rbaca_user'), 0, 'roles carrying no agents permission are not logged');
select pg_temp.as_user('b1');
select is((select count(*)::int from public.org_audit_log(pg_temp.id('orgb'), 200) where action like 'role.%' or action like 'team.%'), 0,
    'B''s log has none of A''s team changes');

select * from finish();
rollback;
