-- pgTAP tests for the secret-encryption construction
-- (migration 20260913000000_authenticated_secret_encryption.sql).
--
-- encrypt_text / decrypt_text secure every stored password, recovery-code set
-- and (through the TOTP trigger) 2FA seed. The old 3-arg extensions.encrypt()
-- form encrypted under an all-zero IV, so ciphertext was a pure function of the
-- plaintext under the one global key: a plain GROUP BY over password_encrypted
-- named every set of users sharing a password, and any signed-in user was an
-- encryption oracle for their own rows. There was no pgTAP coverage asserting a
-- property of the ciphertext at all - only privileges and RLS. This file pins
-- the two regressions the issue calls out (two calls to encrypt_text with the
-- same plaintext must differ, and decrypt_text must still read a legacy row),
-- the integrity guarantee the same change adds, and that the replacement did
-- not re-open the client execute that 20260909120000 revoked.

begin;
select plan(10);

-- A valid documented key: 32 bytes as 64 hex characters (`openssl rand -hex 32`).
select vault.create_secret(
    'a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90',
    'master_encryption_key',
    'pgTAP only'
);

-- 1. The core regression: encryption is non-deterministic. Fails on the old
--    zero-IV construction (both calls returned identical base64), passes now.
select isnt(
    public.encrypt_text('CorrectHorse!23'),
    public.encrypt_text('CorrectHorse!23'),
    'two encrypt_text calls with the same plaintext differ'
);

-- 2. New envelopes are versioned so decrypt_text can tell them from legacy rows.
select matches(
    public.encrypt_text('probe'),
    '^v2:',
    'encrypt_text emits a versioned v2 envelope'
);

-- 3. Round trip through the new construction.
select is(
    public.decrypt_text(public.encrypt_text('s3cr3t value')),
    's3cr3t value',
    'v2 envelope round trips'
);

-- 4. Backward compatibility: a row written by the OLD construction (raw base64
--    of the zero-IV AES output, keyed on the ASCII bytes of the hex key) still
--    decrypts, so existing data survives the upgrade and the backfill can read
--    every row it has not yet rewritten.
select is(
    public.decrypt_text(
        pg_catalog.encode(
            extensions.encrypt(
                'legacy-plaintext'::bytea,
                public.get_encryption_key()::bytea,
                'aes'::text
            ),
            'base64'::text
        )
    ),
    'legacy-plaintext',
    'decrypt_text still reads a legacy (pre-v2) row'
);

-- 5. NULL is preserved on both sides (optional columns).
select is(public.encrypt_text(NULL), NULL::text, 'encrypt_text(NULL) is NULL');
select is(public.decrypt_text(NULL), NULL::text, 'decrypt_text(NULL) is NULL');

-- 6. Integrity: a flipped ciphertext byte is rejected as an authentication
--    failure (22023), not surfaced later as a UTF-8 decode error or ignored.
select throws_ok(
    $$
    WITH e AS (
        SELECT pg_catalog.decode(pg_catalog.substring(public.encrypt_text('tamper me'), 4), 'base64') AS r
    )
    SELECT public.decrypt_text(
        'v2:' || pg_catalog.encode(
            set_byte(r, pg_catalog.length(r) - 1, get_byte(r, pg_catalog.length(r) - 1) # 1),
            'base64'
        )
    )
    FROM e
    $$,
    '22023',
    NULL,
    'a flipped ciphertext byte fails integrity verification'
);

-- 7. A truncated envelope (too short to hold an IV and MAC) is rejected rather
--    than read out of bounds.
select throws_ok(
    $$ SELECT public.decrypt_text('v2:' || pg_catalog.encode('\x0011'::bytea, 'base64')) $$,
    '22023',
    NULL,
    'a truncated v2 envelope is rejected'
);

-- 8-9. CREATE OR REPLACE must not re-open the client execute that
--      20260909120000_close_client_crypto_access.sql revoked.
select ok(
    NOT has_function_privilege('authenticated', 'public.encrypt_text(text)', 'EXECUTE'),
    'authenticated still cannot execute encrypt_text after the replacement'
);
select ok(
    NOT has_function_privilege('anon', 'public.decrypt_text(text)', 'EXECUTE'),
    'anon still cannot execute decrypt_text after the replacement'
);

select * from finish();
rollback;
