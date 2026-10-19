-- pgTAP tests for the agents platform (20261016130000).
-- Run with: supabase test db
begin;
select plan(121);

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
    'public.publish_agent(uuid,text)'::regprocedure, 'public.review_agent(uuid,boolean)'::regprocedure,
    'public.suspend_agent(uuid)'::regprocedure,
    'public.agent_can_join(text,text)'::regprocedure)), 'RPCs are security definer with an empty search_path');
select ok(not has_function_privilege('anon', 'public.publish_agent(uuid,text)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.review_agent(uuid,boolean)', 'EXECUTE')
      and has_function_privilege('anon', 'public.agent_can_join(text,text)', 'EXECUTE'), 'only agent_can_join is anon-callable');
select is((select count(*)::int from storage.buckets where id in ('agent-bundles', 'agent-builds')), 0, 'no bundle or build buckets exist');
select ok(to_regclass('public.agent_persona_versions') is null and to_regprocedure('public.next_persona_version(uuid)') is null, 'persona versions are gone');
select is((select value from public.platform_flags where key = 'agent_creation_open'), 'false'::jsonb, 'agent_creation_open seeded false');
select ok(not has_table_privilege('authenticated', 'public.agents', 'DELETE')
      and not has_column_privilege('authenticated', 'public.agents', 'status', 'UPDATE')
      and not has_column_privilege('authenticated', 'public.agents', 'status', 'INSERT')
      and not has_table_privilege('authenticated', 'public.agent_installs', 'INSERT')
      and not has_table_privilege('authenticated', 'public.platform_flags', 'INSERT'), 'column/table privileges deny status writes, install writes, flag inserts');

-- ---- org admin A creates and edits own agent ----
set local role authenticated;
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000a', true);
select lives_ok($q$ insert into public.agents (slug, org_id, name, plugin_id, created_by)
    values ('acme', (select id from org where k = 'a'), 'Acme', 'ai.rever.boss.plugin.dynamic.agent.acme', 'a9000000-0000-4000-8000-00000000000a') $q$, 'org admin creates an agent for their org');
select throws_ok($q$ insert into public.agents (slug, org_id, name, plugin_id, created_by)
    values ('badplug', (select id from org where k = 'a'), 'B', 'com.example.evil', 'a9000000-0000-4000-8000-00000000000a') $q$, '23514', null, 'plugin_id must be in the agent namespace');
select throws_ok($q$ insert into public.agents (slug, org_id, name, plugin_id, created_by)
    values ('dupplug', (select id from org where k = 'a'), 'D', 'ai.rever.boss.plugin.dynamic.agent.acme', 'a9000000-0000-4000-8000-00000000000a') $q$, '23505', null, 'plugin_id is unique');
select throws_ok($q$ update public.agents set plugin_id = 'ai.rever.boss.plugin.dynamic.agent.other' where slug = 'acme' $q$, '42501', null, 'plugin_id is not updatable by clients');
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
select throws_ok($q$ select public.publish_agent((select id from public.agents), '1.0.0') $q$, '42501', null, 'cannot publish another org''s agent (and cannot see it)');

-- ---- publish ----
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000a', true);
create temp table ag (id uuid);
grant all on ag to authenticated;
insert into ag select id from public.agents where slug = 'acme';
select throws_ok($q$ select public.publish_agent((select id from ag), 'v1') $q$, 'P0001', 'invalid_version', 'a non-semver version is refused');
select lives_ok($q$ insert into public.agents (slug, org_id, name, created_by)
    values ('noplug', (select id from org where k = 'a'), 'NoPlug', 'a9000000-0000-4000-8000-00000000000a') $q$, 'an agent can be created without a plugin_id');
select throws_ok($q$ select public.publish_agent((select id from public.agents where slug = 'noplug'), '1.0.0') $q$, 'P0001', 'plugin_id_not_set', 'cannot publish without a plugin_id');
select is(public.publish_agent((select id from ag), '1.0.0')::text, 'pending_review', 'publish -> pending_review while the flag is false');
select is((select live_version from public.agents where slug = 'acme'), '1.0.0', 'live_version recorded');

reset role;
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
update public.platform_flags set value = 'true'::jsonb where key = 'agent_creation_open';
set local role authenticated;
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000a', true);
select is(public.publish_agent((select id from ag), '1.1.0-rc.1')::text, 'live', 'publish -> live when the flag is true');
select is((select live_version from public.agents where slug = 'acme'), '1.1.0-rc.1', 'live_version updated');
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
select is((select plugin_id from public.agent_public where slug = 'acme'), 'ai.rever.boss.plugin.dynamic.agent.acme', 'plugin_id exposed');
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

-- ---- platform_flags ----
set local role authenticated;
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000c', true);
select is((select value from public.platform_flags where key = 'agent_creation_open'), 'false'::jsonb, 'any signed-in user reads flags');
select is_empty($q$ update public.platform_flags set value = 'true'::jsonb returning 1 $q$, 'non-platform-admin cannot update flags');
select throws_ok($q$ insert into public.platform_flags values ('x', 'true') $q$, '42501', null, 'no flag inserts from clients');
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000d', true);
select lives_ok($q$ update public.platform_flags set value = 'true'::jsonb where key = 'agent_creation_open' $q$, 'platform admin updates a flag');
select is((select value from public.platform_flags where key = 'agent_creation_open'), 'true'::jsonb, 'the flag changed');

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
select ok((select count(*) from public.agent_installs) >= 2, 'bot reads all installs');
select lives_ok($q$ insert into public.agent_installs (user_id, agent_id, instance_id, port, data_dir, state)
    values ('a9000000-0000-4000-8000-00000000000a', (select id from ag), 'inst-a', 8812, 'agents/acme', 'pending') $q$, 'bot inserts an install');
select lives_ok($q$ update public.agent_installs set state = 'running', detail = 'ok' where instance_id = 'inst-a' $q$, 'bot updates an install');
select is((select state from public.agent_installs where instance_id = 'inst-a'), 'running', 'the bot update landed');
select is((select count(*)::int from storage.objects), 0, 'bot reads no storage objects at all');
select is_empty($q$ update public.agents set name = 'bot' returning 1 $q$, 'bot cannot update agents');
select is((select count(*)::int from public.agent_invites), 0, 'bot cannot read agent_invites');
select throws_ok($q$ insert into public.agent_invites (agent_id, email) values ((select id from ag), 'bot@x.test') $q$, '42501', null, 'bot cannot write agent_invites');
select is_empty($q$ update public.platform_flags set value = 'true'::jsonb returning 1 $q$, 'bot cannot update platform_flags');
select throws_ok($q$ delete from public.agent_installs $q$, '42501', null, 'bot cannot delete installs');
select throws_ok($q$ insert into storage.objects (bucket_id, name) values ('agent-brand', (select id::text from ag) || '/bot.png') $q$, '42501', null, 'bot cannot write storage');
select throws_ok($q$ select * from public.agents_service_accounts $q$, '42501', null, 'bot cannot read the accounts table');

-- normal users
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000b', true);
select is(public.is_agents_provisioner(), false, 'is_agents_provisioner is false for a normal user');
select is((select count(*)::int from public.agents), 0, 'org B admin still sees no org A agents');
select is((select array_agg(instance_id) from public.agent_installs), array['inst-b'], 'a normal user reads only their own install');
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000a', true);
select is(public.is_agents_provisioner(), false, 'is_agents_provisioner is false for an org admin');
reset role;
set local role anon;
select throws_ok($q$ select public.is_agents_provisioner() $q$, '42501', null, 'anon cannot call is_agents_provisioner');
reset role;

-- ---- install gate for agent adapters ----
reset role;
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
update public.agents set status = 'live' where slug = 'acme';
insert into public.plugins (author_id, author_name, plugin_id, display_name, type, api_version, published, visibility, org_id)
values ('a9000000-0000-4000-8000-00000000000a', 'A', 'ai.rever.boss.plugin.dynamic.agent.acme', 'Acme', 'panel', '1.0', true, 'org', (select id from org where k = 'a')),
       ('a9000000-0000-4000-8000-00000000000b', 'B', 'ai.rever.boss.plugin.dynamic.agent.globex', 'Globex', 'panel', '1.0', true, 'org', (select id from org where k = 'b'));
insert into public.agents (slug, org_id, name, plugin_id, created_by)
values ('globexagent', (select id from org where k = 'b'), 'Globex', 'ai.rever.boss.plugin.dynamic.agent.globex', 'a9000000-0000-4000-8000-00000000000b');
update public.agents set status = 'live' where slug = 'globexagent';
create temp table pl (k text primary key, id uuid);
insert into pl select 'acme', id from public.plugins where plugin_id = 'ai.rever.boss.plugin.dynamic.agent.acme';
insert into pl select 'globex', id from public.plugins where plugin_id = 'ai.rever.boss.plugin.dynamic.agent.globex';
select is(public.user_can_install_plugin('a9000000-0000-4000-8000-00000000000c', (select id from pl where k = 'acme')), true, 'a non-member with an install of the live agent can install its adapter');
select is(public.user_can_install_plugin('a9000000-0000-4000-8000-00000000000e', (select id from pl where k = 'acme')), false, 'a non-member without an install cannot');
select is(public.user_can_install_plugin('a9000000-0000-4000-8000-00000000000a', (select id from pl where k = 'acme')), true, 'an org member is unaffected');
select is(public.user_can_install_plugin('a9000000-0000-4000-8000-00000000000c', (select id from pl where k = 'globex')), false, 'an install of another agent does not open another org''s adapter');
select is(public.user_can_install_plugin(null, (select id from pl where k = 'acme')), false, 'anonymous cannot install an org adapter');
select is(public.user_can_view_plugin('a9000000-0000-4000-8000-00000000000c', (select id from pl where k = 'acme')), false, 'listing visibility is not widened');
update public.agent_installs set state = 'removed' where instance_id = 'inst-c';
select is(public.user_can_install_plugin('a9000000-0000-4000-8000-00000000000c', (select id from pl where k = 'acme')), false, 'a removed install no longer admits');
update public.agent_installs set state = 'running' where instance_id = 'inst-c';
update public.agents set status = 'suspended' where slug = 'acme';
select is(public.user_can_install_plugin('a9000000-0000-4000-8000-00000000000c', (select id from pl where k = 'acme')), false, 'a suspended agent no longer admits');
update public.agents set status = 'live' where slug = 'acme';
update public.plugins set published = false where plugin_id = 'ai.rever.boss.plugin.dynamic.agent.acme';
select is(public.user_can_install_plugin('a9000000-0000-4000-8000-00000000000c', (select id from pl where k = 'acme')), false, 'an unpublished adapter is not installable through an install');
update public.plugins set published = true where plugin_id = 'ai.rever.boss.plugin.dynamic.agent.acme';
select ok(not has_function_privilege('authenticated', 'public.user_can_install_plugin(uuid,uuid)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.user_can_install_plugin(uuid,uuid)', 'EXECUTE')
      and has_function_privilege('service_role', 'public.user_can_install_plugin(uuid,uuid)', 'EXECUTE'), 'the gate stays service-role only');

-- ---- console helpers: models + platform overview ----
reset role;
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
insert into public.boss_ai_connections (id, base_url, api_key_secret, api_type) values
    ('agtc', 'https://example.com/v1', 'BOSS_AI_AGT', 'openai_chat'),
    ('agtc-off', 'https://example.com/v1', 'BOSS_AI_AGT_OFF', 'openai_chat');
update public.boss_ai_connections set enabled = false where id = 'agtc-off';
insert into public.boss_ai_models (id, display_name, connection_id, upstream_model, capabilities, context_length, max_output_tokens, published, enabled, kind) values
    ('agt-on', 'AGT On', 'agtc', 'vendor/a', array['text', 'tools'], 8192, 1024, true, true, 'chat'),
    ('agt-disabled', 'AGT Disabled', 'agtc', 'vendor/b', array['text'], 8192, 1024, true, false, 'chat'),
    ('agt-unpub', 'AGT Unpublished', 'agtc', 'vendor/c', array['text'], 8192, 1024, false, true, 'chat'),
    ('agt-conn-off', 'AGT Conn Off', 'agtc-off', 'vendor/d', array['text'], 8192, 1024, true, true, 'chat'),
    ('agt-speech', 'AGT Speech', 'agtc', 'vendor/e', array['text'], 8192, 1024, true, true, 'speech');
set local role authenticated;
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000a', true);
select is((select array_agg(model_id order by model_id) from public.list_agent_models() where model_id like 'agt-%'), array['agt-on'], 'an org admin sees only enabled, published chat models on enabled connections');
select is((select provider_id from public.list_agent_models() where model_id = 'agt-on'), 'managed:boss-ai', 'provider id is managed:boss-ai');
select is((select count(*)::int from public.list_agent_models() where description is null or description = ''), 0, 'models carry a description');
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000c', true);
select is((select count(*)::int from public.list_agent_models()), 0, 'a user who administers no org gets no models');
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000d', true);
select ok((select count(*) from public.list_agent_models() where model_id = 'agt-on') = 1, 'a platform admin sees the models');
reset role;
set local role anon;
select throws_ok($q$ select * from public.list_agent_models() $q$, '42501', null, 'anon cannot call list_agent_models');
reset role;

-- fixtures: one agent with a known mix of installs
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
insert into public.agents (slug, org_id, name, plugin_id, created_by)
values ('metrics', (select id from org where k = 'a'), 'Metrics', 'ai.rever.boss.plugin.dynamic.agent.metrics', 'a9000000-0000-4000-8000-00000000000a');
create temp table mg (id uuid);
grant all on mg to authenticated;
insert into mg select id from public.agents where slug = 'metrics';
insert into public.agent_installs (user_id, agent_id, instance_id, port, data_dir, state, running_version, created_at) values
    ('a9000000-0000-4000-8000-00000000000a', (select id from mg), 'm-a', 8901, 'agents/metrics', 'running', '1.0.0', now() - interval '2 days'),
    ('a9000000-0000-4000-8000-00000000000b', (select id from mg), 'm-b', 8902, 'agents/metrics', 'running', '1.0.0', now() - interval '10 days'),
    ('a9000000-0000-4000-8000-00000000000c', (select id from mg), 'm-c', 8903, 'agents/metrics', 'running', '1.1.0', now()),
    ('a9000000-0000-4000-8000-00000000000e', (select id from mg), 'm-e', 8904, 'agents/metrics', 'failed', null, now() - interval '40 days'),
    ('a9000000-0000-4000-8000-00000000000d', (select id from mg), 'm-d', 8905, 'agents/metrics', 'pending', null, now()),
    ('a9000000-0000-4000-8000-00000000000f', (select id from mg), 'm-f', 8906, 'agents/metrics', 'removed', '1.0.0', now() - interval '3 days');

set local role authenticated;
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000a', true);
select throws_ok($q$ select * from public.platform_agents_overview() $q$, '42501', null, 'an org admin cannot read the platform overview');
select throws_ok($q$ select public.platform_agent_detail((select id from mg)) $q$, '42501', null, 'an org admin cannot read platform agent detail');
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000c', true);
select throws_ok($q$ select * from public.platform_agents_overview() $q$, '42501', null, 'a plain user cannot read the platform overview');
select set_config('request.jwt.claim.sub', 'a9000000-0000-4000-8000-00000000000d', true);
select is((select count(*)::int from public.platform_agents_overview()), (select count(*)::int from public.agents), 'the overview covers every agent, whatever its status');
select is((select row(installs_total, installs_running, installs_failed, installs_pending, signups_7d, signups_30d)::text
           from public.platform_agents_overview() where slug = 'metrics'), '(5,3,1,1,4,5)', 'overview counts: removed excluded from installs, signups by created_at');
select ok((select last_activity_at is not null and org_name = 'Agents A' and plugin_id = 'ai.rever.boss.plugin.dynamic.agent.metrics'
           from public.platform_agents_overview() where slug = 'metrics'), 'overview carries org name, plugin id and last activity');
select is((select installs_total from public.platform_agents_overview() where slug = 'globexagent'), 0, 'an agent with no installs shows zeros');

create temp table det (j jsonb);
grant all on det to authenticated;
insert into det select public.platform_agent_detail((select id from mg));
select is((select j #>> '{agent,slug}' from det), 'metrics', 'detail names the agent');
select is((select j #>> '{agent,org_name}' from det), 'Agents A', 'detail names the org');
select is((select j #>> '{agent,created_by_email}' from det), 'owner-a@agents.test', 'detail carries the creator email');
select is((select j #> '{agent,allowed_tools_count}' from det), 'null'::jsonb, 'null allowed_tools means all (count null)');
select is((select jsonb_array_length(j -> 'signups_by_day') from det), 30, 'signups_by_day covers 30 days');
select is((select sum((e ->> 'count')::int) from det, jsonb_array_elements(j -> 'signups_by_day') e), 5::bigint, 'signups_by_day totals the 30-day signups');
select is((select (e ->> 'count')::int from det, jsonb_array_elements(j -> 'signups_by_day') e where e ->> 'day' = ((now() at time zone 'utc')::date - 1)::text), 0, 'quiet days are zero-filled');
select is((select (e ->> 'count')::int from det, jsonb_array_elements(j -> 'signups_by_day') e where e ->> 'day' = ((now() at time zone 'utc')::date)::text), 2, 'today counts both signups');
select is((select j -> 'version_adoption' from det), '[{"version": "1.0.0", "count": 2}, {"version": "1.1.0", "count": 1}]'::jsonb, 'version adoption groups running versions of live installs');
select is((select j -> 'states' from det), '{"running": 3, "failed": 1, "pending": 1, "removed": 1}'::jsonb, 'state counts include removed');
select is((select jsonb_array_length(j -> 'installs') from det), 6, 'installs lists every row');
select ok((select (j #>> '{installs,0,email}') in ('platform@agents.test', 'plain@agents.test') from det), 'installs are newest first');
select is((select j #>> '{installs,5,email}' from det), 'stranger@agents.test', 'the oldest install is last');
select throws_ok($q$ select public.platform_agent_detail(gen_random_uuid()) $q$, 'P0001', 'agent_not_found', 'unknown agent id');
reset role;
select ok(not has_function_privilege('anon', 'public.platform_agents_overview()', 'EXECUTE')
      and not has_function_privilege('anon', 'public.platform_agent_detail(uuid)', 'EXECUTE'), 'platform functions are not anon-callable');
select ok((select column_default is not null from information_schema.columns where table_name = 'agent_installs' and column_name = 'created_at')
      and has_column_privilege('authenticated', 'public.agent_installs', 'running_version', 'UPDATE'), 'created_at defaults and running_version is updatable by the bot grant');

-- nothing in the agents surface leans on the service role
select is((select count(*)::int from pg_proc p where p.pronamespace = 'public'::regnamespace
    and (p.proname like 'agent%' or p.proname in ('publish_agent', 'review_agent', 'suspend_agent',
         'list_pending_agents', 'is_agents_provisioner', 'is_agent_org_admin', 'is_agent_folder_admin'))
    and p.prosrc ilike '%service_role%'), 0, 'no agents function references service_role');
select is((select count(*)::int from pg_policies where (schemaname = 'public' and tablename like 'agent%' or tablename = 'platform_flags'
         or (schemaname = 'storage' and policyname like 'agent-%'))
    and (qual ilike '%service_role%' or with_check ilike '%service_role%' or 'service_role' = any(roles::text[]))), 0, 'no agents policy references service_role');

select * from finish();
rollback;
