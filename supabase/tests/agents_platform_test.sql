-- pgTAP tests for the agents platform (20261016130000).
-- Run with: supabase test db
begin;
select plan(94);

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
    'public.suspend_agent(uuid)'::regprocedure,
    'public.agent_can_join(text,text)'::regprocedure)), 'RPCs are security definer with an empty search_path');
select ok(not has_function_privilege('anon', 'public.publish_agent(uuid,int)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.review_agent(uuid,boolean)', 'EXECUTE')
      and has_function_privilege('anon', 'public.agent_can_join(text,text)', 'EXECUTE'), 'only agent_can_join is anon-callable');
select ok((select not public from storage.buckets where id = 'agent-bundles'), 'agent-bundles is private');
select is((select value from public.platform_flags where key = 'agent_creation_open'), 'false'::jsonb, 'agent_creation_open seeded false');
select ok(not has_table_privilege('authenticated', 'public.agents', 'DELETE')
      and not has_column_privilege('authenticated', 'public.agents', 'status', 'UPDATE')
      and not has_column_privilege('authenticated', 'public.agents', 'status', 'INSERT')
      and not has_table_privilege('authenticated', 'public.agent_installs', 'INSERT')
      and not has_table_privilege('authenticated', 'public.platform_flags', 'INSERT'), 'column/table privileges deny status writes, install writes, flag inserts');

-- ---- org admin A creates and edits own agent ----
set local role authenticated;
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000a', true);
select lives_ok($q$ insert into public.agents (slug, org_id, name, created_by)
    values ('acme', (select id from org where k = 'a'), 'Acme', 'a9000000-0000-4000-8000-00000000000a') $q$, 'org admin creates an agent for their org');
select is((select status::text from public.agents where slug = 'acme'), 'draft', 'new agent is draft');
select lives_ok($q$ update public.agents set name = 'Acme 2' where slug = 'acme' $q$, 'org admin updates their agent');
select is((select name from public.agents where slug = 'acme'), 'Acme 2', 'the update landed');
select throws_ok($q$ update public.agents set status = 'live' where slug = 'acme' $q$, '42501', null, 'status is not directly writable');
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
    values ((select id from ag), 1, 'agent-bundles/' || (select id::text from ag) || '/persona-1.zip', repeat('a', 64), 'a9000000-0000-4000-8000-00000000000a') $q$, 'admin adds a persona version');
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
select is_empty($q$ update public.agent_installs set state = 'failed' returning 1 $q$, 'user cannot update installs');
select throws_ok($q$ insert into public.agent_installs (user_id, agent_id, instance_id, port, data_dir, state)
    values ('a9000000-0000-4000-8000-00000000000c', (select id from ag), 'inst-x', 8899, 'agents/acme', 'pending') $q$, '42501', null, 'user cannot insert installs');

-- ---- brand bucket ----
set local role authenticated;
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000a', true);
select lives_ok($q$ insert into storage.objects (bucket_id, name) values ('agent-brand', (select id::text from ag) || '/logo.png') $q$, 'admin uploads under own agent folder');
select throws_ok($q$ insert into storage.objects (bucket_id, name) values ('agent-brand', 'a9000000-0000-4000-8000-0000000000ee/logo.png') $q$, '42501', null, 'upload under another agent folder refused');
reset role;
select ok((select public and file_size_limit = 2097152 from storage.buckets where id = 'agent-brand'), 'agent-brand is public with a 2 MiB limit');

-- ---- agent-bundles storage ----
reset role;
insert into storage.objects (bucket_id, name) values ('agent-bundles', (select id::text from ag) || '/persona-0.zip');
set local role authenticated;
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000a', true);
select lives_ok($q$ insert into storage.objects (bucket_id, name) values ('agent-bundles', (select id::text from ag) || '/persona-1.zip') $q$, 'admin uploads a bundle under own agent');
select throws_ok($q$ insert into storage.objects (bucket_id, name) values ('agent-bundles', 'a9000000-0000-4000-8000-0000000000ee/persona-1.zip') $q$, '42501', null, 'bundle upload under another agent refused');
select is((select count(*)::int from storage.objects where bucket_id = 'agent-bundles'), 2, 'admin reads back own bundles');
select is_empty($q$ update storage.objects set name = name || 'x' where bucket_id = 'agent-bundles' returning 1 $q$, 'bundles are immutable: no update');
select is((select count(*)::int from pg_policies where schemaname = 'storage' and tablename = 'objects'
    and policyname like 'agent-bundles%' and cmd in ('DELETE', 'UPDATE', 'ALL')), 0, 'bundles are immutable: no update/delete policy');
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000c', true);
select is((select count(*)::int from storage.objects where bucket_id = 'agent-bundles'), 0, 'non-admin sees no bundles');

-- ---- platform_flags ----
select is((select value from public.platform_flags where key = 'agent_creation_open'), 'false'::jsonb, 'any signed-in user reads flags');
select is_empty($q$ update public.platform_flags set value = 'true'::jsonb returning 1 $q$, 'non-platform-admin cannot update flags');
select throws_ok($q$ insert into public.platform_flags values ('x', 'true') $q$, '42501', null, 'no flag inserts from clients');
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000d', true);
select lives_ok($q$ update public.platform_flags set value = 'true'::jsonb where key = 'agent_creation_open' $q$, 'platform admin updates a flag');
select is((select value from public.platform_flags where key = 'agent_creation_open'), 'true'::jsonb, 'the flag changed');

-- ---- persona version numbering ----
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000a', true);
select is(public.next_persona_version((select id from ag)), 2, 'next_persona_version = max + 1');
select throws_ok($q$ insert into public.agent_persona_versions (agent_id, version, bundle_path, sha256, created_by)
    values ((select id from ag), 3, 'agent-bundles/' || (select id::text from ag) || '/persona-3.zip', repeat('b', 64), 'a9000000-0000-4000-8000-00000000000a') $q$, 'P0001', 'version_not_next', 'a skipped version is rejected');
select throws_ok($q$ insert into public.agent_persona_versions (agent_id, version, bundle_path, sha256, created_by)
    values ((select id from ag), 1, 'agent-bundles/' || (select id::text from ag) || '/persona-1.zip', repeat('b', 64), 'a9000000-0000-4000-8000-00000000000a') $q$, 'P0001', 'version_not_next', 'a duplicate version is rejected');
select throws_ok($q$ insert into public.agent_persona_versions (agent_id, version, bundle_path, sha256, created_by)
    values ((select id from ag), 2, 'agent-bundles/a9000000-0000-4000-8000-0000000000ee/persona-2.zip', repeat('b', 64), 'a9000000-0000-4000-8000-00000000000a') $q$, '23514', null, 'a path under another agent is refused');
select throws_ok($q$ insert into public.agent_persona_versions (agent_id, version, bundle_path, sha256, created_by)
    values ((select id from ag), 2, 'agent-brand/' || (select id::text from ag) || '/persona-2.zip', repeat('b', 64), 'a9000000-0000-4000-8000-00000000000a') $q$, '23514', null, 'a path in another bucket is refused');
select throws_ok($q$ insert into public.agent_persona_versions (agent_id, version, bundle_path, sha256, created_by)
    values ((select id from ag), 2, 'agent-bundles/' || (select id::text from ag) || '/persona-9.zip', repeat('b', 64), 'a9000000-0000-4000-8000-00000000000a') $q$, '23514', null, 'a path with the wrong version number is refused');
select throws_ok($q$ insert into public.agent_persona_versions (agent_id, version, bundle_path, sha256, created_by)
    values ((select id from ag), 2, upper('agent-bundles/' || (select id::text from ag) || '/persona-2.zip'), repeat('b', 64), 'a9000000-0000-4000-8000-00000000000a') $q$, '23514', null, 'the path match is case-sensitive');
select lives_ok($q$ insert into public.agent_persona_versions (agent_id, version, bundle_path, sha256, created_by)
    values ((select id from ag), 2, 'agent-bundles/' || (select id::text from ag) || '/persona-2.zip', repeat('b', 64), 'a9000000-0000-4000-8000-00000000000a') $q$, 'the next version is accepted');
select is(public.next_persona_version((select id from ag)), 3, 'the counter advances');
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000c', true);
select throws_ok($q$ select public.next_persona_version((select id from ag)) $q$, '42501', null, 'non-admin cannot ask for the next version');

-- ---- pending review queue ----
reset role;
update public.agents set status = 'pending_review' where slug = 'acme';
set local role authenticated;
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000d', true);
select is((select row(slug, name, org_name)::text from public.list_pending_agents()), '(acme,"Acme 2","Agents A")', 'platform admin lists pending agents with org name');
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000a', true);
select throws_ok($q$ select * from public.list_pending_agents() $q$, '42501', null, 'org admin cannot list the review queue');
reset role;
set local role anon;
select throws_ok($q$ select * from public.list_pending_agents() $q$, '42501', null, 'anon cannot list the review queue');
reset role;

-- ---- provisioner bot ----
reset role;
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
insert into auth.users (id, email, email_confirmed_at) values ('a9000000-0000-4000-8000-00000000000f', 'bot@agents.test', now());
insert into public.agents_service_accounts (user_id, purpose) values ('a9000000-0000-4000-8000-00000000000f', 'provisioner');
insert into storage.objects (bucket_id, name) values ('app-releases', 'other/x.bin');
select throws_ok($q$ insert into public.agents_service_accounts values (gen_random_uuid(), 'other') $q$, '23514', null, 'only the provisioner purpose is allowed');
select ok(not has_table_privilege('authenticated', 'public.agents_service_accounts', 'SELECT')
      and not has_table_privilege('anon', 'public.agents_service_accounts', 'SELECT')
      and not has_function_privilege('anon', 'public.is_agents_provisioner()', 'EXECUTE')
      and not has_table_privilege('authenticated', 'public.agent_installs', 'DELETE'), 'no client grants on the accounts table, no anon, no install delete');

set local role authenticated;
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000f', true);
select is(public.is_agents_provisioner(), true, 'the bot is the provisioner');
select ok((select count(*) from public.agents) >= 1, 'bot reads all agents');
select ok((select count(*) from public.agent_persona_versions) >= 2, 'bot reads all persona versions');
select ok((select count(*) from public.agent_installs) >= 2, 'bot reads all installs');
select lives_ok($q$ insert into public.agent_installs (user_id, agent_id, instance_id, port, data_dir, state)
    values ('a9000000-0000-4000-8000-00000000000a', (select id from ag), 'inst-a', 8812, 'agents/acme', 'pending') $q$, 'bot inserts an install');
select lives_ok($q$ update public.agent_installs set state = 'running', detail = 'ok' where instance_id = 'inst-a' $q$, 'bot updates an install');
select is((select state from public.agent_installs where instance_id = 'inst-a'), 'running', 'the bot update landed');
select ok((select count(*) from storage.objects where bucket_id = 'agent-bundles') >= 2, 'bot reads any agent-bundles object');
select is((select count(*)::int from storage.objects where bucket_id not in ('agent-bundles')), 0, 'bot reads no other bucket (agent-brand included)');
select is_empty($q$ update public.agents set name = 'bot' returning 1 $q$, 'bot cannot update agents');
select throws_ok($q$ insert into public.agent_persona_versions (agent_id, version, bundle_path, sha256, created_by)
    values ((select id from ag), 3, 'agent-bundles/' || (select id::text from ag) || '/persona-3.zip', repeat('c', 64), 'a9000000-0000-4000-8000-00000000000f') $q$, '42501', null, 'bot cannot insert persona versions');
select is((select count(*)::int from public.agent_invites), 0, 'bot cannot read agent_invites');
select throws_ok($q$ insert into public.agent_invites (agent_id, email) values ((select id from ag), 'bot@x.test') $q$, '42501', null, 'bot cannot write agent_invites');
select is_empty($q$ update public.platform_flags set value = 'true'::jsonb returning 1 $q$, 'bot cannot update platform_flags');
select throws_ok($q$ delete from public.agent_installs $q$, '42501', null, 'bot cannot delete installs');
select throws_ok($q$ insert into storage.objects (bucket_id, name) values ('agent-bundles', (select id::text from ag) || '/persona-9.zip') $q$, '42501', null, 'bot cannot write bundles');
select throws_ok($q$ select * from public.agents_service_accounts $q$, '42501', null, 'bot cannot read the accounts table');

-- normal users
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000b', true);
select is(public.is_agents_provisioner(), false, 'is_agents_provisioner is false for a normal user');
select is((select count(*)::int from public.agents), 0, 'org B admin still sees no org A agents');
select is((select count(*)::int from public.agent_persona_versions), 0, 'org B admin sees no org A persona rows');
select is((select array_agg(instance_id) from public.agent_installs), array['inst-b'], 'a normal user reads only their own install');
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000a', true);
select is(public.is_agents_provisioner(), false, 'is_agents_provisioner is false for an org admin');
reset role;
set local role anon;
select throws_ok($q$ select public.is_agents_provisioner() $q$, '42501', null, 'anon cannot call is_agents_provisioner');
reset role;

-- nothing in the agents surface leans on the service role
select is((select count(*)::int from pg_proc p where p.pronamespace = 'public'::regnamespace
    and (p.proname like 'agent%' or p.proname in ('publish_agent', 'review_agent', 'suspend_agent', 'next_persona_version',
         'list_pending_agents', 'is_agents_provisioner', 'is_agent_org_admin', 'is_agent_folder_admin'))
    and p.prosrc ilike '%service_role%'), 0, 'no agents function references service_role');
select is((select count(*)::int from pg_policies where (schemaname = 'public' and tablename like 'agent%' or tablename = 'platform_flags'
         or (schemaname = 'storage' and policyname like 'agent-%'))
    and (qual ilike '%service_role%' or with_check ilike '%service_role%' or 'service_role' = any(roles::text[]))), 0, 'no agents policy references service_role');

select * from finish();
rollback;
