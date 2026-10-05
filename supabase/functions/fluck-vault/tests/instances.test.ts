/**
 * Per install keys: registration, signed requests, link tokens, sealing and claim isolation.
 *
 * Run: cd supabase/functions/fluck-vault && deno task test
 */
import { assert, assertEquals, assertStringIncludes } from "@std/assert"
import {
  type ClaimedItem,
  createHandler,
  type CreateRequest,
  DEFAULT_PUBLIC_BASE_URL,
  type Dependencies,
  type Instance,
  PAGES,
  resetRateLimits,
  type Rotation,
  type RowInstance,
  shortId,
  type VaultRequestRow,
} from "../app.ts"
import { bodyDigest, signingString } from "../signed.ts"
import { encodeBase64Url, mintLink } from "../token.ts"

const AUD = new URL(DEFAULT_PUBLIC_BASE_URL).host
const NOW = 1_800_000_000
const WS = "ws-abcdefghij"
const JTI = "11111111-2222-3333-4444-555555555555"
const ALICE = "aaaaaaaa-0000-0000-0000-000000000001"
const BOB = "bbbbbbbb-0000-0000-0000-000000000002"
const INSTANCE_A = "inst-alice-0123456789"
const INSTANCE_B = "inst-bob-0123456789ab"
const BROWSER = {
  accept: "text/html",
  "user-agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Safari/605.1.15",
}

async function ed25519(): Promise<{ pair: CryptoKeyPair; base64: string; pem: string }> {
  const pair = await crypto.subtle.generateKey({ name: "Ed25519" }, true, [
    "sign",
    "verify",
  ]) as CryptoKeyPair
  const raw = new Uint8Array(await crypto.subtle.exportKey("raw", pair.publicKey))
  const spki = new Uint8Array(await crypto.subtle.exportKey("spki", pair.publicKey))
  return {
    pair,
    base64: btoa(String.fromCharCode(...raw)),
    pem: `-----BEGIN PUBLIC KEY-----\n${
      btoa(String.fromCharCode(...spki))
    }\n-----END PUBLIC KEY-----`,
  }
}

async function p256(): Promise<string> {
  const pair = await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, [
    "deriveBits",
  ]) as CryptoKeyPair
  return btoa(
    String.fromCharCode(...new Uint8Array(await crypto.subtle.exportKey("raw", pair.publicKey))),
  )
}

const LEGACY = await ed25519()
const LEGACY_SEAL = await p256()
const A = await ed25519()
const A_SEAL = await p256()
const B = await ed25519()
const B_SEAL = await p256()

interface Harness {
  handler: (request: Request) => Promise<Response>
  instances: Map<string, Instance & { revoked?: boolean }>
  created: CreateRequest[]
  rotated: Rotation[]
  claimed: [string, string | null][]
  logs: string[]
}

function harness(options: { row?: VaultRequestRow | null; env?: boolean } = {}): Harness {
  resetRateLimits()
  const instances = new Map<string, Instance & { revoked?: boolean }>()
  const created: CreateRequest[] = []
  const rotated: Rotation[] = []
  const claimed: [string, string | null][] = []
  const logs: string[] = []
  const tokens: Record<string, string> = { "alice-token": ALICE, "bob-token": BOB }
  const env: Record<string, string> = options.env === false
    ? {}
    : { FLUCK_LINK_PUBLIC_KEY: LEGACY.base64, FLUCK_SEAL_PUBLIC_KEY: LEGACY_SEAL }
  const deps: Dependencies = {
    env: (name) => env[name],
    now: () => NOW * 1000,
    log: (line) => logs.push(line),
    describeRequest: () => Promise.resolve(options.row ?? null),
    store: () => Promise.resolve({ outcome: "stored", kind: "cvv" }),
    createRequest: (request) => {
      created.push(request)
      return Promise.resolve(true)
    },
    claimInbox: (ws, instanceId) => {
      claimed.push([ws, instanceId])
      return Promise.resolve([] as ClaimedItem[])
    },
    instance: (id) => {
      const found = instances.get(id)
      return Promise.resolve(found && !found.revoked ? found : null)
    },
    // Mirrors fluck_vault_register_instance: never a key change.
    registerInstance: (registration) => {
      const existing = instances.get(registration.instanceId)
      if (existing && existing.userId !== registration.userId) return Promise.resolve("conflict")
      if (existing?.revoked) return Promise.resolve("revoked")
      if (existing) {
        const same = existing.linkPublicKey === registration.linkPublicKey &&
          existing.sealPublicKey === registration.sealPublicKey
        return Promise.resolve(same ? "ok" : "rotation_requires_proof")
      }
      instances.set(registration.instanceId, { ...registration, issuanceApproved: false })
      return Promise.resolve("ok")
    },
    // Mirrors fluck_vault_rotate_instance: compare-and-swap on the current link key.
    rotateInstance: (rotation) => {
      rotated.push(rotation)
      const existing = instances.get(rotation.instanceId)
      if (!existing || existing.userId !== rotation.userId) return Promise.resolve("conflict")
      if (existing.revoked) return Promise.resolve("revoked")
      if (existing.linkPublicKey !== rotation.expectedLinkPublicKey) {
        return Promise.resolve("stale")
      }
      existing.linkPublicKey = rotation.linkPublicKey
      existing.sealPublicKey = rotation.sealPublicKey
      return Promise.resolve("ok")
    },
    userFromToken: (token) => Promise.resolve(tokens[token] ?? null),
  }
  return { handler: createHandler(deps), instances, created, rotated, claimed, logs }
}

function register(body: unknown, token: string | null = "alice-token"): Request {
  return new Request(`${DEFAULT_PUBLIC_BASE_URL}/instances`, {
    method: "POST",
    body: JSON.stringify(body),
    headers: {
      "content-type": "application/json",
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
  })
}

function seed(
  h: Harness,
  instanceId: string,
  userId: string,
  link: string,
  seal: string,
  issuanceApproved = true,
) {
  h.instances.set(instanceId, {
    instanceId,
    userId,
    linkPublicKey: link,
    sealPublicKey: seal,
    issuanceApproved,
  })
}

async function signed(
  path: string,
  body: unknown,
  key: CryptoKey,
  instanceId: string | null,
  extraHeaders: Record<string, string> = {},
  timestamp = NOW,
): Promise<Request> {
  const raw = JSON.stringify(body)
  const message = signingString("POST", path, timestamp, await bodyDigest(raw))
  const signature = await crypto.subtle.sign(
    { name: "Ed25519" },
    key,
    new TextEncoder().encode(message) as BufferSource,
  )
  return new Request(`${DEFAULT_PUBLIC_BASE_URL}${path}`, {
    method: "POST",
    body: raw,
    headers: {
      "content-type": "application/json",
      "x-fluck-timestamp": String(timestamp),
      "x-fluck-signature": encodeBase64Url(new Uint8Array(signature)),
      ...(instanceId ? { "x-fluck-instance": instanceId } : {}),
      ...extraHeaders,
    },
  })
}

const MINT = {
  jti: JTI,
  ws: WS,
  purpose: "vault",
  kind: "password",
  alias: "site.com",
  expiresAt: NOW + 300,
}

function row(instance: RowInstance | null): VaultRequestRow {
  return {
    jti: JTI,
    ws: WS,
    purpose: "vault",
    kind: "password",
    alias: "site.com",
    purchaseId: null,
    merchant: null,
    brand: null,
    last4: null,
    totalCents: null,
    currency: null,
    instance,
  }
}

const ROW_A: RowInstance = { id: INSTANCE_A, linkPublicKey: A.base64, sealPublicKey: A_SEAL }

async function token(key: CryptoKey): Promise<string> {
  return await mintLink(key, {
    jti: JTI,
    ws: WS,
    purpose: "vault",
    kind: "password",
    alias: "site.com",
    aud: AUD,
    iat: NOW - 10,
    exp: NOW + 300,
  })
}

function page(path: string): Request {
  return new Request(`${DEFAULT_PUBLIC_BASE_URL}${path}`, { headers: BROWSER })
}

// --------------------------------------------------------------------------------------------
// Registration
// --------------------------------------------------------------------------------------------

Deno.test("a signed-in user registers an install and its keys are stored normalised", async () => {
  const h = harness()
  const response = await h.handler(
    register({ instanceId: INSTANCE_A, linkPublicKey: A.pem, sealPublicKey: A_SEAL }),
  )
  assertEquals(response.status, 200)
  const stored = h.instances.get(INSTANCE_A)!
  assertEquals(stored.userId, ALICE)
  // PEM in, 32 raw bytes out, so the signed route reads one format.
  assertEquals(stored.linkPublicKey, A.base64)
  assertEquals(stored.sealPublicKey, A_SEAL)
})

Deno.test("registering the same keys again is idempotent", async () => {
  const h = harness()
  const first = { instanceId: INSTANCE_A, linkPublicKey: A.base64, sealPublicKey: A_SEAL }
  assertEquals((await h.handler(register(first))).status, 200)
  resetRateLimits()
  assertEquals((await h.handler(register(first))).status, 200)
  assertEquals(h.rotated.length, 0)
})

// --------------------------------------------------------------------------------------------
// Rotation: a session alone cannot change an install's keys
// --------------------------------------------------------------------------------------------

/** A key change request, signed (or not) the way the DGX signs any request. */
async function rotation(
  body: unknown,
  key: CryptoKey | null,
  token = "alice-token",
  timestamp = NOW,
): Promise<Request> {
  if (key === null) return register(body, token)
  return await signed(
    "/instances",
    body,
    key,
    null,
    { authorization: `Bearer ${token}` },
    timestamp,
  )
}

const TO_B = { instanceId: INSTANCE_A, linkPublicKey: B.base64, sealPublicKey: B_SEAL }

Deno.test("a stolen session cannot re-key an install, and its open links still seal to the owner", async () => {
  const h = harness({ row: row(ROW_A) })
  seed(h, INSTANCE_A, ALICE, A.base64, A_SEAL)
  // The attacker holds Alice's session and their own key pair B, but not Alice's key A. No
  // proof, and a proof made with the attacker's own new key, are both refused.
  for (const key of [null, B.pair.privateKey, LEGACY.pair.privateKey]) {
    resetRateLimits()
    const response = await h.handler(await rotation(TO_B, key))
    assertEquals(response.status, 401, String(key))
    assertEquals(await response.json(), { error: "rotation_requires_proof" })
  }
  assertEquals(h.rotated.length, 0)
  assertEquals(h.instances.get(INSTANCE_A)!.linkPublicKey, A.base64)
  assertEquals(h.instances.get(INSTANCE_A)!.sealPublicKey, A_SEAL)
  // The unconsumed link Alice's Fluck already texted still seals to Alice's key.
  resetRateLimits()
  const html = await (await h.handler(page(`/v/${shortId(JTI)}`))).text()
  assertStringIncludes(html, `data-key="${A_SEAL}"`)
  assert(!html.includes(B_SEAL))
})

Deno.test("a key change proven with the current key rotates, as a compare-and-swap", async () => {
  const h = harness()
  seed(h, INSTANCE_A, ALICE, A.base64, A_SEAL)
  const response = await h.handler(await rotation(TO_B, A.pair.privateKey))
  assertEquals(response.status, 200)
  assertEquals(await response.json(), { ok: true, rotated: true })
  assertEquals(h.rotated[0].expectedLinkPublicKey, A.base64)
  assertEquals(h.instances.get(INSTANCE_A)!.linkPublicKey, B.base64)
  assertEquals(h.instances.get(INSTANCE_A)!.sealPublicKey, B_SEAL)
})

Deno.test("a captured rotation proof is dead once the keys it was made for are gone", async () => {
  const h = harness()
  seed(h, INSTANCE_A, ALICE, A.base64, A_SEAL)
  const captured = await rotation(TO_B, A.pair.privateKey)
  const replay = captured.clone()
  assertEquals((await h.handler(captured)).status, 200)
  // The owner rotates again, B to LEGACY's pair, proven with B.
  resetRateLimits()
  const onward = {
    instanceId: INSTANCE_A,
    linkPublicKey: LEGACY.base64,
    sealPublicKey: LEGACY_SEAL,
  }
  assertEquals((await h.handler(await rotation(onward, B.pair.privateKey))).status, 200)
  // Replaying the A-signed proof now verifies against the current key, which is not A.
  resetRateLimits()
  const response = await h.handler(replay)
  assertEquals(response.status, 401)
  assertEquals(h.instances.get(INSTANCE_A)!.linkPublicKey, LEGACY.base64)
})

Deno.test("a stale rotation proof is refused", async () => {
  const h = harness()
  seed(h, INSTANCE_A, ALICE, A.base64, A_SEAL)
  const response = await h.handler(
    await rotation(TO_B, A.pair.privateKey, "alice-token", NOW - 600),
  )
  assertEquals(response.status, 401)
  assertEquals(h.instances.get(INSTANCE_A)!.linkPublicKey, A.base64)
})

Deno.test("a valid proof under another user's session does not rotate", async () => {
  const h = harness()
  seed(h, INSTANCE_A, ALICE, A.base64, A_SEAL)
  const response = await h.handler(await rotation(TO_B, A.pair.privateKey, "bob-token"))
  assertEquals(response.status, 403)
  assertEquals(h.rotated.length, 0)
  assertEquals(h.instances.get(INSTANCE_A)!.linkPublicKey, A.base64)
})

Deno.test("a rotation that loses a race to another rotation is refused, not applied", async () => {
  const h = harness()
  seed(h, INSTANCE_A, ALICE, A.base64, A_SEAL)
  const request = await rotation(TO_B, A.pair.privateKey)
  // The keys move after the proof is checked but before the write. Lookups, in order: the
  // register fake, the proof's key lookup, then the rotate fake's compare-and-swap.
  const original = h.instances.get(INSTANCE_A)!
  const lookup = h.instances.get.bind(h.instances)
  let calls = 0
  h.instances.get = (id: string) => {
    const found = lookup(id)
    if (found && ++calls === 3) found.linkPublicKey = LEGACY.base64
    return found
  }
  const response = await h.handler(request)
  assertEquals(response.status, 409)
  assertEquals(await response.json(), { error: "stale" })
  assertEquals(original.sealPublicKey, A_SEAL)
})

Deno.test("an install owned by another user is refused and keeps its keys", async () => {
  const h = harness()
  seed(h, INSTANCE_A, ALICE, A.base64, A_SEAL)
  const response = await h.handler(
    register(
      { instanceId: INSTANCE_A, linkPublicKey: B.base64, sealPublicKey: B_SEAL },
      "bob-token",
    ),
  )
  assertEquals(response.status, 403)
  assertEquals(await response.json(), { error: "conflict" })
  assertEquals(h.instances.get(INSTANCE_A)!.linkPublicKey, A.base64)
})

Deno.test("registration without a valid access token is refused", async () => {
  for (const auth of [null, "not-a-real-token"]) {
    const h = harness()
    const response = await h.handler(
      register({ instanceId: INSTANCE_A, linkPublicKey: A.base64, sealPublicKey: A_SEAL }, auth),
    )
    assertEquals(response.status, 401)
    assertEquals(h.instances.size, 0)
  }
})

Deno.test("bad ids and bad keys are refused field by field", async () => {
  const good = { instanceId: INSTANCE_A, linkPublicKey: A.base64, sealPublicKey: A_SEAL }
  const cases: [Record<string, unknown>, string][] = [
    [{ ...good, instanceId: "short" }, "instanceId"],
    [{ ...good, instanceId: "has spaces in it ok?" }, "instanceId"],
    [{ ...good, linkPublicKey: "AAAA" }, "linkPublicKey"],
    [{ ...good, linkPublicKey: undefined }, "linkPublicKey"],
    // A seal key where the link key goes: 65 bytes is not an Ed25519 key.
    [{ ...good, linkPublicKey: A_SEAL }, "linkPublicKey"],
    [{ ...good, sealPublicKey: A.base64 }, "sealPublicKey"],
    [{ ...good, sealPublicKey: "!!" }, "sealPublicKey"],
  ]
  for (const [body, error] of cases) {
    const h = harness()
    const response = await h.handler(register(body))
    assertEquals(response.status, 400, error)
    assertEquals(await response.json(), { error })
    assertEquals(h.instances.size, 0)
  }
})

Deno.test("a GET on the instances route is not a page", async () => {
  const h = harness()
  const response = await h.handler(new Request(`${DEFAULT_PUBLIC_BASE_URL}/instances`))
  assertEquals(response.status, 405)
})

// --------------------------------------------------------------------------------------------
// Signed requests
// --------------------------------------------------------------------------------------------

Deno.test("a request signed by a registered install mints a row tagged with it", async () => {
  const h = harness()
  seed(h, INSTANCE_A, ALICE, A.base64, A_SEAL)
  const response = await h.handler(await signed("/requests", MINT, A.pair.privateKey, INSTANCE_A))
  assertEquals(response.status, 201)
  assertEquals(h.created[0].instanceId, INSTANCE_A)
})

Deno.test("an install header with another key's signature is refused", async () => {
  const h = harness()
  seed(h, INSTANCE_A, ALICE, A.base64, A_SEAL)
  seed(h, INSTANCE_B, BOB, B.base64, B_SEAL)
  for (const key of [B.pair.privateKey, LEGACY.pair.privateKey]) {
    resetRateLimits()
    const response = await h.handler(await signed("/requests", MINT, key, INSTANCE_A))
    assertEquals(response.status, 401)
  }
  assertEquals(h.created.length, 0)
})

Deno.test("an unknown, malformed or revoked install is refused", async () => {
  const h = harness()
  seed(h, INSTANCE_A, ALICE, A.base64, A_SEAL)
  h.instances.get(INSTANCE_A)!.revoked = true
  for (const id of [INSTANCE_A, INSTANCE_B, "bad id"]) {
    resetRateLimits()
    const response = await h.handler(await signed("/requests", MINT, A.pair.privateKey, id))
    assertEquals(response.status, 401, id)
  }
  assertEquals(h.created.length, 0)
})

Deno.test("an install key without the header is not the legacy key", async () => {
  const h = harness()
  seed(h, INSTANCE_A, ALICE, A.base64, A_SEAL)
  const response = await h.handler(await signed("/requests", MINT, A.pair.privateKey, null))
  assertEquals(response.status, 401)
})

Deno.test("the legacy path is unchanged: env key, no header, untagged row", async () => {
  const h = harness()
  const response = await h.handler(await signed("/requests", MINT, LEGACY.pair.privateKey, null))
  assertEquals(response.status, 201)
  assertEquals(h.created[0].instanceId, null)
  resetRateLimits()
  const claim = await h.handler(
    await signed("/inbox/claim", { ws: WS }, LEGACY.pair.privateKey, null),
  )
  assertEquals(claim.status, 200)
  assertEquals(h.claimed, [[WS, null]])
})

Deno.test("a claim drains only the calling install's rows", async () => {
  const h = harness()
  seed(h, INSTANCE_A, ALICE, A.base64, A_SEAL)
  seed(h, INSTANCE_B, BOB, B.base64, B_SEAL)
  await h.handler(await signed("/inbox/claim", { ws: WS }, A.pair.privateKey, INSTANCE_A))
  resetRateLimits()
  await h.handler(await signed("/inbox/claim", { ws: WS }, B.pair.privateKey, INSTANCE_B))
  resetRateLimits()
  await h.handler(await signed("/inbox/claim", { ws: WS }, LEGACY.pair.privateKey, null))
  assertEquals(h.claimed, [[WS, INSTANCE_A], [WS, INSTANCE_B], [WS, null]])
})

// --------------------------------------------------------------------------------------------
// Pages
// --------------------------------------------------------------------------------------------

Deno.test("a token minted by an install verifies with that install's key", async () => {
  const h = harness({ row: row(ROW_A) })
  const response = await h.handler(page(`/vault?t=${await token(A.pair.privateKey)}`))
  assertEquals(response.status, 200)
})

Deno.test("an install's row refuses a token signed by any other key", async () => {
  for (const key of [LEGACY.pair.privateKey, B.pair.privateKey]) {
    const h = harness({ row: row(ROW_A) })
    const response = await h.handler(page(`/vault?t=${await token(key)}`))
    assertEquals(response.status, 400)
  }
})

Deno.test("a legacy row still verifies with the env key and only the env key", async () => {
  const ok = await harness({ row: row(null) }).handler(
    page(`/vault?t=${await token(LEGACY.pair.privateKey)}`),
  )
  assertEquals(ok.status, 200)
  const refused = await harness({ row: row(null) }).handler(
    page(`/vault?t=${await token(A.pair.privateKey)}`),
  )
  assertEquals(refused.status, 400)
})

Deno.test("the page seals to the row's install key, not the env key", async () => {
  for (const path of [`/v/${shortId(JTI)}`, `/vault?t=${await token(A.pair.privateKey)}`]) {
    const h = harness({ row: row(ROW_A) })
    const html = await (await h.handler(page(path))).text()
    assertStringIncludes(html, `data-key="${A_SEAL}"`)
    assert(!html.includes(LEGACY_SEAL), path)
  }
})

Deno.test("a legacy row seals to the env key", async () => {
  const h = harness({ row: row(null) })
  const html = await (await h.handler(page(`/v/${shortId(JTI)}`))).text()
  assertStringIncludes(html, `data-key="${LEGACY_SEAL}"`)
})

Deno.test("an install's page renders with no env keys configured at all", async () => {
  const h = harness({ row: row(ROW_A), env: false })
  const response = await h.handler(page(`/v/${shortId(JTI)}`))
  assertEquals(response.status, 200)
  // And a legacy row on the same deployment is still "not set up".
  const legacy = await harness({ row: row(null), env: false }).handler(page(`/v/${shortId(JTI)}`))
  assertEquals(legacy.status, 503)
})

// --------------------------------------------------------------------------------------------
// Issuance: registering is not enough to mint a page on this domain
// --------------------------------------------------------------------------------------------

const CVV_MINT = {
  jti: JTI,
  ws: WS,
  purpose: "cvv",
  purchaseId: "purchase-1",
  merchant: "shop.example",
  brand: "Visa",
  last4: "4242",
  totalCents: 48_732,
  currency: "USD",
  expiresAt: NOW + 120,
}

Deno.test("an ordinary account that registers its own key cannot mint a payment page", async () => {
  const h = harness()
  // Bob signs in, registers a key he holds, and signs a CVV request with his own merchant,
  // card and amount. Registration succeeds; minting is refused and nothing is written.
  const registered = await h.handler(
    register(
      { instanceId: INSTANCE_B, linkPublicKey: B.base64, sealPublicKey: B_SEAL },
      "bob-token",
    ),
  )
  assertEquals(registered.status, 200)
  assertEquals(h.instances.get(INSTANCE_B)!.issuanceApproved, false)
  for (const body of [CVV_MINT, MINT]) {
    resetRateLimits()
    const response = await h.handler(await signed("/requests", body, B.pair.privateKey, INSTANCE_B))
    assertEquals(response.status, 403)
    assertEquals(await response.json(), { error: "issuance" })
  }
  assertEquals(h.created.length, 0)
})

Deno.test("an approved install mints, and the env key (the operator) still does", async () => {
  const h = harness()
  seed(h, INSTANCE_A, ALICE, A.base64, A_SEAL, true)
  assertEquals(
    (await h.handler(await signed("/requests", CVV_MINT, A.pair.privateKey, INSTANCE_A))).status,
    201,
  )
  resetRateLimits()
  const legacy = { ...CVV_MINT, jti: "22222222-2222-3333-4444-555555555555" }
  assertEquals(
    (await h.handler(await signed("/requests", legacy, LEGACY.pair.privateKey, null))).status,
    201,
  )
  assertEquals(h.created.map((c) => c.instanceId), [INSTANCE_A, null])
})

Deno.test("an unapproved install can still drain its own (empty) inbox", async () => {
  const h = harness()
  seed(h, INSTANCE_B, BOB, B.base64, B_SEAL, false)
  const response = await h.handler(
    await signed("/inbox/claim", { ws: WS }, B.pair.privateKey, INSTANCE_B),
  )
  assertEquals(response.status, 200)
})

Deno.test("a cvv request must name the merchant and a positive amount in a known currency", async () => {
  const cases: [Record<string, unknown>, string][] = [
    [{ ...CVV_MINT, merchant: null }, "merchant"],
    [{ ...CVV_MINT, totalCents: null }, "total"],
    [{ ...CVV_MINT, totalCents: 0 }, "total"],
    [{ ...CVV_MINT, totalCents: -1 }, "total"],
    [{ ...CVV_MINT, totalCents: 1.5 }, "total"],
    [{ ...CVV_MINT, totalCents: 2 ** 53 }, "total"],
    [{ ...CVV_MINT, totalCents: 1_000_000_000_001 }, "total"],
    [{ ...CVV_MINT, currency: null }, "total"],
    [{ ...CVV_MINT, currency: "XQZ" }, "currency"],
    [{ ...CVV_MINT, currency: "usd" }, "currency"],
    [{ ...CVV_MINT, last4: "42" }, "last4"],
  ]
  for (const [body, error] of cases) {
    const h = harness()
    seed(h, INSTANCE_A, ALICE, A.base64, A_SEAL, true)
    const response = await h.handler(await signed("/requests", body, A.pair.privateKey, INSTANCE_A))
    assertEquals(response.status, 400, JSON.stringify(body))
    assertEquals(await response.json(), { error })
    assertEquals(h.created.length, 0)
  }
})

Deno.test("a cvv request in a zero or three decimal currency is accepted as minor units", async () => {
  for (const [currency, totalCents] of [["JPY", 1_200], ["KWD", 1_250], ["USD", 48_732]]) {
    const h = harness()
    seed(h, INSTANCE_A, ALICE, A.base64, A_SEAL, true)
    const body = { ...CVV_MINT, currency, totalCents }
    const response = await h.handler(await signed("/requests", body, A.pair.privateKey, INSTANCE_A))
    assertEquals(response.status, 201, String(currency))
    assertEquals(h.created[0].totalCents, totalCents)
    assertEquals(h.created[0].currency, currency)
  }
})

Deno.test("a vault request carries no purchase details", async () => {
  for (const extra of [{ merchant: "shop.example" }, { totalCents: 100 }, { currency: "USD" }]) {
    const h = harness()
    seed(h, INSTANCE_A, ALICE, A.base64, A_SEAL, true)
    const response = await h.handler(
      await signed("/requests", { ...MINT, ...extra }, A.pair.privateKey, INSTANCE_A),
    )
    assertEquals(response.status, 400, JSON.stringify(extra))
  }
})

Deno.test("the cvv copy is constant and never claims a charge", () => {
  assertEquals(PAGES.cvvSubmit, "Send code")
})
