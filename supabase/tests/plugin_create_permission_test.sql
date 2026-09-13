-- pgTAP tests for least-privilege plugin authoring (migration 20260731000000).
-- Run with: supabase test db
--
-- Covers: the boss_plugin_admin direct/effective permission sets, what it must
-- NOT hold, inheritance up to boss_admin/admin, delegated assignment (it can be
-- assigned, but cannot itself assign), the backfill query, and the
-- user_has_permission service-role probe.
--
-- Fixtures are created inside the test transaction and rolled back. Inserting an
-- auth.users row fires handle_new_user(), which assigns the 'user' role.

begin;
select plan(23);

-- ---------------------------------------------------------------------------
-- Fixtures
-- ---------------------------------------------------------------------------
insert into auth.users (id, email) values
    ('11111111-1111-1111-1111-111111111111', 'pluginadmin@pgtap.test'),
    ('22222222-2222-2222-2222-222222222222', 'bossadmin@pgtap.test'),
    ('33333333-3333-3333-3333-333333333333', 'sysadmin@pgtap.test'),
    ('44444444-4444-4444-4444-444444444444', 'plain@pgtap.test'),
    ('55555555-5555-5555-5555-555555555555', 'legacyauthor@pgtap.test');

insert into public.user_roles (user_id, role_id)
select u.id, r.id from auth.users u join public.roles r on
    (u.email = 'pluginadmin@pgtap.test' and r.name = 'boss_plugin_admin')
 or (u.email = 'bossadmin@pgtap.test'   and r.name = 'boss_admin')
 or (u.email = 'sysadmin@pgtap.test'    and r.name = 'admin')
on conflict do nothing;

-- ---------------------------------------------------------------------------
-- The role and permission exist, and are protected from deletion
-- ---------------------------------------------------------------------------
select ok(
    exists (select 1 from public.roles where name = 'boss_plugin_admin' and is_system),
    'boss_plugin_admin exists and is a system role'
);
select ok(
    exists (select 1 from public.permissions where name = 'plugins.create' and is_system),
    'plugins.create exists and is a system permission'
);
select ok(
    exists (select 1 from public.permissions where name = 'api_key.create' and is_system),
    'api_key.create was promoted to a system permission'
);

-- ---------------------------------------------------------------------------
-- Direct vs effective permission sets
-- ---------------------------------------------------------------------------
select set_eq(
    $$ select p.name from public.role_permissions rp
       join public.roles r on r.id = rp.role_id
       join public.permissions p on p.id = rp.permission_id
       where r.name = 'boss_plugin_admin' $$,
    $$ values ('plugins.create'),('api_key.create') $$,
    'boss_plugin_admin DIRECT permissions = {plugins.create, api_key.create}'
);
select set_eq(
    $$ select unnest(public.get_effective_permissions('11111111-1111-1111-1111-111111111111')) $$,
    $$ values ('plugins.create'),('api_key.create'),
              ('user.read'),('user.write'),('user.update'),('user.delete'),
              -- The organisation feature grants these two to the baseline `user`
              -- role (20260801010000), and boss_plugin_admin inherits from it.
              -- Revoking them from `user` is the kill-switch for self-service
              -- organisation creation, so this list tracks that grant.
              ('organisation.create'),('organisation.read'),
              -- Likewise `secret.read`, granted to `user` by 20260809000000 so that
              -- everyone gets their own vault and Settings > AI Providers. Inherited,
              -- not direct: this role still cannot share a secret with a role, which is
              -- what the secret.share.role assertion below pins.
              ('secret.read') $$,
    'boss_plugin_admin EFFECTIVE = direct + the inherited user.* baseline'
);

-- ---------------------------------------------------------------------------
-- What it must NOT hold. Checked as families, so a permission added to `user`
-- later cannot quietly widen this role past the intent.
-- ---------------------------------------------------------------------------
select is(
    (select count(*)::int from unnest(public.get_effective_permissions('11111111-1111-1111-1111-111111111111')) perm
     where perm like 'plugins.admin.%'),
    0,
    'boss_plugin_admin holds NO plugins.admin.* moderation permission'
);
-- `secret.read` is exempted rather than dropped from the family list: it is now part
-- of the `user` baseline every role inherits (20260809000000), so its presence here is
-- expected. Any OTHER secret.* -- notably secret.share.role -- must still be absent,
-- which is the half of this assertion that still has teeth.
select is(
    (select count(*)::int from unnest(public.get_effective_permissions('11111111-1111-1111-1111-111111111111')) perm
     where perm like 'role.%' or perm like 'finance.%' or perm like 'rpa.%'
        or (perm like 'secret.%' and perm <> 'secret.read')),
    0,
    'boss_plugin_admin holds NO role.*, finance.*, rpa.* or non-baseline secret.* permission'
);

-- ---------------------------------------------------------------------------
-- Inheritance upward: boss_admin and admin both reach plugins.create
-- ---------------------------------------------------------------------------
select ok(
    public.get_effective_permissions('22222222-2222-2222-2222-222222222222') @> array['plugins.create']::text[],
    'boss_admin inherits plugins.create via boss_plugin_admin'
);
select ok(
    public.get_effective_permissions('33333333-3333-3333-3333-333333333333') @> array['plugins.create']::text[],
    'admin inherits plugins.create (admin -> boss_admin -> boss_plugin_admin)'
);
select ok(
    not (public.get_effective_permissions('44444444-4444-4444-4444-444444444444') @> array['plugins.create']::text[]),
    'a plain user does NOT get plugins.create'
);
select ok(
    not (public.get_effective_permissions('44444444-4444-4444-4444-444444444444') @> array['api_key.create']::text[]),
    'a plain user does NOT get api_key.create'
);

-- ---------------------------------------------------------------------------
-- authorize() as boss_plugin_admin
-- ---------------------------------------------------------------------------
select set_config('request.jwt.claims', '{"sub":"11111111-1111-1111-1111-111111111111"}', true);
select is( public.authorize('plugins.create'), true,  'boss_plugin_admin authorize(plugins.create) = true' );
select is( public.authorize('api_key.create'), true,  'boss_plugin_admin authorize(api_key.create) = true' );
select is( public.authorize('plugins.admin.publish'), false,
           'boss_plugin_admin authorize(plugins.admin.publish) = false (moderation stays separate)' );
-- Was `false`. `user` holds secret.read from 20260809000000 and this role inherits it,
-- so the vault is open to it like everyone else. The permission that still separates a
-- plugin author from an admin is the role-share fan-out, asserted next.
select is( public.authorize('secret.read'), true, 'boss_plugin_admin authorize(secret.read) = true (inherited from user)' );
select is( public.authorize('secret.share.role'), false,
           'boss_plugin_admin authorize(secret.share.role) = false (role shares stay admin-only)' );

-- ---------------------------------------------------------------------------
-- Delegated assignment. boss_plugin_admin is a grantable TARGET for boss_admin,
-- but is not itself a grantor: get_grantable_role_ids returns {user}, yet
-- assign_role_to_user still refuses because the role has no role.assign.
-- Both halves are asserted -- the grantable set alone would read as "it can
-- assign user", which is false.
-- ---------------------------------------------------------------------------
select set_eq(
    $$ select name from public.roles where id in (select public.get_grantable_role_ids('11111111-1111-1111-1111-111111111111')) $$,
    $$ values ('user') $$,
    'get_grantable_role_ids(boss_plugin_admin) = {user} (its strict descendant)'
);
select throws_ok(
    $$ select public.assign_role_to_user('44444444-4444-4444-4444-444444444444'::uuid, 'user') $$,
    'P0001',
    'Permission denied: role.assign required',
    'boss_plugin_admin still cannot assign user -- it has no role.assign'
);

select set_config('request.jwt.claims', '{"sub":"22222222-2222-2222-2222-222222222222"}', true);
select lives_ok(
    $$ select public.assign_role_to_user('44444444-4444-4444-4444-444444444444'::uuid, 'boss_plugin_admin') $$,
    'boss_admin may assign boss_plugin_admin (its new strict descendant)'
);

-- ---------------------------------------------------------------------------
-- user_has_permission: the service-role probe used for API-key publishing
-- ---------------------------------------------------------------------------
select is( public.user_has_permission('11111111-1111-1111-1111-111111111111', 'plugins.create'), true,
           'user_has_permission(boss_plugin_admin, plugins.create) = true' );
select is( public.user_has_permission('55555555-5555-5555-5555-555555555555', 'plugins.create'), false,
           'user_has_permission(plain user, plugins.create) = false' );
select is( public.user_has_permission('33333333-3333-3333-3333-333333333333', 'plugins.create'), true,
           'user_has_permission(admin, anything) = true (admin short-circuit)' );

-- ---------------------------------------------------------------------------
-- Backfill. The migration's one-shot INSERT already ran against the pre-existing
-- rows, so this re-runs the same statement against a fixture author to prove the
-- query grants the role rather than to re-observe the migration.
-- ---------------------------------------------------------------------------
insert into public.plugins (plugin_id, display_name, author_id, author_name)
values ('ai.rever.boss.pgtap.legacy', 'pgTAP legacy plugin',
        '55555555-5555-5555-5555-555555555555', 'legacyauthor');

insert into public.user_roles (user_id, role_id)
select distinct legacy_author.id, r.id
from (
    select author_id as id from public.plugins where author_id is not null
    union
    select user_id as id from public.plugin_api_keys where revoked_at is null
) as legacy_author
join public.roles r on r.name = 'boss_plugin_admin'
join auth.users au on au.id = legacy_author.id
on conflict (user_id, role_id) do nothing;

select ok(
    public.get_effective_permissions('55555555-5555-5555-5555-555555555555') @> array['plugins.create']::text[],
    'backfill grants boss_plugin_admin to a pre-existing plugin author'
);

select * from finish();
rollback;
