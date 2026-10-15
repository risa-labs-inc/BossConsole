-- pgTAP tests for the agents platform (20261016130000).
-- Run with: supabase test db
begin;
select plan(57);

insert into auth.users (id, email, email_confirmed_at) values
    ('a9000000-0000-4000-8000-00000000000a', 'owner-a@agents.test', now()),
    ('a9000000-0000-4000-8000-00000000000b', 'owner-b@agents.test', now()),
    ('a9000000-0000-4000-8000-00000000000c', 'plain@agents.test', now()),
    ('a9000000-0000-4000-8000-00000000000d', 'platform@agents.test', now()),
    ('a9000000-0000-4000-8000-00000000000e', 'stranger@agents.test', now());
insert into public.user_roles (user_id, role_id)
    select 'a9000000-0000-4000-8000-00000000000d', id from public.roles where name = 'admin';

select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select public.create_organisation_internal(p_slug => 'agentsa', p_name => 'Agents A', p_description => null,
    p_owner_id => 'a9000000-0000-4000-8000-00000000000a', p_domain => null, p_visibility => 'private', p_join_policy => 'invite_only');
select public.create_organisation_internal(p_slug => 'agentsb', p_name => 'Agents B', p_description => null,
    p_owner_id => 'a9000000-0000-4000-8000-00000000000b', p_domain => null, p_visibility => 'private', p_join_policy => 'invite_only');
create temp table org (k text primary key, id uuid) on commit drop;
grant all on org to authenticated, anon;
insert into org select 'a', id from public.organisations where slug = 'agentsa';
insert into org select 'b', id from public.organisations where slug = 'agentsb';

-- ---- shape ----
select ok((select bool_and(prosecdef and 'search_path=""' = any(proconfig)) from pg_proc where oid in (
    'public.publish_agent(uuid,int)'::regprocedure, 'public.review_agent(uuid,boolean)'::regprocedure,
    'public.suspend_agent(uuid)'::regprocedure, 'public.promote_build(uuid,uuid)'::regprocedure,
    'public.agent_can_join(text,text)'::regprocedure)), 'RPCs are security definer with an empty search_path');
select ok(not has_function_privilege('anon', 'public.publish_agent(uuid,int)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.review_agent(uuid,boolean)', 'EXECUTE')
      and has_function_privilege('anon', 'public.agent_can_join(text,text)', 'EXECUTE'), 'only agent_can_join is anon-callable');
select ok((select not public from storage.buckets where id = 'agent-bundles')
      and (select not public from storage.buckets where id = 'agent-builds'), 'both buckets are private');
select is((select value from public.platform_flags where key = 'agent_creation_open'), 'false'::jsonb, 'agent_creation_open seeded false');
select ok(not has_table_privilege('authenticated', 'public.agents', 'DELETE')
      and not has_column_privilege('authenticated', 'public.agents', 'status', 'UPDATE')
      and not has_column_privilege('authenticated', 'public.agents', 'status', 'INSERT')
      and not has_table_privilege('authenticated', 'public.agent_installs', 'INSERT')
      and not has_table_privilege('authenticated', 'public.platform_flags', 'SELECT'), 'column/table privileges deny status writes, install writes, flag reads');

-- ---- org admin A creates and edits own agent ----
set local role authenticated;
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000a', true);
select lives_ok($q$ insert into public.agents (slug, org_id, name, created_by)
    values ('acme', (select id from org where k = 'a'), 'Acme', 'a9000000-0000-4000-8000-00000000000a') $q$, 'org admin creates an agent for their org');
select is((select status::text from public.agents where slug = 'acme'), 'draft', 'new agent is draft');
select lives_ok($q$ update public.agents set name = 'Acme 2' where slug = 'acme' $q$, 'org admin updates their agent');
select is((select name from public.agents where slug = 'acme'), 'Acme 2', 'the update landed');
select throws_ok($q$ update public.agents set status = 'live' where slug = 'acme' $q$, '42501', null, 'status is not directly writable');
select throws_ok($q$ update public.agents set live_build_id = gen_random_uuid() where slug = 'acme' $q$, '42501', null, 'live_build_id is not directly writable');
select throws_ok($q$ insert into public.agents (slug, org_id, name, status, created_by)
    values ('sneaky', (select id from org where k = 'a'), 'S', 'live', 'a9000000-0000-4000-8000-00000000000a') $q$, '42501', null, 'cannot insert with a status');
select throws_ok($q$ insert into public.agents (slug, org_id, name, created_by)
    values ('theirs', (select id from org where k = 'b'), 'T', 'a9000000-0000-4000-8000-00000000000a') $q$, '42501', null, 'cannot create an agent in another org');
select throws_ok($q$ insert into public.agents (slug, org_id, name, created_by)
    values ('www', (select id from org where k = 'a'), 'W', 'a9000000-0000-4000-8000-00000000000a') $q$, 'P0001', 'slug_reserved', 'reserved slug rejected');
select throws_ok($q$ insert into public.agents (slug, org_id, name, created_by)
    values ('Bad_Slug', (select id from org where k = 'a'), 'W', 'a9000000-0000-4000-8000-00000000000a') $q$, '23514', null, 'malformed slug rejected');

-- ---- org admin B cannot touch or see A's agent ----
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000b', true);
select is((select count(*)::int from public.agents), 0, 'org B admin cannot see org A agents');
select is_empty($q$ update public.agents set name = 'hijack' returning 1 $q$, 'org B admin cannot update org A agent');
select throws_ok($q$ select public.publish_agent((select id from public.agents), 1) $q$, '42501', null, 'cannot publish another org''s agent (and cannot see it)');

-- ---- persona versions + publish ----
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000a', true);
create temp table ag (id uuid);
grant all on ag to authenticated;
insert into ag select id from public.agents where slug = 'acme';
select lives_ok($q$ insert into public.agent_persona_versions (agent_id, version, bundle_path, sha256, created_by)
    values ((select id from ag), 1, 'p1.zip', repeat('a', 64), 'a9000000-0000-4000-8000-00000000000a') $q$, 'admin adds a persona version');
select is(public.publish_agent((select id from ag), 1)::text, 'pending_review', 'publish -> pending_review while the flag is false');

reset role;
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
update public.platform_flags set value = 'true'::jsonb where key = 'agent_creation_open';
set local role authenticated;
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000a', true);
select is(public.publish_agent((select id from ag), 1)::text, 'live', 'publish -> live when the flag is true');
select is((select live_persona_version from public.agents where slug = 'acme'), 1, 'live_persona_version set');
reset role;
update public.platform_flags set value = 'false'::jsonb where key = 'agent_creation_open';
update public.agents set status = 'pending_review' where slug = 'acme';

-- ---- review: platform admin only ----
set local role authenticated;
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000a', true);
select throws_ok($q$ select public.review_agent((select id from ag), true) $q$, '42501', null, 'org admin cannot review');
select throws_ok($q$ select public.suspend_agent((select id from ag)) $q$, '42501', null, 'org admin cannot suspend');
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000d', true);
select is(public.review_agent((select id from ag), true)::text, 'live', 'platform admin approves -> live');

-- ---- agent_public: anon sees only live rows ----
reset role;
set local role anon;
select is((select count(*)::int from public.agent_public), 1, 'anon reads the live agent via agent_public');
select is((select signup_mode from public.agent_public where slug = 'acme'), 'open', 'signup_mode exposed');
select throws_ok($q$ select count(*) from public.agents $q$, '42501', null, 'anon cannot read the agents table');
reset role;
select set_config('request.jwt.claims', '{"role":"service_role"}', true);

-- ---- suspend hides the agent ----
set local role authenticated;
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000d', true);
select lives_ok($q$ select public.suspend_agent((select id from ag)) $q$, 'platform admin suspends');
reset role;
set local role anon;
select is((select count(*)::int from public.agent_public), 0, 'agent_public hides suspended agents');
select is(public.agent_can_join('acme', 'x@y.test'), false, 'suspended agent cannot be joined');

-- ---- agent_can_join ----
reset role;
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
update public.agents set status = 'live' where slug = 'acme';
set local role anon;
select is(public.agent_can_join('acme', 'anyone@y.test'), true, 'open: anyone');
select is(public.agent_can_join('acme', 'not-an-email'), false, 'bad email rejected');
select is(public.agent_can_join('nosuch', 'anyone@y.test'), false, 'unknown slug rejected');
reset role;
update public.agents set signup_policy = '{"mode":"domain","domains":["Corp.test"]}' where slug = 'acme';
set local role anon;
select is(public.agent_can_join('acme', 'Bob@CORP.test'), true, 'domain: matching domain (case-insensitive)');
select is(public.agent_can_join('acme', 'bob@other.test'), false, 'domain: other domain rejected');
reset role;
update public.agents set signup_policy = '{"mode":"invite"}' where slug = 'acme';
insert into public.agent_invites (agent_id, email) values ((select id from ag), 'Invited@y.test');
set local role anon;
select is(public.agent_can_join('acme', 'invited@Y.test'), true, 'invite: invited email (case-insensitive)');
select is(public.agent_can_join('acme', 'other@y.test'), false, 'invite: uninvited email rejected');

-- ---- installs: owner reads only own rows ----
reset role;
insert into public.agent_installs (user_id, agent_id, instance_id, port, data_dir, state) values
    ('a9000000-0000-4000-8000-00000000000c', (select id from ag), 'inst-c', 8810, 'agents/acme', 'running'),
    ('a9000000-0000-4000-8000-00000000000b', (select id from ag), 'inst-b', 8811, 'agents/acme', 'running');
set local role authenticated;
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000c', true);
select is((select array_agg(instance_id) from public.agent_installs), array['inst-c'], 'user reads only their own install');
select throws_ok($q$ update public.agent_installs set state = 'failed' $q$, '42501', null, 'user cannot write installs');

-- ---- evolution requests ----
reset role;
-- installer c has a running install (above); agent is live. org B admin = stranger.
set local role authenticated;
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000c', true);
create temp table ev (k text primary key, id uuid);
grant all on ev to authenticated;
select lives_ok($q$ insert into ev select 'inst', public.request_agent_evolution((select id from ag), 'make the greeting friendlier please') $q$, 'installer requests an evolution');
select throws_ok($q$ select public.request_agent_evolution((select id from ag), 'too short') $q$, '23514', null, 'brief too short refused');
select is((select count(*)::int from public.agent_evolution_requests), 0, 'installer cannot read requests');
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000e', true);
select throws_ok($q$ select public.request_agent_evolution((select id from ag), 'a stranger asking for changes here') $q$, '42501', 'not installed', 'stranger/installer-only refused');
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000a', true);
select lives_ok($q$ insert into ev select 'adm', public.request_agent_evolution((select id from ag), 'admin wants the tone changed to formal') $q$, 'org admin requests an evolution');
select is((select string_agg(source, ',' order by source) from public.agent_evolution_requests), 'admin,agent', 'sources recorded');
select lives_ok($q$ update public.agent_evolution_requests set status = 'discarded' where id = (select id from ev where k = 'adm') $q$, 'admin discards');
select throws_ok($q$ update public.agent_evolution_requests set status = 'built' where id = (select id from ev where k = 'inst') $q$, '42501', null, 'admin cannot set built');
select throws_ok($q$ update public.agent_evolution_requests set brief = 'rewritten brief that is long enough' $q$, '42501', null, 'admin cannot edit the brief');
select throws_ok($q$ insert into public.agent_evolution_requests (agent_id, source, brief) values ((select id from ag), 'admin', 'direct insert is not allowed here') $q$, '42501', null, 'no direct inserts');

-- non-live agent refused for installer
reset role;
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
update public.agents set status = 'suspended' where slug = 'acme';
set local role authenticated;
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000c', true);
select throws_ok($q$ select public.request_agent_evolution((select id from ag), 'installer asks while not live now') $q$, 'P0001', 'agent not live', 'installer refused on a non-live agent');
reset role;
update public.agents set status = 'live' where slug = 'acme';

-- ---- auto_promote ----
set local role authenticated;
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000a', true);
select lives_ok($q$ update public.agents set auto_promote = true where slug = 'acme' $q$, 'admin sets auto_promote');
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000c', true);
select is_empty($q$ update public.agents set auto_promote = false where slug = 'acme' returning 1 $q$, 'non-admin cannot set auto_promote');

-- ---- brand bucket ----
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000a', true);
select lives_ok($q$ insert into storage.objects (bucket_id, name) values ('agent-brand', (select id::text from ag) || '/logo.png') $q$, 'admin uploads under own agent folder');
select throws_ok($q$ insert into storage.objects (bucket_id, name) values ('agent-brand', 'a9000000-0000-4000-8000-0000000000ee/logo.png') $q$, '42501', null, 'upload under another agent folder refused');
reset role;
select ok((select public and file_size_limit = 2097152 from storage.buckets where id = 'agent-brand'), 'agent-brand is public with a 2 MiB limit');
select ok((select file_size_limit = 209715200 from storage.buckets where id = 'agent-builds'), 'agent-builds allows 200 MiB');

select * from finish();
rollback;
