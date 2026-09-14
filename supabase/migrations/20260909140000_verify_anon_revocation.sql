-- Enforce the postcondition even when REVOKE only emits a warning.
-- Preserve existing effective signed-in/server access before removing PUBLIC.
-- This includes access derived only from PUBLIC, including at CREATE time;
-- new sensitive helpers must explicitly revoke authenticated as well as anon.
-- Replacements with an already restricted ACL do not gain that role. Deliberate
-- anonymous grants must be re-issued after CREATE OR REPLACE as well as CREATE.
-- Failure must abort the DDL, not leave an exposed routine behind a warning -
-- except for extension-owned routines, which are exempted with a warning
-- because holding them to it makes CREATE EXTENSION impossible. Databases
-- without the Supabase roles (a plain restore) degrade instead of failing.
create or replace function public.enforce_explicit_anon_grants()
returns event_trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  obj record;
  role_name text;
begin
  for obj in select * from pg_catalog.pg_event_trigger_ddl_commands() loop
    if obj.schema_name = 'public' and obj.object_type in ('function', 'procedure') then
      -- Routines owned by an extension are the extension's to manage, and this
      -- guard cannot hold them to the invariant without making CREATE EXTENSION
      -- impossible: `create extension pgtap` installs 1079 functions into
      -- public, every one anon-executable, and aborting the first of them
      -- aborts the whole install. A guard that forces operators to disable it
      -- to install an extension is a guard that ends up disabled, so warn and
      -- let CHECK 1x of the standing audit report the exposure instead.
      if exists (
        select 1 from pg_catalog.pg_depend d
        where d.classid = 'pg_catalog.pg_proc'::regclass
          and d.objid = obj.objid and d.deptype = 'e'
      ) then
        raise warning 'Extension routine % left with its own grants; see identity_disclosure_audit.sql CHECK 1x',
          obj.object_identity;
        continue;
      end if;
      foreach role_name in array array['authenticated', 'service_role'] loop
        -- A database restored outside Supabase has no anon/authenticated roles.
        -- has_function_privilege() and REVOKE both raise undefined_object for an
        -- unknown role, which would turn every CREATE FUNCTION in public into a
        -- hard failure during the restore of this very migration stream.
        if pg_catalog.to_regrole(role_name) is not null
           and pg_catalog.has_function_privilege(role_name, obj.objid, 'EXECUTE') then
          execute format('grant execute on routine %s to %I', obj.object_identity, role_name);
        end if;
      end loop;
      if pg_catalog.to_regrole('anon') is not null then
        execute format('revoke all on routine %s from public, anon', obj.object_identity);
      else
        execute format('revoke all on routine %s from public', obj.object_identity);
      end if;
      -- GRANT/REVOKE can warn without changing an ACL. Verify the effective
      -- postcondition, including permissions inherited through another role.
      if pg_catalog.to_regrole('anon') is not null
         and pg_catalog.has_function_privilege('anon', obj.objid, 'EXECUTE') then
        raise exception using errcode = '42501',
          message = format('Anonymous EXECUTE remains on %s', obj.object_identity),
          hint = 'Review routine ownership and inherited anon grants. The guard must be authorized to revoke access; do not bypass it for extension routines in public.';
      end if;
    end if;
  end loop;
end;
$$;
revoke all on function public.enforce_explicit_anon_grants() from public, anon, authenticated;
