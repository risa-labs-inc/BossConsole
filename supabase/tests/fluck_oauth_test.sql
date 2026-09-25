BEGIN;
SELECT no_plan();

SELECT ok(NOT has_function_privilege('anon', 'public.fluck_oauth_claim_nonce(text,timestamptz)', 'EXECUTE'), 'anonymous callers cannot claim nonces');
SELECT ok(NOT has_function_privilege('authenticated', 'public.fluck_oauth_claim_nonce(text,timestamptz)', 'EXECUTE'), 'signed-in clients cannot claim nonces');
SELECT ok(NOT has_function_privilege('anon', 'public.fluck_oauth_store_secret(uuid,text,text,text,text)', 'EXECUTE'), 'anonymous callers cannot write connector secrets');
SELECT ok(NOT has_function_privilege('authenticated', 'public.fluck_oauth_store_secret(uuid,text,text,text,text)', 'EXECUTE'), 'signed-in clients cannot write as another user');
SELECT ok(has_function_privilege('service_role', 'public.fluck_oauth_store_secret(uuid,text,text,text,text)', 'EXECUTE'), 'the service role can store verified callbacks');
SELECT ok(public.fluck_oauth_claim_nonce('oauth-test-once', now() + interval '5 minutes'), 'a fresh nonce is claimed');
SELECT ok(NOT public.fluck_oauth_claim_nonce('oauth-test-once', now() + interval '5 minutes'), 'a nonce cannot be replayed');
SELECT ok(NOT public.fluck_oauth_claim_nonce('oauth-test-expired', now()), 'expired nonces are refused');
SELECT ok(NOT public.fluck_oauth_claim_nonce('oauth-test-long', now() + interval '16 minutes'), 'excessive lifetimes are refused');

DO $$
DECLARE existing uuid;
BEGIN
    SELECT id INTO existing FROM vault.secrets WHERE name = 'master_encryption_key';
    IF existing IS NULL THEN
        PERFORM vault.create_secret('oauth-test-key-0123456789abcdef01', 'master_encryption_key', 'pgTAP only');
    ELSE
        PERFORM vault.update_secret(existing, 'oauth-test-key-0123456789abcdef01', 'master_encryption_key', 'pgTAP only');
    END IF;
END;
$$;
INSERT INTO auth.users (id, email) VALUES
    ('d1520000-0000-4000-8000-000000000001', 'oauth-owner@pgtap.test'),
    ('d1520000-0000-4000-8000-000000000002', 'oauth-other@pgtap.test');

SELECT public.fluck_oauth_store_secret('d1520000-0000-4000-8000-000000000001', 'fluck/test/google/GOOGLE_REFRESH_TOKEN', 'old@example.test', 'old-token');
SELECT public.fluck_oauth_store_secret('d1520000-0000-4000-8000-000000000002', 'fluck/test/google/GOOGLE_REFRESH_TOKEN', 'other@example.test', 'other-token');
SELECT public.fluck_oauth_store_secret('d1520000-0000-4000-8000-000000000001', 'fluck/test/google/GOOGLE_REFRESH_TOKEN', 'new@example.test', 'new-token');

SELECT is((SELECT count(*) FROM public.secrets WHERE user_id = 'd1520000-0000-4000-8000-000000000001'), 1::bigint, 'reconnection replaces the old account');
SELECT is((SELECT public.decrypt_text(password_encrypted) FROM public.secrets WHERE user_id = 'd1520000-0000-4000-8000-000000000001'), 'new-token', 'the replacement token is encrypted and readable');
SELECT is((SELECT public.decrypt_text(password_encrypted) FROM public.secrets WHERE user_id = 'd1520000-0000-4000-8000-000000000002'), 'other-token', 'another user keeps their token');
SELECT throws_ok($$SELECT public.fluck_oauth_store_secret('d1520000-0000-4000-8000-000000000001', 'example.test', 'user', 'token')$$, 'P0001', 'website must be a fluck connector key', 'ordinary vault entries cannot be replaced');

SELECT * FROM finish();
ROLLBACK;
