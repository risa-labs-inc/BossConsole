-- pgTAP tests for 20261103100000_fluck_web_push_evolvemax: evolvemax.com is an accepted portal origin.
-- Run with: supabase test db
BEGIN;
SELECT plan(5);
INSERT INTO auth.users (id, email) VALUES ('e7a00000-0000-0000-0000-0000000000a1', 'evolvemax-push@example.test');

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'e7a00000-0000-0000-0000-0000000000a1', true);

SELECT lives_ok($q$ SELECT public.fluck_web_push_subscribe(
    jsonb_build_object('endpoint', 'https://fcm.googleapis.com/fcm/send/evolvemax', 'keys', jsonb_build_object('p256dh', repeat('E', 87), 'auth', repeat('F', 22))),
    'https://evolvemax.com') $q$, 'an evolvemax.com subscription is stored');
SELECT is((SELECT portal_origin FROM public.fluck_web_push_subscriptions WHERE subscription ->> 'endpoint' = 'https://fcm.googleapis.com/fcm/send/evolvemax'),
          'https://evolvemax.com', 'it keeps its portal origin');
SELECT is((SELECT count(*)::int FROM jsonb_array_elements(public.fluck_web_push_keys()) e
           WHERE e ->> 'kind' = 'web' AND e ->> 'portal_origin' = 'https://evolvemax.com'), 1, 'keys() lists it for sealing');
SELECT lives_ok($q$ SELECT public.fluck_web_push_subscribe(
    jsonb_build_object('endpoint', 'https://fcm.googleapis.com/fcm/send/fluckai', 'keys', jsonb_build_object('p256dh', repeat('E', 87), 'auth', repeat('F', 22))),
    'https://fluck.ai') $q$, 'fluck.ai is still accepted');
SELECT throws_ok($q$ SELECT public.fluck_web_push_subscribe(
    jsonb_build_object('endpoint', 'https://fcm.googleapis.com/fcm/send/other', 'keys', jsonb_build_object('p256dh', repeat('E', 87), 'auth', repeat('F', 22))),
    'https://evolvemax.com.evil.example') $q$, 'P0001', 'invalid_subscription', 'any other origin is still refused');

SELECT * FROM finish();
ROLLBACK;
