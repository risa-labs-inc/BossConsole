-- pgTAP tests for resilient secret listing
-- (migration 20260914000000_resilient_secret_listing.sql).
--
-- get_user_secrets / search_user_secrets / get_user_secrets_with_shared read the
-- password with a bare decrypt_text inside a set-returning query. decrypt_text
-- RAISES on an undecryptable value, and one raised row aborts the whole query -
-- so a single corrupt row (wrong key mid-rotation, storage corruption, a v2 MAC
-- mismatch) made every one of the caller's secrets fail to load. The migration
-- routes the password through try_decrypt_text (NULL on failure) and the
-- recovery codes through the existing safe_decrypt_recovery_codes ([] on
-- failure), so one bad row surfaces as one blanked field while the rest load.

begin;
select plan(11);

select vault.create_secret(
    'a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90',
    'master_encryption_key',
    'pgTAP only'
);

insert into auth.users (id, email) values
    ('cafe0000-0000-4000-8000-000000000001', 'listing-owner@pgtap.test');

-- A readable secret, a secret whose password ciphertext is corrupt, and a secret
-- whose recovery codes are corrupt. The password rows are inserted directly (as
-- the test superuser, bypassing RLS) so we control the exact stored bytes.
insert into public.secrets (id, user_id, website, username, password_encrypted) values
    ('cafe0000-0000-4000-8000-0000000000a1', 'cafe0000-0000-4000-8000-000000000001', 'good.example',  'u1', public.encrypt_text('GoodPassword1')),
    ('cafe0000-0000-4000-8000-0000000000a2', 'cafe0000-0000-4000-8000-000000000001', 'broken.example','u2', '!!!not-valid-base64!!!'),
    ('cafe0000-0000-4000-8000-0000000000a3', 'cafe0000-0000-4000-8000-000000000001', 'codes.example', 'u3', public.encrypt_text('GoodPassword3'));
insert into public.secret_metadata (secret_id, twofa_enabled, twofa_type, recovery_codes_encrypted) values
    ('cafe0000-0000-4000-8000-0000000000a3', true, 'app', '###corrupt-codes###');

-- 1-2. try_decrypt_text is decrypt_text with NULL instead of a raise.
select is(public.try_decrypt_text(public.encrypt_text('round-trip')), 'round-trip',
    'try_decrypt_text returns the plaintext for a good value');
select is(public.try_decrypt_text('!!!not-valid-base64!!!'), NULL::text,
    'try_decrypt_text returns NULL instead of raising on a corrupt value');

-- 3. Baseline: a bare decrypt_text over the same set still aborts, which is the
--    behaviour the RPCs used to inherit.
select throws_ok(
    $$ SELECT public.decrypt_text(password_encrypted) FROM public.secrets $$,
    NULL, NULL,
    'bare decrypt_text over the set still aborts on the corrupt row');

set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"cafe0000-0000-4000-8000-000000000001","role":"authenticated"}', true);

-- 4. get_user_secrets returns EVERY row rather than aborting on the bad one.
select is((select count(*) from public.get_user_secrets(50, 0)), 3::bigint,
    'get_user_secrets returns all rows despite a corrupt one');
-- 5-6. The good password decrypts; the corrupt one is a NULL password, not an error.
select is((select password from public.get_user_secrets(50, 0) where website = 'good.example'),
    'GoodPassword1', 'the readable password still decrypts');
select is((select password from public.get_user_secrets(50, 0) where website = 'broken.example'),
    NULL::text, 'the corrupt password is blanked, not fatal');
-- 7. Corrupt recovery codes degrade to [] through safe_decrypt_recovery_codes.
select is((select metadata->>'recovery_codes' from public.get_user_secrets(50, 0) where website = 'codes.example'),
    '[]', 'corrupt recovery codes degrade to an empty array');

-- 8-9. The same resilience holds for the other two listing RPCs.
select is((select count(*) from public.search_user_secrets('.example', 50, 0)), 3::bigint,
    'search_user_secrets returns all matching rows despite a corrupt one');
select is((select count(*) from public.get_user_secrets_with_shared(50, 0)), 3::bigint,
    'get_user_secrets_with_shared returns all rows despite a corrupt one');

reset role;

-- 10-11. try_decrypt_text stays server-side, like decrypt_text (20260909120000).
select ok(not has_function_privilege('anon', 'public.try_decrypt_text(text)', 'EXECUTE'),
    'anon cannot execute try_decrypt_text');
select ok(not has_function_privilege('authenticated', 'public.try_decrypt_text(text)', 'EXECUTE'),
    'authenticated cannot execute try_decrypt_text');

select * from finish();
rollback;
