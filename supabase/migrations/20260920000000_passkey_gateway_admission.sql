-- Lane-aware passkey challenge admission.
--
-- The database limits are storage/CPU safety valves, not fair per-client rate
-- limiting. A trusted public gateway is the only network route to
-- /functions/v1/passkey/auth/challenge. It divides the 300/minute
-- authentication budget into lanes whose hard sum is at most 300: 225/minute
-- for untrusted cold clients and a 75/minute reserve for trusted admission
-- grants. Registration keeps its own 60/minute budget on the untrusted lane.
-- A distributed anonymous spray can exhaust only the untrusted lane, never
-- the reserve.
--
-- This migration carries only the admission machinery. Table and function
-- hardening for user_passkeys, active_user_passkeys, and passkey_challenges
-- lives in the earlier migrations; this file must not restate or widen it.
--
-- Privileged functions must not resolve a caller-controlled schema.
-- Every function below pins search_path to ''.

-- Fixed (type, lane) rows bound admission-state cardinality regardless of
-- attacker-selected emails or headers. Separate registration capacity
-- prevents anonymous authentication traffic from consuming its budget.
create table public.passkey_challenge_admission (
  type public.challenge_type not null,
  lane text not null check (lane in ('untrusted', 'trusted')),
  window_started_at timestamptz not null default clock_timestamp(),
  used integer not null default 0 check (used >= 0),
  primary key (type, lane)
);
alter table public.passkey_challenge_admission enable row level security;
revoke all privileges on table public.passkey_challenge_admission from public, anon, authenticated;
grant select, insert, update, delete on table public.passkey_challenge_admission to service_role;
insert into public.passkey_challenge_admission (type, lane) values
  ('authentication', 'untrusted'),
  ('authentication', 'trusted'),
  ('registration', 'untrusted');

-- Short-lived gateway request receipts. A replayed assertion is refused
-- without consuming another slot or inserting a challenge. Rows expire
-- minutes after admission, so the table stays rate-bounded instead of
-- attacker-key-bounded.
create table public.passkey_gateway_receipt (
  request_id text primary key check (request_id <> '' and octet_length(request_id) <= 128),
  lane text not null,
  expires_at timestamptz not null
);
create index passkey_gateway_receipt_expires_at_idx on public.passkey_gateway_receipt (expires_at);
alter table public.passkey_gateway_receipt enable row level security;
revoke all privileges on table public.passkey_gateway_receipt from public, anon, authenticated;
grant select, insert, update, delete on table public.passkey_gateway_receipt to service_role;

-- Per-(type, lane) transaction locks serialize admission and INSERT capacity
-- checks without making anonymous authentication traffic block registration
-- or the trusted reserve. Bound all lock waits: lock_timeout is 250 ms, so a
-- held authentication transaction cannot indefinitely queue work.
-- Lane caps: 225 untrusted plus 75 trusted authentication attempts per minute
-- (225 + 75 = 300, never above the storage budget), and 60 registration
-- attempts per minute. No caller-selected account key can lock out a
-- particular victim.
create function public.admit_passkey_challenge(
  p_type public.challenge_type,
  p_lane text default 'untrusted',
  p_request_id text default null
)
returns table (allowed boolean, retry_after_seconds integer, duplicate boolean)
language plpgsql security invoker set search_path = '' set lock_timeout = '250ms' as $$
declare
  budget public.passkey_challenge_admission%rowtype;
  observed_at timestamptz;
  maximum integer;
  inserted integer;
begin
  if p_type is null then raise exception 'Challenge type is required' using errcode = '22023'; end if;
  if p_lane not in ('untrusted', 'trusted') then
    raise exception 'Unknown admission lane' using errcode = '22023';
  end if;
  if p_lane = 'trusted' and p_type <> 'authentication' then
    raise exception 'Trusted lane is authentication-only' using errcode = '22023';
  end if;
  perform pg_advisory_xact_lock(545,
    case when p_type = 'authentication' then (case when p_lane = 'trusted' then 16 else 14 end) else 15 end);
  observed_at := clock_timestamp();
  select * into strict budget from public.passkey_challenge_admission
    where type = p_type and lane = p_lane for update;
  maximum :=
    case when p_type = 'authentication' then (case when p_lane = 'trusted' then 75 else 225 end) else 60 end;
  if budget.window_started_at <= observed_at - interval '60 seconds'
     or budget.window_started_at > observed_at then
    update public.passkey_challenge_admission
      set window_started_at = observed_at, used = 0 where type = p_type and lane = p_lane;
    budget.window_started_at := observed_at;
    budget.used := 0;
  end if;
  if p_request_id is not null then
    -- Prune expired receipts with bounded work, then redeem atomically: a
    -- concurrent replay loses the insert race and is refused as a duplicate.
    delete from public.passkey_gateway_receipt where request_id in (
      select request_id from public.passkey_gateway_receipt
        where expires_at <= observed_at order by expires_at limit 512 for update skip locked
    );
    insert into public.passkey_gateway_receipt (request_id, lane, expires_at)
      values (p_request_id, p_lane, observed_at + interval '180 seconds')
      on conflict (request_id) do nothing;
    get diagnostics inserted = row_count;
    if inserted = 0 then
      return query select false, 0, true;
      return;
    end if;
  end if;
  if budget.used >= maximum then
    return query select false, greatest(1, ceil(extract(epoch from
      (budget.window_started_at + interval '60 seconds' - observed_at)))::integer), false;
    return;
  end if;
  if (select count(*) from (select 1 from public.passkey_challenges
      where type = p_type and expires_at > observed_at limit 2048) live) >= 2048 then
    return query select false, 30, false;
    return;
  end if;
  -- Rejected requests must not perform cleanup while holding the admission lock.
  perform public.clean_expired_passkey_challenges();
  update public.passkey_challenge_admission set used = used + 1 where type = p_type and lane = p_lane;
  return query select true, 0, false;
end;
$$;
revoke all privileges on function
  public.admit_passkey_challenge(public.challenge_type, text, text) from public, anon, authenticated;
grant execute on function
  public.admit_passkey_challenge(public.challenge_type, text, text) to service_role;

-- Admission and insertion are separate network calls. The INSERT boundary
-- must enforce capacity again so concurrent requests cannot overbook the
-- last slot after their admission RPC returns.
create or replace function public.enforce_passkey_challenge_capacity() returns trigger
language plpgsql security invoker set search_path = '' set lock_timeout = '250ms' as $$
begin
  perform pg_advisory_xact_lock(545, case when new.type = 'authentication' then 14 else 15 end);
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
drop trigger if exists enforce_passkey_challenge_capacity on public.passkey_challenges;
create trigger enforce_passkey_challenge_capacity before insert on public.passkey_challenges
for each row execute function public.enforce_passkey_challenge_capacity();
