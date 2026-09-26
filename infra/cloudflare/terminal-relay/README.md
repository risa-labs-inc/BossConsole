# Terminal relay (opt-in debug rollout)

This worktree contains the relay, native/browser client adapters and account settings
backend. Relay is disabled by default; existing BossTerm/BossConsole sessions keep their
current transport. Enable it explicitly for staged debug testing after deploying the
backend prerequisites below.

One Durable Object owns each publishing app's room. The host submits a pane frame once;
the object fans out its encrypted bytes to admitted subscribers. A `hello` frame carries a
single-use host/account ticket, never a query parameter. Guests have no ticket and wait
for the host's authenticated handshake and approval. The `account` ticket role is
restricted to the room owner's own additional devices. A signed-in non-owner uses the
guest approval flow, just like another link holder. Tickets alone never grant output.

Routing supports live, batched full output, complete screen previews, and hidden panes.
The relay reports aggregate interests to the host. Batch queues preserve delta order;
preview queues retain the latest complete screen and deliver a trailing update. Queues
are bounded per viewer and room. Delivery acknowledgments cap unacknowledged frames;
live subscriptions wait for a host snapshot boundary before receiving deltas;
a stalled viewer closes with 1013 and must reconnect for a fresh snapshot.

Hibernation attachments hold admission, subscriptions, sequence numbers and outstanding
byte credits only. Pending timers and their payload queues are volatile and may be lost
on eviction. Restored live/batch subscriptions wait for a freshly requested host snapshot
before receiving more deltas. No terminal payload is written to durable storage. Alarms expire unauthenticated connections and room leases.
This follows Cloudflare's [WebSocket hibernation API](https://developers.cloudflare.com/durable-objects/best-practices/websockets/).

## Protocol boundary

- `/v1/rooms/<uuid>` upgrades to WebSocket. `hello`: `{op:"hello",v:1,room,ticket?}`.
- `signal` relays pairwise key exchange and private control envelopes by peer ID.
- Only the host can `grant` pane scopes, `revoke`, or publish `output`/`snapshot`.
- A viewer sends `subscribe` with pane, mode, fps (1–30), and `ack` with delivery number.
- Aggregate interests include output FPS, so a host with only batched viewers coalesces
  before publishing; a focused viewer upgrades publication to the live cadence.
- Large private messages use bounded fragments and `peerCredit` acknowledgments; image
  transfer remains per viewer while terminal text is published once per pane.
- Server `frames` envelopes contain a monotonic `delivery` and ordered `messages` array.
- Output includes room, pane, epoch, representation, sequence, ciphertext and host signature.
  The relay does not interpret crypto fields. Receivers must authenticate all metadata.
- Native/browser crypto implementations use HKDF-SHA256, per-representation AES-256-GCM
  keys, and Ed25519 host signatures. Keys/public signing identity travel only through the
  authenticated pairwise channel. A fresh pane epoch/key is mandatory on revocation.
- Live sequence gaps require a snapshot boundary before resuming. Complete previews may
  skip sequence numbers. Inputs are pairwise authenticated, never replayed on reconnect.
- Payload chunks must fit the 1 MiB wire limit and 512 KiB plaintext crypto limit.

## Local validation

```sh
npm ci
npm run check
npm test
npm run test:integration
npm run bench
cd ../../../supabase/functions/user-settings
deno test --config deno.json app_test.ts
```

The database test creates an isolated PGlite PostgreSQL instance with pgcrypto and applies
the three migrations. It verifies owner isolation, optimistic concurrency, identity guards,
service-only impersonation, one-time handoffs and room tickets. This does not replace the
full Supabase migration suite. The separate integration test runs the bundled Worker
in Miniflare and covers socket admission, guest grants, snapshots, fanout, and ticket replay.
The benchmark covers in-process routing for 10/50/100 panes and 1/3/10 viewers; it does
not measure network latency, encryption, rendering, or production capacity.

## Encrypted loopback load check

`BOSSTERM_SOURCE_DIR=/absolute/path/to/BossTerm npm run bench:wire` starts the real
Worker/DO locally, uses real loopback WebSockets, signs/encrypts each host publication,
and verifies/decrypts with the shipped browser receiver. The paired BossTerm source path
is required so the check cannot silently benchmark a duplicate crypto implementation.

The recorded [development-machine result](test/results/loopback-2026-09-25.json) covers
20 cycles at 10/50/100 panes and 1/3/10 viewers, with 1 KiB of text per publication.
All nine cases delivered every frame. At 100 panes the host sent 2,000 publications /
3,422,500 bytes for all three viewer counts; 10 viewers received 20,000 publications.
Local p95 delivery including signature verification/decryption was 80.7 ms in that largest
case. The driver paces below the actual relay rate limit. These are local load checks,
not Internet, GPU rendering, geographic routing or production capacity measurements.

## Deployment and production rollout

Apply these additive migrations in order:

1. `20260927000000_user_terminal_preferences.sql`
2. `20260927010000_terminal_relay_tickets.sql`
3. `20260927020000_terminal_relay_cleanup.sql`
4. `20260927030000_terminal_preferences_conflict_status.sql` (stale saves return HTTP 409)

Deploy `user-settings`, `live-sessions` and `relay-admission` explicitly. Settings requires
`USER_SETTINGS_SESSION_SECRET` (at least 32 random characters), optional
`USER_SETTINGS_SESSION_SECRET_PREV`, and `USER_SETTINGS_PUBLIC_URL` on the custom functions
domain. Preserve an existing session secret unless intentionally rotating it.

Set a random `RELAY_ADMISSION_KEY` of at least 32 characters in both the Edge environment and
the selected Worker. The Worker signs the exact request body with HMAC-SHA256; the Edge
function validates the signature, bounded body, canonical room/token shape, and invokes only
`consume_terminal_relay_ticket`. The Worker credential cannot query tables or call arbitrary
RPCs. The Supabase service-role credential stays inside the Edge function environment.
Deploy the admission function and configure its key **before** deploying the updated Worker.
Remove any old `SUPABASE_SERVICE_ROLE_KEY` Worker secret after switching successfully.

The default `wrangler deploy` target is `boss-terminal-relay-debug`. Production is an explicit,
separate Worker/room namespace:

```bash
npx wrangler secret put RELAY_ADMISSION_KEY --env production
npx wrangler deploy --env production
```

Both use `workers.dev` endpoints and the configured shared Supabase backend; the Worker name
is not a database isolation boundary. No custom production domain or client default is
changed. Never embed either admission or database keys in a desktop/browser client.

After the cleanup migration, schedule
`SELECT public.cleanup_expired_terminal_relay_records(500)` every minute with `pg_cron`
or an equivalent trusted database scheduler. The explicit, idempotent operator script is
`operations/schedule-cleanup.sql`; it enables the Supabase-supported `pg_cron` extension
and creates/updates only the named `terminal-relay-expired-records` job. The service-only function locks and deletes
bounded batches of expired tickets and handoffs, and at most 100 rooms expired for at least
five minutes. Active and recently expired rooms are preserved. Room UUIDs are not permanently
reserved after deletion; hosts generate fresh random UUIDs and the E2E share keys remain the
trust boundary for old links. Monitor cleanup job failures/backlog and admission failures.

Roll out in this order: backend CI/review and migrations, admission Edge/key, debug Worker
verification, production Worker, then an opt-in client cohort. Release the BossTerm library
before publishing a plugin that pins it. Verify the **published Maven artifact**, including
its rendering/font overrides, without a paired source substitution.

Keep relay disabled by default until deployed native/browser checks cover reconnect,
revocation, settings synchronization, slow viewers, and direct-mode backward compatibility.
Rollback a client cohort by removing its explicit relay opt-in and restarting; direct sharing
remains the default. Keep additive database migrations in place. If reverting a Worker
version predating the admission gateway, its old credential requirements also return; prefer
fix-forward or disable the cohort rather than restoring a broad database credential.

### Settings handoff boundary

The app opens a five-minute, single-use bearer handoff in a query parameter. The handler
strips it with a redirect, uses `Referrer-Policy: no-referrer`, and rejects cross-site Fetch
Metadata. Query values can still appear in edge/CDN access logs: redact query strings for
this route. Clients without Fetch Metadata retain compatibility but cannot prevent an
attacker from persuading a browser to open the attacker's own settings handoff (login CSRF).
That would select the attacker's preferences session, not grant access to the victim's
account. Keep this limitation explicit during debug; a nonce-bound or POST handoff is a
prerequisite for expanding this page to sensitive account settings.

Malformed host protocol messages remain fail-closed: invalid frames may indicate corrupted
sequence or authorization state, so the relay closes the room rather than silently dropping
an ordered frame. Compatible additions must use versioned/additive fields and companion
contract tests. Identical subscriptions are intentional no-ops, not keepalive acknowledgments.

## Debug client configuration

Both the standalone app and terminal plugin keep relay off by default. To opt in, set
`BOSSTERM_RELAY_ENABLED=true` and `BOSSTERM_RELAY_URL=wss://<debug-worker-origin>` in
that app's environment (or JVM properties `bossterm.relay.enabled` and
`bossterm.relay.url`). No production endpoint is assumed. BossConsole still uses its
existing local properties for its own configuration.

A signed-in publishing app opens one room/socket for all shares. Only a ready relay is
advertised, using additive `relay_v`, `relay`, and `room` fields alongside the existing
`#k` fragment. Old clients ignore those fields and retain their existing direct transport.
New native clients opt in locally and require the advertised origin to match their own
configuration; browser clients use the origin embedded by their host in the viewer shell
and its CSP. A selected relay connection does not silently switch transports on failure.

The native and browser adapters reuse host approval, roles and pairwise encryption. Pane
keys and stable snapshot boundaries travel over that private lane; signed group output
is encrypted once per pane publication. Revocation rotates keys. Snapshot delivery clears
older queued batches for that pane. Snapshot capture runs between complete emulator
instructions and holds subsequent output until the private snapshot is queued.

Native subscriptions follow active tabs and split focus and use account preferences.
Browser subscriptions follow the rendered tab, splits-as-tabs and document visibility;
account preferences arrive from the authenticated live-sessions parent on load, return
and a 60-second timer. The parent pins messages to the viewer origin, and the viewer accepts
only its parent on the known live-sessions origins. Browser guests default to full-output
batch at 4 Hz. Native render backlog and
browser delivery queues are bounded, and browser credit waits for xterm write completion.

## Verified locally (2026-09-25)

- TypeScript check; 19 router/database tests; real Worker integration test.
- 6 user-settings and 32 live-sessions Edge-function tests.
- 81 targeted BossTerm tests, including native/browser crypto, graphics ordering and
  credited large images, legacy links/protocols, and a real host + Worker + native-viewer
  scenario covering role/input isolation, hidden resume, key rotation and reconnect.
- 205 terminal-plugin tests and a paired-source `buildPluginJar` build.
- Nine encrypted loopback load cases, as recorded above.

Terminal text is encrypted and published once per pane update. Images use a separate,
credited, encrypted per-viewer channel to preserve existing graphics behavior. Group
text/repaint markers order those images; bounded queues recover each viewer independently.
Preview images are requested only for the received preview and coalesced while an image
is in flight. Text-only previews need no graphics request.

## Debug rollout checks

- Exercise terminal images in both native and browser viewers, including large images,
  preview mode and a slow viewer while another viewer continues receiving live output.
- Exercise real host + relay + native/browser clients together for reconnect, logout,
  simultaneous revocation/scope changes, and targeted file/voice traffic. Current tests
  cover local protocol components and the loopback Worker, not the whole deployed product.
- Validate screen rendering and real network behavior during debug testing; loopback
  encryption/WebSocket fan-out has passed all nine 10/50/100 pane × 1/3/10 viewer cases.
- Apply the additive backend changes to debug and install the paired debug plugin only
  after these checks. The first two migrations, settings/live-sessions functions and the original debug Worker
  were deployed on 2026-09-26; the paired debug plugin was installed. The revised admission
  gateway and cleanup migration still require deployment. This is not full end-to-end sign-off.

Release the updated BossTerm library before publishing the terminal plugin, then update
the plugin dependency to that released version. Local paired-source builds do not require
a BossConsole desktop release; its existing host auth/RPC providers supply the account.
The new migrations, Edge function and Worker still require their own backend deployment.
