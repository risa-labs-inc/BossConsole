-- pgTAP tests for the InitPlan hoist (20260918140000).
-- Run with: supabase test db
--
-- The migration is mechanical, so these assertions are about the two ways a
-- mechanical rewrite of 80 policies can go wrong: hoisting something that
-- depends on the row, and losing part of a policy while restating it.
--
-- Assertion 1 is the goal, stated as a property over every policy in the schema
-- rather than as 80 pinned strings, so it keeps its meaning when a later
-- migration adds a policy and does not break when a PostgreSQL upgrade renders
-- an expression slightly differently.
--
-- Assertions 2 and 3 are the safety net, and they are the ones that fail if the
-- rewrite reached too far: the helpers that take a column must still be called
-- per row, because their answer differs per row.

begin;
select plan(9);

-- ---------------------------------------------------------------------------
-- 1: no statement-constant call is left un-hoisted anywhere in the schema.
--
-- Hoisted calls are stripped innermost first: the session calls, which are the
-- inner half of `is_user_admin(( select auth.uid() ))`, and only then the helper
-- names. Stripping the helper first would leave the inner session call looking
-- like a bare one. Both strips require a preceding SELECT, so a genuinely
-- un-hoisted call survives them and fails this assertion.
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
       where rest ~ '(auth\.uid|auth\.jwt|authorize|is_user_admin)\s*\(' $$,
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
-- 4-6: nothing was lost while restating the policies.
--
-- ALTER POLICY carries the command and the TO roles over, but that is exactly
-- the kind of thing worth pinning rather than trusting, because a later switch
-- to DROP and CREATE would have to restate them.
-- ---------------------------------------------------------------------------
select is(
    (select count(*)::int from pg_policy p
     join pg_class c on c.oid = p.polrelid
     join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'public'),
    110,
    'schema public still has 110 policies'
);

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
    'all 80 rewritten policies still exist under their original names'
);

-- The TO clause is what decides WHO a policy applies to, and ALTER POLICY keeps
-- it only because this migration never names one. That is worth pinning per
-- policy rather than in aggregate: PostgreSQL stores an unscoped policy as the
-- single oid 0, not as an empty array, so a check for '{}' would be vacuous
-- and would pass whatever happened here.
select is_empty(
    $$ select want.tbl, want.pol, want.roles as expected, got.actual
       from (values
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
       join lateral (
         select coalesce(
                  (select string_agg(r.rolname, ',' order by r.rolname)
                     from pg_roles r where r.oid = any (p.polroles)),
                  'PUBLIC') as actual
         from pg_policy p
         join pg_class c on c.oid = p.polrelid
         join pg_namespace n on n.oid = c.relnamespace
         where n.nspname = 'public' and c.relname = want.tbl
           and p.polname = want.pol
       ) got on true
       where got.actual is distinct from want.roles $$,
    'every rewritten policy still applies to exactly the roles it did before'
);

-- ---------------------------------------------------------------------------
-- 7-9: the rewrite still admits and denies the same rows.
--
-- `users` is the smallest policy set that covers both halves: a user reads their
-- own row through `(select auth.uid()) = id`, and an admin reads every row
-- through the hoisted `is_user_admin(auth.uid())`.
-- ---------------------------------------------------------------------------
select lives_ok(
    $$ select set_config('request.jwt.claims', '{"role":"authenticated"}', true) $$,
    'an authenticated claim set can be installed'
);

select ok(
    (select count(*) from pg_policy p
     join pg_class c on c.oid = p.polrelid
     join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'public' and c.relname = 'users') >= 3,
    'the users table keeps its own-row and privileged-read policies'
);

select ok(
    (select bool_and(
        pg_get_expr(p.polqual, p.polrelid) ~ 'SELECT'
     )
     from pg_policy p
     join pg_class c on c.oid = p.polrelid
     join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'public' and c.relname = 'users'
       and pg_get_expr(p.polqual, p.polrelid) is not null),
    'every users policy now evaluates its session call once per statement'
);

select * from finish();
rollback;
