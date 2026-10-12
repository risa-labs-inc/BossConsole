# Watch Boss TURN credentials

This function exchanges a 60-second, one-use database ticket for Cloudflare
Realtime TURN credentials. Fluck obtains the ticket through the existing
authenticated `SupabaseDataProvider.rpc` path. No BOSS session token or
long-lived TURN key reaches Fluck or the browser.

## Provider contract

Cloudflare documents
`POST https://rtc.live.cloudflare.com/v1/turn/keys/{TURN_KEY_ID}/credentials/generate-ice-servers`
with bearer authentication, `{"ttl":86400}`, and an HTTP 201 response containing
`iceServers`. See
<https://developers.cloudflare.com/realtime/turn/generate-credentials/>.

The function requests a 24-hour credential and returns its UTC expiry to the
client as `expires_at`. The client must retain that value for reconnect and
future `setConfiguration()` refresh decisions. This exceeds expected Watch Boss
viewing sessions and matches Cloudflare's documented example. Credential expiry
does not deliberately close an existing allocation. A reconnect or new accepted
`rtc_open` obtains a new ticket and credential. A connection that remains open
beyond 24 hours and then needs an ICE restart must reopen or use a future client
refresh path based on `RTCPeerConnection.setConfiguration()`; indefinite
reconnectability is not claimed.

## Provisioning and deployment

1. In Cloudflare Realtime TURN, create a TURN key and an API token permitted to
   generate credentials. This may incur provider charges. Do not provision it as
   part of repository validation.
2. Store the returned identifiers only in the Supabase function secret store:

   ```sh
   supabase secrets set --project-ref pcnwqamqdnsadranufjv \
     WATCH_BOSS_TURN_KEY_ID='<turn-key-id>' \
     WATCH_BOSS_TURN_KEY_API_TOKEN='<turn-key-api-token>'
   ```

3. Apply `20261012120000_watch_boss_turn_credentials.sql` through the normal
   reviewed migration process, then deploy manually:

   ```sh
   supabase functions deploy watch-boss-turn-credentials \
     --project-ref pcnwqamqdnsadranufjv --no-verify-jwt
   ```

The client endpoint is
`https://api.risaboss.com/functions/v1/watch-boss-turn-credentials`. The
function has `verify_jwt=false` because its only authorization is the opaque,
bound, one-use ticket. Provider credentials, tickets, TURN usernames, and TURN
credentials must never be logged.

Run `deno task check` and `deno task test` in this directory. Database
authorization and quota coverage lives in
`supabase/tests/watch_boss_turn_credentials_test.sql` and requires an isolated
local Supabase/PostgreSQL test database.
