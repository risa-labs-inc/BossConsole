-- Receipt window and trusted-lane INSERT serialization.
--
-- Two follow-ups to the admission machinery in
-- 20260920000000_passkey_gateway_admission.sql, restated here as create-or-replace
-- so the change applies whether that migration has already run or not.
--
-- 1. The gateway accepts an assertion from iat - 60s through exp + 60s, which at
--    the 120s maximum TTL is up to 180s after iat. A receipt expiring 180s after
--    its first presentation stopped covering a replay before the assertion's own
--    acceptance window ended when the gateway clock ran ahead of the database.
--    240s dominates the acceptance window unconditionally.
-- 2. The INSERT trigger only held the untrusted authentication lock (545, 14).
--    The admission path holds (545, 16) for the trusted lane, so a trusted
--    admission and a concurrent INSERT could both pass the 2048-row check
--    against the same snapshot. Authentication inserts now take both locks.
--
-- Privileged functions must not resolve a caller-controlled schema.
-- Every function below pins search_path to ''.

create or replace function public.admit_passkey_challenge(
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
      values (p_request_id, p_lane, observed_at + interval '240 seconds')
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

create or replace function public.enforce_passkey_challenge_capacity() returns trigger
language plpgsql security invoker set search_path = '' set lock_timeout = '250ms' as $$
begin
  perform pg_advisory_xact_lock(545, case when new.type = 'authentication' then 14 else 15 end);
  -- Authentication inserts must also serialize against the trusted lane's
  -- admission lock: the capacity check below is shared by both lanes.
  if new.type = 'authentication' then perform pg_advisory_xact_lock(545, 16); end if;
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
