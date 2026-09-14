-- Guard body superseded by 20260909140000_verify_anon_revocation.sql.
-- Follow-up to the deployed anonymous-grant sweep. Signup is open, so
-- authenticated is not a trust boundary for key material or decryption oracles.
-- These are called inside owner-run secret RPCs; clients use those gated RPCs.
revoke all on function public.get_encryption_key() from public, anon, authenticated;
revoke all on function public.encrypt_text(text) from public, anon, authenticated;
revoke all on function public.decrypt_text(text) from public, anon, authenticated;
revoke all on function public.safe_decrypt_recovery_codes(text) from public, anon, authenticated;

-- This helper is safe to use directly in authenticated RLS expressions.
grant execute on function public.org_is_vetted(uuid) to authenticated;

-- Preserve existing effective signed-in/server access before removing PUBLIC.
-- This includes access derived only from PUBLIC, including at CREATE time;
-- new sensitive helpers must explicitly revoke authenticated as well as anon.
-- Replacements with an already restricted ACL do not gain that role. Deliberate
-- anonymous grants must be re-issued after CREATE OR REPLACE as well as CREATE.
-- Failure must abort the DDL, not leave an exposed routine behind a warning.
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
      foreach role_name in array array['authenticated', 'service_role'] loop
        if pg_catalog.has_function_privilege(role_name, obj.objid, 'EXECUTE') then
          execute format('grant execute on routine %s to %I', obj.object_identity, role_name);
        end if;
      end loop;
      execute format('revoke all on routine %s from public, anon', obj.object_identity);
    end if;
  end loop;
end;
$$;
revoke all on function public.enforce_explicit_anon_grants() from public, anon, authenticated;
