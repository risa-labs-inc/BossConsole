-- Take EXECUTE away from `anon` and PUBLIC across schema public, except where
-- anonymous access is deliberate.
--
-- The event trigger in 20260908000000 stops NEW functions being anon-callable.
-- This is the one-time sweep for the ~30 that already were, none of which had
-- ever been granted anon on purpose: they inherited it, because PostgreSQL
-- hardwires EXECUTE to PUBLIC on every new function and this project's default
-- privileges added `anon` on top.
--
-- None of them was a privilege-escalation hole - the RBAC mutators
-- (delete_user, create_new_role, ...) fail closed, because is_user_admin(NULL)
-- is false - but they included anonymous reads of role and permission
-- assignments, anonymous invocation of two cleanup jobs, and an anonymous write
-- into the API-key audit log. `authenticated` is untouched, so every signed-in
-- client path is unaffected.
--
-- WHAT IS DELIBERATELY LEFT ANON-CALLABLE, and why each one would break:
--
--   search_plugins, get_plugin_with_stats, get_plugin_versions,
--   get_popular_tags, record_plugin_download, upsert_plugin_rating
--       the plugin store is browsable before sign-in.
--
--   can_view_plugin_row, authorize, is_user_admin
--       referenced by RLS policies on tables anon can read (plugins,
--       user_roles). A policy expression is evaluated as the QUERYING role, so
--       revoking these turns an anonymous SELECT on those tables into
--       `permission denied for function ...` rather than an empty result.
--
--   custom_access_token_hook
--       GoTrue calls it as supabase_auth_admin on every token issuance. It has
--       an explicit supabase_auth_admin grant so revoking anon would in fact be
--       safe, but the downside if that reading is wrong is that nobody can log
--       in, and the upside is nil: the function reads an event payload and
--       returns claims, granting nothing. Left alone on purpose.
do $$
declare
  fn regprocedure;
  keep constant text[] := array[
    'search_plugins', 'get_plugin_with_stats', 'get_plugin_versions',
    'get_popular_tags', 'record_plugin_download', 'upsert_plugin_rating',
    'can_view_plugin_row', 'authorize', 'is_user_admin',
    'custom_access_token_hook'
  ];
begin
  for fn in
    select p.oid::regprocedure
    from pg_proc p
    join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'public'
      and p.prokind in ('f', 'p')
      and p.proname <> all (keep)
      -- Extension-owned routines are excluded deliberately. pgcrypto's
      -- digest()/gen_random_uuid()-style helpers are called from column
      -- DEFAULTs, CHECK constraints and policy expressions, which are evaluated
      -- with the DML role's privileges - revoking PUBLIC there breaks writes for
      -- everyone, and the extension would re-grant on its next upgrade anyway.
      and not exists (
        select 1 from pg_catalog.pg_depend d
        where d.classid = 'pg_catalog.pg_proc'::regclass
          and d.objid = p.oid and d.deptype = 'e')
      and exists (
        select 1
        from aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a
        left join pg_roles r on r.oid = a.grantee
        where a.privilege_type = 'EXECUTE'
          and (a.grantee = 0 or r.rolname = 'anon')
      )
  loop
    execute format('revoke all on routine %s from public, anon', fn);
  end loop;
end $$;
