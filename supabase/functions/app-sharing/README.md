# Application sharing backend (boss-app-share/1)

This is additive to terminal sharing. No terminal registry, ticket, relay, or
preference contract changes. Capture activation and the selected windows remain
explicit local host decisions. Account settings cannot start capture.

See the [isolated test and rollout checklist](../../../scripts/app-sharing/ROLLOUT.md)
for exact local commands, deployment order, scheduler setup and rollback.

## Configuration and deployment gates

This change does not deploy anything. Apply
`20260928000000_application_sharing.sql` before deploying capable
clients/functions. `app-sharing` uses `verify_jwt=false` because its public
`/viewer` route is a redirect; every POST independently verifies the Bearer
token with GoTrue `/auth/v1/user` before calling a fixed database RPC. Never
rely on decoding a JWT without verification.

Supabase function environment:

- `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`: platform-provided. The service
  key stays inside the Edge function.
- `APP_SHARING_SFU_APP_ID`, `APP_SHARING_SFU_SECRET`: a separate Cloudflare
  Realtime SFU application and its server-only app secret. Provisioning needs
  account Calls: Edit permission; Workers-only Wrangler OAuth is insufficient. Configure in the
  function secret store, never JavaScript, a URL, logs, or source. Missing
  credentials returns `sfu_not_configured` without a provider request.
- `LIVE_SESSIONS_PUBLIC_BASE_URL` and `LIVE_SESSIONS_PUBLIC_BASE_PATH`: existing
  live-session custom-domain configuration, also used by `/viewer` redirects.
  The public URL must be HTTPS. GoTrue must allow that live-session magic-link
  callback.

Deploy the updated `live-sessions` and `user-settings` functions with
`app-sharing`. Synchronize hosted browser assets using
`node scripts/sync-app-viewer-assets.mjs`; CI's `--check` detects stale
generated assets. The source of truth remains
`composeApp/src/desktopMain/resources/app-sharing/`.

The SFU credential grants this function provider access; callers cannot supply a
provider URL, SFU application ID, session ID or remote track locator. Every
media operation resolves the caller's current owner, session generation,
admitted peer and explicitly shared window in PostgreSQL. Mutations serialize
per peer/window with a bounded operation lease. Publication is host-peer-only;
subscription uses the server-recorded host track. Control replies require a
current control-capable peer and controller lease, checked before and after the
SFU call.

## JSON API

POST `/functions/v1/app-sharing` with an authenticated Bearer JWT and a JSON
object containing `action`. Errors are `{error:code}` and do not include
provider responses or secrets.

- `register`:
  `{session_id,generation,device_id,instance_id,name,windows:[{id,title}],viewer_url,key_epoch,host_public_key}`.
  UUID identifiers, 1–16 explicit windows, name <=120 characters, window ID
  ASCII <=128. `viewer_url` is HTTPS with a 32-byte base64url root key in
  `#k=...`; host Ed25519 public key is base64url SPKI. Returns descriptor with
  `host_peer_id`. Private signing key never enters this API. A new generation
  must have a new key epoch; same-generation register conflicts. The owning
  registry is trusted with the root key, matching existing account terminal-link
  trust.
- `list`: returns `{sessions:[...]}` for this owner, live within 90 seconds,
  without host peer identifiers. Includes `session_id` and `id` aliases. It
  never exposes another account's sessions.
- `heartbeat`: `{session_id,generation}` extends the host descriptor by 90
  seconds. Expired generations cannot resurrect.
- `stop`: same scope, withdraws registry/grants and queues provider teardown.
  Host must immediately stop capture/input locally, irrespective of network
  response.
- `admit`: `{session_id,generation,device_id,role:"view"|"control"}`. Returns a
  60-second one-use application ticket and owner-authorized media key
  descriptor. A control-role peer is only _eligible_ to acquire a controller
  lease; it initially watches. Same-account automatic admission/control default
  true. False returns `approval_required`; guest/manual grants are deliberately
  unsupported and denied.
- `consume`: same scope/device/role plus `ticket`, returns
  `{peer_id,role,expires_at}`. Tickets are SHA-256 stored, audience
  `boss-app-share/1`, bound to owner/session/generation/device/role. Wrong
  bindings, expiry and replay fail. Terminal tickets cannot be reused.
- `peerHeartbeat`: `{session_id,generation,peer_id}` extends an unexpired peer
  by 10 minutes if automatic admission is still enabled. Clients send every
  minute and stop on rejection.
- `revoke`: `{session_id,generation,host_peer_id,target_peer_id}` validates the
  target peer, retires the entire generation and returns `must_stop:true`.
  Provider teardown is durable/retryable. Host must stop immediately and use a
  fresh generation/media key for the next share; other current viewers also
  disconnect.
- `preferencesGet` / `preferencesSet`: separate
  `{auto_admit,auto_control,revision}` account preferences. New accounts/default
  old rows use true/true/revision0. Set requires both booleans and the expected
  revision; stale CAS returns HTTP409. Terminal preference revisions remain
  independent.

All media/data actions include `{session_id,generation,peer_id,window_id}`. One
SFU PeerConnection per peer/window initially:

- `mediaCreate` -> `{peer_id,window_id,ice_servers:[]}`; server binds the
  generated SFU session privately.
- `mediaPublish`: host only, `{mid,session_description:{type:"offer",sdp}}` ->
  `{session_description,mid,track:{window_id,track_name}}`.
- `mediaSubscribe`: viewer only -> `{session_description,mid,track}`. Never
  accepts a remote provider locator.
- `mediaRenegotiate`: `{session_description:{type:"answer",sdp}}` ->
  `{accepted:true}`.
- `mediaClose`: force-closes only this peer/window's recorded media and
  DataChannels.
- `dataEstablish`: negotiates reserved `server-events`, returns
  `{session_description,channel_id}`; answer with mediaRenegotiate.
- `dataPublish`: host-only reliable ordered channel named `controls`, returns
  `{channel_id}`.
- `dataSubscribe`: `{can_reply:false}` view subscription, or
  `{can_reply:true,lease_id}` only for the current controller. Existing
  subscription updates rather than duplicates. Returns `{channel_id,can_reply}`.
- `dataRevoke`: withdraws this viewer's reply path.

Controller lease API: `controlAcquire`, `controlRenew`, `controlRelease`,
`controlPoll`. Scope is session/generation/peer; renew/release also specify
lease_id. Acquire requires control-role admission and creates one 30-second
lease for the instance, returning
`{lease_id,peer_id,expires_at,control_secret}`. Renew retains key/ID and extends
only a live owned lease. Host `controlPoll` uses host_peer_id and returns
`{lease:...|null}`. It transports lease metadata, not high-frequency input.
Input uses encrypted persistent SFU DataChannels. Host verifies lease expiry,
ID, peer, sequence, window and geometry on every decoded command and releases
held keys/buttons on revoke, expiry, disconnect, or local takeover. SFU
`canReply` is supplementary routing, not the final input authorization.

## Browser authentication

`GET app-sharing/viewer?session=UUID` redirects to the configured live-session
origin `/app-viewer/?session=UUID`. It does not accept an arbitrary redirect
target. Browser bootstrap reads the session and key from an authenticated owner
registry, not the forwarded fragment.

Existing live-sessions magic-link login retains HttpOnly access/refresh cookies.
`/api/app-sharing-bootstrap` verifies/refreshes the cookie and returns a random
CSRF nonce also stored as an HttpOnly `__Host-` cookie. `/api/app-sharing`
requires that nonce in a custom header plus exact canonical Origin; it forwards
only viewer actions to the fixed app-sharing endpoint using the cookie JWT
server-side. No token is put in a query, generated script, or JavaScript
storage. CSRF nonce reuse across viewer tabs avoids invalidating another live
tab. Browser routes never acquire a service-role key. Host-publish/register/stop
actions are excluded from this browser bridge.

## Cleanup and rollout limits

Deploy `app-sharing-maintenance` with a distinct random
`APP_SHARING_MAINTENANCE_KEY` (at least 32 characters), plus the same
`APP_SHARING_SFU_APP_ID` / `APP_SHARING_SFU_SECRET` as app-sharing. Schedule
`scripts/app-sharing-maintenance.mjs` once per minute from a trusted scheduler,
with `APP_SHARING_MAINTENANCE_URL` set to the fixed deployed HTTPS function URL
and the key supplied through secret storage. The caller sends an HMAC over the
exact timestamp/nonce body; the database rejects replay. Do not expose the
scheduler key or service-role key to desktop/browser clients. Deployment and
scheduler activation have not been performed.

Each invocation reaps at most 100 expired records and claims at most eight
provider cleanup jobs. Claimed failures back off from one minute to one hour;
other due jobs remain eligible. Closures run concurrently with bounded provider
timeouts. The outbox retains partial failures and accepts already-absent
resources only when the provider confirms the exact expected IDs. Owner cleanup
and stop/register/revoke also attempt at most eight due jobs.

Explicit peer revoke, admission preference disablement, or an expired viewer
that still owns SFU media retires the whole generation. The host stops capture
when heartbeat/controlPoll fails. A fresh share requires a new generation and
key epoch; provider closure retry never authorizes continued publication. This
conservative initial behavior disconnects other viewers too. A normally closed
viewer releases its peer after its final window, so its later admission expiry
does not retire healthy viewers. Empty unused admissions expire without retiring
media. Auto-control disablement removes the lease without stopping viewing.
Metadata reaping alone is not proof of remote SFU disconnection.

Admission is bounded to32 live peers/session,32 unspent tickets/session,32 live
host sessions/account,16 explicitly shared windows/session. These are safety
bounds, not capacity claims. No hosted SFU/capture
permission/cross-platform/media E2EE interoperability validation has occurred
merely because unit tests pass. An SFU application, published custom-domain
assets, real browser/native integration, lock/logout/revocation tests and load
validation are rollout prerequisites. No guest support is advertised.

## Validation

```
cd supabase/functions/app-sharing && deno check *.ts && deno lint && deno test --allow-env
cd supabase/tests/app-sharing && npm ci && npm test
cd supabase/functions/live-sessions && deno check app.ts && deno lint && deno test --allow-env
cd supabase/functions/user-settings && deno check app.ts && deno lint && deno test --allow-env
node scripts/sync-app-viewer-assets.mjs --check
```

Provider schemas were checked against Cloudflare's
[connection patterns](https://developers.cloudflare.com/realtime/sfu/get-started/connection-patterns/)
and
[DataChannels](https://developers.cloudflare.com/realtime/sfu/features/datachannels/)
documentation. Provider request fixtures are not a live provider conformance
test.

## Publication demand

`mediaDemand` requires the current session/generation and exact host peer. It
returns only `windows: [{window_id, viewers}]`, including zero for every
registered window with no active viewer media session. Counts come from live
owner-authorized nonpublisher peers after `mediaCreate`; no peer IDs, SFU
locators, or keys are returned. Expired media peers retire the generation
according to the same health rule as heartbeat. The host may poll once per
second, keep each negotiated publisher/cipher alive, and submit native frames
only while its window count is positive. Closing the final viewer media row
removes demand. This does not permit guests or dormant-window discovery beyond
the explicitly registered descriptors.
