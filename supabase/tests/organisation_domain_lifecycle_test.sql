-- pgTAP tests for the unverified-domain-claim lifecycle (20260923125000,
-- BossConsole#966).
--
-- Before that migration, organisation_domains.domain was globally UNIQUE and
-- add_organisation_domain refused only reserved and already-claimed names, so
-- an unverified claim was the one credential-shaped row in this schema with no
-- lifetime and no per-organisation bound: any org admin could permanently
-- squat any unclaimed domain and the real owner was blocked until someone
-- deleted the row by hand.
--
-- WHAT THESE ASSERTIONS COVER:
--
--   The cap: 5 concurrent unverified claims per organisation, the 6th refused
--   with the cap error (mirroring create_organisation_role's max_custom_roles
--   cap), verified rows NOT counting against it, and the bound being per
--   organisation rather than global.
--
--   The TTL: an unverified claim older than 7 days is removed by the very add
--   it used to block, so the domain becomes claimable again by its real owner,
--   while a VERIFIED row of any age survives every cleanup path untouched, and
--   an unverified row inside the TTL is left alone.
--
-- FIXTURE DISCIPLINE, both lessons earned the hard way in this suite:
--
--   * Every directly-seeded organisation_domains row carries all its required
--     columns explicitly (org_id, domain, is_primary, verified,
--     verification_token, created_by, created_at) -- nothing meaningful is
--     left to a default.
--
--   * Aged rows are seeded INSERT-then-UPDATE, never by INSERTing an old
--     created_at directly: the AFTER INSERT sweep trigger
--     (trigger_cleanup_expired_unverified_organisation_domains) deletes
--     already-expired rows 10% of the time, so an INSERT carrying created_at
--     8 days in the past would be deleted before it was ever observable and
--     the "still parked" assertions below would flake. An UPDATE fires no
--     INSERT trigger, so every assertion here is deterministic. The trigger's
--     own 10% firing can only ever delete rows these tests are about to prove
--     are deleted anyway.

begin;
select plan(16);

-- ---------------------------------------------------------------------------
-- Fixtures
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, email_confirmed_at) values
    ('dc000000-0000-0000-0000-000000000001', 'founder@dcapone.test', now()),
    ('dc000000-0000-0000-0000-000000000002', 'founder@dcaptwo.test', now());

select set_config('request.jwt.claims', '{"role":"service_role"}', true);

select public.create_organisation_internal(
    p_slug => 'dcapone', p_name => 'Domain Cap One', p_description => null,
    p_owner_id => 'dc000000-0000-0000-0000-000000000001',
    p_domain => null, p_visibility => 'private',
    p_join_policy => 'invite_only');
select public.create_organisation_internal(
    p_slug => 'dcaptwo', p_name => 'Domain Cap Two', p_description => null,
    p_owner_id => 'dc000000-0000-0000-0000-000000000002',
    p_domain => null, p_visibility => 'private',
    p_join_policy => 'invite_only');

create temporary table t_ids as
select (select id from public.organisations where slug = 'dcapone') as org_a,
       (select id from public.organisations where slug = 'dcaptwo') as org_b;

-- ===========================================================================
-- The cap: five unverified claims, the sixth refused
-- ===========================================================================

select public.add_organisation_domain((select org_a from t_ids), 'one.test',   false, 'dc000000-0000-0000-0000-000000000001');
select public.add_organisation_domain((select org_a from t_ids), 'two.test',   false, 'dc000000-0000-0000-0000-000000000001');
select public.add_organisation_domain((select org_a from t_ids), 'three.test', false, 'dc000000-0000-0000-0000-000000000001');
select public.add_organisation_domain((select org_a from t_ids), 'four.test',  false, 'dc000000-0000-0000-0000-000000000001');
select public.add_organisation_domain((select org_a from t_ids), 'five.test', false, 'dc000000-0000-0000-0000-000000000001');

select is(
    (select count(*)::int from public.organisation_domains d
      where d.org_id = (select org_a from t_ids) and d.verified = false),
    5,
    'five unverified claims are allowed'
);

select is(
    (select public.add_organisation_domain(
        (select org_a from t_ids), 'six.test', false,
        'dc000000-0000-0000-0000-000000000001') ->> 'error'),
    'This organisation has reached its limit of 5 unverified domain claims',
    'the sixth unverified claim is refused with the cap error'
);

select is(
    (select count(*)::int from public.organisation_domains d
      where d.org_id = (select org_a from t_ids)),
    5,
    'and the refused claim inserted nothing'
);

-- The cap is per organisation, not a global budget.
select ok(
    (select public.add_organisation_domain(
        (select org_b from t_ids), 'other.test', false,
        'dc000000-0000-0000-0000-000000000002') ->> 'success')::boolean,
    'another organisation claims freely while the first is at its cap'
);

-- ===========================================================================
-- The cap counts UNVERIFIED rows only
-- ===========================================================================

select public.mark_organisation_domain_verified(
    (select d.id from public.organisation_domains d
      where d.domain = 'one.test' and d.org_id = (select org_a from t_ids)),
    'dc000000-0000-0000-0000-000000000001');

select is(
    (select d.verified::text from public.organisation_domains d
      where d.domain = 'one.test' and d.org_id = (select org_a from t_ids)),
    'true',
    'the fixture claim was really verified (fixture guard)'
);

-- The slot freed by verification is claimable: 4 unverified + 1 verified.
select ok(
    (select public.add_organisation_domain(
        (select org_a from t_ids), 'six.test', false,
        'dc000000-0000-0000-0000-000000000001') ->> 'success')::boolean,
    'a verified claim no longer counts toward the cap'
);

select is(
    (select public.add_organisation_domain(
        (select org_a from t_ids), 'seven.test', false,
        'dc000000-0000-0000-0000-000000000001') ->> 'error'),
    'This organisation has reached its limit of 5 unverified domain claims',
    'the cap binds again once the freed slot is refilled'
);

-- ===========================================================================
-- The TTL: an expired unverified claim frees its domain
-- ===========================================================================

-- A claim parked by organisation A on somebody else's name, 8 days old and
-- never verified -- a row exactly as the pre-migration database would have
-- stored it. Seeded INSERT-fresh-then-UPDATE-old (see the header): the UPDATE
-- fires no INSERT trigger, so the row is observably there.
insert into public.organisation_domains
    (org_id, domain, is_primary, verified, verification_token, created_by, created_at)
values
    ((select org_a from t_ids), 'squat.test', false, false,
     'squatfixturetoken0000000000001',
     'dc000000-0000-0000-0000-000000000001', now());

update public.organisation_domains
   set created_at = now() - interval '8 days'
 where domain = 'squat.test' and org_id = (select org_a from t_ids);

select is(
    (select count(*)::int from public.organisation_domains d
      where d.domain = 'squat.test'),
    1,
    'the expired claim is still parked on the name'
);

-- THE fix assertion: the real owner's organisation claims it and succeeds,
-- because add_organisation_domain removes the expired row inline before the
-- claimed check can see it.
select ok(
    (select public.add_organisation_domain(
        (select org_b from t_ids), 'squat.test', false,
        'dc000000-0000-0000-0000-000000000002') ->> 'success')::boolean,
    'an expired unverified claim no longer blocks the real owner'
);

select is(
    (select d.org_id::text from public.organisation_domains d
      where d.domain = 'squat.test'),
    (select org_b from t_ids)::text,
    'the stale claim row was removed, not bypassed: the row is the new owner''s'
);

-- ===========================================================================
-- The cleanup path: exactly the expired unverified rows, nothing else
-- ===========================================================================

-- Three more fixtures: an unverified claim inside the TTL (3 days), an
-- unverified claim past it (8 days), and a VERIFIED claim of any age (9 days,
-- verified 9 days ago) -- all seeded INSERT-fresh-then-UPDATE-old.
insert into public.organisation_domains
    (org_id, domain, is_primary, verified, verification_token, created_by, created_at)
values
    ((select org_a from t_ids), 'fresh.test', false, false,
     'freshfixturetoken0000000000001',
     'dc000000-0000-0000-0000-000000000001', now()),
    ((select org_a from t_ids), 'dead.test', false, false,
     'deadfixturetoken00000000000001',
     'dc000000-0000-0000-0000-000000000001', now()),
    ((select org_a from t_ids), 'veteran.test', false, false,
     'veteranfixturetoken00000000001',
     'dc000000-0000-0000-0000-000000000001', now());

update public.organisation_domains
   set created_at = now() - interval '3 days'
 where domain = 'fresh.test' and org_id = (select org_a from t_ids);

update public.organisation_domains
   set created_at = now() - interval '8 days'
 where domain = 'dead.test' and org_id = (select org_a from t_ids);

update public.organisation_domains
   set created_at  = now() - interval '9 days',
       verified    = true,
       verified_at = now() - interval '9 days'
 where domain = 'veteran.test' and org_id = (select org_a from t_ids);

-- dead.test is the only expired unverified row in the fixtures, so the count
-- is exact, not "at least one".
select is(
    public.cleanup_expired_unverified_organisation_domains(),
    1,
    'the cleanup path removes exactly the expired unverified row'
);

select is(
    (select count(*)::int from public.organisation_domains d
      where d.domain = 'dead.test'),
    0,
    'the row it removed is the expired one'
);

select is(
    (select count(*)::int from public.organisation_domains d
      where d.domain = 'fresh.test'),
    1,
    'an unverified claim inside the 7-day TTL survives cleanup'
);

select is(
    (select public.add_organisation_domain(
        (select org_b from t_ids), 'veteran.test', false,
        'dc000000-0000-0000-0000-000000000002') ->> 'error'),
    'Domain "veteran.test" is already claimed',
    'a VERIFIED domain of any age is still refused to another organisation'
);

select is(
    (select d.org_id::text from public.organisation_domains d
      where d.domain = 'veteran.test' and d.verified),
    (select org_a from t_ids)::text,
    'and the verified row itself survived every cleanup path untouched'
);

-- ===========================================================================
-- The sweep trigger is armed
-- ===========================================================================

select ok(
    exists(
        select 1
          from pg_trigger t
          join pg_class c on c.oid = t.tgrelid
         where c.relname = 'organisation_domains'
           and t.tgname = 'trigger_cleanup_expired_unverified_organisation_domains'
           and not t.tgisinternal
    ),
    'the AFTER INSERT sweep is armed, mirroring the challenge-cleanup pattern'
);

select * from finish();
rollback;
