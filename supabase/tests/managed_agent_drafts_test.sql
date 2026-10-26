-- pgTAP tests for 20261019140000_agents_rbac: org-scoped agents.* permissions, built-in agent roles,
-- the swapped agents gates, Team RPCs, email-bound invites and the role/permission audit trail.
-- Run with: supabase test db
begin;
select plan(39);

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

SELECT ok(NOT has_function_privilege('anon','public.managed_agents_list()','EXECUTE'),'anonymous list denied');
SELECT ok(NOT has_table_privilege('authenticated','public.agent_content_drafts','UPDATE'),'direct writes denied');
SELECT ok(NOT has_table_privilege('authenticated','public.agent_content_drafts','SELECT'),'direct content reads denied');
SET LOCAL ROLE authenticated;
SELECT pg_temp.as_user('a6');
SELECT is(public.managed_agents_list(),'[]'::jsonb,'plain member sees no editable agents');
SELECT throws_ok($q$SELECT public.managed_agent_draft_get(pg_temp.id('acme'))$q$,'42501',NULL,'member cannot read content');
SELECT throws_ok($q$SELECT public.managed_agent_draft_update(pg_temp.id('acme'),0,'{"soul":"x"}')$q$,'42501',NULL,'member cannot edit');
SELECT pg_temp.as_user('a4');
SELECT is(jsonb_array_length(public.managed_agents_list()),1,'editor sees only editable org agent');
SELECT throws_ok($q$SELECT public.managed_agent_draft_get(pg_temp.id('beta'))$q$,'42501',NULL,'other org membership grants no edit');
SELECT is((public.managed_agent_draft_get(pg_temp.id('acme'))->>'revision')::int,0,'absent draft revision0');
SELECT is((public.managed_agent_draft_update(pg_temp.id('acme'),0,'{"soul":"Persona","skills":{"research":"Do research"}}')->>'revision')::int,1,'editor creates draft');
SELECT throws_ok($q$SELECT public.managed_agent_draft_update(pg_temp.id('acme'),0,'{"soul":"stale"}')$q$,'40001','draft_revision_conflict','stale editor cannot overwrite');
SELECT is(public.managed_agent_draft_get(pg_temp.id('acme'))->'content'->>'soul','Persona','conflict preserves content');
SELECT throws_ok($q$SELECT public.managed_agent_draft_update(pg_temp.id('acme'),1,'{"live_version":"9.0.0"}')$q$,'22023','invalid_draft_patch','publish field forbidden');
SELECT throws_ok($q$SELECT public.publish_agent(pg_temp.id('acme'),'1.0.0')$q$,'42501',NULL,'edit is not publish');
SELECT is(public.managed_agent_draft_update(pg_temp.id('acme'),1,'{"identity":"Identity"}')->'content'->>'soul','Persona','patch preserves unmodified source');
SELECT throws_ok($q$SELECT public.managed_agent_draft_update(pg_temp.id('acme'),2,'{"skills":{"../escape":"x"}}')$q$,'22023','invalid_skill','skill path traversal denied');
SELECT throws_ok($q$SELECT public.managed_agent_draft_update(pg_temp.id('acme'),2,'{"soul":null}')$q$,'22023','invalid_draft_content','null source forbidden');
SELECT throws_ok($q$SELECT public.managed_agent_draft_update(pg_temp.id('acme'),2,jsonb_build_object('soul',repeat('x',262145)))$q$,'22023','invalid_draft_content','source size bounded');
SELECT is((public.managed_agent_draft_update(pg_temp.id('acme'),2,'{"custom_connectors":[{"id":"mcp-example","name":"Example Tools","url":"https://mcp.example.com/api","auth":"oauth"}]}')->>'revision')::int,3,'safe OAuth definition accepted without credentials');
SELECT throws_ok($q$SELECT public.managed_agent_draft_update(pg_temp.id('acme'),3,'{"custom_connectors":[{"id":"mcp-a","name":"Acme","url":"http://mcp.example.com","auth":"none"}]}')$q$,'22023',NULL,'HTTP connector denied');
SELECT throws_ok($q$SELECT public.managed_agent_draft_update(pg_temp.id('acme'),3,'{"custom_connectors":[{"id":"mcp-a","name":"Acme","url":"https://127.0.0.1","auth":"none"}]}')$q$,'22023',NULL,'IP endpoint denied');
SELECT throws_ok($q$SELECT public.managed_agent_draft_update(pg_temp.id('acme'),3,'{"custom_connectors":[{"id":"mcp-a","name":"Acme","url":"https://mcp.example.com","auth":"none","headers":{"Authorization":"secret"}}]}')$q$,'22023',NULL,'credential fields denied');
SELECT throws_ok($q$SELECT public.managed_agent_draft_update(pg_temp.id('acme'),3,'{"custom_connectors":[{"id":"mcp-a","name":"Acme","url":"https://mcp.example.com?token=secret","auth":"none"}]}')$q$,'22023',NULL,'URL query credentials denied');
SELECT throws_ok($q$SELECT public.managed_agent_draft_update(pg_temp.id('acme'),3,'{"custom_connectors":[{"id":"mcp-a","name":"GitHub","url":"https://mcp.example.com","auth":"none"}]}')$q$,'22023','reserved_connector_name','builtin collision denied');
SELECT throws_ok($q$SELECT public.managed_agent_draft_update(pg_temp.id('acme'),3,'{"custom_connectors":[{"id":"mcp-a","name":"Hello.World","url":"https://mcp.example.com","auth":"none"},{"id":"mcp-b","name":"Hello World","url":"https://mcp.example.com","auth":"none"}]}')$q$,'22023','duplicate_connector','normalized server key collision denied');
SELECT is((public.managed_agent_draft_get(pg_temp.id('acme'))->>'revision')::int,3,'invalid payloads preserve revision');
RESET ROLE;
SELECT is((SELECT live_version FROM public.agents WHERE id=pg_temp.id('acme')),NULL,'draft never auto-publishes');
SELECT is((SELECT count(*)::int FROM public.agent_audit_log WHERE agent_id=pg_temp.id('acme') AND action='agent.draft_updated'),3,'successful changes audited exactly once');
SELECT ok(NOT EXISTS(SELECT 1 FROM public.agent_audit_log WHERE action='agent.draft_updated' AND detail::text LIKE '%Persona%'),'audit excludes source content');
DELETE FROM public.user_roles WHERE user_id=pg_temp.u('a4') AND role_id=pg_temp.id('a_agent_editor');
SET LOCAL ROLE authenticated;
SELECT throws_ok($q$SELECT public.managed_agent_draft_get(pg_temp.id('acme'))$q$,'42501',NULL,'role revocation immediately denies read with same claims');
SELECT throws_ok($q$SELECT public.managed_agent_draft_update(pg_temp.id('acme'),3,'{"soul":"x"}')$q$,'42501',NULL,'role revocation immediately denies write');
SELECT is(public.managed_agents_list(),'[]'::jsonb,'revoked agent disappears from management list');
RESET ROLE;
SELECT set_config('request.jwt.claims','',true);
UPDATE public.agents SET live_version='1.0.0' WHERE id=pg_temp.id('beta');
SET LOCAL ROLE authenticated;
SELECT pg_temp.as_user('b1');
SELECT is(public.managed_agent_draft_get(pg_temp.id('beta'))->>'live_version','1.0.0','published source version exposed for safe seeding');
SELECT throws_ok($q$SELECT public.managed_agent_draft_update(pg_temp.id('beta'),0,'{"soul":"partial"}')$q$,'22023','published_source_required','published agent cannot seed from implicit blank defaults');
SELECT is((public.managed_agent_draft_update(pg_temp.id('beta'),0,'{"soul":"Published persona","identity":"Published identity","skills":{"old":"preserved"},"custom_connectors":[]}')->>'revision')::int,1,'complete published source can seed CAS0');
SELECT throws_ok($q$SELECT public.managed_agent_draft_update(pg_temp.id('beta'),1,'{"custom_connectors":[{"id":"mcp-a","name":"Acme","url":"https://service.internal/mcp","auth":"none"}]}')$q$,'22023',NULL,'private hostname suffix denied');
SELECT throws_ok($q$SELECT public.managed_agent_draft_update(pg_temp.id('beta'),1,'{"custom_connectors":[{"id":"mcp-a","name":"Acme","url":"https://mcp.example.com:8443/mcp","auth":"none"}]}')$q$,'22023',NULL,'nonstandard HTTPS port denied');
SELECT throws_ok($q$SELECT public.managed_agent_draft_update(pg_temp.id('beta'),1,'{"custom_connectors":[{"id":"mcp-a","name":"Acme","url":"https://user:secret@mcp.example.com/mcp","auth":"none"}]}')$q$,'22023',NULL,'URL credential authority denied');
SELECT is(public.managed_agent_draft_get(pg_temp.id('beta'))->'content'->'skills','{"old":"preserved"}'::jsonb,'failed edits retain seeded source');
RESET ROLE;
SELECT * FROM finish();
ROLLBACK;
