-- Compat wrappers for the live console: marketplace_agent_defaults, marketplace_agent_default_set and
-- marketplace_listing_agents over marketplace_auto_installs (agent -> plugin id).
begin;
select plan(47);

-- c1 platform admin, c2 platform viewer, c3 reviewer, a4 plain user.
insert into auth.users (id, email, email_confirmed_at) values
    ('c8000000-0000-4000-8000-0000000000c1', 'admin@cp.test', now()),
    ('c8000000-0000-4000-8000-0000000000c2', 'viewer@cp.test', now()),
    ('c8000000-0000-4000-8000-0000000000c3', 'reviewer@cp.test', now()),
    ('c8000000-0000-4000-8000-0000000000a4', 'plain@cp.test', now());
insert into public.user_roles (user_id, role_id)
    select 'c8000000-0000-4000-8000-0000000000c1', id from public.roles where name = 'admin';
insert into public.user_roles (user_id, role_id)
    select 'c8000000-0000-4000-8000-0000000000c2', id from public.roles where name = 'platform_viewer';
insert into public.user_roles (user_id, role_id)
    select 'c8000000-0000-4000-8000-0000000000c3', id from public.roles where name = 'marketplace_reviewer';

select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select public.create_organisation_internal(p_slug => 'cpco', p_name => 'Cp Co', p_description => null,
    p_owner_id => 'c8000000-0000-4000-8000-0000000000c1', p_domain => null, p_visibility => 'private',
    p_join_policy => 'invite_only');
insert into public.plugins (author_id, author_name, plugin_id, display_name, type, api_version, published, visibility, org_id)
values ('c8000000-0000-4000-8000-0000000000c1', 'A', 'ai.rever.boss.plugin.dynamic.agent.cpone', 'Cp One Plugin', 'panel', '1.0', true, 'org',
        (select id from public.organisations where slug = 'cpco'));
insert into public.agents (slug, org_id, name, plugin_id, created_by) values
    ('cp-one', (select id from public.organisations where slug = 'cpco'), 'Cp One', 'ai.rever.boss.plugin.dynamic.agent.cpone', 'c8000000-0000-4000-8000-0000000000c1'),
    ('cp-two', (select id from public.organisations where slug = 'cpco'), 'Cp Two', 'ai.rever.boss.plugin.dynamic.agent.cptwo', 'c8000000-0000-4000-8000-0000000000c1'),
    ('cp-none', (select id from public.organisations where slug = 'cpco'), 'Cp None', null, 'c8000000-0000-4000-8000-0000000000c1');
select set_config('request.jwt.claims', '', true);
update public.platform_flags set value = 'true'::jsonb where key in ('marketplace_bundles', 'marketplace_restricted_listings');

create function pg_temp.u(p text) returns uuid language sql immutable as $$
    select ('c8000000-0000-4000-8000-0000000000' || p)::uuid $$;
create function pg_temp.ag(s text) returns uuid language sql stable security definer as $$
    select id from public.agents where slug = s $$;
create function pg_temp.as_user(p text) returns void language sql as $$
    select set_config('request.jwt.claim.sub', pg_temp.u(p)::text, true),
           set_config('request.jwt.claims', json_build_object('role', 'authenticated', 'sub', pg_temp.u(p))::text, true);
$$;
create function pg_temp.as_anon() returns void language sql as $$
    select set_config('request.jwt.claim.sub', '', true), set_config('request.jwt.claims', '{"role":"anon"}', true);
$$;
create function pg_temp.skill(slug text) returns jsonb language sql immutable as $$
    select jsonb_build_object('schema', 1, 'kind', 'skill', 'slug', slug, 'name', 'Item ' || slug, 'summary', 'One line.',
                              'publisher_name', 'Pub', 'skill', jsonb_build_object('entry', 'SKILL.md')) $$;
create function pg_temp.slugs(j jsonb) returns jsonb language sql immutable as $$
    select COALESCE(jsonb_agg(x ->> 'slug' order by x ->> 'slug'), '[]'::jsonb) from jsonb_array_elements(COALESCE(j, '[]'::jsonb)) x $$;
create function pg_temp.cnt(q text) returns int language plpgsql security definer as $$
declare n int; begin execute 'select count(*)::int from ' || q into n; return n; end $$;
grant execute on function pg_temp.u(text), pg_temp.ag(text), pg_temp.as_user(text), pg_temp.as_anon(), pg_temp.skill(text),
    pg_temp.slugs(jsonb), pg_temp.cnt(text) to authenticated, anon, service_role;

-- md-a, md-b: legacy markdown skills (md-b reviewed); a bundle, a restricted bundle, a connector, a pending skill.
select pg_temp.as_user('c1');
select public.marketplace_submit('skill', 'md-a', 'Md A', 'Sum', '', 'Pub', '{"markdown":"A."}', true);
select public.marketplace_submit('skill', 'md-b', 'Md B', 'Sum', '', 'Pub', '{"markdown":"B."}', true);
select public.marketplace_mark_reviewed('md-b');
select public.marketplace_submit_bundle(pg_temp.u('c1'), pg_temp.skill('cp-bundle'), 'bundles/' || pg_temp.u('c1') || '/' || repeat('a', 64) || '.zip', repeat('a', 64), 1000, true);
select public.marketplace_submit_bundle(pg_temp.u('c1'), pg_temp.skill('cp-rs') || '{"visibility":{"agents":["ai.rever.boss.plugin.dynamic.agent.cpone"]}}',
    'bundles/' || pg_temp.u('c1') || '/' || repeat('b', 64) || '.zip', repeat('b', 64), 1000, true);
insert into public.marketplace_listings (kind, slug, publisher_id, status)
    values ('connector', 'cp-conn', pg_temp.u('c1'), 'live'), ('skill', 'cp-pending', pg_temp.u('c1'), 'pending_review');
set local role authenticated;

-- ---- grants and permissions ----
select ok(has_function_privilege('authenticated', 'public.marketplace_agent_defaults(uuid)', 'execute')
      and has_function_privilege('authenticated', 'public.marketplace_agent_default_set(uuid, text, boolean)', 'execute')
      and has_function_privilege('authenticated', 'public.marketplace_listing_agents(text)', 'execute')
      and not has_function_privilege('anon', 'public.marketplace_agent_defaults(uuid)', 'execute')
      and not has_function_privilege('anon', 'public.marketplace_agent_default_set(uuid, text, boolean)', 'execute')
      and not has_function_privilege('anon', 'public.marketplace_listing_agents(text)', 'execute'), 'old grants: authenticated, not anon');
select pg_temp.as_user('a4');
select throws_ok($$ select public.marketplace_agent_default_set(pg_temp.ag('cp-one'), 'md-a', true) $$, '42501', 'Permission denied', 'a user cannot set');
select throws_ok($$ select public.marketplace_agent_defaults(pg_temp.ag('cp-one')) $$, '42501', 'Permission denied', 'nor read defaults');
select throws_ok($$ select public.marketplace_listing_agents('md-a') $$, '42501', 'Permission denied', 'nor the reverse lookup');
select pg_temp.as_user('c2');
select throws_ok($$ select public.marketplace_agent_default_set(pg_temp.ag('cp-one'), 'md-a', true) $$, '42501', 'Permission denied', 'a viewer cannot set');
select pg_temp.as_user('c3');
select throws_ok($$ select public.marketplace_agent_default_set(pg_temp.ag('cp-one'), 'md-a', true) $$, '42501', 'Permission denied', 'nor a reviewer');
select pg_temp.as_anon();
select throws_ok($$ select public.marketplace_agent_defaults(pg_temp.ag('cp-one')) $$, '42501', null, 'anon cannot read');

-- ---- refusal codes ----
select pg_temp.as_user('c1');
select throws_ok($$ select public.marketplace_agent_default_set(gen_random_uuid(), 'md-a', true) $$, 'P0001', 'agent_not_found', 'unknown agent');
select throws_ok($$ select public.marketplace_agent_default_set(pg_temp.ag('cp-one'), 'nope', true) $$, 'P0001', 'listing_not_found', 'unknown listing');
select throws_ok($$ select public.marketplace_agent_default_set(pg_temp.ag('cp-one'), 'cp-conn', true) $$, 'P0001', 'not_a_skill', 'connectors cannot be defaults');
select throws_ok($$ select public.marketplace_agent_default_set(pg_temp.ag('cp-one'), 'cp-conn', false) $$, 'P0001', 'not_a_skill', 'not even on removal (as before)');
select throws_ok($$ select public.marketplace_agent_default_set(pg_temp.ag('cp-one'), 'cp-pending', true) $$, 'P0001', 'listing_not_live', 'not live');
select throws_ok($$ select public.marketplace_agent_default_set(pg_temp.ag('cp-one'), 'cp-bundle', true) $$, 'P0001', 'default_requires_legacy_skill', 'a bundle skill is refused');
select throws_ok($$ select public.marketplace_agent_default_set(pg_temp.ag('cp-one'), 'cp-rs', true) $$, 'P0001', 'default_requires_legacy_skill', 'a restricted skill is refused');
select throws_ok($$ select public.marketplace_agent_default_set(pg_temp.ag('cp-none'), 'md-a', true) $$, 'P0001', 'agent_has_no_plugin', 'an agent without a plugin id');
select is(pg_temp.cnt('public.marketplace_auto_installs'), 0, 'refusals write nothing');

-- ---- add, read, reverse ----
select is(jsonb_array_length(public.marketplace_agent_default_set(pg_temp.ag('cp-one'), 'md-b', true)), 1, 'added');
select is((public.marketplace_agent_defaults(pg_temp.ag('cp-one')) -> 0) - 'added_at',
    jsonb_build_object('slug', 'md-b', 'name', 'Md B', 'version', 1, 'reviewed', true, 'status', 'live'), 'row shape matches AgentDefaultSkill');
select ok((public.marketplace_agent_defaults(pg_temp.ag('cp-one')) -> 0 ->> 'added_at')::timestamptz <= now(), 'with added_at');
select is(pg_temp.cnt($q$public.marketplace_auto_installs where plugin_id = 'ai.rever.boss.plugin.dynamic.agent.cpone'$q$), 1, 'stored under the agent''s plugin id');
select is(pg_temp.cnt($q$public.agent_audit_log where action = 'agent.marketplace_default_added' and detail ->> 'slug' = 'md-b'$q$), 1, 'agent audit as before');
select is(pg_temp.cnt($q$public.platform_audit_log where action = 'marketplace.auto_install_set' and detail ->> 'slug' = 'md-b'$q$), 1, 'and the auto-install audit');
select is(jsonb_array_length(public.marketplace_agent_default_set(pg_temp.ag('cp-one'), 'md-b', true)), 1, 'adding twice is a no-op');
select is(pg_temp.cnt($q$public.agent_audit_log where action = 'agent.marketplace_default_added' and detail ->> 'slug' = 'md-b'$q$), 1, 'and not audited again');
select is(pg_temp.slugs(public.marketplace_agent_default_set(pg_temp.ag('cp-one'), 'md-a', true)), '["md-a","md-b"]'::jsonb, 'returns the agent''s defaults');
select is(jsonb_path_query_array(public.marketplace_agent_defaults(pg_temp.ag('cp-one')), '$[*].slug'), '["md-a","md-b"]'::jsonb, 'ordered by slug');
select is(public.marketplace_agent_defaults(pg_temp.ag('cp-one')) -> 0 -> 'reviewed', 'false'::jsonb, 'with reviewed flags');
select public.marketplace_agent_default_set(pg_temp.ag('cp-two'), 'md-a', true);
select is(jsonb_path_query_array(public.marketplace_listing_agents('md-a'), '$[*].slug'), '["cp-one","cp-two"]'::jsonb, 'listing_agents finds both, by slug');
select is((public.marketplace_listing_agents('md-a') -> 0) - 'added_at',
    jsonb_build_object('agent_id', pg_temp.ag('cp-one'), 'slug', 'cp-one', 'name', 'Cp One', 'org_id', (select id from public.organisations where slug = 'cpco')),
    'row shape matches ListingAgent');
select is(public.marketplace_listing_agents('nothing-here'), '[]'::jsonb, 'unknown slug is empty');
select is(public.marketplace_agent_defaults(gen_random_uuid()), '[]'::jsonb, 'unknown agent reads empty');
select is(public.marketplace_agent_defaults(pg_temp.ag('cp-none')), '[]'::jsonb, 'an agent without a plugin id reads empty');

-- A plugin-id auto-install made by the new console shows in the old reads; a non-agent plugin id is left out.
select public.marketplace_toggle_auto_install('md-b', 'test.cp.optimist', true);
select is(jsonb_path_query_array(public.marketplace_listing_agents('md-b'), '$[*].slug'), '["cp-one"]'::jsonb, 'non-agent plugin ids are not agents');
select is(public.marketplace_auto_install_skills('ai.rever.boss.plugin.dynamic.agent.cpone') -> 'skills' -> 0 ->> 'slug', 'md-a', 'the new runtime read serves an old-console default');
select is(pg_temp.slugs(public.marketplace_agent_skills('ai.rever.boss.plugin.dynamic.agent.cpone') -> 'skills'), '["md-a","md-b"]'::jsonb, 'so does released Max''s read');

-- ---- viewer reads ----
select pg_temp.as_user('c2');
select is(jsonb_array_length(public.marketplace_agent_defaults(pg_temp.ag('cp-one'))), 2, 'a viewer reads an agent''s defaults');
select is(jsonb_array_length(public.marketplace_listing_agents('md-a')), 2, 'and which agents have a skill');

-- ---- suspended: shown, removable, not addable ----
select pg_temp.as_user('c1');
select public.marketplace_suspend('md-a', true, 'Reported');
select is(public.marketplace_agent_defaults(pg_temp.ag('cp-one')) -> 0 ->> 'status', 'suspended', 'admin sees the suspended default');
select throws_ok($$ select public.marketplace_agent_default_set(pg_temp.ag('cp-one'), 'md-a', true) $$, 'P0001', 'listing_not_live', 'a suspended listing cannot be added again (no-op refused as before)');
select is(pg_temp.slugs(public.marketplace_agent_default_set(pg_temp.ag('cp-one'), 'md-a', false)), '["md-b"]'::jsonb, 'but can be removed');
select is(pg_temp.cnt($q$public.agent_audit_log where action = 'agent.marketplace_default_removed' and detail ->> 'slug' = 'md-a'$q$), 1, 'removal audited');
select is(pg_temp.slugs(public.marketplace_agent_default_set(pg_temp.ag('cp-one'), 'md-a', false)), '["md-b"]'::jsonb, 'removing twice is a no-op');
select is(pg_temp.cnt($q$public.agent_audit_log where action = 'agent.marketplace_default_removed' and detail ->> 'slug' = 'md-a'$q$), 1, 'and not audited again');
select is(jsonb_path_query_array(public.marketplace_listing_agents('md-a'), '$[*].slug'), '["cp-two"]'::jsonb, 'the other agent keeps it');

-- ---- caps: 10 plugin ids per listing (new), 50 defaults per agent (old) ----
select public.marketplace_suspend('md-a', false, null);
reset role;
insert into public.marketplace_auto_installs (listing_id, plugin_id)
    select (select id from public.marketplace_listings where slug = 'md-b'), 'p.cap' || i from generate_series(1, 8) i;
set local role authenticated;
select pg_temp.as_user('c1');
select throws_ok($$ select public.marketplace_agent_default_set(pg_temp.ag('cp-two'), 'md-b', true) $$, 'P0001', 'too_many_defaults', 'an 11th plugin id on a listing');
reset role;
insert into public.marketplace_listings (kind, slug, publisher_id, status)
    select 'skill', 'cap-' || i, pg_temp.u('c1'), 'live' from generate_series(1, 49) i;
insert into public.marketplace_auto_installs (listing_id, plugin_id)
    select id, 'ai.rever.boss.plugin.dynamic.agent.cptwo' from public.marketplace_listings where slug like 'cap-%';
set local role authenticated;
select pg_temp.as_user('c1');
select public.marketplace_submit('skill', 'md-c', 'Md C', 'Sum', '', 'Pub', '{"markdown":"C."}', true);
select throws_ok($$ select public.marketplace_agent_default_set(pg_temp.ag('cp-two'), 'md-c', true) $$, 'P0001', 'too_many_defaults', 'a 51st default on an agent');
select is(jsonb_array_length(public.marketplace_agent_default_set(pg_temp.ag('cp-two'), 'md-a', true)), 50, 'an existing one is still a no-op at the cap');

select * from finish();
rollback;
