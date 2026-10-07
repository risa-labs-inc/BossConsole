BEGIN;
SELECT no_plan();

SELECT ok(NOT has_function_privilege('anon', 'public.fluck_oauth_claim_nonce(text,timestamptz)', 'EXECUTE'), 'anonymous callers cannot claim nonces');
SELECT ok(NOT has_function_privilege('authenticated', 'public.fluck_oauth_claim_nonce(text,timestamptz)', 'EXECUTE'), 'signed-in clients cannot claim nonces');
SELECT ok(NOT has_function_privilege('anon', 'public.fluck_oauth_store_secret(uuid,text,text,text,text)', 'EXECUTE'), 'anonymous callers cannot write connector secrets');
SELECT ok(NOT has_function_privilege('authenticated', 'public.fluck_oauth_store_secret(uuid,text,text,text,text)', 'EXECUTE'), 'signed-in clients cannot write as another user');
SELECT ok(has_function_privilege('service_role', 'public.fluck_oauth_store_secret(uuid,text,text,text,text)', 'EXECUTE'), 'the service role can store verified callbacks');
SELECT ok(public.fluck_oauth_claim_nonce('oauth-test-once', now() + interval '5 minutes'), 'a fresh nonce is claimed');
SELECT ok(NOT public.fluck_oauth_claim_nonce('oauth-test-once', now() + interval '5 minutes'), 'a nonce cannot be replayed');
SELECT throws_ok($$SELECT public.fluck_oauth_claim_nonce('oauth-test-expired', now() - interval '61 seconds')$$, '22023', 'invalid OAuth nonce or expiry', 'expired nonces are not misreported as replay');
SELECT throws_ok($$SELECT public.fluck_oauth_claim_nonce('oauth-test-long', now() + interval '961 seconds')$$, '22023', 'invalid OAuth nonce or expiry', 'excessive lifetimes are refused');
SELECT ok(public.fluck_oauth_claim_nonce('oauth-test-db-behind', now() + interval '930 seconds'), 'a fresh maximum-lifetime state tolerates a database clock 30 seconds behind');
SELECT ok(public.fluck_oauth_claim_nonce('oauth-test-db-ahead', now() - interval '30 seconds'), 'an edge-valid state tolerates a database clock 30 seconds ahead');
SELECT ok(NOT public.fluck_oauth_claim_nonce('oauth-test-db-ahead', now() - interval '30 seconds'), 'cleanup must retain spent nonces throughout the skew window');
SELECT throws_ok($$SELECT public.fluck_oauth_claim_nonce(NULL, now() + interval '5 minutes')$$, '22023', 'invalid OAuth nonce or expiry', 'null nonce fails closed');
SELECT throws_ok($$SELECT public.fluck_oauth_claim_nonce(repeat('x',257), now() + interval '5 minutes')$$, '22023', 'invalid OAuth nonce or expiry', 'nonce storage is bounded');

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

SELECT ok(NOT has_table_privilege('anon', 'public.fluck_oauth_grants', 'SELECT'), 'anonymous callers cannot read grant bindings');
SELECT ok(NOT has_table_privilege('authenticated', 'public.fluck_oauth_grants', 'SELECT'), 'signed-in clients cannot read grant bindings');
SELECT ok(NOT has_function_privilege('authenticated', 'public.fluck_oauth_bind_grant(text,uuid)', 'EXECUTE'), 'signed-in clients cannot bind a token to themselves');
SELECT ok(NOT has_function_privilege('anon', 'public.fluck_oauth_grant_owner(text)', 'EXECUTE'), 'anonymous callers cannot look up grant owners');
SELECT ok(NOT has_function_privilege('authenticated', 'public.fluck_oauth_forget_grant(text)', 'EXECUTE'), 'signed-in clients cannot drop bindings');
SELECT ok(has_function_privilege('service_role', 'public.fluck_oauth_bind_grant(text,uuid)', 'EXECUTE'), 'the service role can bind grants');
SELECT ok((SELECT relrowsecurity FROM pg_class WHERE oid = 'public.fluck_oauth_grants'::regclass), 'grant bindings have RLS enabled');
SELECT is((SELECT count(*)::int FROM pg_policies WHERE schemaname = 'public' AND tablename = 'fluck_oauth_grants'), 0, 'grant bindings have no client policy');
SELECT ok(public.fluck_oauth_claim_nonce('oauth-test-edge-cap', now() + interval '900 seconds'), 'the edge''s 900 second state cap is inside the claim bound, so a fresh link is never a replay');
SELECT ok(public.fluck_oauth_bind_grant(repeat('a', 64), 'd1520000-0000-4000-8000-000000000001'), 'a grant is bound');
SELECT is(public.fluck_oauth_grant_owner(repeat('a', 64)), 'd1520000-0000-4000-8000-000000000001'::uuid, 'the binding names its owner');
SELECT ok(public.fluck_oauth_bind_grant(repeat('a', 64), 'd1520000-0000-4000-8000-000000000001'), 'a retried bind is idempotent');
SELECT is(public.fluck_oauth_grant_owner(repeat('b', 64)), NULL::uuid, 'an unknown token has no owner');
SELECT ok(NOT public.fluck_oauth_bind_grant('not-a-hash', 'd1520000-0000-4000-8000-000000000001'), 'a malformed hash is refused');
SELECT throws_ok($$INSERT INTO public.fluck_oauth_grants (token_sha256, user_id) VALUES (repeat('A', 64), 'd1520000-0000-4000-8000-000000000001')$$, '23514', NULL, 'only lowercase hex is stored');
SELECT public.fluck_oauth_forget_grant(repeat('a', 64));
SELECT is(public.fluck_oauth_grant_owner(repeat('a', 64)), NULL::uuid, 'a forgotten grant is unbound');

SELECT * FROM finish();
ROLLBACK;
