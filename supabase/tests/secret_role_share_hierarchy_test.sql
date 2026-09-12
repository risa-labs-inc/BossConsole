-- pgTAP tests for role-share hierarchy closure (migration 20260802010000).
-- Run with: supabase test db
--
-- Pins BOTH directions of the widening this migration performs, because the whole
-- point is that it widens read access in exactly one direction and no further:
--   * a PARENT role gains visibility of its descendants' shares (the fix), and
--   * siblings gain nothing from each other (the boundary).
--
-- Uses the seeded hierarchy from 20260625000000:
--     admin -> boss_admin -> user
--     admin -> finance_admin -> user

begin;
select plan(11);

select vault.create_secret(
    '4444444444444444444444444444444444444444444444444444444444444444',
    'master_encryption_key',
    'pgTAP test key (transaction-local)');

-- ---------------------------------------------------------------------------
-- Fixtures: one holder of each role tier, plus a secret owner
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, email_confirmed_at) values
    ('60000000-0000-0000-0000-000000000001', 'hadmin@pgtap.test',   now()),
    ('60000000-0000-0000-0000-000000000002', 'hboss@pgtap.test',    now()),
    ('60000000-0000-0000-0000-000000000003', 'hfinance@pgtap.test', now()),
    ('60000000-0000-0000-0000-000000000004', 'howner@pgtap.test',   now());

insert into public.user_roles (user_id, role_id)
select u.id, r.id from auth.users u join public.roles r on
    (u.email = 'hadmin@pgtap.test'   and r.name = 'admin')
 or (u.email = 'hboss@pgtap.test'    and r.name = 'boss_admin')
 or (u.email = 'hfinance@pgtap.test' and r.name = 'finance_admin')
 -- The owner needs boss_admin purely for its `secret.share.role`: since
 -- 20260809000000, creating a role share takes that permission and a plain owner
 -- is refused. This suite is about who can SEE a role share, not who may create
 -- one, so the fixture buys the ability and moves on. The refusal itself is
 -- covered in secret_read_for_user_role_test.sql.
 or (u.email = 'howner@pgtap.test'   and r.name = 'boss_admin')
on conflict do nothing;

-- The owner creates a secret and shares it with finance_admin.
select set_config('request.jwt.claims',
    '{"sub":"60000000-0000-0000-0000-000000000004","role":"authenticated"}', true);

create temporary table t_h (k text primary key, v uuid);
insert into t_h
select 'secret', (public.create_secret('ledger.test','svc','pw-fin') ->> 'secret_id')::uuid;

select is(
    (select public.share_secret((select v from t_h where k='secret'),
        null, (select id from public.roles where name='finance_admin')) ->> 'target_role'),
    'finance_admin',
    'the fixture secret is shared with the finance_admin role'
);


-- ===========================================================================
-- The closure helpers
-- ===========================================================================
select set_eq(
    $$ select name from public.roles
        where id in (select public.effective_share_role_ids('60000000-0000-0000-0000-000000000001')) $$,
    $$ values ('admin'),('boss_admin'),('boss_plugin_admin'),('finance_admin'),('user') $$,
    'effective_share_role_ids(admin) is the full descendant closure'
);
select set_eq(
    $$ select name from public.roles
        where id in (select public.effective_share_role_ids('60000000-0000-0000-0000-000000000003')) $$,
    $$ values ('finance_admin'),('user') $$,
    'effective_share_role_ids(finance_admin) = {finance_admin, user} -- no upward leak'
);
select set_eq(
    $$ select name from public.roles
        where id in (select public.get_role_ancestors((select id from public.roles where name='user'))) $$,
    $$ values ('user'),('boss_plugin_admin'),('boss_admin'),('finance_admin'),('admin') $$,
    'get_role_ancestors(user) walks up both branches to admin'
);

-- DAG / cycle safety, the way rbac_hierarchy_test.sql checks its counterpart:
-- add user -> admin to make the graph cyclic, assert termination, then remove it.
insert into public.role_hierarchy (parent_role_id, child_role_id)
values ((select id from public.roles where name='user'),
        (select id from public.roles where name='admin'));
select set_eq(
    $$ select name from public.roles
        where id in (select public.get_role_ancestors((select id from public.roles where name='user'))) $$,
    $$ values ('user'),('boss_plugin_admin'),('boss_admin'),('finance_admin'),('admin') $$,
    'get_role_ancestors terminates on a cyclic graph'
);
select lives_ok(
    $$ select count(*) from public.effective_share_role_ids('60000000-0000-0000-0000-000000000001') $$,
    'effective_share_role_ids terminates on a cyclic graph'
);
delete from public.role_hierarchy
 where parent_role_id = (select id from public.roles where name='user')
   and child_role_id  = (select id from public.roles where name='admin');


-- ===========================================================================
-- THE FIX: a parent role now sees its descendant's shares
-- ===========================================================================
select set_config('request.jwt.claims',
    '{"sub":"60000000-0000-0000-0000-000000000003","role":"authenticated"}', true);
select is(
    (select count(*)::int from public.get_user_secrets_with_shared(50,0)
      where id=(select v from t_h where k='secret')),
    1, 'finance_admin (the direct share target) sees the secret -- unchanged behaviour'
);

select set_config('request.jwt.claims',
    '{"sub":"60000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
select is(
    (select count(*)::int from public.get_user_secrets_with_shared(50,0)
      where id=(select v from t_h where k='secret')),
    1,
    'THE FIX: admin, a PARENT of finance_admin, now sees a secret shared with finance_admin'
);
select ok(
    public.can_access_secret((select v from t_h where k='secret')),
    'THE FIX: can_access_secret agrees for the parent role'
);


-- ===========================================================================
-- THE BOUNDARY: siblings gain nothing
-- ===========================================================================
select set_config('request.jwt.claims',
    '{"sub":"60000000-0000-0000-0000-000000000002","role":"authenticated"}', true);
select is(
    (select count(*)::int from public.get_user_secrets_with_shared(50,0)
      where id=(select v from t_h where k='secret')),
    0,
    'BOUNDARY: boss_admin, a SIBLING of finance_admin, still cannot see it -- the widening is not lateral'
);
select ok(
    not public.can_access_secret((select v from t_h where k='secret')),
    'BOUNDARY: can_access_secret agrees for the sibling role'
);

select * from finish();
rollback;
