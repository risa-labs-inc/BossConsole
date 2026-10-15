# Browser location access

Fluck uses BossConsole's shared JxBrowser engine. Website location access needs both
user consent and a working location provider. A permission grant alone cannot supply
coordinates.

## Consent behavior

When Chromium requests location permission, BOSS displays the requesting origin in
an owned dialog with Allow location and Block controls. Dismissal or expiry denies
the request. Pending requests are canceled when their document navigates or their
browser closes. Browsers without an associated visible BOSS window are denied.

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
configuration, and the location request UI explains this.

An embedded desktop API key is extractable from the distributed application. Treat it
as a client credential: use a dedicated project/key, restrict its permitted APIs,
review applicable distribution terms and supported restrictions, and monitor quota
and billing. Do not reuse a server credential. This change does not provision a Google
project, enable billing, or set a repository secret.

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

1. Without a configured key, request location. Confirm that BOSS explains that location
   is unavailable and does not offer a misleading Allow action.
2. With an authorized key, request location and verify the requesting site's origin in
   the dialog. Deny or dismiss it and confirm the website receives a permission error.
3. Repeat and allow. Confirm that the website receives coordinates. A configured but
   invalid key, quota error, or network failure can still prevent location lookup.
4. Start a request, then navigate or close its tab. Confirm no stale approval survives.
5. Exercise an explicitly permitted cross-origin iframe and confirm the requesting
   origin is identified correctly. Insecure or policy-blocked frames must not bypass
   Chromium's checks.
6. Exercise `watchPosition`, clear the watch, and reload the page. Confirm permission
   behavior and watch lifetime against the shipped Chromium version.

Unit tests cover the permission decision and request lifecycle without acquiring a
real location. Successful coordinates with a production provider must be verified
separately; passing unit tests does not establish that the service is configured.
