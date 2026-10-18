# Browser location access

Fluck uses BossConsole's shared JxBrowser engine. Website location access needs both
user consent and a working location provider. A permission grant alone cannot supply
coordinates.

## Consent behavior

When Chromium requests location permission and a provider is configured, BOSS displays
both the requesting origin and the top-level page's origin in an owned dialog with
Allow location and Block controls. Both origins are revalidated before granting.
Dismissal or expiry denies the request. Pending requests are canceled when their document navigates or their
browser closes. Browsers without an associated visible BOSS window are denied.

Repeated requests from the same frame and both origins share one pending decision.
A pending decision holds at most 64 native callbacks; further requests are
immediately denied to bound memory use.
Concurrent requests from other frames/origins are denied while that prompt is open.
After denial, dismissal, timeout, or cancellation, BOSS suppresses new location
prompts in that browser for 30 seconds. Suppressed attempts do not extend the cooldown.
This browser-wide limit prevents a page from rotating iframe origins to spam prompts.

BOSS does not write a persistent location allowlist. This is not a promise of
one-time access: Chromium controls permission caching, and `watchPosition` can
continue returning updates after an approval. A site-permission settings panel with
explicit revocation is a separate follow-up. Runtime permission lifetime must be
checked with the shipped engine before documenting stronger guarantees.

## Provider setup

Set `BOSS_BROWSER_GOOGLE_API_KEY` to an authorized Google API key before starting BOSS.
The key must belong to a project with the Geolocation API enabled and the required
billing setup. BOSS passes it to JxBrowser's `EngineOptions.googleApiKey` before
creating the engine. Changing this startup setting requires restarting BOSS.

For development, use the environment variable or the same key in the git-ignored
`local.properties` file. Runtime configuration also accepts a JVM system property
with that name. Never commit the value.

For packaged releases, maintainers can configure the repository Actions secret
`BOSS_BROWSER_GOOGLE_API_KEY`. Build, release, and Lite release workflows pass it to
`generateEmbeddedConfig`, which embeds the value as a runtime resource. A missing
secret does not break builds. Location lookup remains unavailable without provider
configuration. Requests fail without a modal dialog, and BOSS shows one passive status
message per application session explaining the missing configuration.

An embedded desktop API key is extractable from the distributed application. Treat it
as a client credential: use a dedicated project/key, restrict it to the Geolocation
API, review applicable distribution terms and supported restrictions, and configure quota/billing alerts
before enabling it in a release. Do not reuse a server credential. This change does
not provision a Google project, enable billing, or set a repository secret.

Provider configuration is optional. The implementation does not substitute mock
coordinates, silently contact a different location service, or change browser
secure-context rules. Google-backed location may involve sending network information
to Google. The consent UI identifies Google as the provider.

References:

- [JxBrowser Google API configuration](https://teamdev.com/jxbrowser/docs/guides/engine/#google-apis)
- [JxBrowser privacy practices](https://teamdev.com/jxbrowser/docs/guides/introduction/privacy/)
- [Google Geolocation API setup](https://developers.google.com/maps/documentation/geolocation/get-api-key)

## Manual validation

Use a separate development profile and an HTTPS page that calls
`navigator.geolocation.getCurrentPosition` from a user action. Do not test against an
already-running personal profile.

1. Without a configured key, repeatedly request location. Confirm that BOSS displays
   one passive status message explaining unavailable location and opens no modal.
2. With an authorized key, request location and verify the requesting site's origin in
   the dialog. Deny or dismiss it and confirm the website receives a permission error.
3. Wait at least 30 seconds after denying, then repeat and allow. Confirm that the
   website receives coordinates. A configured but invalid key, quota error, or network
   failure can still prevent location lookup.
4. Start a request, then navigate or close its tab. Confirm no stale approval survives.
5. Exercise an explicitly permitted cross-origin iframe and confirm the requesting
   origin and embedding page are both identified correctly. Insecure, sandboxed opaque,
   or policy-blocked frames must not bypass Chromium's native checks.
6. Exercise `watchPosition`, clear the watch, and reload the page. Confirm permission
   behavior and watch lifetime against the shipped Chromium version.

Callback tests cover actual native approval, denial, duplicate coalescing, origin
changes, cancellation, timeout, cooldown, and the missing-provider notice without
acquiring a real location. Successful coordinates with a production provider must be verified
separately; passing unit tests does not establish that the service is configured.
