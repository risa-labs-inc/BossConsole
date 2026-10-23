-- pgTAP tests for the organisation request queue and discovery
-- (migrations 20260801030000, 20260801010000).
-- Run with: supabase test db
--
-- Two things here are security properties rather than behaviour:
--   - approval is gated on organisation.approve, which only a global admin holds;
--   - the reserved-slug and collision refusals, which are what stop an
--     organisation deriving a role name that already exists globally.

begin;
select plan(36);

-- ---------------------------------------------------------------------------
-- Fixtures
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, email_confirmed_at) values
    ('60000000-0000-0000-0000-000000000001', 'reqasker@pgtap.test',  now()),
    ('60000000-0000-0000-0000-000000000002', 'reqother@pgtap.test',  now()),
    ('60000000-0000-0000-0000-000000000003', 'reqadmin@pgtap.test',  now());

insert into public.user_roles (user_id, role_id)
select '60000000-0000-0000-0000-000000000003', r.id
  from public.roles r where r.name = 'admin'
on conflict do nothing;

select set_config('request.jwt.claims', '{"role":"service_role"}', true);


-- ===========================================================================
-- Slug validation and the collision refusals
-- ===========================================================================
select is(
    (select public.submit_organisation_request(
        'Bad Slug', 'Not A Slug', null, null, null,
        null, '60000000-0000-0000-0000-000000000001') ->> 'error'),
    'Slug must be 2-31 characters, lowercase letters, digits and underscores, starting with a letter',
    'an invalid slug is refused'
);
select isnt(
    (select public.submit_organisation_request(
        'Hyphenated', 'has-hyphen', null, null, null,
        null, '60000000-0000-0000-0000-000000000001') ->> 'error'),
    null,
    'a hyphen is refused -- role names derive from the slug and are validated without hyphens'
);

-- The escalation this exists to prevent: slug `boss` would derive `boss_admin`,
-- which is an existing GLOBAL system role.
select ok(
    public.is_reserved_organisation_slug('boss'),
    'the boss slug is reserved'
);
select isnt(
    (select public.submit_organisation_request(
        'Boss Impostor', 'boss', null, null, null,
        null, '60000000-0000-0000-0000-000000000001') ->> 'error'),
    null,
    'a reserved slug is refused at request time, not discovered at approval'
);
select is(
    public.organisation_role_name('acme', 'admin'),
    'acme_admin',
    'role names derive from the slug'
);


-- ===========================================================================
-- Submitting
-- ===========================================================================
select ok(
    (select public.submit_organisation_request(
        'Acme Inc', 'pgtacme', 'We make things', null, 'because',
        null, '60000000-0000-0000-0000-000000000001') ->> 'success')::boolean,
    'a valid request is accepted'
);
select is(
    (select status from public.organisation_requests where slug='pgtacme'),
    'pending',
    'the request starts pending'
);

-- The partial unique index on slug WHERE status='pending' is what stops two
-- people racing the same slug.
select isnt(
    (select public.submit_organisation_request(
        'Acme Two', 'pgtacme', null, null, null,
        null, '60000000-0000-0000-0000-000000000002') ->> 'error'),
    null,
    'a second pending request for the same slug is refused'
);


-- ===========================================================================
-- Listing is admin-only
-- ===========================================================================
-- A non-reviewer is not refused: they see their OWN requests and nothing else,
-- which is how someone tracks the request they submitted. `is_reviewer` is the
-- flag a client uses to decide whether to render a review queue at all.
select is(
    (select public.list_organisation_requests(
        null, 50, 0, '60000000-0000-0000-0000-000000000002') ->> 'is_reviewer'),
    'false',
    'an ordinary user is not a reviewer'
);
select is(
    (select jsonb_array_length(
        public.list_organisation_requests(
            null, 50, 0, '60000000-0000-0000-0000-000000000002') -> 'data')),
    0,
    'and sees none of someone else''s requests'
);
select is(
    (select jsonb_array_length(
        public.list_organisation_requests(
            null, 50, 0, '60000000-0000-0000-0000-000000000001') -> 'data')),
    1,
    'but does see their own'
);
select ok(
    (select public.list_organisation_requests(
        'pending', 50, 0, '60000000-0000-0000-0000-000000000003') ->> 'success')::boolean,
    'a global admin can list the queue'
);
select is(
    (select jsonb_array_length(
        public.list_organisation_requests(
            'pending', 50, 0, '60000000-0000-0000-0000-000000000003') -> 'data')),
    1,
    'the pending request is in the queue'
);


-- ===========================================================================
-- Withdrawal
-- ===========================================================================
select is(
    (select public.withdraw_organisation_request(
        (select id from public.organisation_requests where slug='pgtacme'),
        '60000000-0000-0000-0000-000000000002') ->> 'error'),
    'Request not found',
    'someone else''s request cannot be withdrawn, and is reported as not found'
);
select ok(
    (select public.withdraw_organisation_request(
        (select id from public.organisation_requests where slug='pgtacme'),
        '60000000-0000-0000-0000-000000000001') ->> 'success')::boolean,
    'the requester can withdraw their own'
);
select is(
    (select status from public.organisation_requests where slug='pgtacme'),
    'withdrawn',
    'the row is marked withdrawn rather than deleted'
);

-- Withdrawing frees the slug, because the unique index only covers pending rows.
select ok(
    (select public.submit_organisation_request(
        'Acme Again', 'pgtacme', null, null, null,
        null, '60000000-0000-0000-0000-000000000002') ->> 'success')::boolean,
    'withdrawing releases the slug for someone else'
);


-- ===========================================================================
-- Approval
-- ===========================================================================
select is(
    (select public.approve_organisation_request(
        (select id from public.organisation_requests where slug='pgtacme' and status='pending'),
        null, '60000000-0000-0000-0000-000000000001') ->> 'error'),
    'Permission denied',
    'an ordinary user cannot approve a request'
);

select ok(
    (select public.approve_organisation_request(
        (select id from public.organisation_requests where slug='pgtacme' and status='pending'),
        'looks fine', '60000000-0000-0000-0000-000000000003') ->> 'success')::boolean,
    'a global admin can approve'
);

select is(
    (select count(*)::int from public.organisations where slug='pgtacme'),
    1,
    'approval creates the organisation'
);
select is(
    (select o.owner_id::text from public.organisations o where o.slug='pgtacme'),
    '60000000-0000-0000-0000-000000000002',
    'the REQUESTER owns it, not the approving admin'
);
select is(
    (select count(*)::int from public.organisation_roles orl
      join public.organisations o on o.id = orl.org_id
     where o.slug='pgtacme' and orl.preset is null),
    2,
    'approval creates the admin and user roles'
);
select ok(
    exists (select 1 from public.roles where name = 'pgtacme_admin')
      and exists (select 1 from public.roles where name = 'pgtacme_user'),
    'the derived role names follow the slug'
);
select ok(
    exists (
        select 1 from public.organisation_members m
        join public.organisations o on o.id = m.org_id
        where o.slug='pgtacme'
          and m.user_id='60000000-0000-0000-0000-000000000002'
          and m.status='active'
          and m.join_source='founder'
    ),
    'the requester is an active founding member'
);
select is(
    (select status from public.organisation_requests
      where slug='pgtacme' and created_org_id is not null),
    'approved',
    'the request is marked approved and linked to the organisation it created'
);

-- Approving twice must not create a second organisation.
select isnt(
    (select public.approve_organisation_request(
        (select id from public.organisation_requests where slug='pgtacme' and status='approved'),
        null, '60000000-0000-0000-0000-000000000003') ->> 'error'),
    null,
    'an already-approved request cannot be approved again'
);
select is(
    (select count(*)::int from public.organisations where slug='pgtacme'),
    1,
    'and no second organisation was created'
);


-- ===========================================================================
-- Rejection
-- ===========================================================================
select public.submit_organisation_request(
    'Rejected Co', 'pgtrej', null, null, null, null, '60000000-0000-0000-0000-000000000001');

select ok(
    (select public.reject_organisation_request(
        (select id from public.organisation_requests where slug='pgtrej'),
        'not this time', '60000000-0000-0000-0000-000000000003') ->> 'success')::boolean,
    'a global admin can reject with notes'
);
select is(
    (select count(*)::int from public.organisations where slug='pgtrej'),
    0,
    'rejection creates no organisation'
);

-- ===========================================================================
-- A refused domain claim must not leave an orphan organisation
--
-- A plain RETURN from PL/pgSQL rolls back nothing, so validating the domain
-- AFTER the organisation, its roles, the hierarchy edges, the grants and the
-- founder membership have been inserted returned success:false while all of it
-- committed. The result was an organisation nothing pointed at, silently owned
-- by the requester, whose slug then blocked every retry.
-- ===========================================================================
insert into public.organisation_domains (org_id, domain, is_primary, verified, verification_token, created_by)
select o.id, 'taken.test', false, false, 'tok', '60000000-0000-0000-0000-000000000003'
  from public.organisations o where o.slug = 'pgtacme';

select is(
    (select public.create_organisation_internal(
        p_slug=>'pgtorphan', p_name=>'Orphan Co',
        p_owner_id=>'60000000-0000-0000-0000-000000000001',
        p_domain=>'taken.test') ->> 'error'),
    'Domain "taken.test" is already claimed by another organisation',
    'a domain already claimed elsewhere is refused'
);
select is(
    (select count(*)::int from public.organisations where slug = 'pgtorphan'),
    0,
    'and NO organisation was left behind -- the refusal happens before the first insert'
);
select is(
    (select count(*)::int from public.roles where name in ('pgtorphan_admin', 'pgtorphan_user')),
    0,
    'nor its derived roles, which would block every retry on the slug'
);

-- Same for a reserved domain, the other late-return path.
select is(
    (select public.create_organisation_internal(
        p_slug=>'pgtorphan2', p_name=>'Orphan Two',
        p_owner_id=>'60000000-0000-0000-0000-000000000001',
        p_domain=>'gmail.com') ->> 'error'),
    '"gmail.com" is a reserved email domain and cannot be claimed by an organisation',
    'a reserved domain is refused'
);
select is(
    (select count(*)::int from public.organisations where slug = 'pgtorphan2'),
    0,
    'and leaves no organisation behind either'
);

-- The happy path still claims the domain, so the reordering did not disable it.
select ok(
    (select public.create_organisation_internal(
        p_slug=>'pgtdomok', p_name=>'Domain OK',
        p_owner_id=>'60000000-0000-0000-0000-000000000001',
        p_domain=>'freshdomain.test') ->> 'success')::boolean,
    'an unclaimed domain still creates the organisation'
);
select is(
    (select d.domain from public.organisation_domains d
       join public.organisations o on o.id = d.org_id
      where o.slug = 'pgtdomok'),
    'freshdomain.test',
    'and the domain row is written'
);

select * from finish();
rollback;
