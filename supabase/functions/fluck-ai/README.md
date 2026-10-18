# Fluck.ai portal

This entrypoint reuses `fluck-web/app.ts` with the fixed public origin `https://fluck.ai` and root base path. It rewrites only the Edge Function routing prefix before dispatching to the shared router. No request header can configure the public origin, and the existing alias-secret, session-cookie, owner-ticket, Origin and CSRF checks still apply.

The configuration override stays in this Edge Function's isolate. The existing `fluck-web` deployment continues using its original environment settings and callbacks. Supabase Edge Runtime does not support `Deno.env.set`; the entrypoint uses an explicit module configuration instead.

Changes to shared portal code, including sign-out, must deploy both `fluck-web` and `fluck-ai`. Each function bundles its imports separately; deploying one does not update the other hostname. After deploying, verify both `https://fluck.risaboss.com/` and `https://fluck.ai/`.

Deploy with `supabase functions deploy fluck-ai --project-ref pcnwqamqdnsadranufjv --no-verify-jwt --use-api`. Add `https://fluck.ai/auth` to the project's Auth redirect allowlist. Deploy the updated `redirect` function and apply `supabase/templates/email/magic-link.html` in Auth → Email Templates → Magic Link so email sign-in returns to Fluck instead of opening BOSS. Keep the callback in lockstep across config, the redirect handler and the email template. The existing `FLUCK_WEB_ALIAS_SECRET` remains shared with the authenticated Cloudflare Worker. No new secret or service-role access is needed.

Run `deno test --allow-env --config supabase/functions/fluck-ai/deno.json supabase/functions/fluck-ai/handler.test.ts` and the existing `fluck-web/tests` suite. The deployed Worker and DNS backup are in `infra/cloudflare/fluck-web-alias`. Its native-app mirror is `infrastructure/fluck-web-alias`; keep their routes and existing Apple association response synchronized when deploying. Wrangler `keep_vars = true` preserves the remote alias-secret binding.

Older BOSS agents may only allow the old portal to frame their chat. The portal already falls back to opening those chats directly. Native app links on the new domain require a native build with that domain in its associated domains.
