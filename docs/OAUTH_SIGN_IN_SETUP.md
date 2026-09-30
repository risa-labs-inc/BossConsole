# Google and Apple sign-in

The login screen offers **Continue with Google** and **Continue with Apple** beside the email
magic link and passkeys. Both are Supabase Auth OAuth providers; BOSS never sees a Google or
Apple credential, only the Supabase session that comes back.

## How the desktop flow works

1. `OAuthSignInService.start` writes a fresh PKCE verifier into Auth's encrypted
   code-verifier cache (`~/.boss/supabase`), then opens
   `<SUPABASE_URL>/auth/v1/authorize?provider=google|apple&redirect_to=boss://auth/callback&code_challenge=...`
   in the system browser.
2. The provider returns to Supabase (`https://api.risaboss.com/auth/v1/callback`), which
   redirects to `boss://auth/callback?code=...`, or `?error=...` on failure.
3. The OS hands the link to BOSS. `AuthDeepLinks.parse` reads it as `OAuthCallback`, and
   `OAuthSignInService.complete` exchanges the code with `exchangeCodeForSession`. The existing
   session collector in `CoreAuthService` takes it from there.

Points worth knowing before changing it:

- **No localhost callback server.** supabase-kt's own `signInWith(Google)` on desktop starts a
  ktor server, which the host build excludes on purpose (`KtorServerAbsentFromHostTest`).
- **PKCE is per call, not global.** `flowType` stays at its default. Under a global
  `FlowType.PKCE` the magic-link send would also write a verifier into the same single-slot
  cache and could clobber a sign-in in progress.
- **A callback nobody started is ignored.** `boss://` is registered with the OS, so any page can
  open one. The service acts only while a sign-in it started is waiting (10 minutes), and the
  code is useless without the verifier, which never leaves the machine.
- **A failed callback does not end the sign-in.** For the same reason, a provider error or a code
  that does not exchange shows a notice on the waiting screen and the sign-in stays open, so a
  forged link cannot cancel the one the user started. Only a successful exchange, Cancel, a new
  start or the 10-minute limit ends it; the waiting screen checks the limit while it is shown.
- **A forged code in flight cannot swallow the real one.** A code that arrives while another is
  being exchanged is kept, and tried if the one in flight fails. One sign-in may start at most
  10 exchanges; past that the waiting screen asks the user to cancel and start again.
- **Cancel and expiry clear the stored verifier.** A failed exchange keeps it, so the user's own
  callback can still exchange after a forged one fails.
- **Cancel wins over an exchange in flight.** The code is exchanged without saving the session
  (`exchangeCodeForSession(code, saveSession = false)`), and the session is imported only if the
  same sign-in is still waiting when the exchange returns. A second callback during an exchange
  is ignored.
- **GoTrue repeats an error in the query and the fragment.** The parser accepts the same error in
  both, refuses two different ones, and reads the description from the section it took the error
  from.
- **The system browser, not JxBrowser.** Google refuses sign-in inside embedded web views.
- **Apple opens in Safari on macOS**, whatever the default browser is (`preferredBrowserCommand`).
  Only Safari offers the Mac's own Apple Account with Touch ID on Apple's page. The native Sign in
  with Apple sheet is not an option: Apple limits the `com.apple.developer.applesignin`
  entitlement to Mac App Store apps and never includes it in Developer ID provisioning profiles
  (checked 2026-09-29 against a freshly generated profile for `ai.rever.boss`). If Safari cannot
  be opened, the default browser is used. Google always uses the default browser.
- **Linux** now registers `boss://` at startup (`LinuxProtocolHandler`, a hidden
  `boss-url-handler.desktop`), and only when nothing else holds the scheme. The waiting screen
  also accepts a pasted `boss://auth/callback` link for a machine where the hand-off fails.
- **Accounts link by email.** Supabase links a Google or Apple identity to an existing user with
  the same verified email, so a magic-link user who picks Google lands in the same account,
  with the same roles and passkeys. An Apple user who hides their email gets a
  `privaterelay.appleid.com` address and therefore a separate account, and joins no
  domain-based organisation.

## cli.risaboss.com (Live Sessions page)

The `live-sessions` edge function offers the same two buttons. They are plain links to
`/api/oauth/{google|apple}`; the function does the whole PKCE exchange server-side, so the page
keeps its no-third-party-script CSP and no token or verifier ever reaches page script:

1. `/api/oauth/:provider` puts a fresh verifier in a 10-minute HttpOnly `boss_live_pkce` cookie
   and 302s to `<auth URL>/auth/v1/authorize` with `redirect_to=<LIVE_SESSIONS_PUBLIC_BASE_URL>/auth`
   (`https://cli.risaboss.com/auth`, already in the redirect allow-list).
2. The provider returns to `/auth?code=`; the function exchanges it at
   `/auth/v1/token?grant_type=pkce` with the cookie's verifier, sets the usual session cookies and
   302s to the page. Failures come back as `?oauth_error=cancelled|expired|failed|rate_limited`.
3. The return deliberately skips the cross-site check (it starts on Google's or Apple's site);
   the verifier cookie is what makes a planted code useless to other sites. It is a `__Secure-`
   cookie, not `__Host-` (which requires `Path=/`), so a compromised sibling subdomain could
   still set one; the session cookies share that exposure.
4. `/auth` is also the magic link's landing, and GoTrue reports a spent or expired link as
   `?error=access_denied&error_code=otp_expired`. An error there counts as a Google or Apple
   return only when the verifier cookie is present; otherwise the page shows GoTrue's own
   description, as it always did. With the cookie, `otp_expired` and a missing or expired flow
   state read as "expired", `access_denied` as "cancelled".
5. A start on any host other than the canonical one is first redirected there, because the
   verifier cookie has to live on the host the provider returns to. A return carrying both a
   code and an error is treated as a failure. Returns have their own rate limit (`oauth-return:`,
   20 per 5 minutes), apart from session establishment.
6. The page drops the URL fragment along with `?oauth_error=`: GoTrue repeats its error in the
   fragment, a browser keeps a fragment across a redirect, and the page would otherwise replace
   its fixed notice with the provider's text.

Unlike the page's magic link (`create_user: false`), a first Google or Apple sign-in here creates
the BOSS account, the same as the desktop apps.

Set `LIVE_SESSIONS_AUTH_PUBLIC_URL=https://api.risaboss.com` on the function so the authorize hop
uses the custom domain (it falls back to `SUPABASE_URL`, which also works on the hosted platform).
Locally set it to `http://127.0.0.1:54321`, because the function's own `SUPABASE_URL` is not
reachable from a browser there.

## Provider setup (production)

Project `pcnwqamqdnsadranufjv`. Nothing below is committed to the repo.

### Supabase

- Dashboard -> Auth -> URL Configuration -> Redirect URLs: add `boss://auth/callback`.
- Dashboard -> Auth -> Providers -> Google and Apple: enable each and paste the values below.

### Google

1. Google Cloud Console -> APIs & Services -> OAuth consent screen: app name "BOSS", support
   email, logo, privacy policy and terms URLs; scopes `openid`, `email`, `profile` only.
   Publish the app (external) and complete verification.
2. Credentials -> Create OAuth client ID -> **Web application** (not Desktop: Supabase performs
   the exchange).
3. Authorized redirect URIs: `https://api.risaboss.com/auth/v1/callback` (the custom domain,
   which is the callback Supabase advertises) and
   `https://pcnwqamqdnsadranufjv.supabase.co/auth/v1/callback`.
   Configured 2026-09-28: GCP project `boss-455616`, Web client "BOSS Supabase Auth". The app
   is External and was published to production the same day. Brand verification passed on
   2026-09-29, so the consent screen shows "BOSS" and its logo. The logo is the BossTerm
   icon: a plain "BOSS" wordmark was rejected as not uniquely identifying the brand. The
   consent screen links home https://www.risaboss.com, privacy
   https://www.risaboss.com/privacy/ and terms https://www.risaboss.com/terms/. Those pages
   live in the BOSSConsole-Website repo and are served at risaboss.com by the
   `risaboss-proxy` Worker in the RISA Labs, Inc Cloudflare account. risaboss.com and
   risalabs.ai are both verified in Search Console under shivang@risalabs.ai; keep the
   `google-site-verification` TXT record on risaboss.com or that verification lapses.
   Changing the logo, name or links sends the app back through brand verification, and the
   Console only draws its "Verify branding" control when the window is wide.
4. Paste the client ID and secret into the Supabase Google provider.

### Apple

1. Apple Developer -> Identifiers: the App ID `ai.rever.boss` with **Sign in with Apple**
   enabled.
2. Identifiers -> Services IDs: create one (for example `ai.rever.boss.signin`). This is the
   Supabase **Client ID**. Configure Sign in with Apple on it: primary App ID above, domain
   `api.risaboss.com`, return URL `https://api.risaboss.com/auth/v1/callback`.
3. Keys: create a key with Sign in with Apple, download the `.p8` once, note the Key ID and the
   Team ID.
4. Generate the client secret JWT from the `.p8` (Supabase's Apple provider page has a
   generator) and paste it into the Supabase Apple provider.

Configured 2026-09-28: team `7X4CJM22GN`, primary App ID `ai.rever.boss` (Sign in with Apple
enabled), Services ID `ai.rever.boss.signin` (domains `api.risaboss.com` and
`pcnwqamqdnsadranufjv.supabase.co`, return URLs on both), key "BOSS Sign in with Apple",
Key ID `84AS6PR56Q`. **The current client secret expires 2027-03-28.**

To regenerate it, sign an ES256 JWT with the `.p8`: header `{"alg":"ES256","kid":"<Key ID>"}`,
claims `iss` = Team ID, `iat` = now, `exp` = now + at most 15777000 seconds,
`aud` = `https://appleid.apple.com`, `sub` = the Services ID. Paste it into the Supabase
Apple provider's Secret Key field.

**The Apple client secret expires after at most six months.** When it lapses, every Apple
sign-in fails at the exchange. Regenerate it from the same `.p8` before the expiry date and
record the next date where the team tracks renewals. Store the `.p8` in the team's secret
manager, never in the repo.

## Local testing

`supabase/config.toml` carries both providers disabled, so `supabase start` needs no
credentials. To test against the local stack, create a second Google web client whose redirect
URI is `http://127.0.0.1:54321/auth/v1/callback`, export
`SUPABASE_AUTH_EXTERNAL_GOOGLE_CLIENT_ID` / `SUPABASE_AUTH_EXTERNAL_GOOGLE_SECRET` (Apple:
`SUPABASE_AUTH_EXTERNAL_APPLE_CLIENT_ID` / `SUPABASE_AUTH_EXTERNAL_APPLE_SECRET`), set
`enabled = true` for the provider and restart the stack. Apple rejects `http` return URLs, so
Apple is tested against a hosted project.

## Compliance notes

Google and Apple act only as identity providers: they learn that a user signed in to BOSS and
receive no application data. Scopes are the minimum (`openid email profile`, Apple's
`email name`). Authorization codes, verifiers and tokens are never logged; the callback's
`toString()` redacts the code.
