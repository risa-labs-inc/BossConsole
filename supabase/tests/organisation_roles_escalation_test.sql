-- pgTAP tests for organisation role escalation guards
-- (migrations 20260801000000 .. 20260801070000).
-- Run with: supabase test db
--
-- THIS IS THE LOAD-BEARING SUITE. It pins the three hazards that make
-- slug-derived organisation roles safe. Each assertion here exists because the
-- alternative was a real privilege escalation, and the Guard 2 section exists
-- because the first implementation of that guard was WRONG and shipped a hole
-- that only a behavioural test caught.
--
-- H1 -- authorize() is org-blind and short-circuits for global admins, so it can
--       never gate an org-scoped action.
-- H2 -- a slug whose derived role name collides with a global system role
--       (boss -> boss_admin) would map that role into an organisation.
-- H3 -- the <slug>_user -> user hierarchy edge puts the global `user` role inside
--       an org admin's delegated grant scope.
--
-- Fixtures are created inside the test transaction and rolled back. Inserting an
-- auth.users row fires handle_new_user(), which assigns the 'user' role and
-- (once the boss organisation exists) default membership.

begin;
select plan(42);

-- ---------------------------------------------------------------------------
-- Fixtures
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, email_confirmed_at) values
    ('a0000000-0000-0000-0000-00000000000a', 'globaladmin@pgtap.test', now()),
    ('b0000000-0000-0000-0000-00000000000b', 'orgowner@pgtap.test',    now()),
    ('c0000000-0000-0000-0000-00000000000c', 'orgmember@pgtap.test',   now()),
    ('d0000000-0000-0000-0000-00000000000d', 'outsider@pgtap.test',    now()),
    ('e0000000-0000-0000-0000-00000000000e', 'otherowner@pgtap.test',  now());

insert into public.user_roles (user_id, role_id)
select 'a0000000-0000-0000-0000-00000000000a', id from public.roles where name = 'admin'
on conflict do nothing;

-- Two organisations, so cross-organisation isolation is testable.
select lives_ok(
    $$ select public.create_organisation_internal(
           p_slug=>'pgtacme', p_name=>'PGTap Acme',
           p_owner_id=>'b0000000-0000-0000-0000-00000000000b',
           p_visibility=>'private', p_join_policy=>'request_to_join') $$,
    'create_organisation_internal builds an organisation'
);
select lives_ok(
    $$ select public.create_organisation_internal(
           p_slug=>'pgtother', p_name=>'PGTap Other',
           p_owner_id=>'e0000000-0000-0000-0000-00000000000e',
           p_visibility=>'private', p_join_policy=>'invite_only') $$,
    'a second organisation is independent'
);

-- The member joins acme and holds its member role.
insert into public.organisation_members (org_id, user_id, status, joined_at, join_source)
select id, 'c0000000-0000-0000-0000-00000000000c', 'active', now(), 'admin'
from public.organisations where slug = 'pgtacme';

insert into public.user_roles (user_id, role_id)
select 'c0000000-0000-0000-0000-00000000000c', orl.role_id
from public.organisation_roles orl
join public.organisations o on o.id = orl.org_id
where o.slug = 'pgtacme' and orl.kind = 'user'
on conflict do nothing;


-- ===========================================================================
-- Creation shape
-- ===========================================================================
select is(
    (select count(*)::int from public.roles where name in ('pgtacme_admin','pgtacme_user')),
    2, 'both organisation roles are created'
);
select is(
    (select count(*)::int from public.roles
      where name in ('pgtacme_admin','pgtacme_user') and is_system = true),
    0, 'organisation roles are NEVER is_system (else they could not be mapped or cleaned up)'
);
select set_eq(
    $$ select orl.kind from public.organisation_roles orl
       join public.organisations o on o.id = orl.org_id where o.slug='pgtacme' and orl.preset is null $$,
    $$ values ('admin'),('user') $$,
    'organisation_roles maps exactly one admin-kind and one user-kind role'
);
select is(
    (select count(*)::int from public.role_hierarchy rh
      join public.roles p on p.id = rh.parent_role_id
      join public.roles c on c.id = rh.child_role_id
     where (p.name='pgtacme_admin' and c.name='pgtacme_user')
        or (p.name='pgtacme_user'  and c.name='user')),
    2, 'hierarchy edges pgtacme_admin -> pgtacme_user -> user both exist'
);
select is(
    (select count(*)::int from public.organisation_members m
      join public.organisations o on o.id = m.org_id
     where o.slug='pgtacme' and m.user_id='b0000000-0000-0000-0000-00000000000b'
       and m.status='active' and m.join_source='founder'),
    1, 'the founder is an active member'
);


-- ===========================================================================
-- H2: slug -> role-name collision
-- ===========================================================================
select ok(
    public.is_reserved_organisation_slug('boss'),
    'H2: slug "boss" is reserved (it would derive the GLOBAL boss_admin role)'
);
select ok(
    public.is_reserved_organisation_slug('finance'),
    'H2: slug "finance" is reserved (it would derive the GLOBAL finance_admin role)'
);
select ok(
    public.is_reserved_organisation_slug('pgtacme'),
    'H2: a slug whose derived role names already exist is refused'
);
select ok(
    not public.is_reserved_organisation_slug('pgtfresh'),
    'H2: an unused, non-reserved slug is allowed'
);
select is(
    (select public.create_organisation_internal(
        p_slug=>'boss2', p_name=>'X',
        p_owner_id=>'b0000000-0000-0000-0000-00000000000b',
        p_admin_role_name=>'boss_admin') ->> 'success'),
    'false',
    'H2: create refuses when an explicit admin role name already exists'
);

-- Guard 1: the structural backstop. Even by direct INSERT, a system role can
-- never be mapped into an organisation.
select throws_ok(
    $$ insert into public.organisation_roles (org_id, role_id, kind)
       values ((select id from public.organisations where slug='pgtacme'),
               (select id from public.roles where name='boss_admin'), 'custom') $$,
    'organisation_roles: cannot map system role "boss_admin" to an organisation',
    'GUARD 1: mapping the global boss_admin role into an organisation is refused'
);
select throws_ok(
    $$ insert into public.organisation_roles (org_id, role_id, kind)
       values ((select id from public.organisations where slug='pgtacme'),
               (select id from public.roles where name='user'), 'custom') $$,
    'organisation_roles: cannot map system role "user" to an organisation',
    'GUARD 1: mapping the global user role into an organisation is refused'
);


-- ===========================================================================
-- H3 / GUARD 2: which permissions an organisation role may hold
-- ===========================================================================
-- FIRST, pin the reason the original implementation was wrong. permissions.is_system
-- is inconsistent in this catalog: the role-escalation permissions are is_system
-- = FALSE. Any future "simplify this to is_system" change must fail here.
select is(
    (select is_system from public.permissions where name = 'role.assign'),
    false,
    'PIN: role.assign is is_system = FALSE -- is_system must NOT be used as the org-grantable test'
);
select is(
    (select is_system from public.permissions where name = 'secret.read'),
    true,
    'PIN: secret.read is is_system = TRUE, yet IS org-grantable -- the flag does not track delegability'
);

-- The deny-list, by domain.
select ok(not public.is_org_grantable_permission(
    (select id from public.permissions where name='role.assign')),
    'GUARD 2: role.assign is not org-grantable');
select ok(not public.is_org_grantable_permission(
    (select id from public.permissions where name='role.create')),
    'GUARD 2: role.create is not org-grantable');
select ok(not public.is_org_grantable_permission(
    (select id from public.permissions where name='user.delete')),
    'GUARD 2: user.delete is not org-grantable');
select ok(not public.is_org_grantable_permission(
    (select id from public.permissions where name='plugins.admin.publish')),
    'GUARD 2: plugins.admin.publish is not org-grantable (two dots -- domain is "plugins")');
select ok(not public.is_org_grantable_permission(
    (select id from public.permissions where name='organisation.approve')),
    'GUARD 2: organisation.approve is not org-grantable');
select ok(not public.is_org_grantable_permission(
    (select id from public.permissions where name='api_key.create')),
    'GUARD 2: api_key.create is not org-grantable');
select ok(public.is_org_grantable_permission(
    (select id from public.permissions where name='organisation.admin')),
    'GUARD 2: organisation.admin IS org-grantable (explicit allowlist)');
select ok(public.is_org_grantable_permission(
    (select id from public.permissions where name='secret.read')),
    'GUARD 2: secret.read IS org-grantable (explicit allowlist)');
select ok(not public.is_org_grantable_permission(NULL),
    'GUARD 2: a NULL permission id fails closed');

-- A plugin-defined permission in its own domain is an organisation's business.
insert into public.permissions (name, description, is_system)
values ('pgtapdomain.manage', 'test plugin permission', false);
select ok(public.is_org_grantable_permission(
    (select id from public.permissions where name='pgtapdomain.manage')),
    'GUARD 2: a plugin-defined permission outside the reserved domains IS org-grantable');

-- The trigger, and note the role: this runs as the migration owner (a superuser),
-- proving the guard is NOT caller-dependent. A global admin cannot do this either.
select throws_ok(
    $$ insert into public.role_permissions (role_id, permission_id)
       values ((select id from public.roles where name='pgtacme_admin'),
               (select id from public.permissions where name='role.assign')) $$,
    'Permission "role.assign" cannot be granted to an organisation role (reserved for global roles)',
    'GUARD 2 TRIGGER: refuses role.assign on an organisation role, even for a superuser'
);
select lives_ok(
    $$ insert into public.role_permissions (role_id, permission_id)
       values ((select id from public.roles where name='pgtacme_admin'),
               (select id from public.permissions where name='secret.read')) $$,
    'GUARD 2 TRIGGER: allows an allowlisted permission'
);

-- H3's surface, documented and then shown to be inert. The global `user` role IS
-- in the org admin's grantable closure...
select ok(
    (select id from public.roles where name='user')
        in (select public.get_grantable_role_ids('b0000000-0000-0000-0000-00000000000b')),
    'H3 SURFACE: the global user role IS inside an organisation admin''s grantable scope'
);
-- ...but the only thing that surface could be used for is blocked by Guard 2.
select throws_ok(
    $$ insert into public.role_permissions (role_id, permission_id)
       values ((select id from public.roles where name='pgtacme_user'),
               (select id from public.permissions where name='role.update')) $$,
    'Permission "role.update" cannot be granted to an organisation role (reserved for global roles)',
    'H3 NEUTRALISED: the escalation the grantable-scope overlap would enable is refused'
);


-- ===========================================================================
-- H1: the trap. authorize()/user_holds_permission are org-blind.
-- ===========================================================================
-- The organisation owner holds organisation.admin (their org admin role carries
-- it) -- so a permission check says yes GLOBALLY, while the org-scoped predicate
-- correctly says no for a DIFFERENT organisation. Anyone who later "simplifies" an
-- org gate to a permission check has to delete this assertion to do it.
select ok(
    public.user_holds_permission('b0000000-0000-0000-0000-00000000000b', 'organisation.admin'),
    'H1 TRAP: the owner of pgtacme holds organisation.admin (a global permission check says yes)'
);
select ok(
    public.user_is_org_admin('b0000000-0000-0000-0000-00000000000b',
        (select id from public.organisations where slug='pgtacme')),
    'H1: ...and is genuinely an admin of their OWN organisation'
);
select ok(
    not public.user_is_org_admin('b0000000-0000-0000-0000-00000000000b',
        (select id from public.organisations where slug='pgtother')),
    'H1 TRAP: ...but is NOT an admin of another organisation -- which is why a bare permission check must never gate an org action'
);
select ok(
    public.user_is_org_admin('a0000000-0000-0000-0000-00000000000a',
        (select id from public.organisations where slug='pgtother')),
    'H1: a global admin IS an org admin everywhere, by documented design'
);


-- ===========================================================================
-- Cross-organisation isolation of the org-scoped RPCs
-- ===========================================================================
-- These call the RPCs with an explicit p_actor_id, which resolve_org_actor
-- honours ONLY for a service_role caller. Without the claim below every call
-- returns "Not authenticated" -- which is resolve_org_actor failing closed, and
-- is itself worth asserting.
select is(
    (select public.assign_organisation_role(
        (select id from public.organisations where slug='pgtacme'),
        'c0000000-0000-0000-0000-00000000000c',
        (select orl.role_id from public.organisation_roles orl
           join public.organisations o on o.id=orl.org_id
          where o.slug='pgtacme' and orl.kind='user'),
        'b0000000-0000-0000-0000-00000000000b') ->> 'error'),
    'Not authenticated',
    'resolve_org_actor FAILS CLOSED: p_actor_id is ignored without a service_role claim'
);

-- Now act as the edge function does.
select set_config('request.jwt.claims', '{"role":"service_role"}', true);

select is(
    (select public.assign_organisation_role(
        (select id from public.organisations where slug='pgtacme'),
        'c0000000-0000-0000-0000-00000000000c',
        (select orl.role_id from public.organisation_roles orl
           join public.organisations o on o.id=orl.org_id
          where o.slug='pgtother' and orl.kind='user'),
        'b0000000-0000-0000-0000-00000000000b') ->> 'error'),
    'That role does not belong to this organisation',
    'assign_organisation_role refuses another organisation''s role id'
);
select is(
    (select public.assign_organisation_role(
        (select id from public.organisations where slug='pgtacme'),
        'd0000000-0000-0000-0000-00000000000d',
        (select orl.role_id from public.organisation_roles orl
           join public.organisations o on o.id=orl.org_id
          where o.slug='pgtacme' and orl.kind='user'),
        'b0000000-0000-0000-0000-00000000000b') ->> 'error'),
    'That user is not an active member of this organisation',
    'assign_organisation_role refuses a non-member'
);
select is(
    (select public.create_organisation_role(
        (select id from public.organisations where slug='pgtother'), 'sneak', null,
        'b0000000-0000-0000-0000-00000000000b') ->> 'error'),
    'Permission denied',
    'create_organisation_role refuses an admin of a DIFFERENT organisation'
);


-- ===========================================================================
-- Custom organisation roles, created without global role.create
-- ===========================================================================
select ok(
    not public.user_holds_permission('b0000000-0000-0000-0000-00000000000b', 'role.create'),
    'the organisation owner does NOT hold global role.create'
);
select is(
    (select public.create_organisation_role(
        (select id from public.organisations where slug='pgtacme'), 'publisher',
        null, 'b0000000-0000-0000-0000-00000000000b') ->> 'role_name'),
    'pgtacme_publisher',
    '...yet can create a slug-prefixed custom role for their own organisation'
);
select is(
    (select public.create_organisation_role(
        (select id from public.organisations where slug='pgtacme'), 'admin',
        null, 'b0000000-0000-0000-0000-00000000000b') ->> 'error'),
    '"admin" and "user" are reserved -- the organisation already has those roles',
    'the reserved suffixes admin and user are refused'
);
-- The custom role's hierarchy position is hard-coded, never caller-supplied.
select set_eq(
    $$ select p.name || '->' || c.name
         from public.role_hierarchy rh
         join public.roles p on p.id = rh.parent_role_id
         join public.roles c on c.id = rh.child_role_id
        where p.name = 'pgtacme_publisher' or c.name = 'pgtacme_publisher' $$,
    $$ values ('pgtacme_admin->pgtacme_publisher'),('pgtacme_publisher->pgtacme_user') $$,
    'a custom role sits strictly between the organisation''s admin and user roles'
);

select * from finish();
rollback;
