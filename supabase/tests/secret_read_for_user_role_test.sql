-- pgTAP tests for the per-user vault (migration 20260809000000).
-- Run with: supabase test db
--
-- Two halves, and they pull in opposite directions on purpose:
--
--   1. WIDENING. `secret.read` reaches the baseline `user` role, so a plain
--      user can open the Secret Manager and Settings > AI Providers. This is
--      the point of the migration.
--   2. TIGHTENING. Sharing a secret with a ROLE now needs `secret.share.role`,
--      which `user` does NOT get. A role share fans out to every holder and
--      `user` is a descendant of every role, so an ungated role target is a
--      one-click broadcast to the whole deployment. That was survivable only
--      while non-admins could not reach the panel at all.
--
-- The share assertions check the secret_shares TABLE, not just the RPC's
-- success flag: a gate that returns an error while still writing the row would
-- pass a message-only assertion and leak exactly what it claims to prevent.
--
-- The suite provisions the Vault master key itself, inside the transaction, so
-- it passes on a fresh `supabase db reset`. Everything rolls back.

begin;
select plan(19);

-- ---------------------------------------------------------------------------
-- Vault key (rolled back). Without it encrypt_text raises and nothing works.
-- ---------------------------------------------------------------------------
select vault.create_secret(
    '3333333333333333333333333333333333333333333333333333333333333333',
    'master_encryption_key',
    'pgTAP test key (transaction-local)');

-- ---------------------------------------------------------------------------
-- Fixtures. handle_new_user assigns 'user' to each; the admin gets it as well
-- as its own role, which is the real shape of an admin account.
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, email_confirmed_at) values
    ('60000000-0000-0000-0000-000000000001', 'vaultplain@pgtap.test',  now()),
    ('60000000-0000-0000-0000-000000000002', 'vaultadmin@pgtap.test',  now()),
    ('60000000-0000-0000-0000-000000000003', 'vaultmate@pgtap.test',   now());

insert into public.user_roles (user_id, role_id)
select '60000000-0000-0000-0000-000000000002', id
from public.roles where name = 'admin'
on conflict do nothing;

create temporary table t_vault (k text primary key, v uuid);
insert into t_vault select 'role_user', id from public.roles where name = 'user';


-- ===========================================================================
-- SECTION 1: the widening
-- ===========================================================================
select ok(
    exists (
        select 1 from public.role_permissions rp
        join public.roles r on r.id = rp.role_id
        join public.permissions p on p.id = rp.permission_id
        where r.name = 'user' and p.name = 'secret.read'
    ),
    'the `user` role holds secret.read directly'
);

select ok(
    public.get_effective_permissions('60000000-0000-0000-0000-000000000001') @> array['secret.read']::text[],
    'a plain user effectively holds secret.read'
);

select set_config('request.jwt.claims',
    '{"sub":"60000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select is( public.authorize('secret.read'), true,
    'authorize(secret.read) = true for a plain user (this is what the panel gate reads)' );

-- The vault was ALWAYS per-user server-side; the gate was client-side only.
-- These pin that, so a future "tighten secret.read" change cannot quietly
-- assume the RPCs were ever the thing enforcing it.
insert into t_vault
select 'own',
       (public.create_secret('example.com','plainuser','pw-plain') ->> 'secret_id')::uuid;
select isnt((select v from t_vault where k='own'), NULL,
    'a plain user can create a secret (the RPC never checked a permission)');
select is(
    (select password from public.get_user_secrets(50,0) where id=(select v from t_vault where k='own')),
    'pw-plain',
    'a plain user can read back their own secret'
);


-- ===========================================================================
-- SECTION 2: secret.share.role exists and is scoped
-- ===========================================================================
select ok(
    exists (select 1 from public.permissions where name = 'secret.share.role' and is_system),
    'secret.share.role exists and is a system permission (delete_permission refuses it)'
);

select set_eq(
    $$ select r.name from public.role_permissions rp
       join public.roles r on r.id = rp.role_id
       join public.permissions p on p.id = rp.permission_id
       where p.name = 'secret.share.role' $$,
    $$ values ('admin'),('boss_admin') $$,
    'secret.share.role is granted to admin and boss_admin only'
);

select ok(
    not (public.get_effective_permissions('60000000-0000-0000-0000-000000000001') @> array['secret.share.role']::text[]),
    'a plain user does NOT hold secret.share.role'
);

-- The org-grantability boundary. `secret` is a reserved domain and only
-- `secret.read` is on the allowlist, so an organisation admin cannot mint a
-- global-role sharer inside their own org.
select ok(
    not public.is_org_grantable_permission(
        (select id from public.permissions where name='secret.share.role')),
    'secret.share.role is NOT org-grantable (reserved domain, not on the allowlist)'
);
select ok(
    public.is_org_grantable_permission(
        (select id from public.permissions where name='secret.read')),
    'secret.read IS still org-grantable (unchanged by this migration)'
);


-- ===========================================================================
-- SECTION 3: share_secret enforces it, and only for role targets
-- ===========================================================================
-- Still acting as the plain user, who OWNS the secret. Ownership passes
-- can_manage_secret, so the role gate is the only thing that can refuse this.
select is(
    (public.share_secret(
        p_secret_id => (select v from t_vault where k='own'),
        p_target_role_id => (select v from t_vault where k='role_user')
     ) ->> 'success')::boolean,
    false,
    'a plain OWNER cannot share their own secret with a role'
);
select is(
    (select count(*)::int from public.secret_shares
     where secret_id = (select v from t_vault where k='own')
       and shared_with_role_id is not null),
    0,
    'and no role share row was written (the refusal is not message-only)'
);

-- Sharing with a named person is untouched: one recipient the sharer chose.
select is(
    (public.share_secret(
        p_secret_id => (select v from t_vault where k='own'),
        p_target_user_id => '60000000-0000-0000-0000-000000000003'
     ) ->> 'success')::boolean,
    true,
    'a plain user CAN still share with an individual user'
);
select is(
    (select count(*)::int from public.secret_shares
     where secret_id = (select v from t_vault where k='own')
       and shared_with_user_id = '60000000-0000-0000-0000-000000000003'),
    1,
    'and that user share row exists'
);

-- The recipient sees it, which is the whole point of the untouched path.
select set_config('request.jwt.claims',
    '{"sub":"60000000-0000-0000-0000-000000000003","role":"authenticated"}', true);
select is(
    (select count(*)::int from public.get_user_secrets_with_shared(50,0)
     where id = (select v from t_vault where k='own') and is_owner = false),
    1,
    'the recipient sees the shared secret'
);

-- An admin may still create a role share.
select set_config('request.jwt.claims',
    '{"sub":"60000000-0000-0000-0000-000000000002","role":"authenticated"}', true);
insert into t_vault
select 'adminown',
       (public.create_secret('admin.example.com','vaultadmin','pw-admin') ->> 'secret_id')::uuid;
select is(
    (public.share_secret(
        p_secret_id => (select v from t_vault where k='adminown'),
        p_target_role_id => (select v from t_vault where k='role_user')
     ) ->> 'success')::boolean,
    true,
    'an admin CAN share with a role'
);
select is(
    (select count(*)::int from public.secret_shares
     where secret_id = (select v from t_vault where k='adminown')
       and shared_with_role_id = (select v from t_vault where k='role_user')),
    1,
    'and the admin role share row exists'
);

-- The refusal must not depend on the role being findable. A nonexistent role id
-- has to hit the permission check FIRST, or the error message tells an
-- unprivileged caller which role ids are real.
select set_config('request.jwt.claims',
    '{"sub":"60000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select alike(
    public.share_secret(
        p_secret_id => (select v from t_vault where k='own'),
        p_target_role_id => '00000000-0000-0000-0000-0000000000ff'
    ) ->> 'error',
    '%secret.share.role%',
    'the permission check runs before the role lookup (no role-existence oracle)'
);

-- Ownership is still required on top of the permission: the gate is additive,
-- not a replacement for can_manage_secret.
select set_config('request.jwt.claims',
    '{"sub":"60000000-0000-0000-0000-000000000003","role":"authenticated"}', true);
select alike(
    public.share_secret(
        p_secret_id => (select v from t_vault where k='own'),
        p_target_user_id => '60000000-0000-0000-0000-000000000002'
    ) ->> 'error',
    '%Unauthorized%',
    'a non-owner still cannot share someone else''s secret with anyone'
);

select * from finish();
rollback;
