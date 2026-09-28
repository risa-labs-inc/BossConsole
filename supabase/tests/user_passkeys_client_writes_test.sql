-- 20260927210000_restrict_user_passkeys_client_writes.sql: no client role may
-- INSERT or UPDATE public.user_passkeys, on any column. Every legitimate
-- writer (enrollment, sign_count/last_used_at on assertion, rename, soft
-- delete) is the `passkey` edge function on the service-role key. The
-- auto-updatable active_user_passkeys view loses INSERT/UPDATE/DELETE for
-- client roles too, keeping SELECT as the client read path.
--
-- Client statements run under `set local role authenticated` with a JWT claim,
-- the same privileges PostgREST applies; fixtures are staged as the test owner.
-- TRUNCATE, REFERENCES and TRIGGER (also in GRANT ALL, and not subject to RLS)
-- are revoked for client roles as well.
begin;
select plan(21);

insert into auth.users (id, email) values
    ('e1a00000-0000-4000-8000-000000000001', 'passkey-owner@pgtap.test');

-- The owner's existing passkey, staged so the row exists regardless of the
-- INSERT rules under test. public_key/sign_count are the attack targets.
insert into public.user_passkeys (id, user_id, credential_id, public_key, display_name, sign_count, rp_id)
values ('e1a00000-0000-4000-8000-0000000000b1', 'e1a00000-0000-4000-8000-000000000001',
        'cred-original', 'pk-original', 'Original Key', 42, 'app.boss.test');

select set_config('request.jwt.claims',
    '{"sub":"e1a00000-0000-4000-8000-000000000001","role":"authenticated"}', true);
set local role authenticated;

-- ---- The attacks, on the verbs each was reachable through.
select throws_ok(
    $$ insert into public.user_passkeys (user_id, credential_id, public_key, display_name)
       values ('e1a00000-0000-4000-8000-000000000001', 'cred-attacker', 'pk-attacker', 'Attacker Key') $$,
    '42501', null,
    'a bearer holder cannot insert a passkey row with their own key material');
select throws_ok(
    $$ update public.user_passkeys set public_key = 'pk-attacker' where credential_id = 'cred-original' $$,
    '42501', null,
    'a bearer holder cannot replace public_key under an existing credential_id');
select throws_ok(
    $$ update public.user_passkeys set sign_count = 0 where credential_id = 'cred-original' $$,
    '42501', null,
    'a bearer holder cannot zero sign_count to defeat clone detection');
select throws_ok(
    $$ update public.user_passkeys set rp_id = 'evil.test', public_key_alg = -257
       where credential_id = 'cred-original' $$,
    '42501', null,
    'a bearer holder cannot re-pin rp_id or mis-describe the key algorithm');

-- ---- There is no ordinary client column either: rename and soft delete go
-- through the edge function, so display_name and active are closed as well.
select throws_ok(
    $$ update public.user_passkeys set display_name = 'Renamed' where credential_id = 'cred-original' $$,
    '42501', null,
    'clients cannot rename directly; the edge function owns that path');

-- ---- The view is not a back door into the same table.
select throws_ok(
    $$ update public.active_user_passkeys set credential_id = 'cred-swapped'
       where credential_id = 'cred-original' $$,
    '42501', null,
    'the auto-updatable view no longer writes the base table for clients');

-- ---- The non-row privileges from GRANT ALL are gone too. TRUNCATE ignores
-- RLS entirely, so before this migration a reachable client TRUNCATE erased
-- every user's passkeys.
select throws_ok(
    $$ truncate table public.user_passkeys $$,
    '42501', null,
    'a client cannot TRUNCATE the table (row-level security does not apply to TRUNCATE)');

-- ---- Client reads still work, on both surfaces.
select lives_ok(
    $$ select credential_id from public.user_passkeys where credential_id = 'cred-original' $$,
    'an owner can still read their own passkey row');
select is(
    (select display_name from public.active_user_passkeys where credential_id = 'cred-original'),
    'Original Key',
    'and the management listing view still serves the owner');
reset role;

-- The staged trust material is untouched: the attacker never wrote it.
select is(
    (select public_key from public.user_passkeys where credential_id = 'cred-original'),
    'pk-original',
    'public_key is unchanged after the attempts');
select is(
    (select sign_count from public.user_passkeys where credential_id = 'cred-original'),
    42::bigint,
    'and sign_count still holds the real counter');

-- ---- The service-role flows the edge function uses keep working.
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
set local role service_role;
select lives_ok(
    $$ insert into public.user_passkeys (user_id, credential_id, public_key, display_name, sign_count)
       values ('e1a00000-0000-4000-8000-000000000001', 'cred-enrolled', 'pk-enrolled', 'Enrolled Key', 1) $$,
    'service role can still enroll a passkey');
select lives_ok(
    $$ update public.user_passkeys set sign_count = 2, last_used_at = 1 where credential_id = 'cred-enrolled' $$,
    'service role can still record an assertion counter');
select lives_ok(
    $$ update public.user_passkeys set display_name = 'Renamed Key', active = false
       where credential_id = 'cred-enrolled' $$,
    'service role can still rename and soft-delete');
reset role;

-- ---- The catalog states the rule, independent of any one statement above.
select is(
    (select count(*)::int from information_schema.table_privileges
     where table_schema = 'public' and table_name = 'user_passkeys'
       and grantee in ('anon', 'authenticated') and privilege_type in ('INSERT', 'UPDATE')),
    0,
    'no client role holds a table-level INSERT or UPDATE on user_passkeys');
select ok(
    not exists (
        select 1 from pg_attribute a
        where a.attrelid = 'public.user_passkeys'::regclass and a.attnum > 0 and not a.attisdropped
          and (has_column_privilege('authenticated', 'public.user_passkeys', a.attname, 'INSERT')
            or has_column_privilege('authenticated', 'public.user_passkeys', a.attname, 'UPDATE'))),
    'authenticated holds no INSERT or UPDATE privilege on any user_passkeys column');
select ok(
    not exists (
        select 1 from pg_attribute a
        where a.attrelid = 'public.user_passkeys'::regclass and a.attnum > 0 and not a.attisdropped
          and (has_column_privilege('anon', 'public.user_passkeys', a.attname, 'INSERT')
            or has_column_privilege('anon', 'public.user_passkeys', a.attname, 'UPDATE'))),
    'anon holds no INSERT or UPDATE privilege on any user_passkeys column');
select ok(
    has_table_privilege('service_role', 'public.user_passkeys', 'INSERT')
    and has_table_privilege('service_role', 'public.user_passkeys', 'UPDATE'),
    'service_role keeps table-level INSERT and UPDATE');
select ok(
    not has_table_privilege('authenticated', 'public.user_passkeys', 'TRUNCATE')
    and not has_table_privilege('authenticated', 'public.user_passkeys', 'REFERENCES')
    and not has_table_privilege('authenticated', 'public.user_passkeys', 'TRIGGER')
    and not has_table_privilege('anon', 'public.user_passkeys', 'TRUNCATE')
    and not has_table_privilege('anon', 'public.user_passkeys', 'REFERENCES')
    and not has_table_privilege('anon', 'public.user_passkeys', 'TRIGGER'),
    'no client role holds TRUNCATE, REFERENCES or TRIGGER on user_passkeys');
select ok(
    not has_table_privilege('authenticated', 'public.active_user_passkeys', 'INSERT')
    and not has_table_privilege('authenticated', 'public.active_user_passkeys', 'UPDATE')
    and not has_table_privilege('authenticated', 'public.active_user_passkeys', 'DELETE')
    and not has_table_privilege('anon', 'public.active_user_passkeys', 'UPDATE')
    and has_table_privilege('authenticated', 'public.active_user_passkeys', 'SELECT'),
    'the view keeps client SELECT but loses client INSERT, UPDATE and DELETE');
select ok(
    not has_table_privilege('authenticated', 'public.active_user_passkeys', 'TRIGGER')
    and not has_table_privilege('authenticated', 'public.active_user_passkeys', 'REFERENCES')
    and not has_table_privilege('anon', 'public.active_user_passkeys', 'TRIGGER')
    and not has_table_privilege('anon', 'public.active_user_passkeys', 'REFERENCES'),
    'the view also loses client TRIGGER and REFERENCES (both valid on views)');

select * from finish();
rollback;
