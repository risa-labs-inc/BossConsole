-- ============================================================================
-- Tests for TOTP Secret Encryption Trigger and Safe Decrypt (v1 prefix)
-- ============================================================================

BEGIN;

-- Setup: load testing framework
SELECT plan(4);

-- Insert vault key to allow encryption to work during test
INSERT INTO vault.secrets (secret, name, description)
VALUES ('0123456789abcdef0123456789abcdef', 'master_encryption_key', 'Test key')
ON CONFLICT DO NOTHING;

-- Create a dummy user
INSERT INTO auth.users (id, email) VALUES ('00000000-0000-0000-0000-000000000001', 'test1@example.com') ON CONFLICT DO NOTHING;
INSERT INTO public.users (id, email) VALUES ('00000000-0000-0000-0000-000000000001', 'test1@example.com') ON CONFLICT DO NOTHING;

-- Create a secret
INSERT INTO public.secrets (id, user_id, website, username, password_encrypted)
VALUES ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'example.com', 'user', 'encrypted_pwd')
ON CONFLICT DO NOTHING;

-- Test 1: Inserting a plaintext string gets encrypted by the trigger and prefixed with v1:
INSERT INTO public.secret_metadata (secret_id, twofa_enabled, twofa_type, twofa_secret)
VALUES ('00000000-0000-0000-0000-000000000002', true, 'app', 'JBSWY3DPEHPK3PXP');

SELECT like(
    (SELECT twofa_secret FROM public.secret_metadata WHERE secret_id = '00000000-0000-0000-0000-000000000002'),
    'v1:%',
    'Trigger should encrypt plaintext twofa_secret and prepend v1: prefix'
);

-- Test 2: The encrypted value should decrypt back to the original string using the safe wrapper
SELECT is(
    (SELECT public.safe_decrypt_twofa_secret(twofa_secret) FROM public.secret_metadata WHERE secret_id = '00000000-0000-0000-0000-000000000002'),
    'JBSWY3DPEHPK3PXP',
    'safe_decrypt_twofa_secret should successfully decrypt the triggered v1: ciphertext'
);

-- Test 3: Trigger ignores already prefixed strings
UPDATE public.secret_metadata
SET twofa_secret = 'v1:already_encrypted_string'
WHERE secret_id = '00000000-0000-0000-0000-000000000002';

SELECT is(
    (SELECT twofa_secret FROM public.secret_metadata WHERE secret_id = '00000000-0000-0000-0000-000000000002'),
    'v1:already_encrypted_string',
    'Trigger should NOT double-encrypt strings that already start with v1:'
);

-- Test 4: safe_decrypt_twofa_secret fails safely (returns NULL) on bad ciphertext with valid prefix
SELECT is(
    public.safe_decrypt_twofa_secret('v1:corrupt_or_invalid_ciphertext_that_is_not_base64_aes'),
    NULL::text,
    'safe_decrypt_twofa_secret should return NULL when decryption of v1: string fails'
);

SELECT * FROM finish();
ROLLBACK;
