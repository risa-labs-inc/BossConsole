-- pgTAP tests for the InitPlan hoist (20260923160000, and 20260923161000 for
-- the four terminal_sessions policies created after it was written).
-- Run with: supabase test db
--
-- The migrations are mechanical, so these assertions are about the two ways a
-- mechanical rewrite of 84 policies can go wrong: hoisting something that
-- depends on the row, and losing part of a policy while restating it.
--
-- Assertion 1 is the goal, stated as a property over every policy in the schema
-- rather than as 84 pinned strings, so it keeps its meaning when a later
-- migration adds a policy and does not break when a PostgreSQL upgrade renders
-- an expression slightly differently. It is what caught the terminal_sessions
-- policies when that table landed after the first migration was written.
--
-- Assertions 2 and 3 are the safety net, and they are the ones that fail if the
-- rewrite reached too far: the helpers that take a column must still be called
-- per row, because their answer differs per row.

begin;
select plan(14);

-- ---------------------------------------------------------------------------
-- 1: no statement-constant call is left un-hoisted anywhere in the schema.
--
-- Hoisted calls are stripped innermost first: the session calls, which are the
-- inner half of `is_user_admin(( select auth.uid() ))`, and only then the helper
-- names. Stripping the helper first would leave the inner session call looking
-- like a bare one. Both strips require a preceding SELECT, so a genuinely
-- un-hoisted call survives them and fails this assertion.
--
-- auth.role() is detected as well as stripped. Nothing in the schema calls it
-- today, but it is statement-constant like the other two session calls, and a
-- strip with no matching detection would let a bare one through.
-- ---------------------------------------------------------------------------
select is_empty(
    $$ with pol as (
         select c.relname as tbl, p.polname,
                coalesce(pg_get_expr(p.polqual, p.polrelid), '') || ' ' ||
                coalesce(pg_get_expr(p.polwithcheck, p.polrelid), '') as expr
         from pg_policy p
         join pg_class c on c.oid = p.polrelid
         join pg_namespace n on n.oid = c.relnamespace
         where n.nspname = 'public'
       ), stripped as (
         select tbl, polname,
                regexp_replace(
                  regexp_replace(expr,
                    'SELECT\s+auth\.(uid|jwt|role)\s*\(\s*\)', '', 'g'),
                  'SELECT\s+(authorize|is_user_admin)', '', 'g') as rest
         from pol
       )
       select tbl, polname from stripped
       where rest ~ '(auth\.uid|auth\.jwt|auth\.role|authorize|is_user_admin)\s*\(' $$,
    'every statement-constant call in every public policy is hoisted'
);

-- ---------------------------------------------------------------------------
-- 2-3: the column-taking helpers are still evaluated per row.
--
-- If a later change hoists one of these, the policy returns one row's answer for
-- every row. On `secrets` that means a member of one organisation reading
-- another organisation's secrets, so this is a security assertion, not a
-- performance one.
-- ---------------------------------------------------------------------------
select isnt_empty(
    $$ select c.relname
       from pg_policy p
       join pg_class c on c.oid = p.polrelid
       join pg_namespace n on n.oid = c.relnamespace
       where n.nspname = 'public'
         and pg_get_expr(p.polqual, p.polrelid) ~ 'is_org_member\(' $$,
    'is_org_member is still called with a column, not hoisted'
);

select is_empty(
    $$ select c.relname, p.polname
       from pg_policy p
       join pg_class c on c.oid = p.polrelid
       join pg_namespace n on n.oid = c.relnamespace
       where n.nspname = 'public'
         and (coalesce(pg_get_expr(p.polqual, p.polrelid), '') || ' ' ||
              coalesce(pg_get_expr(p.polwithcheck, p.polrelid), ''))
             ~ 'SELECT\s+(is_org_member|is_org_admin|can_manage_secret|can_view_plugin_row|can_publish_org_plugin)\(' $$,
    'no column-taking helper was hoisted into a subquery'
);

-- ---------------------------------------------------------------------------
-- 4-5: nothing was lost while restating the policies.
--
-- ALTER POLICY carries the command, the TO roles and PERMISSIVE/RESTRICTIVE
-- over, but that is exactly the kind of thing worth pinning rather than
-- trusting, because a later switch to DROP and CREATE would have to restate
-- all three.
--
-- There is deliberately no count of the schema's policies. ALTER POLICY cannot
-- add or remove one, 4 already names all 84, and a count would fail the next
-- change that adds a policy anywhere in public, without saying why.
-- ---------------------------------------------------------------------------
select is_empty(
    $$ select * from (values
    ('organisation_domains', 'Service role full access to organisation domains', 'PUBLIC'),
    ('organisation_handoff_tokens', 'Service role full access to handoff tokens', 'PUBLIC'),
    ('organisation_invite_redemptions', 'Service role full access to invite redemptions', 'PUBLIC'),
    ('organisation_invite_redemptions', 'Users can view their own invite redemptions', 'authenticated'),
    ('organisation_invites', 'Service role full access to organisation invites', 'PUBLIC'),
    ('organisation_members', 'Organisation members can view the roster', 'authenticated'),
    ('organisation_members', 'Service role full access to organisation members', 'PUBLIC'),
    ('organisation_members', 'Users can view their own memberships', 'authenticated'),
    ('organisation_requests', 'Requesters can view their own organisation requests', 'authenticated'),
    ('organisation_requests', 'Reviewers can view all organisation requests', 'authenticated'),
    ('organisation_requests', 'Service role full access to organisation requests', 'PUBLIC'),
    ('organisation_roles', 'Service role full access to organisation roles', 'PUBLIC'),
    ('organisations', 'Organisation reviewers can view all organisations', 'authenticated'),
    ('organisations', 'Service role full access to organisations', 'PUBLIC'),
    ('passkey_challenges', 'Allow session-based access for mobile flows', 'PUBLIC'),
    ('passkey_challenges', 'Service role can access all challenges', 'PUBLIC'),
    ('passkey_challenges', 'Users can insert their own challenges', 'PUBLIC'),
    ('passkey_challenges', 'Users can view their own challenges', 'PUBLIC'),
    ('permissions', 'Admins can create permissions', 'PUBLIC'),
    ('permissions', 'Admins can delete non-system permissions', 'PUBLIC'),
    ('permissions', 'Admins can update non-system permissions', 'PUBLIC'),
    ('permissions', 'Service role full access to permissions', 'PUBLIC'),
    ('plugin_api_key_logs', 'Users can view own API key logs', 'PUBLIC'),
    ('plugin_api_keys', 'Users can create own API keys', 'PUBLIC'),
    ('plugin_api_keys', 'Users can delete own API keys', 'PUBLIC'),
    ('plugin_api_keys', 'Users can update own API keys', 'PUBLIC'),
    ('plugin_api_keys', 'Users can view own API keys', 'PUBLIC'),
    ('plugin_permissions', 'role.read can view plugin permission provenance', 'authenticated'),
    ('plugin_screenshots', 'Authors can manage own plugin screenshots', 'PUBLIC'),
    ('plugin_screenshots', 'Users with plugins.admin.delete can manage all screenshots', 'PUBLIC'),
    ('plugin_screenshots', 'Users with plugins.admin.view can view all screenshots', 'PUBLIC'),
    ('plugin_tags', 'Authors can manage own plugin tags', 'PUBLIC'),
    ('plugin_tags', 'Users with plugins.admin.delete can manage all tags', 'PUBLIC'),
    ('plugin_tags', 'Users with plugins.admin.view can view all tags', 'PUBLIC'),
    ('plugin_versions', 'Authors can add versions', 'PUBLIC'),
    ('plugin_versions', 'Authors can view own plugin versions', 'PUBLIC'),
    ('plugin_versions', 'Users with plugins.admin.delete can delete versions', 'PUBLIC'),
    ('plugin_versions', 'Users with plugins.admin.view can view all versions', 'PUBLIC'),
    ('plugins', 'Authorised publishers can create plugins', 'authenticated'),
    ('plugins', 'Authors and organisation admins can update plugins', 'authenticated'),
    ('plugins', 'Authors can delete own plugins', 'PUBLIC'),
    ('plugins', 'Authors can view own plugins', 'PUBLIC'),
    ('plugins', 'Users with plugins.admin.delete can delete any plugin', 'PUBLIC'),
    ('plugins', 'Users with plugins.admin.publish can update any plugin', 'PUBLIC'),
    ('plugins', 'Users with plugins.admin.view can view all plugins', 'PUBLIC'),
    ('reserved_email_domains', 'Service role full access to reserved email domains', 'PUBLIC'),
    ('role_hierarchy', 'Admins can manage role hierarchy', 'PUBLIC'),
    ('role_hierarchy', 'Service role full access to role hierarchy', 'PUBLIC'),
    ('role_permissions', 'Admins can manage role permissions', 'PUBLIC'),
    ('role_permissions', 'Service role full access to role_permissions', 'PUBLIC'),
    ('roles', 'Admins can create roles', 'PUBLIC'),
    ('roles', 'Admins can delete non-system roles', 'PUBLIC'),
    ('roles', 'Admins can update non-system roles', 'PUBLIC'),
    ('roles', 'Service role full access to roles', 'PUBLIC'),
    ('secret_access_log', 'secret_access_log_select', 'PUBLIC'),
    ('secret_metadata', 'Users can create own secret metadata', 'PUBLIC'),
    ('secret_metadata', 'Users can delete own secret metadata', 'PUBLIC'),
    ('secret_metadata', 'Users can update own secret metadata', 'PUBLIC'),
    ('secret_metadata', 'Users can view own secret metadata', 'PUBLIC'),
    ('secret_shares', 'secret_shares_select', 'PUBLIC'),
    ('secret_tags', 'Users can create own secret tags', 'PUBLIC'),
    ('secret_tags', 'Users can delete own secret tags', 'PUBLIC'),
    ('secret_tags', 'Users can view own secret tags', 'PUBLIC'),
    ('secrets', 'Owners and organisation admins can delete secrets', 'PUBLIC'),
    ('secrets', 'Owners and organisation admins can update secrets', 'PUBLIC'),
    ('secrets', 'Users can create own or organisation secrets', 'PUBLIC'),
    ('secrets', 'Users can view own or organisation secrets', 'PUBLIC'),
    ('terminal_sessions', 'terminal_sessions owner delete', 'authenticated'),
    ('terminal_sessions', 'terminal_sessions owner insert', 'authenticated'),
    ('terminal_sessions', 'terminal_sessions owner select', 'authenticated'),
    ('terminal_sessions', 'terminal_sessions owner update', 'authenticated'),
    ('user_passkeys', 'Service role can access all passkeys', 'PUBLIC'),
    ('user_passkeys', 'Users can delete their own passkeys', 'PUBLIC'),
    ('user_passkeys', 'Users can insert their own passkeys', 'PUBLIC'),
    ('user_passkeys', 'Users can update their own passkeys', 'PUBLIC'),
    ('user_passkeys', 'Users can view their own passkeys', 'PUBLIC'),
    ('user_roles', 'Admins can assign roles', 'PUBLIC'),
    ('user_roles', 'Admins can remove roles', 'PUBLIC'),
    ('user_roles', 'Admins can view all roles', 'PUBLIC'),
    ('user_roles', 'Service role full access to user_roles', 'PUBLIC'),
    ('user_roles', 'Users can view their own roles', 'PUBLIC'),
    ('users', 'Privileged users can read all users', 'PUBLIC'),
    ('users', 'Users can read own data', 'PUBLIC'),
    ('users', 'Users can update own data', 'PUBLIC')
       ) as want(tbl, pol, roles)
       where not exists (
         select 1 from pg_policy p
         join pg_class c on c.oid = p.polrelid
         join pg_namespace n on n.oid = c.relnamespace
         where n.nspname = 'public' and c.relname = want.tbl
           and p.polname = want.pol) $$,
    'all 84 rewritten policies still exist under their original names'
);

-- The TO clause is what decides WHO a policy applies to, and ALTER POLICY keeps
-- it only because this migration never names one. That is worth pinning per
-- policy rather than in aggregate: PostgreSQL stores an unscoped policy as the
-- single oid 0, not as an empty array, so a check for '{}' would be vacuous
-- and would pass whatever happened here.
--
-- The command and PERMISSIVE/RESTRICTIVE are pinned in the same values list,
-- for the same reason ALTER cannot change either one on this migration's own
-- statements. That makes today's rewrite trivially safe on both; the pin is
-- for the regression this migration cannot cause but a later DROP/CREATE
-- could - most sharply, AS RESTRICTIVE silently becoming the PERMISSIVE
-- default, which widens access without touching a single role.
select is_empty(
    $$ select want.tbl, want.pol,
              want.roles as expected_roles, got.actual_roles,
              want.cmd as expected_cmd, got.actual_cmd,
              want.permissive as expected_permissive, got.actual_permissive
       from (values
    ('organisation_domains', 'Service role full access to organisation domains', 'PUBLIC', 'ALL', true),
    ('organisation_handoff_tokens', 'Service role full access to handoff tokens', 'PUBLIC', 'ALL', true),
    ('organisation_invite_redemptions', 'Service role full access to invite redemptions', 'PUBLIC', 'ALL', true),
    ('organisation_invite_redemptions', 'Users can view their own invite redemptions', 'authenticated', 'SELECT', true),
    ('organisation_invites', 'Service role full access to organisation invites', 'PUBLIC', 'ALL', true),
    ('organisation_members', 'Organisation members can view the roster', 'authenticated', 'SELECT', true),
    ('organisation_members', 'Service role full access to organisation members', 'PUBLIC', 'ALL', true),
    ('organisation_members', 'Users can view their own memberships', 'authenticated', 'SELECT', true),
    ('organisation_requests', 'Requesters can view their own organisation requests', 'authenticated', 'SELECT', true),
    ('organisation_requests', 'Reviewers can view all organisation requests', 'authenticated', 'SELECT', true),
    ('organisation_requests', 'Service role full access to organisation requests', 'PUBLIC', 'ALL', true),
    ('organisation_roles', 'Service role full access to organisation roles', 'PUBLIC', 'ALL', true),
    ('organisations', 'Organisation reviewers can view all organisations', 'authenticated', 'SELECT', true),
    ('organisations', 'Service role full access to organisations', 'PUBLIC', 'ALL', true),
    ('passkey_challenges', 'Allow session-based access for mobile flows', 'PUBLIC', 'SELECT', true),
    ('passkey_challenges', 'Service role can access all challenges', 'PUBLIC', 'ALL', true),
    ('passkey_challenges', 'Users can insert their own challenges', 'PUBLIC', 'INSERT', true),
    ('passkey_challenges', 'Users can view their own challenges', 'PUBLIC', 'SELECT', true),
    ('permissions', 'Admins can create permissions', 'PUBLIC', 'INSERT', true),
    ('permissions', 'Admins can delete non-system permissions', 'PUBLIC', 'DELETE', true),
    ('permissions', 'Admins can update non-system permissions', 'PUBLIC', 'UPDATE', true),
    ('permissions', 'Service role full access to permissions', 'PUBLIC', 'ALL', true),
    ('plugin_api_key_logs', 'Users can view own API key logs', 'PUBLIC', 'SELECT', true),
    ('plugin_api_keys', 'Users can create own API keys', 'PUBLIC', 'INSERT', true),
    ('plugin_api_keys', 'Users can delete own API keys', 'PUBLIC', 'DELETE', true),
    ('plugin_api_keys', 'Users can update own API keys', 'PUBLIC', 'UPDATE', true),
    ('plugin_api_keys', 'Users can view own API keys', 'PUBLIC', 'SELECT', true),
    ('plugin_permissions', 'role.read can view plugin permission provenance', 'authenticated', 'SELECT', true),
    ('plugin_screenshots', 'Authors can manage own plugin screenshots', 'PUBLIC', 'ALL', true),
    ('plugin_screenshots', 'Users with plugins.admin.delete can manage all screenshots', 'PUBLIC', 'ALL', true),
    ('plugin_screenshots', 'Users with plugins.admin.view can view all screenshots', 'PUBLIC', 'SELECT', true),
    ('plugin_tags', 'Authors can manage own plugin tags', 'PUBLIC', 'ALL', true),
    ('plugin_tags', 'Users with plugins.admin.delete can manage all tags', 'PUBLIC', 'ALL', true),
    ('plugin_tags', 'Users with plugins.admin.view can view all tags', 'PUBLIC', 'SELECT', true),
    ('plugin_versions', 'Authors can add versions', 'PUBLIC', 'INSERT', true),
    ('plugin_versions', 'Authors can view own plugin versions', 'PUBLIC', 'SELECT', true),
    ('plugin_versions', 'Users with plugins.admin.delete can delete versions', 'PUBLIC', 'DELETE', true),
    ('plugin_versions', 'Users with plugins.admin.view can view all versions', 'PUBLIC', 'SELECT', true),
    ('plugins', 'Authorised publishers can create plugins', 'authenticated', 'INSERT', true),
    ('plugins', 'Authors and organisation admins can update plugins', 'authenticated', 'UPDATE', true),
    ('plugins', 'Authors can delete own plugins', 'PUBLIC', 'DELETE', true),
    ('plugins', 'Authors can view own plugins', 'PUBLIC', 'SELECT', true),
    ('plugins', 'Users with plugins.admin.delete can delete any plugin', 'PUBLIC', 'DELETE', true),
    ('plugins', 'Users with plugins.admin.publish can update any plugin', 'PUBLIC', 'UPDATE', true),
    ('plugins', 'Users with plugins.admin.view can view all plugins', 'PUBLIC', 'SELECT', true),
    ('reserved_email_domains', 'Service role full access to reserved email domains', 'PUBLIC', 'ALL', true),
    ('role_hierarchy', 'Admins can manage role hierarchy', 'PUBLIC', 'ALL', true),
    ('role_hierarchy', 'Service role full access to role hierarchy', 'PUBLIC', 'ALL', true),
    ('role_permissions', 'Admins can manage role permissions', 'PUBLIC', 'ALL', true),
    ('role_permissions', 'Service role full access to role_permissions', 'PUBLIC', 'ALL', true),
    ('roles', 'Admins can create roles', 'PUBLIC', 'INSERT', true),
    ('roles', 'Admins can delete non-system roles', 'PUBLIC', 'DELETE', true),
    ('roles', 'Admins can update non-system roles', 'PUBLIC', 'UPDATE', true),
    ('roles', 'Service role full access to roles', 'PUBLIC', 'ALL', true),
    ('secret_access_log', 'secret_access_log_select', 'PUBLIC', 'SELECT', true),
    ('secret_metadata', 'Users can create own secret metadata', 'PUBLIC', 'INSERT', true),
    ('secret_metadata', 'Users can delete own secret metadata', 'PUBLIC', 'DELETE', true),
    ('secret_metadata', 'Users can update own secret metadata', 'PUBLIC', 'UPDATE', true),
    ('secret_metadata', 'Users can view own secret metadata', 'PUBLIC', 'SELECT', true),
    ('secret_shares', 'secret_shares_select', 'PUBLIC', 'SELECT', true),
    ('secret_tags', 'Users can create own secret tags', 'PUBLIC', 'INSERT', true),
    ('secret_tags', 'Users can delete own secret tags', 'PUBLIC', 'DELETE', true),
    ('secret_tags', 'Users can view own secret tags', 'PUBLIC', 'SELECT', true),
    ('secrets', 'Owners and organisation admins can delete secrets', 'PUBLIC', 'DELETE', true),
    ('secrets', 'Owners and organisation admins can update secrets', 'PUBLIC', 'UPDATE', true),
    ('secrets', 'Users can create own or organisation secrets', 'PUBLIC', 'INSERT', true),
    ('secrets', 'Users can view own or organisation secrets', 'PUBLIC', 'SELECT', true),
    ('terminal_sessions', 'terminal_sessions owner delete', 'authenticated', 'DELETE', true),
    ('terminal_sessions', 'terminal_sessions owner insert', 'authenticated', 'INSERT', true),
    ('terminal_sessions', 'terminal_sessions owner select', 'authenticated', 'SELECT', true),
    ('terminal_sessions', 'terminal_sessions owner update', 'authenticated', 'UPDATE', true),
    ('user_passkeys', 'Service role can access all passkeys', 'PUBLIC', 'ALL', true),
    ('user_passkeys', 'Users can delete their own passkeys', 'PUBLIC', 'DELETE', true),
    ('user_passkeys', 'Users can insert their own passkeys', 'PUBLIC', 'INSERT', true),
    ('user_passkeys', 'Users can update their own passkeys', 'PUBLIC', 'UPDATE', true),
    ('user_passkeys', 'Users can view their own passkeys', 'PUBLIC', 'SELECT', true),
    ('user_roles', 'Admins can assign roles', 'PUBLIC', 'INSERT', true),
    ('user_roles', 'Admins can remove roles', 'PUBLIC', 'DELETE', true),
    ('user_roles', 'Admins can view all roles', 'PUBLIC', 'SELECT', true),
    ('user_roles', 'Service role full access to user_roles', 'PUBLIC', 'ALL', true),
    ('user_roles', 'Users can view their own roles', 'PUBLIC', 'SELECT', true),
    ('users', 'Privileged users can read all users', 'PUBLIC', 'SELECT', true),
    ('users', 'Users can read own data', 'PUBLIC', 'SELECT', true),
    ('users', 'Users can update own data', 'PUBLIC', 'UPDATE', true)
       ) as want(tbl, pol, roles, cmd, permissive)
       join lateral (
         select coalesce(
                  (select string_agg(r.rolname, ',' order by r.rolname)
                     from pg_roles r where r.oid = any (p.polroles)),
                  'PUBLIC') as actual_roles,
                case p.polcmd
                  when '*' then 'ALL'
                  when 'r' then 'SELECT'
                  when 'a' then 'INSERT'
                  when 'w' then 'UPDATE'
                  when 'd' then 'DELETE'
                end as actual_cmd,
                p.polpermissive as actual_permissive
         from pg_policy p
         join pg_class c on c.oid = p.polrelid
         join pg_namespace n on n.oid = c.relnamespace
         where n.nspname = 'public' and c.relname = want.tbl
           and p.polname = want.pol
       ) got on true
       where got.actual_roles is distinct from want.roles
          or got.actual_cmd is distinct from want.cmd
          or got.actual_permissive is distinct from want.permissive $$,
    'every rewritten policy still applies to exactly the roles, command and permissiveness it did before'
);

-- ---------------------------------------------------------------------------
-- 6-7: the rewrite still admits and denies the same rows.
--
-- `users` carries both hoisted shapes at once: "Users can read own data" is the
-- `(select auth.uid()) = id` hoist, and "Privileged users can read all users" is
-- the `(select auth.jwt())` hoist. A catalog read cannot tell "the expression
-- still says SELECT somewhere" from "the rewrite changed which rows this policy
-- admits", so this runs two real SELECTs under `authenticated`, the same role
-- PostgREST uses, against two real fixture rows.
-- ---------------------------------------------------------------------------
insert into auth.users (id, email) values
    ('d1987000-0000-4000-8000-000000000001', 'plain@pgtap.test'),
    ('d1987000-0000-4000-8000-000000000002', 'admin@pgtap.test');

select set_config('request.jwt.claims',
    '{"sub":"d1987000-0000-4000-8000-000000000001","role":"authenticated"}', true);
set local role authenticated;
select is(
    (select count(*)::int from public.users
     where id in ('d1987000-0000-4000-8000-000000000001', 'd1987000-0000-4000-8000-000000000002')),
    1,
    'a plain user sees only their own row through the hoisted own-row policy'
);
reset role;

select set_config('request.jwt.claims',
    '{"sub":"d1987000-0000-4000-8000-000000000002","role":"authenticated","is_admin":true}', true);
set local role authenticated;
select is(
    (select count(*)::int from public.users
     where id in ('d1987000-0000-4000-8000-000000000001', 'd1987000-0000-4000-8000-000000000002')),
    2,
    'an admin claim sees every row through the hoisted privileged-read policy'
);
reset role;

-- ---------------------------------------------------------------------------
-- 8: the first half of the rule. A call may be hoisted only if its function is
-- STABLE or IMMUTABLE, because a VOLATILE one may answer differently on every
-- call. The rewrite hoists exactly these four, so a later change that makes one
-- of them VOLATILE fails here instead of changing what a policy admits.
-- ---------------------------------------------------------------------------
select is(
    (select count(*)::int from pg_proc
     where oid in ('auth.uid()'::regprocedure,
                   'auth.jwt()'::regprocedure,
                   'public.authorize(text)'::regprocedure,
                   'public.is_user_admin(uuid)'::regprocedure)
       and provolatile in ('s', 'i')),
    4,
    'every function the rewrite hoists is STABLE or IMMUTABLE'
);

-- ---------------------------------------------------------------------------
-- 9: the terminal_sessions follow-up still admits and denies the same rows.
-- Two fixture sessions, one per user, inserted as the table owner; the plain
-- user must see their own and not the other one.
-- ---------------------------------------------------------------------------
insert into public.terminal_sessions (user_id, share_id, device_name, scope, view_url, control_url) values
    ('d1987000-0000-4000-8000-000000000001', 'd198700000000001', 'pgtap', 'TAB',
     'https://view.pgtap.test/1', 'https://control.pgtap.test/1'),
    ('d1987000-0000-4000-8000-000000000002', 'd198700000000002', 'pgtap', 'TAB',
     'https://view.pgtap.test/2', 'https://control.pgtap.test/2');

select set_config('request.jwt.claims',
    '{"sub":"d1987000-0000-4000-8000-000000000001","role":"authenticated"}', true);
set local role authenticated;
select is(
    (select count(*)::int from public.terminal_sessions
     where share_id in ('d198700000000001', 'd198700000000002')),
    1,
    'a user sees only their own terminal session through the hoisted owner policy'
);
reset role;

-- ---------------------------------------------------------------------------
-- 10-11: the authorize() hoist still admits and denies the same rows.
--
-- 6-7 and 9 run the shapes Supabase's advisor documents. The two this rewrite
-- goes beyond it with, `(select authorize(...))` and
-- `(select is_user_admin((select auth.uid())))`, rest on the argument in the
-- migration header rather than on the advisor, so they get real rows too.
--
-- plugin_permissions has exactly one policy, `(select authorize('role.read'))`,
-- so what a user sees there is that call's answer and nothing else. boss_admin
-- holds role.read through role_permissions and is not named `admin`, so its
-- row comes through authorize()'s role lookup, not the admin short-circuit at
-- the top of it.
-- ---------------------------------------------------------------------------
insert into auth.users (id, email) values
    ('d1987000-0000-4000-8000-000000000003', 'role-admin@pgtap.test'),
    ('d1987000-0000-4000-8000-000000000004', 'boss-admin@pgtap.test');

insert into public.user_roles (user_id, role_id, assigned_by, assigned_at)
select u.id::uuid, r.id, null, now()
  from (values ('d1987000-0000-4000-8000-000000000001', 'user'),
               ('d1987000-0000-4000-8000-000000000003', 'user'),
               ('d1987000-0000-4000-8000-000000000003', 'admin'),
               ('d1987000-0000-4000-8000-000000000004', 'boss_admin')) as u(id, role)
  join public.roles r on r.name = u.role
on conflict (user_id, role_id) do nothing;

with p as (
    insert into public.permissions (name, description, is_system)
    values ('initplan.probe', 'fixture for rls_initplan_test', false)
    returning id)
insert into public.plugin_permissions (permission_id, plugin_id)
select id, 'pgtap.initplan' from p;

select set_config('request.jwt.claims',
    '{"sub":"d1987000-0000-4000-8000-000000000001","role":"authenticated"}', true);
set local role authenticated;
select is(
    (select count(*)::int from public.plugin_permissions where plugin_id = 'pgtap.initplan'),
    0,
    'a user without role.read sees no plugin permission provenance through the hoisted authorize()'
);
reset role;

select set_config('request.jwt.claims',
    '{"sub":"d1987000-0000-4000-8000-000000000004","role":"authenticated"}', true);
set local role authenticated;
select is(
    (select count(*)::int from public.plugin_permissions where plugin_id = 'pgtap.initplan'),
    1,
    'a role.read holder who is not an admin sees it through the hoisted authorize()'
);
reset role;

-- ---------------------------------------------------------------------------
-- 12-14: the is_user_admin() hoist on user_roles DELETE, including the guard
-- that stops an admin removing their own admin role.
--
-- A DELETE its policy refuses deletes nothing and raises nothing, so each case
-- deletes as `authenticated` and then counts what is left as the table owner.
-- Every row these DELETEs aim at is one the caller can already see through a
-- SELECT policy, so the only thing that can refuse it is the DELETE policy.
-- ---------------------------------------------------------------------------
select set_config('request.jwt.claims',
    '{"sub":"d1987000-0000-4000-8000-000000000001","role":"authenticated"}', true);
set local role authenticated;
delete from public.user_roles where user_id = 'd1987000-0000-4000-8000-000000000001';
reset role;
select is(
    (select count(*)::int from public.user_roles ur
     join public.roles r on r.id = ur.role_id
     where ur.user_id = 'd1987000-0000-4000-8000-000000000001' and r.name = 'user'),
    1,
    'a user who is not an admin cannot remove a role, even their own'
);

select set_config('request.jwt.claims',
    '{"sub":"d1987000-0000-4000-8000-000000000003","role":"authenticated"}', true);
set local role authenticated;
delete from public.user_roles where user_id = 'd1987000-0000-4000-8000-000000000001';
reset role;
select is(
    (select count(*)::int from public.user_roles
     where user_id = 'd1987000-0000-4000-8000-000000000001'),
    0,
    'an admin removes another user''s roles'
);

set local role authenticated;
delete from public.user_roles where user_id = 'd1987000-0000-4000-8000-000000000003';
reset role;
select is(
    (select string_agg(r.name, ',' order by r.name) from public.user_roles ur
     join public.roles r on r.id = ur.role_id
     where ur.user_id = 'd1987000-0000-4000-8000-000000000003'),
    'admin',
    'an admin can remove their own other roles but not their admin role'
);

select * from finish();
rollback;
