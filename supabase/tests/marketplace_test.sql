-- Marketplace: validation shared with agent drafts, review before listing, re-review of every version, publisher-only
-- edits, admin-only moderation, public reads of live listings only, install counts.
begin;
select plan(44);

-- p1 publisher, p2 another person, c1 platform admin.
insert into auth.users (id, email, email_confirmed_at) values
    ('c5000000-0000-4000-8000-0000000000a1', 'publisher@market.test', now()),
    ('c5000000-0000-4000-8000-0000000000a2', 'other@market.test', now()),
    ('c5000000-0000-4000-8000-0000000000c1', 'platform@market.test', now());
insert into public.user_roles (user_id, role_id)
    select 'c5000000-0000-4000-8000-0000000000c1', id from public.roles where name = 'admin';

create function pg_temp.u(p text) returns uuid language sql immutable as $$
    select ('c5000000-0000-4000-8000-0000000000' || p)::uuid $$;
create function pg_temp.as_user(p text) returns void language sql as $$
    select set_config('request.jwt.claim.sub', pg_temp.u(p)::text, true),
           set_config('request.jwt.claims', json_build_object('role', 'authenticated', 'sub', pg_temp.u(p))::text, true);
$$;
create function pg_temp.as_anon() returns void language sql as $$
    select set_config('request.jwt.claim.sub', '', true),
           set_config('request.jwt.claims', '{"role":"anon"}', true);
$$;
create function pg_temp.skill(md text) returns jsonb language sql immutable as $$ select jsonb_build_object('markdown', md) $$;
grant execute on function pg_temp.u(text), pg_temp.as_user(text), pg_temp.as_anon(), pg_temp.skill(text)
    to authenticated, anon, service_role;
create temp table vids (k text primary key, id uuid) on commit drop;
grant all on vids to authenticated, anon, service_role;

-- Tables are closed to every client role.
select ok(not has_table_privilege('authenticated', 'public.marketplace_listings', 'select'), 'listings closed to authenticated');
select ok(not has_table_privilege('anon', 'public.marketplace_versions', 'select'), 'versions closed to anon');

-- Submitting needs a session.
select pg_temp.as_anon();
select throws_ok($$ select public.marketplace_submit('skill', 'prior-auth', 'Prior auth', 'Fill PA forms', '', 'Risa', pg_temp.skill('# x')) $$,
    '42501', null, 'anon cannot submit');

set local role authenticated;
select pg_temp.as_user('a1');

-- Validation: the agent draft rules, plus the listing's fields.
select throws_ok($$ select public.marketplace_submit('skill', 'Bad_Slug', 'Name', 'Sum', '', 'Risa', pg_temp.skill('x')) $$,
    '22023', 'invalid_slug', 'slug format');
select throws_ok($$ select public.marketplace_submit('skill', 'evolve', 'Evolve', 'Sum', '', 'Risa', pg_temp.skill('x')) $$,
    '22023', 'reserved_skill', 'built-in skill names are reserved');
select throws_ok($$ select public.marketplace_submit('skill', 'empty', 'Empty', 'Sum', '', 'Risa', pg_temp.skill('   ')) $$,
    '22023', 'invalid_content', 'empty skill');
select throws_ok($$ select public.marketplace_submit('skill', 'two-line', 'Two', E'a\nb', '', 'Risa', pg_temp.skill('x')) $$,
    '22023', 'invalid_summary', 'summary is one line');
select throws_ok($$ select public.marketplace_submit('connector', 'local-mcp', 'Local', 'Sum', '', 'Risa',
    '{"url":"https://mcp.internal/mcp","auth":"none"}') $$, '22023', 'connector_requires_public_https', 'no internal hosts');
select throws_ok($$ select public.marketplace_submit('connector', 'plain-http', 'Plain', 'Sum', '', 'Risa',
    '{"url":"http://mcp.example.com/mcp","auth":"none"}') $$, '22023', 'invalid_connector', 'https only');
select throws_ok($$ select public.marketplace_submit('connector', 'gh', 'GitHub', 'Sum', '', 'Risa',
    '{"url":"https://mcp.example.com/mcp","auth":"none"}') $$, '22023', 'reserved_connector_name', 'built-in connector names');
select throws_ok($$ select public.marketplace_submit('connector', 'extra', 'Extra', 'Sum', '', 'Risa',
    '{"url":"https://mcp.example.com/mcp","auth":"none","command":"rm"}') $$, '22023', 'invalid_content', 'no extra connector keys');

-- A first submission waits for review and is not public.
select is(public.marketplace_submit('skill', 'prior-auth', 'Prior auth', 'Fill prior auth forms', 'Long text', 'Risa Labs',
    pg_temp.skill(E'## Steps\n1. Read the chart')) ->> 'status', 'pending_review', 'skill submitted to review');
select is(public.marketplace_submit('connector', 'pubmed', 'PubMed', 'Search PubMed', '', 'Risa Labs',
    '{"url":"https://mcp.example.com/pubmed","auth":"none"}') ->> 'version', '1', 'connector submitted');
select is(public.marketplace_get('prior-auth') ->> 'status', 'pending_review', 'publisher sees status');
select is(jsonb_array_length(public.marketplace_search()), 0, 'nothing live yet');

select pg_temp.as_user('a2');
select is(public.marketplace_get('prior-auth'), null, 'others cannot see an unreviewed listing');
select throws_ok($$ select public.marketplace_submit('skill', 'prior-auth', 'Mine now', 'Sum', '', 'Me', pg_temp.skill('x')) $$,
    'P0001', 'slug_taken', 'a slug belongs to its publisher');
select throws_ok($$ select public.marketplace_pending() $$, '42501', null, 'queue is admin only');
select throws_ok($$ select public.marketplace_review(gen_random_uuid(), true) $$, '42501', null, 'review is admin only');

-- Admin review.
select pg_temp.as_user('c1');
insert into vids select 'pa1', ((x->'pending')->>'id')::uuid from jsonb_array_elements(public.marketplace_pending()) x
    where x->>'slug' = 'prior-auth';
insert into vids select 'pm1', ((x->'pending')->>'id')::uuid from jsonb_array_elements(public.marketplace_pending()) x
    where x->>'slug' = 'pubmed';
select is(jsonb_array_length(public.marketplace_pending()), 2, 'both in the queue');
select throws_ok($$ select public.marketplace_review((select id from vids where k = 'pm1'), false) $$,
    'P0001', 'reason_required', 'rejecting needs a reason');
select is(public.marketplace_review((select id from vids where k = 'pa1'), true)::text, 'live', 'approved');
select is(public.marketplace_review((select id from vids where k = 'pm1'), false, 'Endpoint is not reachable')::text,
    'rejected', 'rejected');
select throws_ok($$ select public.marketplace_review((select id from vids where k = 'pa1'), true) $$,
    'P0001', 'version_not_pending_review', 'a decided version cannot be decided again');
select is((select count(*) from public.platform_audit_log where action in ('marketplace.approve', 'marketplace.reject')
    and detail->>'slug' in ('prior-auth', 'pubmed'))::int, 2, 'decisions audited');

-- Public reads: live only, approved content only.
select pg_temp.as_anon();
set local role anon;
select is(jsonb_array_length(public.marketplace_search()), 1, 'one live listing');
select is(public.marketplace_search('prior') -> 0 ->> 'slug', 'prior-auth', 'search by name');
select is(jsonb_array_length(public.marketplace_search('pubmed')), 0, 'rejected listing is not public');
select is(jsonb_array_length(public.marketplace_search(null, 'connector')), 0, 'kind filter');
select is(public.marketplace_get('prior-auth') -> 'live' -> 'content' ->> 'markdown', E'## Steps\n1. Read the chart', 'content public');
select ok(public.marketplace_get('prior-auth') -> 'status' is null, 'no status for the public');
select is(jsonb_array_length(public.marketplace_search('%')), 0, 'wildcards are literal');

-- The publisher sees the reason and resubmits; a new version of a live listing keeps the old one serving.
set local role authenticated;
select pg_temp.as_user('a1');
select is((select x->>'review_note' from jsonb_array_elements(public.marketplace_mine()) x where x->>'slug' = 'pubmed'),
    'Endpoint is not reachable', 'publisher sees the reason');
select is(public.marketplace_submit('skill', 'prior-auth', 'Prior auth', 'Fill prior auth forms', '', 'Risa Labs',
    pg_temp.skill('v2 text')) ->> 'version', '2', 'new version');
select is(public.marketplace_get('prior-auth') -> 'pending' ->> 'version', '2', 'v2 pending');
select pg_temp.as_anon();
set local role anon;
select is(public.marketplace_get('prior-auth') -> 'live' -> 'content' ->> 'markdown', E'## Steps\n1. Read the chart', 'v1 still served');
select ok(public.marketplace_get('prior-auth') -> 'pending' is null, 'pending hidden from the public');

-- Installs count once per person.
set local role authenticated;
select pg_temp.as_user('a2');
select is(public.marketplace_record_install('prior-auth') -> 'version' ->> 'version', '1', 'install gets the live version');
select public.marketplace_record_install('prior-auth');
select is((public.marketplace_search() -> 0 ->> 'install_count')::int, 1, 'counted once');
select throws_ok($$ select public.marketplace_record_install('pubmed') $$, 'P0001', 'listing_not_found', 'cannot install unlisted');

-- Suspension hides a listing and blocks new versions; withdraw is the publisher's.
select throws_ok($$ select public.marketplace_withdraw('prior-auth') $$, 'P0001', 'listing_not_found', 'only the publisher withdraws');
select pg_temp.as_user('c1');
select is(public.marketplace_suspend('prior-auth', true, 'Reported')::text, 'suspended', 'suspended');
select pg_temp.as_user('a1');
select throws_ok($$ select public.marketplace_submit('skill', 'prior-auth', 'Prior auth', 'Sum', '', 'Risa', pg_temp.skill('v3')) $$,
    'P0001', 'listing_suspended', 'no new versions while suspended');
select is(jsonb_array_length(public.marketplace_search()), 0, 'suspended is not public');

select * from finish();
rollback;
