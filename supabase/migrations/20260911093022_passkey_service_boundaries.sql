-- Enrollment and credential management are validated by the service-role Edge routes.
-- Owner RLS alone must not permit replacement of credential material through PostgREST.
revoke all privileges on table public.user_passkeys, public.active_user_passkeys,
  public.passkey_challenges from public, anon, authenticated;

-- Column grants survive a table-level REVOKE; remove those alternate write/read paths too.
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

revoke all privileges on function public.find_user_by_email(text), public.get_session_status(text),
  public.clean_expired_passkey_challenges() from public, anon, authenticated;
grant execute on function public.find_user_by_email(text), public.get_session_status(text),
  public.clean_expired_passkey_challenges() to service_role;

-- Privileged lookup functions must not resolve a caller-controlled schema or temporary table.
alter function public.find_user_by_email(text) set search_path = '';
create or replace function public.get_session_status(p_session_id text)
returns table (session_id text, status text, user_email text, created_at timestamptz, expires_at timestamptz)
language sql security definer set search_path = '' as $$
  select pc.session_id, pc.status, pc.user_email, pc.created_at, pc.expires_at
  from public.passkey_challenges pc
  where pc.session_id = p_session_id and pc.type = 'registration'
  order by pc.created_at desc limit 1;
$$;

-- Two fixed rows bound admission-state cardinality regardless of attacker-selected emails/headers.
-- Separate registration capacity prevents anonymous authentication traffic consuming its budget.
-- These are service capacity limits, not per-IP limits; no unverified forwarded header is trusted.
create table public.passkey_challenge_admission (
  type public.challenge_type primary key,
  window_started_at timestamptz not null default clock_timestamp(),
  used integer not null default 0 check (used >= 0)
);
alter table public.passkey_challenge_admission enable row level security;
revoke all privileges on table public.passkey_challenge_admission from public, anon, authenticated;
grant select, insert, update, delete on table public.passkey_challenge_admission to service_role;
insert into public.passkey_challenge_admission (type) values ('authentication'), ('registration');

-- Every cleanup invocation does bounded work through the existing expiry index.
-- All current challenges expire in five minutes; stale status is not a reason to retain them.
create or replace function public.clean_expired_passkey_challenges() returns void
language sql security invoker set search_path = '' as $$
  delete from public.passkey_challenges where id in (
    select id from public.passkey_challenges where expires_at <= clock_timestamp()
    order by expires_at limit 256 for update skip locked
  );
$$;

create or replace function public.trigger_cleanup_expired_challenges() returns trigger
language plpgsql security invoker set search_path = '' as $$
begin
  perform public.clean_expired_passkey_challenges();
  return new;
end;
$$;
revoke all privileges on function public.trigger_cleanup_expired_challenges() from public, anon, authenticated;
grant execute on function public.trigger_cleanup_expired_challenges() to service_role;

-- A shared transaction lock serializes both admission and actual INSERT capacity checks.
-- 300 authentication and 60 registration attempts per minute allow interactive retries while
-- bounding anonymous lookup work. No caller-selected account key can lock out a particular victim.
create function public.admit_passkey_challenge(p_type public.challenge_type)
returns table (allowed boolean, retry_after_seconds integer)
language plpgsql security invoker set search_path = '' as $$
declare
  budget public.passkey_challenge_admission%rowtype;
  observed_at timestamptz;
  maximum integer;
begin
  if p_type is null then raise exception 'Challenge type is required' using errcode = '22023'; end if;
  perform pg_advisory_xact_lock(545, 14);
  observed_at := clock_timestamp();
  perform public.clean_expired_passkey_challenges();
  select * into strict budget from public.passkey_challenge_admission where type = p_type for update;
  maximum := case when p_type = 'authentication' then 300 else 60 end;
  if budget.window_started_at <= observed_at - interval '60 seconds'
     or budget.window_started_at > observed_at then
    update public.passkey_challenge_admission set window_started_at = observed_at, used = 0 where type = p_type;
    budget.window_started_at := observed_at;
    budget.used := 0;
  end if;
  if budget.used >= maximum then
    return query select false, greatest(1, ceil(extract(epoch from
      (budget.window_started_at + interval '60 seconds' - observed_at)))::integer);
    return;
  end if;
  if (select count(*) from (select 1 from public.passkey_challenges
      where type = p_type and expires_at > observed_at limit 2048) live) >= 2048 then
    return query select false, 30;
    return;
  end if;
  update public.passkey_challenge_admission set used = used + 1 where type = p_type;
  return query select true, 0;
end;
$$;
revoke all privileges on function public.admit_passkey_challenge(public.challenge_type) from public, anon, authenticated;
grant execute on function public.admit_passkey_challenge(public.challenge_type) to service_role;

-- Admission and insertion are separate network calls. The INSERT boundary must enforce capacity
-- again so concurrent requests cannot overbook the last slot after their admission RPC returns.
create or replace function public.enforce_passkey_challenge_capacity() returns trigger
language plpgsql security invoker set search_path = '' as $$
begin
  perform pg_advisory_xact_lock(545, 14);
  if new.expires_at > clock_timestamp() + interval '5 minutes 5 seconds'
     or octet_length(new.challenge) > 512 or octet_length(new.session_id) > 512 then
    raise exception 'Invalid challenge bounds' using errcode = '22023';
  end if;
  if (select count(*) from (select 1 from public.passkey_challenges
      where type = new.type and expires_at > clock_timestamp() limit 2048) live) >= 2048 then
    raise exception 'Challenge capacity exhausted' using errcode = '53300';
  end if;
  return new;
end;
$$;
revoke all privileges on function public.enforce_passkey_challenge_capacity() from public, anon, authenticated;
grant execute on function public.enforce_passkey_challenge_capacity() to service_role;
create trigger enforce_passkey_challenge_capacity before insert on public.passkey_challenges
for each row execute function public.enforce_passkey_challenge_capacity();
