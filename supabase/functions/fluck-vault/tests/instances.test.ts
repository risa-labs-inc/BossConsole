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
  resetRateLimits,
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
  claimed: [string, string | null][]
  logs: string[]
}

function harness(options: { row?: VaultRequestRow | null; env?: boolean } = {}): Harness {
  resetRateLimits()
  const instances = new Map<string, Instance & { revoked?: boolean }>()
  const created: CreateRequest[] = []
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
    registerInstance: (instance) => {
      const existing = instances.get(instance.instanceId)
      if (existing && existing.userId !== instance.userId) return Promise.resolve("conflict")
      if (existing?.revoked) return Promise.resolve("revoked")
      instances.set(instance.instanceId, instance)
      return Promise.resolve("ok")
    },
    userFromToken: (token) => Promise.resolve(tokens[token] ?? null),
  }
  return { handler: createHandler(deps), instances, created, claimed, logs }
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

function seed(h: Harness, instanceId: string, userId: string, link: string, seal: string) {
  h.instances.set(instanceId, { instanceId, userId, linkPublicKey: link, sealPublicKey: seal })
}

async function signed(
  path: string,
  body: unknown,
  key: CryptoKey,
  instanceId: string | null,
): Promise<Request> {
  const raw = JSON.stringify(body)
  const message = signingString("POST", path, NOW, await bodyDigest(raw))
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
      "x-fluck-timestamp": String(NOW),
      "x-fluck-signature": encodeBase64Url(new Uint8Array(signature)),
      ...(instanceId ? { "x-fluck-instance": instanceId } : {}),
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

Deno.test("registering again for the same user is an idempotent re-key", async () => {
  const h = harness()
  const first = { instanceId: INSTANCE_A, linkPublicKey: A.base64, sealPublicKey: A_SEAL }
  assertEquals((await h.handler(register(first))).status, 200)
  resetRateLimits()
  assertEquals((await h.handler(register(first))).status, 200)
  resetRateLimits()
  const rekey = { instanceId: INSTANCE_A, linkPublicKey: B.base64, sealPublicKey: B_SEAL }
  assertEquals((await h.handler(register(rekey))).status, 200)
  assertEquals(h.instances.get(INSTANCE_A)!.linkPublicKey, B.base64)
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
