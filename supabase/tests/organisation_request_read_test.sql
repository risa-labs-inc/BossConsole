-- pgTAP tests for organisation.request_read (migration 20260816000000).
--
-- The property under test is a separation, not a feature: reading the
-- organisation-creation queue used to ride on organisation.approve, which is granted
-- to admin AND boss_admin, so every boss_admin could list every pending request with
-- its requester email and free-text justification. Splitting the read out is only
-- worth anything if BOTH halves hold:
--   - a holder of organisation.request_read still sees the whole queue, and
--   - a holder of organisation.approve ALONE no longer does,
-- so each subject below is a different user, and no subject is a global admin except
-- the one arm that is specifically about the admin short-circuit. A fixture that gave
-- a subject a second reason to pass would make this file agree with the bug.

begin;
select plan(24);

-- ---------------------------------------------------------------------------
-- Fixtures
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, email_confirmed_at) values
    ('6a000000-0000-0000-0000-000000000001', 'rrasker@pgtap.test',  now()),
    ('6a000000-0000-0000-0000-000000000002', 'rrother@pgtap.test',  now()),
    ('6a000000-0000-0000-0000-000000000003', 'rrreader@pgtap.test', now()),
    ('6a000000-0000-0000-0000-000000000004', 'rrbossad@pgtap.test', now()),
    ('6a000000-0000-0000-0000-000000000005', 'rradmin@pgtap.test',  now());

-- The reader gets the permission through a role of its own, NOT through admin.
-- Using admin here would pass via is_user_admin's short-circuit whether or not the
-- permission was ever wired up, which is the failure mode this file exists to avoid.
insert into public.roles (name, description, is_system)
values ('pgtap_req_reader', 'pgTAP fixture role holding organisation.request_read', false)
on conflict (name) do nothing;

insert into public.role_permissions (role_id, permission_id)
select r.id, p.id
  from public.roles r, public.permissions p
 where r.name = 'pgtap_req_reader' and p.name = 'organisation.request_read'
on conflict do nothing;

insert into public.user_roles (user_id, role_id)
select '6a000000-0000-0000-0000-000000000003', r.id
  from public.roles r where r.name = 'pgtap_req_reader'
on conflict do nothing;

insert into public.user_roles (user_id, role_id)
select '6a000000-0000-0000-0000-000000000004', r.id
  from public.roles r where r.name = 'boss_admin'
on conflict do nothing;

insert into public.user_roles (user_id, role_id)
select '6a000000-0000-0000-0000-000000000005', r.id
  from public.roles r where r.name = 'admin'
on conflict do nothing;

select set_config('request.jwt.claims', '{"role":"service_role"}', true);

-- Two requests from two different requesters, so "sees only their own" is
-- distinguishable from "sees everything" (with one row it would not be).
select public.submit_organisation_request(
    'RR Asker Org', 'pgtrrask', null, null, 'because',
    null, '6a000000-0000-0000-0000-000000000001');
select public.submit_organisation_request(
    'RR Other Org', 'pgtrroth', null, null, 'because',
    null, '6a000000-0000-0000-0000-000000000002');

-- Guard: if the fixtures did not land, every row-count assertion below would pass
-- against an empty table and prove nothing.
select is(
    (select count(*) from public.organisation_requests where slug in ('pgtrrask','pgtrroth')),
    2::bigint,
    'fixture: two requests exist, from two different requesters'
);


-- ===========================================================================
-- SECTION 1: the permission itself
-- ===========================================================================
select ok(
    exists (select 1 from public.permissions where name = 'organisation.request_read'),
    'organisation.request_read exists'
);

-- Not is_system, and that is load-bearing rather than cosmetic: the roles plugin
-- hides the Remove button for is_system permissions while its add-list does not
-- filter them, so a system permission can be granted and then never taken back.
-- This permission exists to be re-pointed as the policy widens.
select ok(
    not (select is_system from public.permissions where name = 'organisation.request_read'),
    'organisation.request_read is NOT a system permission, so it can be un-granted from the roles UI'
);

-- The migration deliberately grants it to nobody -- admin works via the
-- short-circuit, and which roles hold it should be a deliberate operator act.
select is(
    (select count(*) from public.role_permissions rp
       join public.permissions p on p.id = rp.permission_id
      where p.name = 'organisation.request_read'
        and rp.role_id not in (select id from public.roles where name in ('pgtap_req_reader', 'platform_viewer'))),
    0::bigint,
    'only platform_viewer (20261028120000) and this test fixture hold organisation.request_read'
);

-- The name has to survive the RBAC name check, which allows exactly one dot.
-- Read the name off the seeded row rather than restating the literal, so this
-- checks what the migration actually inserted.
select matches(
    (select name from public.permissions
      where description like 'Read the global organisation-creation request queue%'),
    '^[a-z][a-z0-9_]{1,30}\.[a-z][a-z0-9_]{1,30}$',
    'the seeded permission name satisfies the enforced RBAC name pattern (exactly one dot)'
);


-- ===========================================================================
-- SECTION 2: who sees the queue
-- ===========================================================================
-- A holder of the new permission, who is not an admin.
select is(
    (public.list_organisation_requests(null, 50, 0,
        '6a000000-0000-0000-0000-000000000003') ->> 'is_reviewer')::boolean,
    true,
    'a holder of organisation.request_read is a reviewer'
);
select is(
    (select count(*) from jsonb_array_elements(
        public.list_organisation_requests(null, 50, 0,
            '6a000000-0000-0000-0000-000000000003') -> 'data')),
    2::bigint,
    'a holder of organisation.request_read sees requests from every requester'
);
select ok(
    not public.is_user_admin('6a000000-0000-0000-0000-000000000003'),
    'and that reader is NOT a global admin, so the arm above is not the admin short-circuit'
);


-- ===========================================================================
-- SECTION 3: the regression -- organisation.approve alone no longer reads
-- ===========================================================================
-- The fixture is only meaningful if this subject really does still hold
-- organisation.approve. Assert that first, or a boss_admin who had lost every
-- permission would satisfy the two assertions after it for the wrong reason.
select ok(
    public.get_effective_permissions('6a000000-0000-0000-0000-000000000004')
        @> array['organisation.approve']::text[],
    'fixture: the boss_admin subject does hold organisation.approve'
);
select ok(
    not (public.get_effective_permissions('6a000000-0000-0000-0000-000000000004')
        @> array['organisation.request_read']::text[]),
    'boss_admin does not inherit organisation.request_read (admin is the PARENT of boss_admin, not the child)'
);
select is(
    (public.list_organisation_requests(null, 50, 0,
        '6a000000-0000-0000-0000-000000000004') ->> 'is_reviewer')::boolean,
    false,
    'a boss_admin is no longer a reviewer of the request queue'
);
select is(
    (select count(*) from jsonb_array_elements(
        public.list_organisation_requests(null, 50, 0,
            '6a000000-0000-0000-0000-000000000004') -> 'data')),
    0::bigint,
    'a boss_admin sees no other requester rows -- is_reviewer is the row filter, not just a UI flag'
);


-- ===========================================================================
-- SECTION 4: the arms that must NOT change
-- ===========================================================================
-- A global admin, before anyone grants them anything: authorize() and
-- user_holds_permission() both short-circuit on is_user_admin, which is why the
-- migration can ship without a grant and leave the operator's queue working.
select is(
    (public.list_organisation_requests(null, 50, 0,
        '6a000000-0000-0000-0000-000000000005') ->> 'is_reviewer')::boolean,
    true,
    'a global admin is still a reviewer with no explicit grant (is_user_admin short-circuit)'
);
select is(
    (select count(*) from jsonb_array_elements(
        public.list_organisation_requests(null, 50, 0,
            '6a000000-0000-0000-0000-000000000005') -> 'data')),
    2::bigint,
    'a global admin still sees the whole queue'
);

-- A requester keeps their own row. This is the arm that stops the change from
-- being "nobody sees anything", which would also make sections 2 and 3 pass.
select is(
    (public.list_organisation_requests(null, 50, 0,
        '6a000000-0000-0000-0000-000000000001') ->> 'is_reviewer')::boolean,
    false,
    'a plain requester is not a reviewer'
);
select is(
    (select count(*) from jsonb_array_elements(
        public.list_organisation_requests(null, 50, 0,
            '6a000000-0000-0000-0000-000000000001') -> 'data')),
    1::bigint,
    'a plain requester still sees exactly their own request'
);
select is(
    (select e ->> 'slug' from jsonb_array_elements(
        public.list_organisation_requests(null, 50, 0,
            '6a000000-0000-0000-0000-000000000001') -> 'data') e),
    'pgtrrask',
    'and the row a requester sees is theirs, not the other requester''s'
);


-- ===========================================================================
-- SECTION 5: the sites deliberately left on organisation.approve
-- ===========================================================================
-- Acting on a request is a different capability from reading the queue. If a later
-- change wants to move these too, that is a decision to take on purpose -- these
-- assertions are here so it cannot happen by accident while editing this file.
select ok(
    pg_get_functiondef('public.approve_organisation_request(uuid,text,uuid)'::regprocedure)
        like '%organisation.approve%',
    'approve_organisation_request still gates on organisation.approve'
);
select ok(
    pg_get_functiondef('public.reject_organisation_request(uuid,text,uuid)'::regprocedure)
        like '%organisation.approve%',
    'reject_organisation_request still gates on organisation.approve'
);
select ok(
    exists (
        select 1 from pg_policies
         where schemaname = 'public' and tablename = 'organisations'
           and qual like '%organisation.approve%'
    ),
    'the organisations SELECT policy still lets an approver see private organisations'
);


-- ===========================================================================
-- SECTION 6: the direct table read moved too
-- ===========================================================================
-- Nothing reads this table through PostgREST today, but leaving the policy on
-- organisation.approve would mean a boss_admin who lost the queue in the RPC could
-- select the same rows straight off the table.
select ok(
    exists (
        select 1 from pg_policies
         where schemaname = 'public' and tablename = 'organisation_requests'
           and policyname = 'Reviewers can view all organisation requests'
           and qual like '%organisation.request_read%'
    ),
    'the reviewer RLS policy on organisation_requests reads organisation.request_read'
);
select ok(
    not exists (
        select 1 from pg_policies
         where schemaname = 'public' and tablename = 'organisation_requests'
           and qual like '%organisation.approve%'
    ),
    'no policy on organisation_requests still reads organisation.approve'
);


-- ===========================================================================
-- SECTION 7: is_system = false does not weaken the org-role deny-list
-- ===========================================================================
-- The obvious worry about seeding this permission non-system is that it becomes
-- attachable to an organisation's own admin role, which would hand every org admin
-- the global request queue -- a much worse leak than the boss_admin one being fixed.
-- It does not: is_org_grantable_permission tests the permission DOMAIN, and its own
-- comment says it deliberately does not read permissions.is_system (role.assign and
-- role.create are is_system = false). These two assertions pin that reasoning, so a
-- later change to either the flag or the deny-list cannot quietly cross them.
select ok(
    not public.is_org_grantable_permission(
        (select id from public.permissions where name = 'organisation.request_read')),
    'organisation.request_read cannot be attached to an organisation-owned role'
);
select ok(
    not public.is_org_grantable_permission(
        (select id from public.permissions where name = 'organisation.approve')),
    'and neither can organisation.approve -- the deny-list is by domain, not by is_system'
);

select * from finish();
rollback;
