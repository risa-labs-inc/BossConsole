-- Marketplace auto-install: per-plugin auto-install of skill listings, the runtime list and fetch, hidden restricted
-- listings (search, get, category and tag counts), pruning when a new live version drops a plugin, reviewer access.
begin;
select plan(92);

-- c1 platform admin, c2 platform viewer, c3 reviewer, a1 trusted publisher, a3 member with an installed agent,
-- a4 plain signed-in user, a5 user whose agent install was removed.
insert into auth.users (id, email, email_confirmed_at) values
    ('c7000000-0000-4000-8000-0000000000c1', 'admin@ai.test', now()),
    ('c7000000-0000-4000-8000-0000000000c2', 'viewer@ai.test', now()),
    ('c7000000-0000-4000-8000-0000000000c3', 'reviewer@ai.test', now()),
    ('c7000000-0000-4000-8000-0000000000a1', 'pub@ai.test', now()),
    ('c7000000-0000-4000-8000-0000000000a3', 'member@ai.test', now()),
    ('c7000000-0000-4000-8000-0000000000a4', 'plain@ai.test', now()),
    ('c7000000-0000-4000-8000-0000000000a5', 'removed@ai.test', now());
insert into public.user_roles (user_id, role_id)
    select 'c7000000-0000-4000-8000-0000000000c1', id from public.roles where name = 'admin';
insert into public.user_roles (user_id, role_id)
    select 'c7000000-0000-4000-8000-0000000000c2', id from public.roles where name = 'platform_viewer';
insert into public.user_roles (user_id, role_id)
    select 'c7000000-0000-4000-8000-0000000000c3', id from public.roles where name = 'marketplace_reviewer';
insert into public.marketplace_trusted_publishers (user_id) values ('c7000000-0000-4000-8000-0000000000a1');

select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select public.create_organisation_internal(p_slug => 'aico', p_name => 'Ai Co', p_description => null,
    p_owner_id => 'c7000000-0000-4000-8000-0000000000a1', p_domain => null, p_visibility => 'private',
    p_join_policy => 'invite_only');
insert into public.plugins (author_id, author_name, plugin_id, display_name, type, api_version, published, visibility, org_id)
values ('c7000000-0000-4000-8000-0000000000a1', 'A', 'test.ai.optimist', 'Optimist Test', 'panel', '1.0', true, 'public', (select id from public.organisations where slug = 'aico')),
       ('c7000000-0000-4000-8000-0000000000a1', 'A', 'test.ai.other', 'Other Test', 'panel', '1.0', true, 'public', (select id from public.organisations where slug = 'aico')),
       ('c7000000-0000-4000-8000-0000000000a1', 'A', 'test.ai.draft', 'Draft Test', 'panel', '1.0', false, 'public', (select id from public.organisations where slug = 'aico')),
       ('c7000000-0000-4000-8000-0000000000a1', 'A', 'ai.rever.boss.plugin.dynamic.agent.aiagent', 'Ai Agent Plugin', 'panel', '1.0', false, 'org', (select id from public.organisations where slug = 'aico'));
insert into public.agents (slug, org_id, name, plugin_id, created_by) values
    ('aiagent', (select id from public.organisations where slug = 'aico'), 'Ai Agent',
     'ai.rever.boss.plugin.dynamic.agent.aiagent', 'c7000000-0000-4000-8000-0000000000a1');
insert into public.agent_installs (user_id, agent_id, instance_id, port, data_dir, state) values
    ('c7000000-0000-4000-8000-0000000000a3', (select id from public.agents where slug = 'aiagent'), 'ai-a', 9911, 'agents/aiagent', 'running'),
    ('c7000000-0000-4000-8000-0000000000a5', (select id from public.agents where slug = 'aiagent'), 'ai-b', 9912, 'agents/aiagent', 'removed');
select set_config('request.jwt.claims', '', true);
update public.platform_flags set value = 'true'::jsonb where key in ('marketplace_bundles', 'marketplace_restricted_listings');

create function pg_temp.u(p text) returns uuid language sql immutable as $$
    select ('c7000000-0000-4000-8000-0000000000' || p)::uuid $$;
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
create function pg_temp.sb(who text, m jsonb, sha char default 'a') returns jsonb language sql as $$
    select public.marketplace_submit_bundle(pg_temp.u(who), m, 'bundles/' || pg_temp.u(who) || '/' || repeat(sha, 64) || '.zip',
                                            repeat(sha, 64), 1000, true) $$;
create function pg_temp.slugs(j jsonb) returns jsonb language sql immutable as $$
    select COALESCE(jsonb_agg(x ->> 'slug' order by x ->> 'slug'), '[]'::jsonb) from jsonb_array_elements(COALESCE(j, '[]'::jsonb)) x $$;
create function pg_temp.cnt(q text) returns int language plpgsql security definer as $$
declare n int; begin execute 'select count(*)::int from ' || q into n; return n; end $$;
grant execute on function pg_temp.u(text), pg_temp.as_user(text), pg_temp.as_anon(), pg_temp.skill(text),
    pg_temp.sb(text, jsonb, char), pg_temp.slugs(jsonb), pg_temp.cnt(text) to authenticated, anon, service_role;

-- Listings: open-md (legacy markdown), open-bundle, rs-bundle (restricted to Optimist), a2 (publisher a1 bundle, open),
-- a connector row and a not-live skill row.
select pg_temp.as_user('c1');
select public.marketplace_submit('skill', 'open-md', 'Open md', 'Sum', '', 'Pub', '{"markdown":"Hello md."}', true);
select pg_temp.sb('c1', pg_temp.skill('open-bundle'));
select pg_temp.sb('c1', pg_temp.skill('rs-bundle') || '{"visibility":{"agents":["test.ai.optimist"]}}', 'b');
select pg_temp.sb('a1', pg_temp.skill('pub-bundle'), 'c');
insert into public.marketplace_listings (kind, slug, publisher_id, status)
    values ('connector', 'a-connector', pg_temp.u('c1'), 'live'), ('skill', 'a-pending', pg_temp.u('c1'), 'pending_review');
create temp table cat as select slug from public.marketplace_categories order by sort, slug limit 1;
grant select on cat to authenticated, anon;
update public.marketplace_versions v set category = (select slug from cat)
    from public.marketplace_listings l where l.id = v.listing_id and l.slug in ('open-md', 'rs-bundle');
set local role authenticated;

-- ---- grants ----
select ok(not has_table_privilege('authenticated', 'public.marketplace_auto_installs', 'select')
      and not has_table_privilege('anon', 'public.marketplace_auto_installs', 'select')
      and not has_table_privilege('service_role', 'public.marketplace_auto_installs', 'select'), 'the table has no client grants');
select ok(not has_function_privilege('anon', 'public.marketplace_set_auto_install(text, text[])', 'execute')
      and not has_function_privilege('anon', 'public.marketplace_auto_install_skills(text)', 'execute')
      and not has_function_privilege('anon', 'public.marketplace_auto_install_fetch(text, text)', 'execute')
      and has_function_privilege('authenticated', 'public.marketplace_set_auto_install(text, text[])', 'execute')
      and has_function_privilege('authenticated', 'public.marketplace_auto_install_skills(text)', 'execute')
      and has_function_privilege('authenticated', 'public.marketplace_auto_install_fetch(text, text)', 'execute'), 'RPC grants');
select ok(not has_function_privilege('authenticated', 'public.marketplace_restricted_hidden(uuid, text[], text)', 'execute')
      and not has_function_privilege('authenticated', 'public.marketplace_can_use_plugin(uuid, text)', 'execute')
      and not has_function_privilege('authenticated', 'public.marketplace_auto_install_prune()', 'execute'), 'helpers are internal');
select is(to_regclass('public.marketplace_agent_defaults'), null, 'the old table is gone');
select is(to_regprocedure('public.marketplace_agent_skills(text)'), null, 'and the old runtime read');
select is(to_regprocedure('public.marketplace_agent_default_set(uuid, text, boolean)'), null, 'and the old setter');

-- ---- marketplace_set_auto_install ----
select pg_temp.as_user('a4');
select throws_ok($$ select public.marketplace_set_auto_install('open-md', array['test.ai.optimist']) $$, '42501', 'Permission denied', 'admin only');
select pg_temp.as_user('c2');
select throws_ok($$ select public.marketplace_set_auto_install('open-md', array['test.ai.optimist']) $$, '42501', 'Permission denied', 'a platform viewer cannot');
select pg_temp.as_user('c3');
select throws_ok($$ select public.marketplace_set_auto_install('open-md', array['test.ai.optimist']) $$, '42501', 'Permission denied', 'nor a reviewer');
select pg_temp.as_anon();
select throws_ok($$ select public.marketplace_set_auto_install('open-md', array['test.ai.optimist']) $$, '42501', null, 'nor anon');
select pg_temp.as_user('c1');
select is(public.marketplace_set_auto_install('nope', array['test.ai.optimist']), '{"ok":false,"reason":"not_found"}'::jsonb, 'unknown slug');
select is(public.marketplace_set_auto_install('a-connector', array['test.ai.optimist']), '{"ok":false,"reason":"not_skill"}'::jsonb, 'skills only');
select is(public.marketplace_set_auto_install('a-pending', array['test.ai.optimist']), '{"ok":false,"reason":"not_live"}'::jsonb, 'must be live');
select is(public.marketplace_set_auto_install('open-md', (select array_agg('p.id' || i) from generate_series(1, 11) i)), '{"ok":false,"reason":"too_many"}'::jsonb, 'more than 10');
select is(public.marketplace_set_auto_install('open-md', array['Bad ID']), '{"ok":false,"reason":"invalid_plugin_id"}'::jsonb, 'malformed plugin id');
select is(public.marketplace_set_auto_install('rs-bundle', array['test.ai.other']), '{"ok":false,"reason":"not_allowed_agent"}'::jsonb, 'outside the restriction');
select is(public.marketplace_set_auto_install('rs-bundle', array['test.ai.optimist', 'test.ai.other']), '{"ok":false,"reason":"not_allowed_agent"}'::jsonb, 'any id outside refuses all');
select is(pg_temp.cnt('public.marketplace_auto_installs'), 0, 'refusals write nothing');
select is(public.marketplace_set_auto_install('open-md', array['test.ai.other', 'test.ai.optimist', 'test.ai.optimist', null]),
    '{"ok":true,"auto_install":["test.ai.optimist","test.ai.other"]}'::jsonb, 'set: sorted, deduplicated, nulls dropped');
select is(pg_temp.cnt('public.platform_audit_log where action = ''marketplace.auto_install_set'''), 1, 'audited');
select is(public.marketplace_set_auto_install('open-md', array['test.ai.optimist', 'test.ai.other']) ->> 'ok', 'true', 'same set again');
select is(pg_temp.cnt('public.platform_audit_log where action = ''marketplace.auto_install_set'''), 1, 'an unchanged set is not audited again');
select is(public.marketplace_set_auto_install('open-md', array['test.ai.other']), '{"ok":true,"auto_install":["test.ai.other"]}'::jsonb, 'replaces the set');
select is(pg_temp.cnt('public.marketplace_auto_installs where plugin_id = ''test.ai.optimist'''), 0, 'the dropped id is gone');
select is(public.marketplace_set_auto_install('open-md', array['test.ai.optimist']) ->> 'ok', 'true', 'back to optimist');
select is(public.marketplace_set_auto_install('open-bundle', array['test.ai.optimist']) ->> 'ok', 'true', 'an open bundle skill');
select is(public.marketplace_set_auto_install('rs-bundle', array['test.ai.optimist']) ->> 'ok', 'true', 'a restricted skill to its allowed plugin');
select is(public.marketplace_set_auto_install('pub-bundle', array['ai.rever.boss.plugin.dynamic.agent.aiagent', 'test.ai.draft']) ->> 'ok', 'true', 'a managed agent plugin id and a draft plugin id');
select is(public.marketplace_set_auto_install('pub-bundle', array[]::text[]), '{"ok":true,"auto_install":[]}'::jsonb, 'an empty set clears');
select is(public.marketplace_set_auto_install('pub-bundle', null), '{"ok":true,"auto_install":[]}'::jsonb, 'null too');
select is(public.marketplace_set_auto_install('a-connector', array[]::text[]) ->> 'ok', 'true', 'clearing a non-skill is fine');
select is(public.marketplace_set_auto_install('pub-bundle', array['test.ai.draft']) ->> 'ok', 'true', 'draft plugin kept for the permission test');

-- ---- the runtime list ----
select pg_temp.as_user('a4');
select is(pg_temp.slugs(public.marketplace_auto_install_skills('test.ai.optimist') -> 'skills'), '["open-bundle","open-md","rs-bundle"]'::jsonb,
    'a person who may install the plugin gets the open and the restricted listings');
select is(public.marketplace_auto_install_skills('test.ai.optimist') -> 'skills' -> 1,
    jsonb_build_object('slug', 'open-md', 'name', 'Open md', 'summary', 'Sum', 'version', 1, 'reviewed', false, 'bundle', false, 'markdown', 'Hello md.'),
    'a markdown skill carries its markdown and no bundle fields');
select is((public.marketplace_auto_install_skills('test.ai.optimist') -> 'skills' -> 2) - 'name' - 'summary', jsonb_build_object('slug', 'rs-bundle', 'version', 1,
    'reviewed', false, 'bundle', true, 'bundle_sha256', repeat('b', 64), 'bundle_size', 1000), 'a bundle skill carries sha256 and size and no markdown');
select is(pg_temp.slugs(public.marketplace_auto_install_skills('test.ai.other') -> 'skills'), '[]'::jsonb, 'another plugin gets nothing');
select throws_ok($$ select public.marketplace_auto_install_skills('test.ai.nobody') $$, '42501', 'Permission denied', 'an unknown plugin id is denied like an unusable one (no oracle)');
select throws_ok($$ select public.marketplace_auto_install_skills('test.ai.draft') $$, '42501', 'Permission denied', 'a plugin the caller cannot install is denied');
select throws_ok($$ select public.marketplace_auto_install_skills('ai.rever.boss.plugin.dynamic.agent.aiagent') $$, '42501', 'Permission denied',
    'an org agent without an install, edit right or plugin access is denied');
select pg_temp.as_user('a5');
select throws_ok($$ select public.marketplace_auto_install_skills('ai.rever.boss.plugin.dynamic.agent.aiagent') $$, '42501', 'Permission denied', 'a removed install is denied');
select pg_temp.as_user('a3');
select lives_ok($$ select public.marketplace_auto_install_skills('ai.rever.boss.plugin.dynamic.agent.aiagent') $$, 'an installed person is allowed on the agent path');
select pg_temp.as_user('c1');
select lives_ok($$ select public.marketplace_auto_install_skills('test.ai.draft') $$, 'a platform admin is allowed');
select is(pg_temp.slugs(public.marketplace_auto_install_skills('test.ai.draft') -> 'skills'), '["pub-bundle"]'::jsonb, 'and sees its list');
select pg_temp.as_anon();
select throws_ok($$ select public.marketplace_auto_install_skills('test.ai.optimist') $$, '42501', null, 'signed out is denied');

-- Bundles off: bundle entries leave the list (the install check refuses them), markdown stays.
reset role;
update public.platform_flags set value = 'false'::jsonb where key = 'marketplace_bundles';
set local role authenticated;
select pg_temp.as_user('a4');
select is(pg_temp.slugs(public.marketplace_auto_install_skills('test.ai.optimist') -> 'skills'), '["open-md"]'::jsonb, 'bundles flag off: markdown only');
reset role;
update public.platform_flags set value = 'true'::jsonb where key = 'marketplace_bundles';
update public.platform_flags set value = 'false'::jsonb where key = 'marketplace_restricted_listings';
set local role authenticated;
select pg_temp.as_user('a4');
select is(pg_temp.slugs(public.marketplace_auto_install_skills('test.ai.optimist') -> 'skills'), '["open-bundle","open-md"]'::jsonb, 'restricted flag off: restricted skill leaves the list');
reset role;
update public.platform_flags set value = 'true'::jsonb where key = 'marketplace_restricted_listings';
set local role authenticated;

-- Suspended: gone from the list.
select pg_temp.as_user('c1');
select public.marketplace_suspend('open-md', true, 'Reported');
select pg_temp.as_user('a4');
select is(pg_temp.slugs(public.marketplace_auto_install_skills('test.ai.optimist') -> 'skills'), '["open-bundle","rs-bundle"]'::jsonb, 'a suspended listing is not served');
select pg_temp.as_user('c1');
select public.marketplace_suspend('open-md', false, null);

-- ---- fetch: no install is recorded or counted ----
reset role;
create function pg_temp.totals() returns text language sql security definer as $$
    select (select coalesce(sum(install_count), 0) from public.marketplace_listings) || '/' || (select count(*) from public.marketplace_installs) $$;
grant execute on function pg_temp.totals() to authenticated, anon;
create temp table before_counts as select pg_temp.totals() as t;
grant select on before_counts to authenticated, anon;
set local role authenticated;
select pg_temp.as_user('a4');
select is(public.marketplace_auto_install_fetch('rs-bundle', 'test.ai.optimist') ->> 'bundle_path',
    'bundles/' || pg_temp.u('c1') || '/' || repeat('b', 64) || '.zip', 'fetch returns the bundle path of a restricted listing for its plugin');
select is(public.marketplace_auto_install_fetch('rs-bundle', 'test.ai.optimist') ->> 'bundle_sha256', repeat('b', 64), 'and the sha256');
select is(public.marketplace_auto_install_fetch('open-md', 'test.ai.optimist') -> 'content' ->> 'markdown', 'Hello md.', 'and markdown content');
select is(public.marketplace_auto_install_fetch('open-md', 'test.ai.other'), '{"error":"not_found"}'::jsonb, 'a listing not auto-installed for that plugin is not found');
select is(public.marketplace_auto_install_fetch('nope', 'test.ai.optimist'), '{"error":"not_found"}'::jsonb, 'unknown slug');
select is(public.marketplace_auto_install_fetch('a-connector', 'test.ai.optimist'), '{"error":"not_found"}'::jsonb, 'a non-skill is never served');
select throws_ok($$ select public.marketplace_auto_install_fetch('pub-bundle', 'test.ai.draft') $$, '42501', 'Permission denied', 'fetch needs the same plugin access');
select pg_temp.as_anon();
select throws_ok($$ select public.marketplace_auto_install_fetch('open-md', 'test.ai.optimist') $$, '42501', null, 'and a signed-in caller');
select is(pg_temp.totals(), (select t from before_counts), 'install_count and marketplace_installs are untouched');

-- ---- hidden restricted listings ----
select pg_temp.as_anon();
select is(pg_temp.slugs(public.marketplace_search()), '["open-bundle","open-md","pub-bundle"]'::jsonb, 'anon search skips the restricted listing');
select is(public.marketplace_get('rs-bundle'), null, 'anon get is null');
select is(public.marketplace_get('rs-bundle', 'test.ai.optimist'), null, 'anon cannot claim the agent id');
select pg_temp.as_user('a4');
select is(pg_temp.slugs(public.marketplace_search()), '["open-bundle","open-md","pub-bundle"]'::jsonb, 'a signed-in user without an agent: hidden');
select is(public.marketplace_get('rs-bundle'), null, 'get is null, same as a missing slug');
select is(public.marketplace_get('rs-bundle', 'test.ai.other'), null, 'another agent id: hidden');
select is(pg_temp.slugs(public.marketplace_search(p_agent => 'test.ai.other')), '["open-bundle","open-md","pub-bundle"]'::jsonb, 'search for another agent: hidden');
select is(pg_temp.slugs(public.marketplace_search(p_agent => 'test.ai.optimist')), '["open-bundle","open-md","pub-bundle","rs-bundle"]'::jsonb, 'the allowed agent sees it');
select is(public.marketplace_get('rs-bundle', 'test.ai.optimist') ->> 'installable', 'true', 'and can install it');
select ok(public.marketplace_get('rs-bundle', 'test.ai.optimist') -> 'live' ? 'content', 'with content');
select is(public.marketplace_get('rs-bundle', 'test.ai.optimist') -> 'auto_install', null, 'auto_install is not shown to it');
select is(public.marketplace_get('open-md') -> 'auto_install_names', null, 'nor to a plain viewer of an open listing');
select is(public.marketplace_get('nope'), public.marketplace_get('rs-bundle'), 'hidden and missing are the same answer');
select is((select (x ->> 'count')::int from jsonb_array_elements(public.marketplace_categories()) x where x ->> 'slug' = (select slug from cat)), 1,
    'category counts skip the hidden listing');
select is((select (x ->> 'count')::int from jsonb_array_elements(public.marketplace_categories('test.ai.optimist')) x where x ->> 'slug' = (select slug from cat)), 2,
    'and count it for the allowed agent');
reset role;
update public.marketplace_versions v set tags = array['zz-hidden-tag'] from public.marketplace_listings l where l.id = v.listing_id and l.slug = 'rs-bundle';
set local role authenticated;
select pg_temp.as_user('a4');
select is(public.marketplace_tags() @> '[{"tag":"zz-hidden-tag"}]'::jsonb, false, 'tags skip it');
select is(public.marketplace_tags(30, 'test.ai.optimist') @> '[{"tag":"zz-hidden-tag","count":1}]'::jsonb, true, 'tags count it for the allowed agent');
-- Flag off: not even the allowed agent sees it.
reset role;
update public.platform_flags set value = 'false'::jsonb where key = 'marketplace_restricted_listings';
set local role authenticated;
select pg_temp.as_user('a4');
select is(public.marketplace_get('rs-bundle', 'test.ai.optimist'), null, 'restricted flag off: hidden even from the allowed agent');
reset role;
update public.platform_flags set value = 'true'::jsonb where key = 'marketplace_restricted_listings';
set local role authenticated;
-- Staff and the publisher always see it.
select pg_temp.as_user('c1');
select is(pg_temp.slugs(public.marketplace_search()) @> '["rs-bundle"]'::jsonb, true, 'the publisher (an admin) sees it in search');
select pg_temp.as_user('c2');
select is(pg_temp.slugs(public.marketplace_search()) @> '["rs-bundle"]'::jsonb, true, 'a platform viewer sees it in search');
select is(public.marketplace_get('rs-bundle') ->> 'slug', 'rs-bundle', 'and get');
select is(public.marketplace_get('rs-bundle') -> 'auto_install', '["test.ai.optimist"]'::jsonb, 'with auto_install');
select is(public.marketplace_get('rs-bundle') -> 'auto_install_names', '["Optimist Test"]'::jsonb, 'and display names');
select is((select x -> 'auto_install_names' from jsonb_array_elements(public.marketplace_search()) x where x ->> 'slug' = 'rs-bundle'), '["Optimist Test"]'::jsonb, 'search rows carry names for readers');
select is(public.marketplace_get('pub-bundle') -> 'auto_install_names', '["Draft Test"]'::jsonb, 'a plugin without a display row falls back (draft plugin name)');
select pg_temp.as_user('c3');
select is(pg_temp.slugs(public.marketplace_search()) @> '["rs-bundle"]'::jsonb, true, 'a reviewer sees it in search');
select is(public.marketplace_get('rs-bundle') ->> 'status', 'live', 'get gives a reviewer the owner view');
select is(public.marketplace_get('rs-bundle') -> 'auto_install', '["test.ai.optimist"]'::jsonb, 'including auto_install');
select pg_temp.as_user('a1');
select is(pg_temp.slugs(public.marketplace_search()) @> '["rs-bundle"]'::jsonb, false, 'a publisher of other listings does not see it');
select is(public.marketplace_get('pub-bundle') -> 'auto_install', '[]'::jsonb || '["test.ai.draft"]'::jsonb, 'a publisher sees auto_install on their own listing');

-- ---- bundle read for reviewers ----
reset role;
create temp table vid as select v.id from public.marketplace_versions v join public.marketplace_listings l on l.id = v.listing_id where l.slug = 'pub-bundle';
select is(public.marketplace_bundle_access(pg_temp.u('c3'), (select id from vid)), 'bundles/' || pg_temp.u('a1') || '/' || repeat('c', 64) || '.zip', 'a reviewer gets the bundle path');
select is(public.marketplace_bundle_access(pg_temp.u('a4'), (select id from vid)), null, 'a plain user still does not');

-- ---- pruning when a new live version drops a plugin ----
select pg_temp.sb('c1', pg_temp.skill('rs-bundle') || '{"visibility":{"agents":["test.ai.optimist","test.ai.other"]}}', 'd');
select is(pg_temp.cnt('public.marketplace_auto_installs where plugin_id = ''test.ai.optimist'' and listing_id = (select id from public.marketplace_listings where slug = ''rs-bundle'')'), 1,
    'a version that still allows the plugin keeps its auto-install');
select pg_temp.sb('c1', pg_temp.skill('rs-bundle') || '{"visibility":{"agents":["test.ai.other"]}}', 'e');
select is(pg_temp.cnt('public.marketplace_auto_installs where listing_id = (select id from public.marketplace_listings where slug = ''rs-bundle'')'), 0,
    'a version that drops the plugin removes the auto-install in the same transaction');
select is(pg_temp.cnt('public.platform_audit_log where action = ''marketplace.auto_install_dropped'''), 1, 'and audits it');
select pg_temp.sb('c1', pg_temp.skill('open-bundle'), 'f');
select is(pg_temp.cnt('public.marketplace_auto_installs where listing_id = (select id from public.marketplace_listings where slug = ''open-bundle'')'), 1,
    'an unrestricted new version keeps auto-installs');
select * from finish();
rollback;
