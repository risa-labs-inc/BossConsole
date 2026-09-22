-- pgTAP tests for lane-aware passkey challenge admission
-- (20260920000000_passkey_gateway_admission).
-- Run with: supabase test db
--
-- A distributed anonymous caller can consume a shared budget, so the 300/minute
-- authentication budget is split into lanes whose hard sum is at most 300:
-- 225/minute untrusted plus a 75/minute trusted reserve. Registration keeps its
-- own 60/minute untrusted budget. Gateway request IDs are redeemed atomically
-- with admission, so a replay is refused without consuming another slot.
--
-- Fixtures and counter changes are rolled back.

begin;
select plan(26);

-- The fixed (type, lane) rows bound cardinality regardless of caller input.
select is((select count(*) from public.passkey_challenge_admission), 3::bigint,
 'admission state has fixed cardinality independent of caller-supplied identities');
select is_empty(
  $$ select type::text, lane from public.passkey_challenge_admission
     except values ('authentication', 'untrusted'),
       ('authentication', 'trusted'), ('registration', 'untrusted') $$,
 'exactly the three configured lanes exist');

-- New tables stay service-role-only behind RLS.
select ok((select relrowsecurity from pg_class where oid = 'public.passkey_challenge_admission'::regclass),
 'admission table has RLS');
select ok((select relrowsecurity from pg_class where oid = 'public.passkey_gateway_receipt'::regclass),
 'receipt table has RLS');
select ok(not has_table_privilege('anon', 'public.passkey_gateway_receipt', 'SELECT'),
 'anon cannot read gateway receipts');
select ok(not has_table_privilege('authenticated', 'public.passkey_gateway_receipt', 'INSERT'),
 'authenticated cannot plant gateway receipts');
select ok(not has_function_privilege('anon',
 'public.admit_passkey_challenge(public.challenge_type, text, text)', 'EXECUTE'),
 'anonymous callers cannot consume shared budget directly');
select ok(not has_function_privilege('authenticated',
 'public.admit_passkey_challenge(public.challenge_type, text, text)', 'EXECUTE'),
 'authenticated callers cannot consume shared budget directly');

set local role service_role;

-- Untrusted authentication admits up to 225 per window, then refuses bounded.
update public.passkey_challenge_admission set used = 224,
  window_started_at = clock_timestamp() where type = 'authentication' and lane = 'untrusted';
select ok((select allowed from public.admit_passkey_challenge('authentication', 'untrusted')),
 'the last untrusted authentication slot is admitted');
select ok(not (select allowed from public.admit_passkey_challenge('authentication', 'untrusted')),
 'the 226th untrusted request is refused');
select ok((select retry_after_seconds between 1 and 60
 from public.admit_passkey_challenge('authentication', 'untrusted')),
 'untrusted refusal includes a bounded retry interval');

-- The trusted reserve is untouched by untrusted exhaustion.
select ok((select allowed from public.admit_passkey_challenge('authentication', 'trusted')),
 'trusted admission survives untrusted exhaustion');
update public.passkey_challenge_admission set used = 75,
  window_started_at = clock_timestamp() where type = 'authentication' and lane = 'trusted';
select ok(not (select allowed from public.admit_passkey_challenge('authentication', 'trusted')),
 'the 76th trusted request is refused');

-- Registration keeps its own 60/minute budget on the untrusted lane.
select ok((select allowed from public.admit_passkey_challenge('registration', 'untrusted')),
 'registration retains independent admission capacity');
update public.passkey_challenge_admission set used = 60,
  window_started_at = clock_timestamp() where type = 'registration' and lane = 'untrusted';
select ok(not (select allowed from public.admit_passkey_challenge('registration', 'untrusted')),
 'the 61st registration request is refused');

-- A replayed gateway request ID is refused without consuming another slot.
update public.passkey_challenge_admission set used = 0,
  window_started_at = clock_timestamp() where type = 'authentication' and lane = 'trusted';
select ok((select allowed from public.admit_passkey_challenge('authentication', 'trusted', 'receipt-one')),
 'the first redemption of a gateway request ID is admitted');
select is((select used from public.passkey_challenge_admission
 where type = 'authentication' and lane = 'trusted'), 1,
 'the first redemption consumes exactly one slot');
select ok(not (select allowed from public.admit_passkey_challenge('authentication', 'trusted', 'receipt-one')),
 'the replayed gateway request ID is refused');
select is((select duplicate from public.admit_passkey_challenge('authentication', 'trusted', 'receipt-one')),
 true, 'the replay is reported as a duplicate');
select is((select used from public.passkey_challenge_admission
 where type = 'authentication' and lane = 'trusted'), 1,
 'the replay consumes no further budget');
select ok((select allowed from public.admit_passkey_challenge('authentication', 'trusted', 'receipt-two')),
 'a fresh gateway request ID is still admitted');

-- Expired receipts are pruned with bounded work instead of accumulating.
insert into public.passkey_gateway_receipt (request_id, lane, expires_at)
  values ('stale-receipt', 'trusted', clock_timestamp() - interval '1 second');
select ok((select allowed from public.admit_passkey_challenge('authentication', 'trusted', 'receipt-three')),
 'admission proceeds while a stale receipt exists');
select is((select count(*) from public.passkey_gateway_receipt where request_id = 'stale-receipt'),
 0::bigint, 'the expired receipt was pruned');

-- Expired windows reset before recording the new request.
update public.passkey_challenge_admission set used = 225,
  window_started_at = clock_timestamp() - interval '61 seconds'
  where type = 'authentication' and lane = 'untrusted';
select ok((select allowed from public.admit_passkey_challenge('authentication', 'untrusted')),
 'retry succeeds after the window expires');
select is((select used from public.passkey_challenge_admission
 where type = 'authentication' and lane = 'untrusted'), 1,
 'expired windows reset before recording the new request');

-- The single-argument call keeps working through the new defaults.
update public.passkey_challenge_admission set used = 0,
  window_started_at = clock_timestamp() where type = 'registration' and lane = 'untrusted';
select ok((select allowed from public.admit_passkey_challenge('registration')),
 'omitted lane and request ID default to untrusted admission');

reset role;
select * from finish();
rollback;
