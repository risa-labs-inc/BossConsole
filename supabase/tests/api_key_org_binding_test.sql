-- pgTAP tests for binding publishing API keys to an organisation (20260811000000).
-- Run with: supabase test db
--
-- These call `public.bind_api_keys_to_publisher_org()` - the real function the migration runs, not
-- a copy of its body pasted here. That distinction is the reason the migration defines a function
-- at all: a test that re-implements the logic passes whether or not the shipped version works.
--
-- The migration ran once already during `db reset`, against a database with no users, no keys and
-- no boss organisation, so it bound nothing. Everything below builds the state it was written for
-- and calls it again, which is also what proves it is re-runnable.

begin;
select plan(19);

-- ---------------------------------------------------------------------------
-- Fixtures
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, email_confirmed_at) values
    ('c0000000-0000-4000-8000-000000000001', 'sole@keys.test',    now()),
    ('c0000000-0000-4000-8000-000000000002', 'multi@keys.test',   now()),
    ('c0000000-0000-4000-8000-000000000003', 'nomember@keys.test', now()),
    ('c0000000-0000-4000-8000-000000000004', 'nopublish@keys.test', now()),
    ('c0000000-0000-4000-8000-000000000005', 'already@keys.test',  now()),
    -- lockedco needs its OWN owner. Making user 1 own it as well gave that user TWO non-system
    -- organisations, so the sole-organisation case stopped being sole and the function correctly
    -- bound nothing - the fixture contradicted what the test claimed to be checking.
    ('c0000000-0000-4000-8000-000000000006', 'lockowner@keys.test', now());

select set_config('request.jwt.claims', '{"role":"service_role"}', true);

-- The system organisation. Every user joins it, which is exactly why it must not count.
select ok(
    (public.ensure_boss_organisation() ->> 'success')::boolean,
    'the boss organisation exists, so the boss-bound arm is reachable'
);

-- `members` publish policy: membership alone is enough, so the sole-org user qualifies.
select ok(
    (public.create_organisation_internal(
        p_slug => 'acmeone', p_name => 'Acme One', p_description => null,
        p_owner_id => 'c0000000-0000-4000-8000-000000000001',
        p_domain => null, p_visibility => 'private',
        p_join_policy => 'invite_only') ->> 'success')::boolean,
    'the sole-organisation user has an organisation'
);
update public.organisations set publish_policy = 'members' where slug = 'acmeone';

-- Two organisations for the multi user: nothing to derive.
select ok(
    (public.create_organisation_internal(
        p_slug => 'twoa', p_name => 'Two A', p_description => null,
        p_owner_id => 'c0000000-0000-4000-8000-000000000002',
        p_domain => null, p_visibility => 'private',
        p_join_policy => 'invite_only') ->> 'success')::boolean,
    'the multi-organisation user has a first organisation'
);
select ok(
    (public.create_organisation_internal(
        p_slug => 'twob', p_name => 'Two B', p_description => null,
        p_owner_id => 'c0000000-0000-4000-8000-000000000002',
        p_domain => null, p_visibility => 'private',
        p_join_policy => 'invite_only') ->> 'success')::boolean,
    'and a second'
);
update public.organisations set publish_policy = 'members' where slug in ('twoa', 'twob');

-- One organisation, but owner_only and this user is NOT the owner: a member who may not publish.
select ok(
    (public.create_organisation_internal(
        p_slug => 'lockedco', p_name => 'Locked Co', p_description => null,
        p_owner_id => 'c0000000-0000-4000-8000-000000000006',
        p_domain => null, p_visibility => 'private',
        p_join_policy => 'invite_only') ->> 'success')::boolean,
    'the no-publish user''s organisation exists'
);
update public.organisations set publish_policy = 'owner_only' where slug = 'lockedco';
insert into public.organisation_members (org_id, user_id, status, joined_at, join_source)
values (
    (select id from public.organisations where slug = 'lockedco'),
    'c0000000-0000-4000-8000-000000000004', 'active', now(), 'admin');

-- And a member of acmeone whose key is already bound somewhere deliberate.
insert into public.organisation_members (org_id, user_id, status, joined_at, join_source)
values (
    (select id from public.organisations where slug = 'acmeone'),
    'c0000000-0000-4000-8000-000000000005', 'active', now(), 'admin');

create temporary table t_org as
select (select id from public.organisations where slug = 'acmeone') as acmeone,
       (select id from public.organisations where slug = 'twoa')    as twoa,
       (select id from public.organisations where slug = 'lockedco') as lockedco,
       (select id from public.organisations where slug = 'boss')     as boss;

-- The keys. Deliberately mixed: NULL-bound and boss-bound must both be candidates,
-- because the backfill produced the second and key creation produced the first.
-- key_hash/key_prefix fixtures carry the storage-form values 20260923172000
-- constrains (64-hex digest; 16-char mask), so this suite keeps passing with the
-- constraints in force; nothing here asserts on the specific values.
insert into public.plugin_api_keys (user_id, name, key_prefix, key_hash, scopes, org_id) values
    ('c0000000-0000-4000-8000-000000000001', 'sole-null',  'boss_pk_orgbind1', '9fd1eed316827de40b20eb908422bcccc2e84dbe7698f98d192c9febbcbab217', array['publish'], null),
    ('c0000000-0000-4000-8000-000000000001', 'sole-boss',  'boss_pk_orgbind2', 'cf39c07223209020190d01f6c2e14b4e1c26285fa78cf6d65316d8ec279d2836', array['publish'],
        (select boss from t_org)),
    ('c0000000-0000-4000-8000-000000000002', 'multi',      'boss_pk_orgbind3', '6c7678f24e8c6577a526f2d2a6dab110e21abb41feb0c68aec5362a91ee7a224', array['publish'], null),
    ('c0000000-0000-4000-8000-000000000003', 'nomember',   'boss_pk_orgbind4', '68c8dd45c96866c41db890faa92689f0447418739bab6c63233a4ef784fe8930', array['publish'], null),
    ('c0000000-0000-4000-8000-000000000004', 'nopublish',  'boss_pk_orgbind5', 'ba48c1cd909a87fa31979a62586f16c56aab13a129189ff4cf4c34f1499467d0', array['publish'], null),
    ('c0000000-0000-4000-8000-000000000005', 'already',    'boss_pk_orgbind6', '17f8aaead8011022ab65eded84377a05fc56885c1c3c3cb159cf7f64fccd5d43', array['publish'],
        (select twoa from t_org));


-- ===========================================================================
-- The binding
-- ===========================================================================
create temporary table t_run as select public.bind_api_keys_to_publisher_org() as result;

select ok((select (result ->> 'success')::boolean from t_run), 'the function reports success');

select is(
    (select (result ->> 'bound')::int from t_run),
    2,
    'exactly the two keys of the sole-organisation user are bound'
);

select is(
    (select org_id from public.plugin_api_keys where name = 'sole-null'),
    (select acmeone from t_org),
    'a NULL-bound key is bound - this is what key creation produced'
);

select is(
    (select org_id from public.plugin_api_keys where name = 'sole-boss'),
    (select acmeone from t_org),
    'a boss-bound key is bound too - this is what the 20260803000000 backfill produced'
);

-- ===========================================================================
-- Who is left alone, and why
-- ===========================================================================
select is(
    (select org_id from public.plugin_api_keys where name = 'multi'),
    null,
    'two candidate organisations means nothing is derived, rather than one being guessed'
);

select is(
    (select org_id from public.plugin_api_keys where name = 'nomember'),
    null,
    'an owner in no non-system organisation is left alone'
);

select is(
    (select org_id from public.plugin_api_keys where name = 'nopublish'),
    null,
    'membership without publishing rights is not enough - owner_only would refuse every publish'
);

select is(
    (select org_id from public.plugin_api_keys where name = 'already'),
    (select twoa from t_org),
    'a key already bound deliberately is never re-pointed, even to a different valid answer'
);

select is(
    (select (result ->> 'remaining')::int from t_run),
    3,
    'and it reports how many still resolve to boss, so the deploy output is checkable'
);


-- ===========================================================================
-- Re-running
-- ===========================================================================
select is(
    (public.bind_api_keys_to_publisher_org() ->> 'bound')::int,
    0,
    're-running binds nothing: every row it moved no longer points at boss'
);

select is(
    (select org_id from public.plugin_api_keys where name = 'sole-null'),
    (select acmeone from t_org),
    'and does not disturb what it bound the first time'
);


-- ===========================================================================
-- It is not callable by a client
-- ===========================================================================
-- It rewrites which organisation other people's keys publish for. An authenticated
-- caller reaching it would be able to re-point every key whose owner happens to
-- have one organisation.
select ok(
    not has_function_privilege('authenticated', 'public.bind_api_keys_to_publisher_org()', 'EXECUTE'),
    'authenticated cannot execute it'
);

select ok(
    not has_function_privilege('anon', 'public.bind_api_keys_to_publisher_org()', 'EXECUTE'),
    'anon cannot execute it'
);

select ok(
    has_function_privilege('service_role', 'public.bind_api_keys_to_publisher_org()', 'EXECUTE'),
    'service_role can, which is what the migration and an operator use'
);

select * from finish();
rollback;
