-- Marketplace v2: bundle submissions, four default-off platform flags, listings restricted to named agents, the install RPC
-- Max calls, and the private bundle bucket. Refusals of the new RPCs are {"error": code}; only "not signed in" raises.
begin;
select plan(138);

-- a1 org admin (may publish for the org's agent), a2 plain org member, a3 outsider, a4 trusted publisher, a5 rate-limit user,
-- c1 platform admin.
insert into auth.users (id, email, email_confirmed_at) values
    ('c9000000-0000-4000-8000-0000000000a1', 'orgadmin@mkt2.test', now()),
    ('c9000000-0000-4000-8000-0000000000a2', 'member@mkt2.test', now()),
    ('c9000000-0000-4000-8000-0000000000a3', 'outsider@mkt2.test', now()),
    ('c9000000-0000-4000-8000-0000000000a4', 'trusted@mkt2.test', now()),
    ('c9000000-0000-4000-8000-0000000000a5', 'cap@mkt2.test', now()),
    ('c9000000-0000-4000-8000-0000000000c1', 'admin@mkt2.test', now());
insert into public.user_roles (user_id, role_id)
    select 'c9000000-0000-4000-8000-0000000000c1', id from public.roles where name = 'admin';
insert into public.marketplace_trusted_publishers (user_id) values ('c9000000-0000-4000-8000-0000000000a4');

select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select public.create_organisation_internal(p_slug => 'mkt2co', p_name => 'Mkt2 Co', p_description => null,
    p_owner_id => 'c9000000-0000-4000-8000-0000000000a1', p_domain => null, p_visibility => 'private',
    p_join_policy => 'invite_only');
insert into public.organisation_members (org_id, user_id, status, joined_at, join_source)
    values ((select id from public.organisations where slug = 'mkt2co'), 'c9000000-0000-4000-8000-0000000000a2', 'active', now(), 'admin');
-- An org-only agent (members install it), a public one, and an unpublished one.
insert into public.plugins (author_id, author_name, plugin_id, display_name, type, api_version, published, visibility, org_id)
values ('c9000000-0000-4000-8000-0000000000a5', 'A', 'test.mkt2.org', 'Org Agent', 'panel', '1.0', true, 'org', (select id from public.organisations where slug = 'mkt2co')),
       ('c9000000-0000-4000-8000-0000000000a5', 'A', 'test.mkt2.pub', 'Public Agent', 'panel', '1.0', true, 'public', (select id from public.organisations where slug = 'mkt2co')),
       ('c9000000-0000-4000-8000-0000000000a5', 'A', 'test.mkt2.draft', 'Draft Agent', 'panel', '1.0', false, 'public', (select id from public.organisations where slug = 'mkt2co'));

create function pg_temp.u(p text) returns uuid language sql immutable as $$
    select ('c9000000-0000-4000-8000-0000000000' || p)::uuid $$;
create function pg_temp.as_user(p text) returns void language sql as $$
    select set_config('request.jwt.claim.sub', pg_temp.u(p)::text, true),
           set_config('request.jwt.claims', json_build_object('role', 'authenticated', 'sub', pg_temp.u(p))::text, true);
$$;
create function pg_temp.as_anon() returns void language sql as $$
    select set_config('request.jwt.claim.sub', '', true), set_config('request.jwt.claims', '{"role":"anon"}', true);
$$;
-- Manifests.
create function pg_temp.base(kind text, slug text) returns jsonb language sql immutable as $$
    select jsonb_build_object('schema', 1, 'kind', kind, 'slug', slug, 'name', 'Item ' || slug, 'summary', 'One line.',
                              'publisher_name', 'Pub') $$;
create function pg_temp.skill(slug text) returns jsonb language sql immutable as $$
    select pg_temp.base('skill', slug) || '{"skill":{"entry":"SKILL.md"}}'::jsonb $$;
create function pg_temp.http(slug text) returns jsonb language sql immutable as $$
    select pg_temp.base('connector', slug) || '{"connector":{"type":"http","base_url":"https://api.example.com/v1",
        "auth":{"type":"api_key","header":"X-Api-Key","secret":"API_KEY"},
        "tools":[{"name":"get_item","description":"Get one","method":"GET","path":"/items/{id}",
                  "input_schema":{"type":"object","properties":{"id":{"type":"string"},"q":{"type":"string"}}},"query":["q"]}]}}'::jsonb $$;
create function pg_temp.scripts(slug text) returns jsonb language sql immutable as $$
    select pg_temp.base('connector', slug) || '{"connector":{"type":"scripts","runtime":"python3",
        "tools":[{"name":"run_it","description":"Run","file":"tools/run.py","input_schema":{"type":"object"},"effect":"read","timeout_s":30}],
        "egress":["api.example.com"],"secrets":[{"name":"TOKEN","description":"API token"}]}}'::jsonb $$;
create function pg_temp.mcp(slug text) returns jsonb language sql immutable as $$
    select pg_temp.base('connector', slug) || '{"connector":{"type":"mcp","url":"https://mcp.example.com/mcp","auth":"none"}}'::jsonb $$;
-- Service-role call of the submit RPC with a path/sha derived from the slug-independent constant.
create function pg_temp.sb(who text, m jsonb, skip boolean default false) returns jsonb language sql as $$
    select public.marketplace_submit_bundle(pg_temp.u(who), m, 'bundles/' || repeat('a', 64) || '.zip', repeat('a', 64), 1000, skip) $$;
create function pg_temp.err(j jsonb) returns text language sql immutable as $$ select j ->> 'error' $$;
create function pg_temp.submit_many(who text, n int) returns int language plpgsql as $$
declare ok int := 0; i int;
begin
    for i in 1..n loop
        if not (pg_temp.sb(who, pg_temp.skill('cap-' || i)) ? 'error') then ok := ok + 1; end if;
    end loop;
    return ok;
end $$;
grant execute on function pg_temp.u(text), pg_temp.as_user(text), pg_temp.as_anon(), pg_temp.base(text, text), pg_temp.skill(text),
    pg_temp.http(text), pg_temp.scripts(text), pg_temp.mcp(text), pg_temp.sb(text, jsonb, boolean), pg_temp.err(jsonb),
    pg_temp.submit_many(text, int) to authenticated, anon, service_role;

-- ---------------------------------------------------------------------------------------------------------------------
-- Flags, grants, bucket, backfill.
-- ---------------------------------------------------------------------------------------------------------------------
select is((select count(*)::int from public.platform_flags where key in
    ('marketplace_bundles', 'marketplace_restricted_listings', 'marketplace_http_connectors', 'marketplace_script_connectors')
    and value = 'false'::jsonb), 4, 'the four flags ship off');
select ok(not has_function_privilege('authenticated', 'public.marketplace_submit_bundle(uuid, jsonb, text, text, int, boolean)', 'execute')
      and not has_function_privilege('anon', 'public.marketplace_submit_bundle(uuid, jsonb, text, text, int, boolean)', 'execute')
      and has_function_privilege('service_role', 'public.marketplace_submit_bundle(uuid, jsonb, text, text, int, boolean)', 'execute'),
    'submit_bundle is service-role only');
select ok(not has_function_privilege('authenticated', 'public.marketplace_bundle_access(uuid, uuid)', 'execute')
      and not has_function_privilege('anon', 'public.marketplace_bundle_access(uuid, uuid)', 'execute')
      and has_function_privilege('service_role', 'public.marketplace_bundle_access(uuid, uuid)', 'execute'),
    'bundle_access is service-role only');
select ok(has_function_privilege('anon', 'public.marketplace_flags()', 'execute')
      and has_function_privilege('authenticated', 'public.marketplace_flags()', 'execute'), 'marketplace_flags is readable by everyone');
select ok(has_function_privilege('authenticated', 'public.marketplace_install_v2(text, text)', 'execute')
      and not has_function_privilege('anon', 'public.marketplace_install_v2(text, text)', 'execute'), 'install_v2 is for signed-in users');
select ok(not has_function_privilege('authenticated', 'public.marketplace_check_manifest(jsonb)', 'execute')
      and not has_function_privilege('anon', 'public.marketplace_install_check(uuid, public.marketplace_versions, text)', 'execute'),
    'the validators are not callable by clients');
select is((select public from storage.buckets where id = 'marketplace-bundles'), false, 'the bundle bucket is private');
select is((select count(*)::int from pg_policies where schemaname = 'storage' and tablename = 'objects'
           and (qual ilike '%marketplace-bundles%' or with_check ilike '%marketplace-bundles%')), 0,
    'and no storage policy opens it to clients');

-- Backfill: a legacy connector is MCP, a legacy skill has no type, neither has a manifest.
set local role authenticated;
select pg_temp.as_user('a3');
select public.marketplace_submit('connector', 'legacy-mcp', 'Legacy MCP', 'Sum', '', 'Pub', '{"url":"https://mcp.example.com/mcp","auth":"none"}');
select public.marketplace_submit('skill', 'legacy-skill', 'Legacy Skill', 'Sum', '', 'Pub', '{"markdown":"Do it."}');
reset role;
update public.marketplace_versions v set connector_type = null where listing_id in (select id from public.marketplace_listings where slug like 'legacy-%');
update public.marketplace_versions v set connector_type = 'mcp'
    from public.marketplace_listings l where l.id = v.listing_id and l.kind = 'connector' and v.manifest is null;
select is((select v.connector_type from public.marketplace_versions v join public.marketplace_listings l on l.id = v.listing_id where l.slug = 'legacy-mcp'),
    'mcp', 'backfill: a legacy connector becomes mcp');
select is((select v.connector_type from public.marketplace_versions v join public.marketplace_listings l on l.id = v.listing_id where l.slug = 'legacy-skill'),
    null, 'backfill: a legacy skill stays untyped');
select is((select count(*)::int from public.marketplace_versions where manifest is not null), 0, 'legacy rows have no manifest');

-- ---------------------------------------------------------------------------------------------------------------------
-- Flags off: everything is refused with a code.
-- ---------------------------------------------------------------------------------------------------------------------
set local role anon;
select pg_temp.as_anon();
select is(public.marketplace_flags(), '{"bundles":false,"restricted":false,"http":false,"scripts":false}'::jsonb, 'flags read all false for anon');
reset role;
set local role service_role;
select throws_ok($$ select public.marketplace_submit_bundle(null, pg_temp.skill('x-skill'), 'bundles/' || repeat('a', 64) || '.zip', repeat('a', 64), 10) $$,
    '42501', 'Not signed in', 'submit_bundle needs a user');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.skill('off-skill'))), 'bundles_disabled', 'bundles off: submission refused');

reset role;
update public.platform_flags set value = 'true'::jsonb where key = 'marketplace_bundles';
set local role service_role;

-- ---------------------------------------------------------------------------------------------------------------------
-- Submissions: success, storage of the new columns, versioning.
-- ---------------------------------------------------------------------------------------------------------------------
select is(pg_temp.sb('a1', pg_temp.skill('bundle-skill')), '{"slug":"bundle-skill","version":1,"status":"pending_review"}'::jsonb,
    'a skill bundle waits for review');
reset role;
select is((select v.content from public.marketplace_versions v join public.marketplace_listings l on l.id = v.listing_id where l.slug = 'bundle-skill'),
    '{}'::jsonb, 'the bundle version stores empty legacy content');
select is((select v.manifest ->> 'slug' || v.bundle_sha256 || v.bundle_size::text || v.bundle_path || coalesce(v.connector_type, '-')
           from public.marketplace_versions v join public.marketplace_listings l on l.id = v.listing_id where l.slug = 'bundle-skill'),
    'bundle-skill' || repeat('a', 64) || '1000' || 'bundles/' || repeat('a', 64) || '.zip' || '-', 'manifest, sha, size and path are stored');
set local role service_role;
select is(pg_temp.sb('a1', pg_temp.skill('bundle-skill')) ->> 'version', '2', 'a second submission is version 2');
reset role;
select is((select v.decision from public.marketplace_versions v join public.marketplace_listings l on l.id = v.listing_id
           where l.slug = 'bundle-skill' and v.version = 1), 'superseded', 'and supersedes the pending one');
select is((select count(*)::int from public.platform_audit_log where action = 'marketplace.submit' and actor = pg_temp.u('a1')
           and detail ->> 'bundle' = 'true'), 2, 'each bundle submission is audited');
set local role service_role;

-- Refusals of the manifest and the bundle.
select is(pg_temp.err(public.marketplace_submit_bundle(pg_temp.u('a1'), pg_temp.skill('bad-sha'), 'bundles/x.zip', 'xyz', 10)), 'invalid_bundle', 'bad sha');
select is(pg_temp.err(public.marketplace_submit_bundle(pg_temp.u('a1'), pg_temp.skill('bad-path'), 'bundles/other.zip', repeat('a', 64), 10)), 'invalid_bundle', 'path must be the sha path');
select is(pg_temp.err(public.marketplace_submit_bundle(pg_temp.u('a1'), pg_temp.skill('too-big'), 'bundles/' || repeat('a', 64) || '.zip', repeat('a', 64), 10485761)), 'bundle_too_large', 'over 10 MB');
select is(pg_temp.err(pg_temp.sb('a1', '[]'::jsonb)), 'invalid_manifest', 'manifest must be an object');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.skill('s1') || '{"schema":2}')), 'invalid_manifest', 'schema must be 1');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.skill('s1') || '{"extra":1}')), 'invalid_manifest', 'unknown top-level key');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.skill('s1') - 'skill')), 'invalid_manifest', 'a skill needs its entry');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.skill('s1') || '{"skill":{"entry":"../x"}}')), 'invalid_manifest', 'entry cannot climb');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.skill('s1') || '{"connector":{"type":"mcp"}}')), 'invalid_manifest', 'a skill has no connector');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.skill('Bad_Slug'))), 'invalid_slug', 'slug format');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.skill('publish'))), 'reserved_slug', 'reserved slug');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.skill('s1') || '{"name":"x"}')), 'invalid_name', 'name length');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.skill('s1') || jsonb_build_object('summary', E'a\nb'))), 'invalid_summary', 'summary is one line');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.skill('s1') || jsonb_build_object('description', repeat('x', 4001)))), 'invalid_description', 'description cap');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.skill('s1') || '{"publisher_name":""}')), 'invalid_publisher_name', 'publisher name');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.skill('s1') || '{"category":"nope"}')), 'invalid_category', 'unknown category');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.skill('s1') || '{"tags":["a","b","c","d","e","f","g","h","i"]}')), 'too_many_tags', 'tag cap');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.skill('s1') || '{"tags":["Bad Tag!"]}')), 'invalid_tag', 'tag format');
select is(pg_temp.sb('a1', pg_temp.skill('cat-ok') || '{"category":"development","tags":["Fast Tool","x"]}') ->> 'status', 'pending_review', 'category and tags are accepted');
-- mcp
select is(pg_temp.sb('a1', pg_temp.mcp('mcp-ok')) ->> 'status', 'pending_review', 'an mcp bundle is accepted');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.mcp('mcp-bad') || '{"connector":{"type":"mcp","url":"http://mcp.example.com/mcp","auth":"none"}}')), 'invalid_connector', 'mcp: https only');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.mcp('mcp-bad') || '{"connector":{"type":"mcp","url":"https://mcp.internal/mcp","auth":"none"}}')), 'connector_requires_public_https', 'mcp: public host');
-- http / scripts flags
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.http('http-off'))), 'http_connectors_disabled', 'http off');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.scripts('scripts-off'))), 'script_connectors_disabled', 'scripts off');

reset role;
update public.platform_flags set value = 'true'::jsonb where key in ('marketplace_http_connectors', 'marketplace_script_connectors');
set local role service_role;
select is(pg_temp.sb('a1', pg_temp.http('http-ok')) ->> 'status', 'pending_review', 'http on: accepted');
select is(pg_temp.sb('a1', pg_temp.scripts('scripts-ok')) ->> 'status', 'pending_review', 'scripts on: accepted');
-- http rules
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.http('http-x') || jsonb_build_object('connector', (pg_temp.http('http-x') -> 'connector') || '{"base_url":"http://api.example.com"}'))), 'invalid_manifest', 'http: https only');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.http('http-x') || jsonb_build_object('connector', (pg_temp.http('http-x') -> 'connector') || '{"base_url":"https://api.example.com:8443"}'))), 'invalid_manifest', 'http: port 443 only');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.http('http-x') || jsonb_build_object('connector', (pg_temp.http('http-x') -> 'connector') || '{"base_url":"https://10.0.0.1/v1"}'))), 'connector_requires_public_https', 'http: no IP hosts');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.http('http-x') || jsonb_build_object('connector', (pg_temp.http('http-x') -> 'connector') || '{"base_url":"https://svc.local"}'))), 'connector_requires_public_https', 'http: no .local');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.http('http-x') || jsonb_build_object('connector', jsonb_set(pg_temp.http('http-x') -> 'connector', '{auth,header}', '"Host"')))), 'invalid_manifest', 'http: forbidden header');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.http('http-x') || jsonb_build_object('connector', jsonb_set(pg_temp.http('http-x') -> 'connector', '{auth,secret}', '"lower"')))), 'invalid_manifest', 'http: secret name format');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.http('http-x') || jsonb_build_object('connector', jsonb_set(pg_temp.http('http-x') -> 'connector', '{tools,0,path}', '"/items/{missing}"')))), 'invalid_manifest', 'http: path placeholder must be an input property');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.http('http-x') || jsonb_build_object('connector', jsonb_set(pg_temp.http('http-x') -> 'connector', '{tools,0,path}', '"/a/../b"')))), 'invalid_manifest', 'http: no .. in a path');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.http('http-x') || jsonb_build_object('connector', jsonb_set(pg_temp.http('http-x') -> 'connector', '{tools,0,query}', '["nope"]')))), 'invalid_manifest', 'http: query keys are input properties');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.http('http-x') || jsonb_build_object('connector', jsonb_set(pg_temp.http('http-x') -> 'connector', '{tools,0,method}', '"TRACE"')))), 'invalid_manifest', 'http: method list');
-- scripts rules
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.scripts('sc') || jsonb_build_object('connector', jsonb_set(pg_temp.scripts('sc') -> 'connector', '{egress}', '["127.0.0.1"]')))), 'invalid_manifest', 'scripts: no IP egress');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.scripts('sc') || jsonb_build_object('connector', jsonb_set(pg_temp.scripts('sc') -> 'connector', '{egress}', '["svc.internal"]')))), 'invalid_manifest', 'scripts: no internal egress');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.scripts('sc') || jsonb_build_object('connector', jsonb_set(pg_temp.scripts('sc') -> 'connector', '{tools,0,file}', '"../etc/x.py"')))), 'invalid_manifest', 'scripts: file under tools/');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.scripts('sc') || jsonb_build_object('connector', jsonb_set(pg_temp.scripts('sc') -> 'connector', '{tools,0,timeout_s}', '121')))), 'invalid_manifest', 'scripts: timeout cap');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.scripts('sc') || jsonb_build_object('connector', jsonb_set(pg_temp.scripts('sc') -> 'connector', '{runtime}', '"ruby"')))), 'invalid_manifest', 'scripts: runtime list');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.scripts('sc') || jsonb_build_object('connector', jsonb_set(pg_temp.scripts('sc') -> 'connector', '{secrets}', '[{"name":"A"},{"name":"A"}]')))), 'invalid_manifest', 'scripts: unique secret names');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.scripts('sc') || jsonb_build_object('connector', jsonb_set(pg_temp.scripts('sc') -> 'connector', '{tools}', (select jsonb_agg(jsonb_build_object('name', 't' || i, 'description', 'd', 'file', 'tools/a.py', 'input_schema', '{}'::jsonb)) from generate_series(1, 51) i))))), 'invalid_manifest', 'scripts: at most 50 tools');

-- ---------------------------------------------------------------------------------------------------------------------
-- Review skip rules.
-- ---------------------------------------------------------------------------------------------------------------------
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.skill('skip-no'), true)), 'review_skip_not_allowed', 'an ordinary user cannot skip review');
select is(pg_temp.sb('a4', pg_temp.skill('skip-yes'), true) - 'version', '{"slug":"skip-yes","status":"live","reviewed":false}'::jsonb, 'a trusted publisher skips review: live, unreviewed');
select is(pg_temp.sb('a4', pg_temp.http('skip-http'), true) ->> 'status', 'live', 'a trusted publisher can skip review for http too');
select is(pg_temp.err(pg_temp.sb('a4', pg_temp.scripts('skip-scr'), true)), 'review_required', 'scripts never skip review, even for a trusted publisher');
select is(pg_temp.err(pg_temp.sb('c1', pg_temp.scripts('skip-scr'), true)), 'review_required', 'nor for a platform admin');
select is(pg_temp.sb('a4', pg_temp.scripts('skip-scr')) ->> 'status', 'pending_review', 'a trusted publisher script submission waits');

reset role;
update public.platform_flags set value = 'true'::jsonb where key = 'marketplace_open_publishing';
set local role service_role;
select is(pg_temp.sb('a1', pg_temp.skill('open-skill')) ->> 'status', 'live', 'open publishing: a skill bundle goes live');
select is(pg_temp.sb('a1', pg_temp.scripts('open-scripts')) ->> 'status', 'pending_review', 'open publishing does not apply to scripts');
reset role;
update public.platform_flags set value = 'false'::jsonb where key = 'marketplace_open_publishing';
set local role service_role;

-- ---------------------------------------------------------------------------------------------------------------------
-- Slug ownership, kind, suspension, rate limit.
-- ---------------------------------------------------------------------------------------------------------------------
select is(pg_temp.err(pg_temp.sb('a3', pg_temp.skill('bundle-skill'))), 'slug_taken', 'another publisher cannot take a slug');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.mcp('bundle-skill'))), 'kind_mismatch', 'the kind cannot change');
reset role;
select is((select count(*)::int from public.marketplace_listings where slug = 'bundle-skill'), 1, 'a refusal leaves no extra listing');

set local role service_role;
select is(pg_temp.submit_many('a5', 21), 20, 'the 20-a-day cap applies to an ordinary user');
select is(pg_temp.err(pg_temp.sb('a5', pg_temp.skill('cap-extra'))), 'submission_rate_limited', 'and refuses the next one');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.skill('over-cap-ok'))), null, 'the cap is per user');

reset role;
set local role authenticated;
select pg_temp.as_user('c1');
select public.marketplace_suspend('open-skill', true, 'test');
reset role;
set local role service_role;
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.skill('open-skill'))), 'listing_suspended', 'a suspended listing takes no new version');
reset role;
set local role authenticated;
select pg_temp.as_user('c1');
select is(public.marketplace_install_v2('open-skill', null) ->> 'error', 'not_found', 'a suspended listing is not_found to install');
select public.marketplace_suspend('open-skill', false);

-- ---------------------------------------------------------------------------------------------------------------------
-- Restricted listings: submission.
-- ---------------------------------------------------------------------------------------------------------------------
reset role;
set local role service_role;
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.skill('rs-pending') || '{"visibility":{"agents":["test.mkt2.org"]}}')), 'restricted_disabled', 'restricted off: a listing naming agents is refused');
reset role;
update public.platform_flags set value = 'true'::jsonb where key = 'marketplace_restricted_listings';
set local role service_role;
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.skill('rs-pending') || '{"visibility":{"agents":["nope.agent"]}}')), 'unknown_agent', 'an unknown agent');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.skill('rs-pending') || '{"visibility":{"agents":["test.mkt2.draft"]}}')), 'unknown_agent', 'an unpublished agent is unknown');
select is(pg_temp.err(pg_temp.sb('a2', pg_temp.skill('rs-pending') || '{"visibility":{"agents":["test.mkt2.org"]}}')), 'agent_restriction_not_allowed', 'a plain org member cannot restrict to the org agent');
select is(pg_temp.err(pg_temp.sb('a3', pg_temp.skill('rs-pending') || '{"visibility":{"agents":["test.mkt2.org"]}}')), 'agent_restriction_not_allowed', 'an outsider cannot either');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.skill('rs-pending') || '{"visibility":{"agents":["test.mkt2.org","nope.agent"]}}')), 'unknown_agent', 'every named agent is checked');
select is(pg_temp.err(pg_temp.sb('a1', pg_temp.skill('rs-pending') || jsonb_build_object('visibility', jsonb_build_object('agents', (select jsonb_agg('a' || i) from generate_series(1, 11) i))))), 'invalid_manifest', 'at most 10 agents');
select is(pg_temp.sb('a1', pg_temp.skill('rs-pending') || '{"visibility":{"agents":["test.mkt2.org"]}}') ->> 'status', 'pending_review', 'the agent''s org admin can restrict to it');
select is(pg_temp.sb('c1', pg_temp.skill('rs-skill') || '{"visibility":{"agents":["test.mkt2.org"]}}', true) ->> 'status', 'live', 'a platform admin can restrict (and skip review)');
select is(pg_temp.sb('c1', pg_temp.skill('rs-pub') || '{"visibility":{"agents":["test.mkt2.pub","test.mkt2.pub"]}}', true) ->> 'status', 'live', 'duplicate agent names are fine');
reset role;
select is((select allowed_agents from public.marketplace_versions v join public.marketplace_listings l on l.id = v.listing_id where l.slug = 'rs-pub'),
    array['test.mkt2.pub'], 'and stored once');
set local role service_role;
select is(pg_temp.sb('c1', pg_temp.skill('rs-open') || '{"visibility":{"agents":[]}}', true) ->> 'status', 'live', 'an empty agent list is public');

-- ---------------------------------------------------------------------------------------------------------------------
-- Reads: restricted listings stay visible, content only for agents that may install.
-- ---------------------------------------------------------------------------------------------------------------------
reset role;
set local role anon;
select pg_temp.as_anon();
select is(public.marketplace_get('rs-skill') -> 'restricted_to', '[{"plugin_id":"test.mkt2.org","name":"Org Agent"}]'::jsonb, 'get lists the agents a listing is restricted to');
select ok(not (public.marketplace_get('rs-skill') -> 'live' ? 'content') and not (public.marketplace_get('rs-skill') -> 'live' ? 'manifest'),
    'get hides the content and manifest of a restricted listing from anon');
select is(public.marketplace_get('rs-skill') -> 'installable', 'false'::jsonb, 'and says it is not installable');
select is(public.marketplace_get('rs-skill') -> 'live' ->> 'name', 'Item rs-skill', 'while the listing itself stays visible');
select ok(public.marketplace_get('skip-yes') -> 'live' ? 'manifest' and public.marketplace_get('skip-yes') -> 'live' ? 'content', 'an unrestricted bundle shows its manifest');
select is(public.marketplace_get('skip-yes') -> 'installable', 'true'::jsonb, 'and is installable');
select is(public.marketplace_get('skip-yes') -> 'restricted_to', '[]'::jsonb, 'with no restriction');
select is(public.marketplace_get('skip-http') ->> 'connector_type', 'http', 'get carries the connector type');
select is((select x -> 'installable' from jsonb_array_elements(public.marketplace_search('rs-skill')) x limit 1), 'false'::jsonb, 'search lists a restricted listing, not installable for anon');
select is((select jsonb_array_length(x -> 'restricted_to') from jsonb_array_elements(public.marketplace_search('rs-skill')) x limit 1), 1, 'search carries restricted_to');
select is((select x ->> 'connector_type' from jsonb_array_elements(public.marketplace_search('skip-http')) x limit 1), 'http', 'search carries the connector type');
reset role;

set local role authenticated;
select pg_temp.as_user('a2');
select ok(public.marketplace_get('rs-skill', 'test.mkt2.org') -> 'live' ? 'manifest', 'an agent that may install sees the manifest');
select is(public.marketplace_get('rs-skill', 'test.mkt2.org') -> 'installable', 'true'::jsonb, 'and installable is true');
select ok(not (public.marketplace_get('rs-skill', 'test.mkt2.pub') -> 'live' ? 'manifest'), 'the wrong agent sees no manifest');
select is(public.marketplace_get('rs-skill') -> 'installable', 'false'::jsonb, 'no agent: not installable');
select is((select x -> 'installable' from jsonb_array_elements(public.marketplace_search('rs-skill', null, 50, 0, null, null, 'test.mkt2.org')) x limit 1), 'true'::jsonb, 'search: installable for the allowed agent');
select pg_temp.as_user('a3');
select ok(not (public.marketplace_get('rs-skill', 'test.mkt2.org') -> 'live' ? 'manifest'), 'an outsider cannot read it through the org agent');
select is(public.marketplace_get('rs-skill', 'test.mkt2.org') -> 'installable', 'false'::jsonb, 'nor install it');
select pg_temp.as_user('c1');
select ok(public.marketplace_get('rs-skill') -> 'live' ? 'manifest', 'a platform reader sees the content');

-- ---------------------------------------------------------------------------------------------------------------------
-- Install.
-- ---------------------------------------------------------------------------------------------------------------------
select pg_temp.as_anon();
select throws_ok($$ select public.marketplace_install_v2('rs-skill', 'test.mkt2.org') $$, '42501', 'Not signed in', 'install needs a session');
select pg_temp.as_user('a2');
select is(public.marketplace_install_v2('no-such-listing', null) ->> 'error', 'not_found', 'unknown listing: not_found');
select is(public.marketplace_install_v2('bundle-skill', null) ->> 'error', 'not_found', 'a listing with no live version: not_found');
select is(public.marketplace_install_v2('rs-skill', null) ->> 'error', 'agent_not_allowed', 'restricted, no agent: agent_not_allowed');
select is(public.marketplace_install_v2('rs-skill', 'test.mkt2.pub') ->> 'error', 'agent_not_allowed', 'restricted, wrong agent');
select is(public.marketplace_install_v2('rs-skill', 'test.mkt2.org') - 'manifest' - 'bundle_path',
    jsonb_build_object('slug', 'rs-skill', 'kind', 'skill', 'version', 1, 'name', 'Item rs-skill', 'summary', 'One line.', 'reviewed', false,
                       'content', '{}'::jsonb, 'connector_type', null, 'bundle_sha256', repeat('a', 64), 'bundle_size', 1000,
                       'allowed_agents', '["test.mkt2.org"]'::jsonb), 'restricted, allowed agent and installable plugin: installed');
select is((public.marketplace_install_v2('rs-skill', 'test.mkt2.org') -> 'manifest' ->> 'slug') || (public.marketplace_install_v2('rs-skill', 'test.mkt2.org') ->> 'bundle_path'),
    'rs-skill' || 'bundles/' || repeat('a', 64) || '.zip', 'the install carries the manifest and bundle path');
select pg_temp.as_user('a3');
select is(public.marketplace_install_v2('rs-skill', 'test.mkt2.org') ->> 'error', 'agent_not_allowed', 'restricted, the person cannot install the agent');
select is(public.marketplace_install_v2('rs-pub', 'test.mkt2.pub') ->> 'slug', 'rs-pub', 'an outsider can use a listing restricted to a public agent');
select is(public.marketplace_install_v2('skip-yes', null) ->> 'slug', 'skip-yes', 'an unrestricted listing needs no agent');
reset role;
select is((select install_count::int from public.marketplace_listings where slug = 'rs-skill'), 1, 'an install is counted once per person');
update public.plugins set published = false where plugin_id = 'test.mkt2.pub';
set local role authenticated;
select pg_temp.as_user('a3');
select is(public.marketplace_install_v2('rs-pub', 'test.mkt2.pub') ->> 'error', 'agent_not_allowed', 'the agent is no longer installable: refused');
reset role;
update public.plugins set published = true where plugin_id = 'test.mkt2.pub';

-- A flag turned off refuses at install, fail closed.
update public.platform_flags set value = 'false'::jsonb where key = 'marketplace_restricted_listings';
set local role authenticated;
select pg_temp.as_user('a2');
select is(public.marketplace_install_v2('rs-skill', 'test.mkt2.org') ->> 'error', 'agent_not_allowed', 'restricted flag off: a restricted listing is not installable');
select is(public.marketplace_get('rs-skill', 'test.mkt2.org') -> 'installable', 'false'::jsonb, 'but it stays visible, not installable');
select is(jsonb_array_length(public.marketplace_search('rs-skill', null, 50, 0, null, null, 'test.mkt2.org')), 1, 'and is still searchable with its badge');
reset role;
update public.platform_flags set value = 'true'::jsonb where key = 'marketplace_restricted_listings';
update public.platform_flags set value = 'false'::jsonb where key = 'marketplace_http_connectors';
set local role authenticated;
select pg_temp.as_user('a2');
select is(public.marketplace_install_v2('skip-http', null) ->> 'error', 'http_connectors_disabled', 'http flag off: refused at install');
reset role;
update public.platform_flags set value = 'true'::jsonb where key = 'marketplace_http_connectors';
-- Approve a script connector, then toggle its flag.
create temp table pend as select pending_version_id as id from public.marketplace_listings where slug = 'scripts-ok';
grant select on pend to authenticated;
set local role authenticated;
select pg_temp.as_user('c1');
select public.marketplace_review((select id from pend), true);
select pg_temp.as_user('a2');
select is(public.marketplace_install_v2('scripts-ok', null) ->> 'connector_type', 'scripts', 'scripts on: an approved script connector installs');
reset role;
update public.platform_flags set value = 'false'::jsonb where key = 'marketplace_script_connectors';
set local role authenticated;
select pg_temp.as_user('a2');
select is(public.marketplace_install_v2('scripts-ok', null) ->> 'error', 'script_connectors_disabled', 'scripts flag off: refused at install');
reset role;
update public.platform_flags set value = 'false'::jsonb where key = 'marketplace_bundles';
set local role authenticated;
select pg_temp.as_user('a2');
select is(public.marketplace_install_v2('skip-yes', null) ->> 'error', 'bundles_disabled', 'bundles flag off: refused at install');
reset role;
update public.platform_flags set value = 'true'::jsonb where key = 'marketplace_bundles';
update public.platform_flags set value = 'true'::jsonb where key = 'marketplace_script_connectors';

-- The legacy install RPC does not serve bundle versions.
set local role authenticated;
select pg_temp.as_user('a2');
select throws_ok($$ select public.marketplace_record_install('rs-skill') $$, 'P0001', 'use_marketplace_install_v2', 'the legacy install refuses a bundle version');

-- Version JSON carries the bundle fields; mine and pending pick them up.
select pg_temp.as_user('a4');
select is((select x -> 'live' ->> 'connector_type' from jsonb_array_elements(public.marketplace_mine()) x where x ->> 'slug' = 'skip-http'), 'http', 'mine: connector type');
select is((select x -> 'live' ->> 'bundle_sha256' from jsonb_array_elements(public.marketplace_mine()) x where x ->> 'slug' = 'skip-http'), repeat('a', 64), 'mine: bundle sha');
select pg_temp.as_user('c1');
select ok((select x -> 'pending' ? 'manifest' and x -> 'pending' ->> 'allowed_agents' = '["test.mkt2.org"]'
           from jsonb_array_elements(public.marketplace_pending()) x where x ->> 'slug' = 'rs-pending'), 'pending: manifest and allowed agents for the reviewer');

-- ---------------------------------------------------------------------------------------------------------------------
-- Bundle access: the creator and platform readers only.
-- ---------------------------------------------------------------------------------------------------------------------
reset role;
create temp table vid as select pending_version_id as id from public.marketplace_listings where slug = 'rs-pending';
grant select on vid to service_role;
set local role service_role;
select is(public.marketplace_bundle_access(pg_temp.u('a1'), (select id from vid)), 'bundles/' || repeat('a', 64) || '.zip', 'the creator gets the bundle path');
select is(public.marketplace_bundle_access(pg_temp.u('c1'), (select id from vid)), 'bundles/' || repeat('a', 64) || '.zip', 'a platform admin gets it');
select is(public.marketplace_bundle_access(pg_temp.u('a2'), (select id from vid)), null, 'another user gets nothing');
select is(public.marketplace_bundle_access(null, (select id from vid)), null, 'no user gets nothing');
select is(public.marketplace_bundle_access(pg_temp.u('c1'), gen_random_uuid()), null, 'an unknown version gets nothing');

select * from finish();
rollback;
