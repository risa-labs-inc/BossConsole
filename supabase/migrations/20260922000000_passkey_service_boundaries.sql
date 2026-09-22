-- Credential and challenge writes belong to the validated service-role Edge
-- routes, not to the client. The original 20251023 grants gave anon and
-- authenticated TABLE-level ALL on the passkey tables, and the 20251023 RLS
-- set added per-user INSERT/UPDATE/DELETE policies, so any signed-in caller
-- could enroll, replace or delete credential material and pre-book challenges
-- through PostgREST without the ceremony. Lane-aware admission
-- (20260920000000) bounds that traffic but does not close the write path; this
-- migration does.
--
-- Scope: table/column grants, the per-user policies, and the client EXECUTE
-- grants the earlier sweep left in place. find_user_by_email and
-- get_session_status already lost client EXECUTE in 20260910000000 and their
-- search_path was closed in the 20260916 set, so this file neither restates
-- those fixes nor redefines either function.
--
-- What stays intact:
--   service_role keeps every write path (enrollment, ceremony storage,
--   management, cleanup);
--   authenticated keeps read-only access to its own user_passkeys rows and to
--   the security_invoker active_user_passkeys view;
--   the SECURITY DEFINER trigger_cleanup_expired_challenges (20260916130000)
--   keeps its self-contained cleanup, which needs no client grants;
--   the vestigial FOR ALL service-role policies are restated as per-command
--   service_role policies (a no-op at runtime, documentation otherwise).

revoke all privileges on table public.user_passkeys, public.active_user_passkeys,
  public.passkey_challenges from public, anon, authenticated;

-- Column grants survive a table-level REVOKE; remove those alternate write
-- paths too.
do $$
declare
  target regclass;
  columns text;
begin
  foreach target in array array['public.user_passkeys'::regclass,
    'public.active_user_passkeys'::regclass, 'public.passkey_challenges'::regclass]
  loop
    select string_agg(quote_ident(attname), ', ' order by attnum) into columns
      from pg_attribute where attrelid = target and attnum > 0 and not attisdropped;
    execute format('revoke all privileges (%s) on table %s from public, anon, authenticated', columns, target);
  end loop;
end;
$$;

grant select on table public.user_passkeys, public.active_user_passkeys to authenticated;
grant all privileges on table public.user_passkeys, public.active_user_passkeys,
  public.passkey_challenges to service_role;

drop policy if exists "Users can insert their own passkeys" on public.user_passkeys;
drop policy if exists "Users can update their own passkeys" on public.user_passkeys;
drop policy if exists "Users can delete their own passkeys" on public.user_passkeys;
drop policy if exists "Users can view their own challenges" on public.passkey_challenges;
drop policy if exists "Allow session-based access for mobile flows" on public.passkey_challenges;
drop policy if exists "Users can insert their own challenges" on public.passkey_challenges;

-- The original sweep also left the vestigial FOR ALL service-role policies from
-- 20251023000013. They are no-ops at runtime (service_role bypasses RLS) but a
-- FOR ALL policy is a standing invitation for the table to be treated as
-- service-scoped by whatever next touches it; restate the intent the way
-- 20260910120000 did for plugin_downloads: drop the ALL command, keep the
-- per-command grants explicit.
drop policy if exists "Service role can access all passkeys" on public.user_passkeys;
drop policy if exists "Service role can access all challenges" on public.passkey_challenges;

create policy "user_passkeys service role select" on public.user_passkeys
  for select to service_role using (true);
create policy "user_passkeys service role insert" on public.user_passkeys
  for insert to service_role with check (true);
create policy "user_passkeys service role update" on public.user_passkeys
  for update to service_role using (true) with check (true);
create policy "user_passkeys service role delete" on public.user_passkeys
  for delete to service_role using (true);

create policy "passkey_challenges service role select" on public.passkey_challenges
  for select to service_role using (true);
create policy "passkey_challenges service role insert" on public.passkey_challenges
  for insert to service_role with check (true);
create policy "passkey_challenges service role update" on public.passkey_challenges
  for update to service_role using (true) with check (true);
create policy "passkey_challenges service role delete" on public.passkey_challenges
  for delete to service_role using (true);

revoke all privileges on function public.clean_expired_passkey_challenges(),
  public.create_mobile_registration_session(text, text, text) from public, anon, authenticated;
grant execute on function public.clean_expired_passkey_challenges(),
  public.create_mobile_registration_session(text, text, text) to service_role;
