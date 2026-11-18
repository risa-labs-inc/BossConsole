-- Marketplace trust and defaults: publishing without review (trusted publishers and admins), unreviewed live versions,
-- mark-reviewed, trust administration, per-plugin auto-install lives in marketplace_auto_install_test.sql.
begin;
select plan(71);

-- a1 untrusted publisher, a2 trusted publisher, a3 installed person, a4 agent org owner, a5 outsider, c1 platform admin,
-- c2 platform viewer.
insert into auth.users (id, email, email_confirmed_at) values
    ('c6000000-0000-4000-8000-0000000000a1', 'untrusted@trust.test', now()),
    ('c6000000-0000-4000-8000-0000000000a2', 'Trusted@trust.test', now()),
    ('c6000000-0000-4000-8000-0000000000a3', 'installed@trust.test', now()),
    ('c6000000-0000-4000-8000-0000000000a4', 'owner@trust.test', now()),
    ('c6000000-0000-4000-8000-0000000000a5', 'outsider@trust.test', now()),
    ('c6000000-0000-4000-8000-0000000000c1', 'platform@trust.test', now()),
    ('c6000000-0000-4000-8000-0000000000c2', 'viewer@trust.test', now());
insert into public.user_roles (user_id, role_id)
    select 'c6000000-0000-4000-8000-0000000000c1', id from public.roles where name = 'admin';
insert into public.user_roles (user_id, role_id)
    select 'c6000000-0000-4000-8000-0000000000c2', id from public.roles where name = 'platform_viewer';

select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select public.create_organisation_internal(p_slug => 'trustorg', p_name => 'Trust Org', p_description => null,
    p_owner_id => 'c6000000-0000-4000-8000-0000000000a4', p_domain => null, p_visibility => 'private',
    p_join_policy => 'invite_only');
insert into public.agents (slug, org_id, name, plugin_id, created_by) values
    ('trustagent', (select id from public.organisations where slug = 'trustorg'), 'Trust Agent',
     'ai.rever.boss.plugin.dynamic.agent.trustagent', 'c6000000-0000-4000-8000-0000000000a4'),
    ('trustother', (select id from public.organisations where slug = 'trustorg'), 'Other Agent',
     'ai.rever.boss.plugin.dynamic.agent.trustother', 'c6000000-0000-4000-8000-0000000000a4');
insert into public.agent_installs (user_id, agent_id, instance_id, port, data_dir, state) values
    ('c6000000-0000-4000-8000-0000000000a3', (select id from public.agents where slug = 'trustagent'), 't-a', 9901, 'agents/trustagent', 'running'),
    ('c6000000-0000-4000-8000-0000000000a5', (select id from public.agents where slug = 'trustagent'), 't-b', 9902, 'agents/trustagent', 'removed');
select set_config('request.jwt.claims', '', true);

create function pg_temp.u(p text) returns uuid language sql immutable as $$
    select ('c6000000-0000-4000-8000-0000000000' || p)::uuid $$;
create function pg_temp.as_user(p text) returns void language sql as $$
    select set_config('request.jwt.claim.sub', pg_temp.u(p)::text, true),
           set_config('request.jwt.claims', json_build_object('role', 'authenticated', 'sub', pg_temp.u(p))::text, true);
$$;
create function pg_temp.as_anon() returns void language sql as $$
    select set_config('request.jwt.claim.sub', '', true), set_config('request.jwt.claims', '{"role":"anon"}', true);
$$;
create function pg_temp.skill(md text) returns jsonb language sql immutable as $$ select jsonb_build_object('markdown', md) $$;
create function pg_temp.cnt(q text) returns int language plpgsql security definer as $$
declare n int; begin execute 'select count(*)::int from ' || q into n; return n; end $$;
grant execute on function pg_temp.u(text), pg_temp.as_user(text), pg_temp.as_anon(), pg_temp.skill(text), pg_temp.cnt(text)
    to authenticated, anon, service_role;
create temp table ids (k text primary key, id uuid) on commit drop;
grant all on ids to authenticated, anon, service_role;
insert into ids select 'agent', id from public.agents where slug = 'trustagent';
insert into ids select 'other', id from public.agents where slug = 'trustother';

-- ---- closed tables, constraint ----
select ok(not has_table_privilege('authenticated', 'public.marketplace_trusted_publishers', 'select')
      and not has_table_privilege('authenticated', 'public.marketplace_auto_installs', 'select'), 'new tables closed to clients');
select ok(not has_function_privilege('anon', 'public.marketplace_auto_install_skills(text)', 'EXECUTE')
      and not has_function_privilege('anon', 'public.marketplace_can_skip_review()', 'EXECUTE')
      and not has_function_privilege('anon', 'public.marketplace_submit(public.marketplace_kind,text,text,text,text,text,jsonb,boolean,text,text[])', 'EXECUTE'),
    'not anon-callable');
select ok(not exists (select 1 from pg_proc where proname = 'marketplace_submit' and pronargs = 7), 'the 7-argument submit is gone');

-- ---- can_skip_review ----
select pg_temp.as_anon();
select is(public.marketplace_can_skip_review(), false, 'anon cannot skip');
set local role authenticated;
select pg_temp.as_user('a1');
select is(public.marketplace_can_skip_review(), false, 'untrusted cannot skip');
select pg_temp.as_user('c1');
select is(public.marketplace_can_skip_review(), true, 'admin can skip');

-- ---- trust administration ----
select pg_temp.as_user('a1');
select throws_ok($$ select public.marketplace_trust_set('trusted@trust.test', true) $$, '42501', 'Permission denied', 'trust_set is admin only');
select throws_ok($$ select public.marketplace_trusted_list() $$, '42501', 'Permission denied', 'trusted_list is admin only');
select throws_ok($$ select public.marketplace_unreviewed() $$, '42501', 'Permission denied', 'unreviewed is admin only');
select throws_ok($$ select public.marketplace_mark_reviewed('x') $$, '42501', 'Permission denied', 'mark_reviewed is admin only');
select pg_temp.as_user('c1');
select throws_ok($$ select public.marketplace_trust_set('nobody@trust.test', true) $$, 'P0001', 'user_not_found', 'unknown email');
select throws_ok($$ select public.marketplace_trust_set('trusted@trust.test', true, repeat('n', 201)) $$, 'P0001', 'note_too_long', 'note length');
select is(public.marketplace_trust_set(' TRUSTED@trust.test ', true, 'Risa partner') ->> 'trusted', 'true', 'granted, email matched case-insensitively');
select is(public.marketplace_trusted_list() -> 0 ->> 'email', 'Trusted@trust.test', 'listed');
select is(public.marketplace_trusted_list() -> 0 ->> 'granted_by_email', 'platform@trust.test', 'with who granted');
select is(public.marketplace_trusted_list() -> 0 ->> 'note', 'Risa partner', 'and the note');
select is(public.marketplace_trust_set('trusted@trust.test', true, 'Updated note') ->> 'trusted', 'true', 'granting twice is fine');
select is(jsonb_array_length(public.marketplace_trusted_list()), 1, 'still one row');
select is(pg_temp.cnt('public.platform_audit_log where action = ''marketplace.trust_grant'' and target_user = pg_temp.u(''a2'')'),
    2, 'grants audited with the target');
select pg_temp.as_user('a2');
select is(public.marketplace_can_skip_review(), true, 'trusted can skip');

-- ---- skipping review ----
select pg_temp.as_user('a1');
select throws_ok($$ select public.marketplace_submit('skill', 'sneaky', 'Sneaky', 'Sum', '', 'Me', pg_temp.skill('x'), true) $$,
    '42501', 'review_skip_not_allowed', 'untrusted skip refused');
select is(public.marketplace_get('sneaky'), null, 'and nothing was created');
select is(public.marketplace_submit('skill', 'normal', 'Normal', 'Goes to review', '', 'Me', pg_temp.skill('n1')) ->> 'status',
    'pending_review', 'the default path still waits for review');
select is(public.marketplace_submit('skill', 'normal', 'Normal', 'Still review', '', 'Me', pg_temp.skill('n2'), false) ->> 'version',
    '2', 'explicit false behaves the same');

select pg_temp.as_user('a2');
select is(public.marketplace_submit('skill', 'fast-skill', 'Fast skill', 'Published at once', 'Long', 'Partner',
    pg_temp.skill('fast v1'), true), '{"slug":"fast-skill","version":1,"status":"live","reviewed":false}'::jsonb, 'trusted skip goes live');
select is(public.marketplace_submit('connector', 'fast-conn', 'Fast conn', 'Connector', '', 'Partner',
    '{"url":"https://mcp.example.com/fast","auth":"none"}', true) ->> 'status', 'live', 'connectors can skip too');
select is(public.marketplace_submit('skill', 'trusted-normal', 'Trusted normal', 'Asked for review', '', 'Partner',
    pg_temp.skill('tn')) ->> 'status', 'pending_review', 'a trusted publisher can still choose review');
select throws_ok($$ select public.marketplace_submit('skill', 'bad_slug', 'Bad', 'Sum', '', 'P', pg_temp.skill('x'), true) $$,
    '22023', 'invalid_slug', 'skipping does not skip validation');

select pg_temp.as_anon();
set local role anon;
select is(public.marketplace_search('fast-skill') -> 0 ->> 'slug', 'fast-skill', 'the unreviewed listing is public');
select is((public.marketplace_search('fast-skill') -> 0 ->> 'reviewed')::boolean, false, 'search marks it unreviewed');
select is((public.marketplace_get('fast-skill') ->> 'reviewed')::boolean, false, 'get marks it unreviewed');
select is(public.marketplace_get('fast-skill') -> 'live' ->> 'decision', 'unreviewed', 'live version decision');
select ok(public.marketplace_get('trusted-normal') -> 'reviewed' is null, 'get omits reviewed with no live version');
set local role authenticated;
select pg_temp.as_user('a2');
select is((select (x ->> 'reviewed')::boolean from jsonb_array_elements(public.marketplace_mine()) x where x ->> 'slug' = 'fast-skill'),
    false, 'mine marks it unreviewed');
select ok((select x -> 'reviewed' is null from jsonb_array_elements(public.marketplace_mine()) x where x ->> 'slug' = 'trusted-normal'),
    'mine omits reviewed with no live version');
select is(public.marketplace_record_install('fast-skill') ->> 'reviewed', 'false', 'install result carries reviewed');
select is(pg_temp.cnt('public.platform_audit_log where action = ''marketplace.publish_unreviewed'' and actor = pg_temp.u(''a2'')'),
    2, 'unreviewed publishes audited');

-- A skip replaces a pending version and a rejected listing returns to live.
select is(public.marketplace_submit('skill', 'fast-skill', 'Fast skill', 'Second', '', 'Partner', pg_temp.skill('fast v2')) ->> 'reviewed',
    'false', 'a normal submit reports the live version''s reviewed flag');
select is(public.marketplace_get('fast-skill') -> 'pending' ->> 'version', '2', 'v2 waits in review while v1 is live');
select is(public.marketplace_submit('skill', 'fast-skill', 'Fast skill', 'Third', '', 'Partner', pg_temp.skill('fast v3'), true) ->> 'version',
    '3', 'a skip supersedes the pending version');
select ok(public.marketplace_get('fast-skill') -> 'pending' is null, 'nothing pending');
select is(public.marketplace_get('fast-skill') -> 'live' -> 'content' ->> 'markdown', 'fast v3', 'v3 is live');

-- ---- admin: unreviewed list, mark reviewed, review keeps working ----
select pg_temp.as_user('c1');
select is(jsonb_array_length(public.marketplace_unreviewed()), 2, 'two unreviewed live listings');
select is(public.marketplace_unreviewed() -> 0 ->> 'publisher_email', 'Trusted@trust.test', 'with the publisher');
select is(public.marketplace_unreviewed() -> 0 -> 'live' -> 'content' is not null, true, 'and the live content');
select throws_ok($$ select public.marketplace_mark_reviewed('normal') $$, 'P0001', 'not_unreviewed', 'a listing with no live version');
select throws_ok($$ select public.marketplace_mark_reviewed('nope') $$, 'P0001', 'listing_not_found', 'unknown slug');
-- Pending v2 of a live-unreviewed listing: reviewing it replaces the unreviewed live version.
select pg_temp.as_user('a2');
select public.marketplace_submit('connector', 'fast-conn', 'Fast conn', 'Connector v2', '', 'Partner',
    '{"url":"https://mcp.example.com/fast2","auth":"none"}');
select pg_temp.as_user('c1');
insert into ids select 'fc2', ((x -> 'pending') ->> 'id')::uuid from jsonb_array_elements(public.marketplace_pending()) x where x ->> 'slug' = 'fast-conn';
select is((select (x ->> 'reviewed')::boolean from jsonb_array_elements(public.marketplace_pending()) x where x ->> 'slug' = 'fast-conn'),
    false, 'the pending queue shows the live version is unreviewed');
select is(public.marketplace_review((select id from ids where k = 'fc2'), false, 'Not this version')::text, 'live',
    'rejecting the pending version leaves the unreviewed one live');
select is(public.marketplace_get('fast-conn') -> 'live' ->> 'decision', 'unreviewed', 'still unreviewed');
select throws_ok($$ select public.marketplace_review((select (public.marketplace_get('fast-conn') -> 'live' ->> 'id')::uuid), true) $$,
    'P0001', 'version_not_pending_review', 'review never touches the live unreviewed version');
select is(public.marketplace_mark_reviewed('fast-conn') ->> 'reviewed', 'true', 'marked reviewed');
select is(public.marketplace_get('fast-conn') -> 'live' ->> 'decision', 'approved', 'live version approved');
select is((public.marketplace_get('fast-conn') ->> 'reviewed')::boolean, true, 'listing reviewed');
select throws_ok($$ select public.marketplace_mark_reviewed('fast-conn') $$, 'P0001', 'not_unreviewed', 'cannot mark twice');
select is(jsonb_array_length(public.marketplace_unreviewed()), 1, 'one left');
select is(pg_temp.cnt('public.platform_audit_log where action = ''marketplace.mark_reviewed'''), 1, 'mark audited');

-- ---- revoking trust unpublishes nothing ----
select is(public.marketplace_trust_set('trusted@trust.test', false) ->> 'trusted', 'false', 'revoked');
select is(jsonb_array_length(public.marketplace_trusted_list()), 0, 'no longer listed');
select is(public.marketplace_get('fast-skill') ->> 'status', 'live', 'their listing stays live');
select is(pg_temp.cnt('public.platform_audit_log where action = ''marketplace.trust_revoke'' and target_user = pg_temp.u(''a2'')'),
    1, 'revoke audited');
select pg_temp.as_user('a2');
select throws_ok($$ select public.marketplace_submit('skill', 'fast-skill', 'Fast skill', 'Fourth', '', 'P', pg_temp.skill('v4'), true) $$,
    '42501', 'review_skip_not_allowed', 'a revoked publisher can no longer skip');

-- ---- an admin publishing needs no trust row ----
select pg_temp.as_user('c1');
select is(public.marketplace_submit('skill', 'admin-skill', 'Admin skill', 'By an admin', '', 'Risa', pg_temp.skill('adm'), true) ->> 'status',
    'live', 'admin skip goes live');
select public.marketplace_mark_reviewed('admin-skill');

-- ---- a platform viewer reads every review surface and changes nothing ----
select pg_temp.as_user('c2');
select lives_ok($$ select public.marketplace_pending() $$, 'viewer reads the review queue');
select lives_ok($$ select public.marketplace_unreviewed() $$, 'viewer reads unreviewed versions');
select lives_ok($$ select public.marketplace_trusted_list() $$, 'viewer reads trusted publishers');
select is(public.marketplace_can_skip_review(), false, 'viewer cannot skip review');
select throws_ok($$ select public.marketplace_review(gen_random_uuid(), true, null) $$, '42501', null, 'viewer cannot review');
select throws_ok($$ select public.marketplace_mark_reviewed('fast-skill') $$, '42501', null, 'viewer cannot mark reviewed');
select throws_ok($$ select public.marketplace_trust_set('untrusted@trust.test', true, null) $$, '42501', null, 'viewer cannot trust');
select throws_ok($$ select public.marketplace_suspend('fast-skill', false, null) $$, '42501', null, 'viewer cannot suspend');

select * from finish();
rollback;
