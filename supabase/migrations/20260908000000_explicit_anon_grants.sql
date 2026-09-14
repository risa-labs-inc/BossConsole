-- Anonymous access to a function in schema `public` must be STATED, never
-- INHERITED.
--
-- Why this exists: PostgreSQL hardwires EXECUTE on every new function to
-- PUBLIC, and PUBLIC includes Supabase's `anon` role - so a SECURITY DEFINER
-- function is callable by anyone holding the project's anon key from the moment
-- it is created. That key is compiled into the public BossConsole repo, so
-- "callable by anon" means "callable by the internet".
--
-- A per-schema ALTER DEFAULT PRIVILEGES cannot remove the global PUBLIC
-- default. A global revoke can, but would affect other schemas too. This guard
-- deliberately confines the change to public, regardless of the creator role.
--
-- This is how the BOSS Arcade published the roster of everyone who had opened
-- it, including to unauthenticated callers: five leaderboard and picker
-- functions were written with a `revoke ... from public` line that did not name
-- `anon`, and nothing else stood between them and the anon key.
--
-- Effect on migrations: a function that genuinely needs anonymous access - the
-- passkey pre-auth path is a real example - keeps working, because an explicit
-- `grant execute ... to anon` runs AFTER the create and therefore after this
-- trigger. CREATE OR REPLACE fires it too: every replacement of an intentionally
-- anonymous function must repeat that explicit grant after the definition.
-- See 20260909120000 for the fail-closed follow-up and role-preserving guard.
create or replace function public.enforce_explicit_anon_grants()
returns event_trigger
language plpgsql
security definer
set search_path = public
as $$
declare
  obj record;
begin
  for obj in select * from pg_event_trigger_ddl_commands() loop
    if obj.schema_name = 'public' and obj.object_type in ('function', 'procedure') then
      begin
        execute format('revoke all on routine %s from public, anon', obj.object_identity);
      exception when others then
        -- Never block DDL. A warning plus the audit query in
        -- boss-arcade/supabase/arcade_grants_audit.sql is the backstop.
        raise warning 'explicit-anon-grant guard could not lock down %: %',
          obj.object_identity, sqlerrm;
      end;
    end if;
  end loop;
end;
$$;

drop event trigger if exists enforce_explicit_anon_grants;
create event trigger enforce_explicit_anon_grants
  on ddl_command_end
  when tag in ('CREATE FUNCTION', 'CREATE PROCEDURE')
  execute function public.enforce_explicit_anon_grants();

-- Belt to the trigger's braces: default privileges stop handing `anon` rights
-- on new tables and sequences too. This one CAN be defaulted away (only the
-- function grant to PUBLIC is hardwired), and it means a table created without
-- `enable row level security` is no longer world-readable through the anon key.
-- `authenticated` defaults are deliberately untouched: for tables RLS is the
-- intended gate, and revoking them would break every migration rather than only
-- the ones that want anonymous access.
alter default privileges for role postgres in schema public revoke all on functions from anon, public;
alter default privileges for role postgres in schema public revoke all on tables from anon;
alter default privileges for role postgres in schema public revoke all on sequences from anon;

-- The audit that proves it, for any schema-public function. Must return no rows.
-- boss-arcade/supabase/arcade_grants_audit.sql runs the arcade-scoped version.
--
--   select p.oid::regprocedure as fn, coalesce(r.rolname,'PUBLIC') as grantee
--   from pg_proc p
--   join pg_namespace n on n.oid = p.pronamespace
--   cross join lateral aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a
--   left join pg_roles r on r.oid = a.grantee
--   where n.nspname = 'public'
--     and (a.grantee = 0 or r.rolname = 'anon')
--     and p.proname not in (/* functions that intend anonymous access */);
