-- pgTAP tests for the granular role hierarchy (migration 20260625000000).
-- Run with: supabase test db
--
-- Covers: get_role_descendants (tree, DAG, cycle-safety), get_effective_permissions
-- (inheritance), authorize() over the hierarchy, and tree-derived grant delegation
-- (get_grantable_role_ids + assign_role_to_user rejections).
--
-- Fixtures are created inside the test transaction and rolled back. Inserting an
-- auth.users row fires handle_new_user(), which assigns the 'user' role.

begin;
select plan(26);

-- ---------------------------------------------------------------------------
-- Fixtures: four test users (handle_new_user assigns 'user' to each)
-- ---------------------------------------------------------------------------
insert into auth.users (id, email) values
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'admin@pgtap.test'),
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'boss@pgtap.test'),
    ('cccccccc-cccc-cccc-cccc-cccccccccccc', 'finance@pgtap.test'),
    ('dddddddd-dddd-dddd-dddd-dddddddddddd', 'target@pgtap.test');

insert into public.user_roles (user_id, role_id)
select u.id, r.id from auth.users u join public.roles r on
    (u.email = 'admin@pgtap.test'   and r.name = 'admin')
 or (u.email = 'boss@pgtap.test'    and r.name = 'boss_admin')
 or (u.email = 'finance@pgtap.test' and r.name = 'finance_admin')
on conflict do nothing;

-- ---------------------------------------------------------------------------
-- get_role_descendants: tree + DAG (user is a child of both branches)
-- ---------------------------------------------------------------------------
select set_eq(
    $$ select name from public.roles where id in (select public.get_role_descendants((select id from public.roles where name='admin'))) $$,
    $$ values ('admin'),('boss_admin'),('finance_admin'),('user') $$,
    'descendants(admin) = all four roles'
);
select set_eq(
    $$ select name from public.roles where id in (select public.get_role_descendants((select id from public.roles where name='boss_admin'))) $$,
    $$ values ('boss_admin'),('user') $$,
    'descendants(boss_admin) = {boss_admin, user}'
);
select set_eq(
    $$ select name from public.roles where id in (select public.get_role_descendants((select id from public.roles where name='finance_admin'))) $$,
    $$ values ('finance_admin'),('user') $$,
    'descendants(finance_admin) = {finance_admin, user}'
);
select set_eq(
    $$ select name from public.roles where id in (select public.get_role_descendants((select id from public.roles where name='user'))) $$,
    $$ values ('user') $$,
    'descendants(user) = {user} (leaf)'
);

-- Cycle safety: add user -> admin (makes the graph cyclic), assert termination,
-- then remove it so the rest of the suite sees the clean tree.
insert into public.role_hierarchy (parent_role_id, child_role_id)
values ((select id from public.roles where name='user'), (select id from public.roles where name='admin'));
select set_eq(
    $$ select name from public.roles where id in (select public.get_role_descendants((select id from public.roles where name='admin'))) $$,
    $$ values ('admin'),('boss_admin'),('finance_admin'),('user') $$,
    'descendants(admin) terminates and is unchanged under a cycle'
);
delete from public.role_hierarchy
where parent_role_id = (select id from public.roles where name='user')
  and child_role_id  = (select id from public.roles where name='admin');

-- ---------------------------------------------------------------------------
-- get_effective_permissions: inheritance
-- ---------------------------------------------------------------------------
select ok(
    public.get_effective_permissions('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb') @> array['role.create','user.read']::text[],
    'boss_admin effective perms include own role.create AND inherited user.read'
);
select ok(
    not (public.get_effective_permissions('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb') @> array['finance.read']::text[]),
    'boss_admin effective perms do NOT include finance.read'
);
select ok(
    public.get_effective_permissions('cccccccc-cccc-cccc-cccc-cccccccccccc') @> array['finance.read','user.read']::text[],
    'finance_admin effective perms include finance.read AND inherited user.read'
);
select ok(
    not (public.get_effective_permissions('cccccccc-cccc-cccc-cccc-cccccccccccc') @> array['role.create']::text[]),
    'finance_admin effective perms do NOT include role.create'
);
select ok(
    public.get_effective_permissions('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa') @> array['finance.read','role.delete','user.read']::text[],
    'admin inherits everything (finance.read + role.delete + user.read)'
);

-- ---------------------------------------------------------------------------
-- get_grantable_role_ids: strict descendants (admins exempt -> all)
-- ---------------------------------------------------------------------------
select set_eq(
    $$ select name from public.roles where id in (select public.get_grantable_role_ids('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb')) $$,
    $$ values ('user') $$,
    'boss_admin may grant only user (its strict descendant)'
);
select set_eq(
    $$ select name from public.roles where id in (select public.get_grantable_role_ids('cccccccc-cccc-cccc-cccc-cccccccccccc')) $$,
    $$ values ('user') $$,
    'finance_admin may grant only user'
);
select set_eq(
    $$ select name from public.roles where id in (select public.get_grantable_role_ids('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa')) $$,
    $$ values ('admin'),('boss_admin'),('finance_admin'),('user') $$,
    'admin may grant every role'
);

-- ---------------------------------------------------------------------------
-- authorize() + delegated assignment as boss_admin (auth.uid via jwt claim)
-- ---------------------------------------------------------------------------
select set_config('request.jwt.claims', '{"sub":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"}', true);
select is( public.authorize('role.create'), true,  'boss_admin authorize(role.create) = true' );
select is( public.authorize('finance.read'), false, 'boss_admin authorize(finance.read) = false' );
select is( public.authorize('role.delete'), false, 'boss_admin authorize(role.delete) = false (admin-only)' );
select throws_ok(
    $$ select public.assign_role_to_user('dddddddd-dddd-dddd-dddd-dddddddddddd'::uuid, 'finance_admin') $$,
    'P0001',
    'Permission denied: you are not allowed to assign role finance_admin',
    'boss_admin cannot assign finance_admin (sibling)'
);
select lives_ok(
    $$ select public.assign_role_to_user('dddddddd-dddd-dddd-dddd-dddddddddddd'::uuid, 'user') $$,
    'boss_admin may assign user (descendant)'
);
select throws_ok(
    $$ select public.assign_role_to_user('dddddddd-dddd-dddd-dddd-dddddddddddd'::uuid, 'admin') $$,
    'P0001',
    'Permission denied: you are not allowed to assign role admin',
    'boss_admin cannot assign admin (no privilege escalation)'
);

-- as finance_admin
select set_config('request.jwt.claims', '{"sub":"cccccccc-cccc-cccc-cccc-cccccccccccc"}', true);
select is( public.authorize('finance.read'), true, 'finance_admin authorize(finance.read) = true' );

-- as admin
select set_config('request.jwt.claims', '{"sub":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"}', true);
select lives_ok(
    $$ select public.assign_role_to_user('dddddddd-dddd-dddd-dddd-dddddddddddd'::uuid, 'finance_admin') $$,
    'admin may assign finance_admin'
);

-- ---------------------------------------------------------------------------
-- authorize() admin short-circuit (server agrees with the client admin bypass)
-- ---------------------------------------------------------------------------
-- (admin jwt claim still set from the block above)
select is(
    public.authorize('a.permission.that.does.not.exist'), true,
    'admin authorize() short-circuits true even for a permission outside its closure'
);
select is(
    (public.assign_permission_to_role('finance_admin', 'role.read'))->>'success', 'true',
    'admin may attach any permission to any role'
);

-- ---------------------------------------------------------------------------
-- Delegated role.update is constrained to the caller's subtree + held perms.
-- Grant role.update to boss_admin within this (rolled-back) transaction.
-- ---------------------------------------------------------------------------
insert into public.role_permissions (role_id, permission_id)
values ((select id from public.roles where name='boss_admin'),
        (select id from public.permissions where name='role.update'))
on conflict do nothing;
select set_config('request.jwt.claims', '{"sub":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"}', true);
select is(
    (public.assign_permission_to_role('user', 'role.read'))->>'success', 'true',
    'delegated role.update: boss_admin may attach a held perm to a role below it (user)'
);
select is(
    (public.assign_permission_to_role('finance_admin', 'role.read'))->>'success', 'false',
    'delegated role.update: boss_admin cannot modify finance_admin (outside its scope)'
);
select is(
    (public.assign_permission_to_role('user', 'finance.read'))->>'success', 'false',
    'delegated role.update: boss_admin cannot attach a permission it does not hold (finance.read)'
);

select * from finish();
rollback;
