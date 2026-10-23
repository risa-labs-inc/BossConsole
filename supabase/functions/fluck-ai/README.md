# Fluck.ai portal

This entrypoint reuses `fluck-web/app.ts` with the fixed public origin `https://fluck.ai` and root base path. It rewrites only the Edge Function routing prefix before dispatching to the shared router. No request header can configure the public origin, and the existing alias-secret, session-cookie, owner-ticket, Origin and CSRF checks still apply.

The configuration override stays in this Edge Function's isolate. The existing `fluck-web` deployment continues using its original environment settings and callbacks. Supabase Edge Runtime does not support `Deno.env.set`; the entrypoint uses an explicit module configuration instead.

Deploy with `supabase functions deploy fluck-ai --project-ref pcnwqamqdnsadranufjv --no-verify-jwt --use-api`. Add `https://fluck.ai/auth` to the project's Auth redirect allowlist. Deploy the updated `redirect` function and apply `supabase/templates/email/magic-link.html` in Auth → Email Templates → Magic Link so email sign-in returns to Fluck instead of opening BOSS. Keep the callback in lockstep across config, the redirect handler and the email template. The existing `FLUCK_WEB_ALIAS_SECRET` remains shared with the authenticated Cloudflare Worker. No new secret or service-role access is needed.

Run `deno test --allow-env --config supabase/functions/fluck-ai/deno.json supabase/functions/fluck-ai/handler.test.ts` and the existing `fluck-web/tests` suite. The deployed Worker and DNS backup are in `infra/cloudflare/fluck-web-alias`. Its native-app mirror is `infrastructure/fluck-web-alias`; keep their routes and existing Apple association response synchronized when deploying. Wrangler `keep_vars = true` preserves the remote alias-secret binding.

Older BOSS agents may only allow the old portal to frame their chat. The portal already falls back to opening those chats directly. Native app links on the new domain require a native build with that domain in its associated domains.

## Web Push and seamless reconnect (shared with `fluck-web`)

The portal owns Web Push for both `https://fluck.risaboss.com` and `https://fluck.ai`, because the framed chat is cross-origin and cannot ask for notification permission. `fluck-web` and `fluck-ai` share one router, so both get the same routes: `GET /sw.js`, `GET /manifest.webmanifest`, `GET /icon-512.png`, `GET /api/push/config`, `POST /api/push/subscribe`, `POST /api/push/unsubscribe` and `POST /api/push/notify`. The page also reloads a framed chat by itself after a Fluck restart or a dropped connection (hidden second frame, swapped in on its hello).

Deploy, in this order (nothing here is automated by CI):

1. Apply `supabase/migrations/20261013100000_fluck_web_push.sql` (new tables `fluck_web_push_subscriptions` and `fluck_web_push_tickets`, and the RPCs `fluck_web_push_subscribe`, `fluck_web_push_unsubscribe`, `fluck_web_push_ticket`, `fluck_web_push_redeem`, `fluck_web_push_prune`).
2. Generate one VAPID key pair (P-256) and set these project secrets, which both functions read:
   - `FLUCK_WEB_VAPID_PUBLIC_KEY`: base64url uncompressed P-256 point (87 characters).
   - `FLUCK_WEB_VAPID_PRIVATE_KEY`: base64url 32-byte private scalar `d` (43 characters).
   - `FLUCK_WEB_VAPID_SUBJECT`: optional, defaults to `mailto:support@risalabs.ai`.

   Without the two keys `GET /api/push/config` and `POST /api/push/notify` answer 503 and the page reports push as unsupported; rotating them invalidates every stored subscription's authorisation, so users must turn notifications on again.
3. Native iPhone push and encrypted previews: apply `supabase/migrations/20261013200000_fluck_web_push_native.sql` (table `fluck_web_push_devices`; RPCs `fluck_web_push_register_native`, `fluck_web_push_unregister_native`, `fluck_web_push_native_status`, `fluck_web_push_keys`; `fluck_web_push_redeem` also returns `devices`; `fluck_web_push_prune` is now `(p_ticket, p_ids, p_device_ids)`). Then set three project secrets for the app with bundle `app.fluck.ios`; without any one of them APNs is skipped with a single log line per isolate and web push is unaffected:
   - `FLUCK_APNS_KEY_P8`: the APNs auth key (.p8) PEM text, PKCS#8 P-256.
   - `FLUCK_APNS_KEY_ID`: the key's 10-character id.
   - `FLUCK_APNS_TEAM_ID`: the Apple developer team's 10-character id.
4. Redeploy BOTH functions so the new routes exist on both hosts: `supabase functions deploy fluck-web --project-ref pcnwqamqdnsadranufjv --no-verify-jwt --use-api` and `supabase functions deploy fluck-ai --project-ref pcnwqamqdnsadranufjv --no-verify-jwt --use-api`. The alias Worker needs no change: it forwards every path.

The Fluck plugin calls the RPC `fluck_web_push_ticket(p_instance_id)` as its signed-in BOSS user, then `POST https://api.risaboss.com/functions/v1/fluck-web/api/push/notify` with `{"ticket", "message_id"}`. A 404 from that URL means the function is not deployed; a 410 `{"error":"unknown_ticket"}` means the ticket was spent or expired. The push carries only the agent name and the text "New message": no message content, names or PHI are sent to a push service or stored.

Encrypted previews: the server never reads message text. The plugin calls `fluck_web_push_keys()` as the signed-in user, which returns `[{id, kind:"web", p256dh, auth, portal_origin}, {id, kind:"apns", p256dh, auth}]`: public encryption targets, one per web subscription and per APNs device that registered keys. It encrypts the preview on the user's own Fluck to each target (RFC 8291 aes128gcm, one record) and sends `{"ticket", "message_id", "sealed": {"<target id>": "<base64url bytes>"}}` (at most 20 entries, each at most 5500 characters and 4096 decoded bytes; the body is capped at 128 KB). For a web subscription with an entry, the function POSTs exactly those bytes with `Content-Encoding: aes128gcm` and its VAPID header, with no re-encryption; without an entry it sends the generic "New message" push. For an APNs device it sends `{"aps":{"alert":{"title":<agent>,"body":"New message"},"sound":"default","thread-id":"fluck-<instance>","mutable-content":1},"message_id","url":"https://fluck.risaboss.com/?instance=<instance>"}` and adds `"sealed":"<base64url>"` only when an entry exists and the whole JSON stays within 4096 bytes; the app's notification service extension decrypts it on the device. The answer is 202 `{"sent","pruned"}`; devices APNs reports as 410, or 400 `BadDeviceToken` / `DeviceTokenNotForTopic`, are pruned. Apple and Google carry ciphertext only. Registration is plugin to database as the signed-in user (`fluck_web_push_register_native`); the portal has no native route.

Tests: `cd supabase/functions/fluck-web && deno test --allow-all` (includes the RFC 8291 section 5 vector), plus the `fluck-ai` commands above.
