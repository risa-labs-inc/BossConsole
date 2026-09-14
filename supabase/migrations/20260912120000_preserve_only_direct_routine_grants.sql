-- Do not turn access inherited through PUBLIC into a permanent client grant.
--
-- The earlier guard used has_function_privilege() before revoking PUBLIC. For a
-- routine created by a role without Supabase's default ACLs, that answered true
-- for authenticated and service_role solely because both roles inherit PUBLIC.
-- The guard then materialised that accidental access as an explicit grant. A
-- newly-created SECURITY DEFINER helper could therefore become callable by any
-- self-registered account even though nobody granted authenticated access.
--
-- Direct authenticated/service_role ACL entries need no preservation step:
-- revoking PUBLIC and anon does not remove them. Removing the effective-access
-- loop preserves deliberate direct grants while dropping PUBLIC-derived access.
create or replace function public.enforce_explicit_anon_grants()
returns event_trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  obj record;
begin
  for obj in select * from pg_catalog.pg_event_trigger_ddl_commands() loop
    if obj.schema_name = 'public' and obj.object_type in ('function', 'procedure') then
      -- Extension routines retain their extension-managed ACLs. Installing an
      -- extension otherwise fails on its first public routine; CHECK 1x in the
      -- standing audit keeps that exposure visible.
      if exists (
        select 1 from pg_catalog.pg_depend d
        where d.classid = 'pg_catalog.pg_proc'::regclass
          and d.objid = obj.objid and d.deptype = 'e'
      ) then
        raise warning 'Extension routine % left with its own grants; see identity_disclosure_audit.sql CHECK 1x',
          obj.object_identity;
        continue;
      end if;

      if pg_catalog.to_regrole('anon') is not null then
        execute format('revoke all on routine %s from public, anon', obj.object_identity);
      else
        execute format('revoke all on routine %s from public', obj.object_identity);
      end if;

      -- GRANT/REVOKE can warn without changing an ACL. Verify the effective
      -- anonymous postcondition, including access inherited through another role.
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

revoke all on function public.enforce_explicit_anon_grants()
  from public, anon, authenticated;
