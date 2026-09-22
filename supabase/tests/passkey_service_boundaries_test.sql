-- Client write boundaries for the passkey credential and challenge tables
-- (20260922000000_passkey_service_boundaries), with the lane-aware admission
-- state from 20260920000000. Exercises actual database roles; fixtures and
-- counter changes are rolled back.
begin;
select no_plan();

insert into auth.users (id, email) values
 ('f1454500-0000-0000-0000-000000000001', 'passkey-owner@example.test'),
 ('f1454500-0000-0000-0000-000000000002', 'passkey-other@example.test');

-- No client DML grant on any credential or challenge object, table or column.
select ok(not has_table_privilege(role_name, object_name, privilege),
  role_name || ' lacks ' || privilege || ' on ' || object_name)
from (values ('anon'), ('authenticated')) roles(role_name)
cross join (values ('public.user_passkeys'), ('public.active_user_passkeys'),
 ('public.passkey_challenges'), ('public.passkey_challenge_admission')) objects(object_name)
cross join (values ('INSERT'), ('UPDATE'), ('DELETE'), ('TRUNCATE'), ('REFERENCES'), ('TRIGGER')) permissions(privilege);
select ok(not has_any_column_privilege(role_name, object_name, privilege),
  role_name || ' has no column ' || privilege || ' grants on ' || object_name)
from (values ('anon'), ('authenticated')) roles(role_name)
cross join (values ('public.user_passkeys'), ('public.active_user_passkeys'),
 ('public.passkey_challenges'), ('public.passkey_challenge_admission')) objects(object_name)
cross join (values ('INSERT'), ('UPDATE'), ('REFERENCES')) permissions(privilege);

-- Client EXECUTE is gone on the privileged passkey routines.
select ok(not has_function_privilege(role_name, function_name, 'EXECUTE'),
  role_name || ' cannot execute ' || function_name)
from (values ('anon'), ('authenticated')) roles(role_name)
cross join (values ('public.find_user_by_email(text)'), ('public.get_session_status(text)'),
 ('public.clean_expired_passkey_challenges()'),
 ('public.admit_passkey_challenge(public.challenge_type, text, text)'),
 ('public.create_mobile_registration_session(text,text,text)')) functions(function_name);

-- The per-user write policies are dropped; the read policies stay.
select is((select count(*) from pg_policies
 where tablename in ('user_passkeys', 'passkey_challenges') and cmd = 'ALL'),
 0::bigint, 'no residual all-command policy on the credential or challenge tables');
select ok(has_table_privilege('authenticated', 'public.user_passkeys', 'SELECT'),
 'authenticated retains owner-scoped credential reads');
select ok(has_table_privilege('authenticated', 'public.active_user_passkeys', 'SELECT'),
 'authenticated retains the security_invoker view read');

-- The new admission state has fixed (type, lane) cardinality.
select is((select count(*) from public.passkey_challenge_admission), 3::bigint,
 'admission state has fixed cardinality independent of caller-supplied identities');

-- Service role keeps every ceremony path.
set local role service_role;
select lives_ok($sql$insert into public.user_passkeys (id,user_id,credential_id,public_key,display_name)
 values ('f1454500-0000-0000-0000-000000000011','f1454500-0000-0000-0000-000000000001',
 'fixture-credential-one','fixture-public-key','Verified ceremony fixture'),
 ('f1454500-0000-0000-0000-000000000012','f1454500-0000-0000-0000-000000000002',
 'fixture-credential-two','fixture-public-key','Other user fixture')$sql$,
 'service-role ceremony retains enrollment writes');
select lives_ok($sql$update public.user_passkeys set sign_count = 1, display_name = 'Renamed', active = true
 where id = 'f1454500-0000-0000-0000-000000000011'$sql$,
 'service role retains verified counter updates and management');
select lives_ok($sql$insert into public.passkey_challenges (challenge,type,expires_at)
 values ('fixture-expired-challenge','authentication',clock_timestamp() - interval '1 second')$sql$,
 'service role can store a challenge');
select lives_ok($sql$select public.clean_expired_passkey_challenges()$sql$,
 'service-role scheduler retains bounded cleanup');
select is((select count(*) from public.passkey_challenges where challenge='fixture-expired-challenge'),
 0::bigint, 'expired challenge was removed');
select is((select id from public.find_user_by_email('passkey-owner@example.test')),
 'f1454500-0000-0000-0000-000000000001'::uuid, 'service-role lookup retains its account result');
select lives_ok($sql$insert into public.passkey_challenges(challenge,type,session_id,expires_at)
 values ('fixture-registration','registration','fixture-session',clock_timestamp()+interval '5 minutes')$sql$,
 'service-role registration can store a polling session');
select is((select session_id from public.get_session_status('fixture-session')), 'fixture-session',
 'qualified session lookup preserves registration polling');
reset role;

-- Anonymous callers cannot read or write credential material.
set local role anon;
select throws_ok($sql$select * from public.user_passkeys$sql$, '42501',
 'permission denied for table user_passkeys', 'anonymous credential reads fail');
select throws_ok($sql$insert into public.passkey_challenges (challenge,type,expires_at)
 values ('anonymous-reservation','authentication',clock_timestamp()+interval '5 minutes')$sql$, '42501',
 'permission denied for table passkey_challenges', 'anonymous null-user challenge insertion fails');
select throws_ok($sql$select public.clean_expired_passkey_challenges()$sql$, '42501',
 'permission denied for function clean_expired_passkey_challenges', 'anonymous cleanup fails');
select throws_ok($sql$select public.find_user_by_email('passkey-owner@example.test')$sql$, '42501',
 'permission denied for function find_user_by_email', 'anonymous lookup cannot bypass Edge admission');
select throws_ok($sql$select public.admit_passkey_challenge('authentication')$sql$, '42501',
 'permission denied for function admit_passkey_challenge', 'anonymous caller cannot directly consume shared budget');
reset role;

-- Signed-in callers: owner reads stay, credential and challenge writes do not.
set local role authenticated;
select set_config('request.jwt.claim.sub', 'f1454500-0000-0000-0000-000000000001', true);
select is((select count(*) from public.user_passkeys), 1::bigint, 'owner retains SELECT of own credentials only');
select is((select count(*) from public.active_user_passkeys), 1::bigint, 'invoker view preserves owner-scoped SELECT');
select throws_ok($sql$insert into public.user_passkeys(user_id,credential_id,public_key,display_name)
 values ('f1454500-0000-0000-0000-000000000001','forged-credential','unverified-key','Forged')$sql$, '42501',
 'permission denied for table user_passkeys', 'owner cannot enroll an unverified key directly');
select throws_ok($sql$update public.user_passkeys set public_key='replacement-key'
 where id='f1454500-0000-0000-0000-000000000011'$sql$, '42501',
 'permission denied for table user_passkeys', 'owner cannot replace credential material directly');
select throws_ok($sql$delete from public.user_passkeys$sql$, '42501',
 'permission denied for table user_passkeys', 'owner must use the validated management route for deletion');
select throws_ok($sql$update public.active_user_passkeys set display_name='forged'$sql$, '42501',
 'permission denied for view active_user_passkeys', 'view cannot bypass management mutation restrictions');
select throws_ok($sql$truncate public.user_passkeys$sql$, '42501',
 'permission denied for table user_passkeys', 'table-wide truncation is refused independently of RLS');
select throws_ok($sql$insert into public.passkey_challenges(user_id,challenge,type,expires_at)
 values ('f1454500-0000-0000-0000-000000000001','owner-forged-challenge','registration',clock_timestamp()+interval '5 minutes')$sql$,
 '42501', 'permission denied for table passkey_challenges', 'owner cannot bypass challenge admission');
select set_config('request.jwt.claim.sub', 'f1454500-0000-0000-0000-000000000002', true);
select is((select count(*) from public.user_passkeys where id='f1454500-0000-0000-0000-000000000011'),
 0::bigint, 'another user cannot read the owner credential');
select throws_ok($sql$update public.user_passkeys set public_key='another-key'
 where id='f1454500-0000-0000-0000-000000000011'$sql$, '42501',
 'permission denied for table user_passkeys', 'another user cannot mutate the owner credential');
reset role;

-- The lane-aware admission budget still admits and refuses as before.
set local role service_role;
update public.passkey_challenge_admission set used=224, window_started_at=clock_timestamp()
 where type='authentication' and lane='untrusted';
select ok((select allowed from public.admit_passkey_challenge('authentication', 'untrusted')),
 'the last untrusted authentication slot is admitted');
insert into public.passkey_challenges(challenge,type,expires_at)
 values ('refused-admission-cleanup-fixture','authentication',clock_timestamp()+interval '5 minutes');
select ok(not (select allowed from public.admit_passkey_challenge('authentication', 'untrusted')),
 'the 226th untrusted request is refused');
select is((select count(*) from public.passkey_challenges where challenge='refused-admission-cleanup-fixture'),
 1::bigint, 'budget refusal does not perform cleanup work');
select ok((select retry_after_seconds between 1 and 60
 from public.admit_passkey_challenge('authentication', 'untrusted')),
 'refusal includes a bounded retry interval');
reset role;
select * from finish();
rollback;
