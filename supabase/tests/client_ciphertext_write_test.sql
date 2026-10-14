-- 20260918000000_revoke_client_ciphertext_writes.sql: a signed-in user must not
-- be able to import a ciphertext into a row they own and read it back decrypted
-- through the listing RPCs. Every legitimate write path keeps working.
--
-- Client statements run under `set local role authenticated` with a JWT claim,
-- the same privileges PostgREST applies; fixtures and leaks are staged as the
-- test owner between them.
begin;
select plan(18);

do $fixture$
declare existing uuid;
begin
    select id into existing from vault.secrets where name = 'master_encryption_key';
    if existing is null then
        perform vault.create_secret(encode(extensions.gen_random_bytes(32), 'hex'), 'master_encryption_key', 'pgTAP only');
    else
        perform vault.update_secret(existing, encode(extensions.gen_random_bytes(32), 'hex'), 'master_encryption_key', 'pgTAP only');
    end if;
end;
$fixture$;

insert into auth.users (id, email) values
    ('d1810000-0000-4000-8000-000000000001', 'victim@pgtap.test'),
    ('d1810000-0000-4000-8000-000000000002', 'attacker@pgtap.test');

-- ---- The victim stores a secret with recovery codes through the RPC.
select set_config('request.jwt.claims',
    '{"sub":"d1810000-0000-4000-8000-000000000001","role":"authenticated"}', true);
set local role authenticated;
select lives_ok(
    $$ select public.create_secret('bank.example', 'victim', 'victims-real-password',
           p_twofa_enabled => true, p_twofa_type => 'app',
           p_recovery_codes => array['victim-recovery-1']) $$,
    'create_secret still works for a signed-in user');
reset role;

-- A copy of the victim's ciphertext escapes (a dump, a backup, an export).
create temp table leaked as
select s.password_encrypted, m.recovery_codes_encrypted
from public.secrets s join public.secret_metadata m on m.secret_id = s.id
where s.username = 'victim';
grant select on leaked to authenticated;

-- ---- The attacker owns an ordinary secret of their own.
select set_config('request.jwt.claims',
    '{"sub":"d1810000-0000-4000-8000-000000000002","role":"authenticated"}', true);
set local role authenticated;
select lives_ok(
    $$ select public.create_secret('mine.example', 'attacker', 'attackers-own',
           p_twofa_enabled => true, p_twofa_type => 'app',
           p_recovery_codes => array['attacker-recovery-1']) $$,
    'the attacker creates a secret of their own through the RPC');

-- ---- The import is refused on both ciphertext columns and on both verbs.
select throws_ok(
    $$ update public.secrets set password_encrypted = (select password_encrypted from leaked)
       where username = 'attacker' $$,
    '42501', null,
    'a client cannot write a ciphertext into password_encrypted of a row it owns');
select throws_ok(
    $$ update public.secret_metadata m set recovery_codes_encrypted = (select recovery_codes_encrypted from leaked)
       from public.secrets s where s.id = m.secret_id and s.username = 'attacker' $$,
    '42501', null,
    'a client cannot write a ciphertext into recovery_codes_encrypted of a row it owns');
select throws_ok(
    $$ insert into public.secrets (user_id, website, username, password_encrypted)
       values ('d1810000-0000-4000-8000-000000000002', 'imported.example', 'attacker',
               (select password_encrypted from leaked)) $$,
    '42501', null,
    'a client cannot insert a row carrying a chosen ciphertext');

-- ---- So the listing returns the attacker's own values, never the victim's.
select is(
    (select password from public.get_user_secrets(50, 0) where website = 'mine.example'),
    'attackers-own',
    'get_user_secrets returns the attacker''s own password, not the imported one');
select is(
    (select metadata -> 'recovery_codes' ->> 0 from public.get_user_secrets(50, 0) where website = 'mine.example'),
    'attacker-recovery-1',
    'and the attacker''s own recovery codes');
select is(
    (select count(*)::int from public.get_user_secrets(50, 0) where password = 'victims-real-password'),
    0,
    'no listing row carries the victim''s password');

-- ---- Every other client write path still works.
select lives_ok(
    $$ update public.secrets set notes = 'edited directly', website = 'mine2.example'
       where username = 'attacker' $$,
    'a client can still update the non-ciphertext columns of its own row');
select is(
    (select notes from public.secrets where username = 'attacker'),
    'edited directly',
    'and the edit landed');
select lives_ok(
    $$ select public.update_secret(
           (select id from public.secrets where username = 'attacker'),
           'mine2.example', 'attacker', 'rotated-by-rpc',
           p_twofa_enabled => true, p_twofa_type => 'app',
           p_recovery_codes => array['attacker-recovery-2']) $$,
    'update_secret still re-encrypts a new password');
select is(
    (select password from public.get_user_secrets(50, 0) where username = 'attacker'),
    'rotated-by-rpc',
    'and the listing decrypts the password update_secret wrote');
select lives_ok(
    $$ update public.secret_metadata m set twofa_secret = 'JBSWY3DPEHPK3PXP'
       from public.secrets s where s.id = m.secret_id and s.username = 'attacker' $$,
    'twofa_secret stays client-writable as plaintext input for its trigger');
reset role;
select matches(
    (select m.twofa_secret from public.secret_metadata m join public.secrets s on s.id = m.secret_id
     where s.username = 'attacker'),
    '^v1:',
    'and the trigger stored it as an envelope');

-- ---- The catalog states the rule, independent of any one statement above.
select is(
    (select count(*)::int from information_schema.table_privileges
     where table_schema = 'public' and table_name in ('secrets', 'secret_metadata')
       and grantee in ('anon', 'authenticated') and privilege_type in ('INSERT', 'UPDATE')),
    0,
    'no client role holds a table-level INSERT or UPDATE on the secret tables');
select is(
    (select count(*)::int from information_schema.column_privileges
     where table_schema = 'public'
       and ((table_name = 'secrets' and column_name = 'password_encrypted')
         or (table_name = 'secret_metadata' and column_name = 'recovery_codes_encrypted'))
       and grantee in ('anon', 'authenticated') and privilege_type in ('INSERT', 'UPDATE')),
    0,
    'no client role can write either ciphertext column');
select ok(
    not has_column_privilege('authenticated', 'public.secrets', 'password_encrypted', 'UPDATE')
    and not has_column_privilege('authenticated', 'public.secrets', 'password_encrypted', 'INSERT')
    and not has_column_privilege('anon', 'public.secrets', 'password_encrypted', 'UPDATE')
    and not has_column_privilege('anon', 'public.secrets', 'password_encrypted', 'INSERT')
    and not has_column_privilege(
        'authenticated', 'public.secret_metadata', 'recovery_codes_encrypted', 'UPDATE')
    and not has_column_privilege(
        'authenticated', 'public.secret_metadata', 'recovery_codes_encrypted', 'INSERT')
    and not has_column_privilege('anon', 'public.secret_metadata', 'recovery_codes_encrypted', 'UPDATE')
    and not has_column_privilege('anon', 'public.secret_metadata', 'recovery_codes_encrypted', 'INSERT'),
    'no client role holds effective INSERT or UPDATE on either ciphertext column');
select ok(
    has_column_privilege('authenticated', 'public.secrets', 'website', 'UPDATE')
    and has_column_privilege('authenticated', 'public.secret_metadata', 'twofa_secret', 'UPDATE'),
    'authenticated keeps UPDATE on the ordinary columns');

select * from finish();
rollback;
