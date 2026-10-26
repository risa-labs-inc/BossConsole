-- pgTAP tests for 20261019140000_agents_rbac: org-scoped agents.* permissions, built-in agent roles,
-- the swapped agents gates, Team RPCs, email-bound invites and the role/permission audit trail.
-- Run with: supabase test db
begin;
select plan(15);

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


INSERT INTO public.organisations(slug,name,is_system,owner_id) VALUES('boss','BOSS',true,pg_temp.u('c1')) ON CONFLICT(slug) DO NOTHING;
INSERT INTO public.plugins(plugin_id,display_name,author_name,author_id,org_id,visibility,published)
VALUES('ai.rever.boss.plugin.dynamic.agentadaptertemplate','Template','BOSS',pg_temp.u('c1'),(SELECT id FROM organisations WHERE slug='boss'),'unlisted',true)
ON CONFLICT(plugin_id) DO UPDATE SET org_id=excluded.org_id,visibility='unlisted',published=true,author_id=excluded.author_id;
DELETE FROM organisation_members WHERE org_id=(SELECT id FROM organisations WHERE slug='boss') AND user_id IN(SELECT pg_temp.u(x) FROM unnest(ARRAY['a1','a2','a3','a4','a5','a6','a7','a8','a9','b1'])x);
INSERT INTO ids SELECT 'template',id FROM plugins WHERE plugin_id='ai.rever.boss.plugin.dynamic.agentadaptertemplate';
SELECT ok(public.user_can_install_plugin(pg_temp.u('a1'),pg_temp.id('template')),'outside org admin can download canonical template');
SELECT ok(public.user_can_install_plugin(pg_temp.u('a3'),pg_temp.id('template')),'delegated agent manager can download canonical template');
SELECT ok(NOT public.user_can_install_plugin(pg_temp.u('a4'),pg_temp.id('template')),'editor without publish denied');
SELECT ok(NOT public.user_can_install_plugin(pg_temp.u('a6'),pg_temp.id('template')),'ordinary member denied');
SELECT ok(NOT public.user_can_install_plugin(pg_temp.u('a9'),pg_temp.id('template')),'outsider denied');
SELECT ok(NOT public.user_can_install_plugin(NULL,pg_temp.id('template')),'anonymous denied');
UPDATE plugins SET visibility='org' WHERE id=pg_temp.id('template');
SELECT ok(NOT public.user_can_install_plugin(pg_temp.u('a3'),pg_temp.id('template')),'private visibility does not qualify');
UPDATE plugins SET visibility='unlisted',published=false WHERE id=pg_temp.id('template');
SELECT ok(NOT public.user_can_install_plugin(pg_temp.u('a3'),pg_temp.id('template')),'unpublished template denied');
UPDATE plugins SET published=true,plugin_id='ai.rever.boss.plugin.dynamic.otherprivate' WHERE id=pg_temp.id('template');
SELECT ok(NOT public.user_can_install_plugin(pg_temp.u('a3'),pg_temp.id('template')),'other unlisted plugin denied');
UPDATE plugins SET plugin_id='ai.rever.boss.plugin.dynamic.agentadaptertemplate',org_id=pg_temp.id('orgb') WHERE id=pg_temp.id('template');
SELECT ok(NOT public.user_can_install_plugin(pg_temp.u('a3'),pg_temp.id('template')),'wrong publisher organisation denied');
UPDATE plugins SET org_id=(SELECT id FROM organisations WHERE slug='boss') WHERE id=pg_temp.id('template');
UPDATE organisations SET is_system=false WHERE slug='boss';
SELECT ok(NOT public.user_can_install_plugin(pg_temp.u('a3'),pg_temp.id('template')),'non-system boss organisation denied');
UPDATE organisations SET is_system=true WHERE slug='boss';
DELETE FROM user_roles WHERE user_id=pg_temp.u('a3') AND role_id=pg_temp.id('a_agent_manager');
SELECT ok(NOT public.user_can_install_plugin(pg_temp.u('a3'),pg_temp.id('template')),'revocation takes effect immediately');
SELECT ok(NOT has_function_privilege('anon','public.user_can_install_plugin(uuid,uuid)','execute'),'anonymous cannot call subject predicate');
SELECT ok(NOT has_function_privilege('authenticated','public.user_can_install_plugin(uuid,uuid)','execute'),'caller cannot impersonate another subject');
SELECT ok(has_function_privilege('service_role','public.user_can_install_plugin(uuid,uuid)','execute'),'trusted store can evaluate subject');
SELECT * FROM finish();
ROLLBACK;
