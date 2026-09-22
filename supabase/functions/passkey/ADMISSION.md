# Passkey admission and the trusted gateway

The database limits are storage and CPU safety valves, not fair per-client
rate limiting. A trusted public gateway is the only network route to
`POST /functions/v1/passkey/auth/challenge`. Direct calls to the provider
function hostname are rejected by the edge.

## Lane arithmetic

The gateway divides the 300/minute authentication budget into lanes whose
hard sum is at most 300:

- 225/minute for untrusted cold clients
- 75/minute reserved for trusted admission grants

Registration keeps its own 60/minute budget on the untrusted lane. The three
database rows are `(authentication, untrusted)`, `(authentication, trusted)`,
and `(registration, untrusted)`, keyed by `(type, lane)`. A distributed
anonymous spray can exhaust only the 225-request untrusted lane, never the
reserve. The sum of every bucket maximum is never more than the storage
budget. The 2,048-row live-challenge ceiling and the trigger capacity check
stay per challenge type.

Crossing the gateway's soft per-source buckets sends a cold client through
the grant challenge rather than denying a shared network outright. No anonymous
design can guarantee availability against an attacker who can repeatedly
satisfy the same human challenge as legitimate users. The reserve makes
anonymous spraying insufficient, and the proof-of-possession grant raises the
attacker's cost. If a hard availability guarantee is required, the trusted
lane must instead require a pre-existing account or device credential.

## Request flow

1. The gateway deletes every inbound internal header, including all `X-Boss-*`
   admission headers. It derives the client address from platform connection
   metadata, never from `Forwarded` or `X-Forwarded-For`.
2. A cold client that is throttled can earn a short-lived, single-use
   admission grant after a prior passkey authentication or a human
   privacy-preserving challenge. The grant is bound to a client-generated
   Ed25519 public key with proof of possession on redemption, so a copied
   grant is not a reusable bearer credential. For the desktop app, the
   challenge opens in a system or embedded browser and the grant returns over
   the existing `boss://` deep-link mechanism.
3. After admitting a request, the gateway adds an internal JWS assertion
   (EdDSA over a 32-byte raw key) covering the HTTP method, the canonical
   path, the SHA-256 body digest, issuance and expiry, a unique request ID,
   the window ID, and the lane. The assertion header is never in the CORS
   allow-list and is never forwarded back to the client. The edge holds only
   the verification key, so a compromise there cannot mint admissions.
   The canonical path is the request pathname without query string or
   fragment, exactly as the platform presents it - for the deployed
   function that is `/functions/v1/passkey/auth/challenge`,
   `/functions/v1/passkey/auth/complete`, and so on (the tests mount the
   routes bare, so their fixtures sign `/auth/challenge`). The signer
   lives in the gateway repository; a path mismatch is a 403
   `path_mismatch` on every request, so the two must stay in step.
4. The edge verifies the assertion before parsing credentials or calling
   `admit_passkey_challenge`. Missing, forged, expired, wrong-lane, and
   body-mismatched assertions return 403 with no database RPC. Requests whose
   Host is not a configured gateway host are refused as direct-origin with
   no database RPC. If the verification key or the host list is not
   configured, the endpoint fails closed with 503 and a bounded Retry-After.
   It never falls back to the direct origin and never raises the database cap.
5. The verified lane and request ID travel in typed Hono context variables.
   The handler never reads them from the request body. The lane reaches
   `admit_passkey_challenge` as `p_lane`; registration always uses the
   untrusted lane.
6. The request ID is redeemed atomically with admission under the same
   advisory lock. A replay returns 403 without incrementing `used` and
   without inserting a challenge. Receipts expire after 180 seconds and are
   pruned with bounded work, so the receipt table stays rate-bounded rather
   than attacker-key-bounded.
7. Logs carry only the lane, coarse counter state, the request ID, and the
   refusal reason. Raw IP addresses, email addresses, grants, assertions,
   and passkey material are never logged here.

## Key rotation and outage behavior

The edge reads `GATEWAY_ADMISSION_PUBLIC_KEY` (base64url 32-byte raw Ed25519
key) and `GATEWAY_PUBLIC_HOSTS` (comma-separated) from the environment on
every request, so rotation is a secret update with no deploy. Assertions live
at most 120 seconds with 60 seconds of clock-skew tolerance, which bounds the
overlap during a rotation. A gateway dependency outage returns 503 with a
bounded retry hint. Clients must honor Retry-After.

## What lives elsewhere

Gateway routing, atomic counters, TTL-bounded grant state, assertion signing,
metrics, and secret rotation live in the gateway deployment repository, not
here. This repo proves the edge half: verification, lane-aware storage,
receipts, and refusal behavior.

Desktop follow-up: today's desktop client posts directly to the function URL
and carries no grant. Once the gateway is the only route, direct desktop
calls are refused. Wiring the desktop cold-client path (gateway URL config,
browser grant challenge, `boss://` grant return, grant on retry) belongs with
the gateway rollout, not with this edge change.

## Validation

- `deno check`, the Deno suite (including `gateway-admission.test.ts`), and
  the pgTAP suite `supabase/tests/passkey_gateway_admission_test.sql` run on
  every head.
- `scripts/test/test-passkey-admission-concurrency.py` checks final-slot
  races and a held authentication lock while a registration proceeds,
  against the disposable local Supabase database.
- Staging acceptance for the full design: saturate the untrusted lane with
  distributed traffic from many source addresses while legitimate cold and
  grant-bearing clients share one NAT, and confirm the reserve, the caps,
  the refusal shapes, the 503 behavior, TTL-bounded state, and clean logs.
