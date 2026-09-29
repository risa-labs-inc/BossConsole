/**
 * The two routes the DGX calls, and the signature that is the whole of their authentication.
 *
 * Run: cd supabase/functions/fluck-vault && deno task test
 *
 * The point being asserted is narrow and important: these routes reach a service role client,
 * and the only thing standing in front of them is an Ed25519 signature this end can verify and
 * cannot produce. So every way of getting the signature wrong has a case here.
 */
import { assert, assertEquals } from "@std/assert"
import {
  type ClaimedItem,
  createHandler,
  type CreateRequest,
  DEFAULT_PUBLIC_BASE_URL,
  type Dependencies,
  resetRateLimits,
} from "../app.ts"
import { bodyDigest, signingString } from "../signed.ts"
import { encodeBase64Url } from "../token.ts"

const NOW = 1_800_000_000
const WS = "ws-abcdefghij"
const JTI = "11111111-2222-3333-4444-555555555555"

const keyPair = await crypto.subtle.generateKey({ name: "Ed25519" }, true, [
  "sign",
  "verify",
]) as CryptoKeyPair
const other = await crypto.subtle.generateKey({ name: "Ed25519" }, true, [
  "sign",
  "verify",
]) as CryptoKeyPair
const LINK_PUBLIC = btoa(
  String.fromCharCode(...new Uint8Array(await crypto.subtle.exportKey("raw", keyPair.publicKey))),
)
const SEAL_PUBLIC = btoa(
  String.fromCharCode(
    ...new Uint8Array(
      await crypto.subtle.exportKey(
        "raw",
        ((await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, [
          "deriveBits",
        ])) as CryptoKeyPair).publicKey,
      ),
    ),
  ),
)

interface Harness {
  handler: (request: Request) => Promise<Response>
  created: CreateRequest[]
  claimed: string[]
  logs: string[]
}

function harness(options: { create?: boolean; items?: ClaimedItem[] } = {}): Harness {
  resetRateLimits()
  const created: CreateRequest[] = []
  const claimed: string[] = []
  const logs: string[] = []
  const deps: Dependencies = {
    env: (name) =>
      name === "FLUCK_LINK_PUBLIC_KEY"
        ? LINK_PUBLIC
        : name === "FLUCK_SEAL_PUBLIC_KEY"
        ? SEAL_PUBLIC
        : undefined,
    now: () => NOW * 1000,
    log: (line) => logs.push(line),
    describeRequest: () => Promise.resolve(null),
    store: () => Promise.resolve({ outcome: "gone", kind: null }),
    createRequest: (request) => {
      created.push(request)
      return Promise.resolve(options.create ?? true)
    },
    claimInbox: (ws) => {
      claimed.push(ws)
      return Promise.resolve(options.items ?? [])
    },
  }
  return { handler: createHandler(deps), created, claimed, logs }
}

async function signed(
  path: string,
  body: unknown,
  options: { key?: CryptoKey; timestamp?: number; tamper?: string } = {},
): Promise<Request> {
  const raw = JSON.stringify(body)
  const ts = options.timestamp ?? NOW
  const message = signingString("POST", path, ts, await bodyDigest(raw))
  const signature = await crypto.subtle.sign(
    { name: "Ed25519" },
    options.key ?? keyPair.privateKey,
    new TextEncoder().encode(message) as BufferSource,
  )
  return new Request(`${DEFAULT_PUBLIC_BASE_URL}${path}`, {
    method: "POST",
    body: options.tamper ?? raw,
    headers: {
      "content-type": "application/json",
      "x-fluck-timestamp": String(ts),
      "x-fluck-signature": encodeBase64Url(new Uint8Array(signature)),
    },
  })
}

function request(expiresIn = 300, overrides: Record<string, unknown> = {}) {
  return {
    jti: JTI,
    ws: WS,
    purpose: "vault",
    kind: "password",
    alias: "united.com",
    expiresAt: NOW + expiresIn,
    ...overrides,
  }
}

Deno.test("a signed request mints a row", async () => {
  const h = harness()
  const response = await h.handler(await signed("/requests", request()))
  assertEquals(response.status, 201)
  assertEquals(h.created.length, 1)
  assertEquals(h.created[0].alias, "united.com")
  assertEquals(h.created[0].kind, "password")
  assertEquals(h.created[0].purchaseId, null)
})

Deno.test("a tampered body is refused and nothing is written", async () => {
  const h = harness()
  const response = await h.handler(
    await signed("/requests", request(), {
      tamper: JSON.stringify(request(300, { ws: "ws-somebody-else" })),
    }),
  )
  assertEquals(response.status, 401)
  assertEquals(h.created.length, 0)
})

Deno.test("a signature from another key is refused", async () => {
  const h = harness()
  const response = await h.handler(
    await signed("/requests", request(), { key: other.privateKey }),
  )
  assertEquals(response.status, 401)
  assertEquals(h.created.length, 0)
})

Deno.test("a stale timestamp is refused even though the signature is good", async () => {
  const h = harness()
  const response = await h.handler(
    await signed("/requests", request(), { timestamp: NOW - 3600 }),
  )
  assertEquals(response.status, 401)
  assertEquals(h.created.length, 0)
})

Deno.test("a signature for one route does not work on the other", async () => {
  const h = harness()
  const raw = JSON.stringify({ ws: WS })
  const message = signingString("POST", "/requests", NOW, await bodyDigest(raw))
  const signature = await crypto.subtle.sign(
    { name: "Ed25519" },
    keyPair.privateKey,
    new TextEncoder().encode(message) as BufferSource,
  )
  const moved = new Request(`${DEFAULT_PUBLIC_BASE_URL}/inbox/claim`, {
    method: "POST",
    body: raw,
    headers: {
      "x-fluck-timestamp": String(NOW),
      "x-fluck-signature": encodeBase64Url(new Uint8Array(signature)),
    },
  })
  assertEquals((await h.handler(moved)).status, 401)
  assertEquals(h.claimed.length, 0)
})

Deno.test("an unsigned request is refused", async () => {
  const h = harness()
  const response = await h.handler(
    new Request(`${DEFAULT_PUBLIC_BASE_URL}/requests`, {
      method: "POST",
      body: JSON.stringify(request()),
    }),
  )
  assertEquals(response.status, 401)
  assertEquals(h.created.length, 0)
})

Deno.test("a lifetime longer than the policy is refused", async () => {
  const h = harness()
  assertEquals((await h.handler(await signed("/requests", request(601)))).status, 400)
  assertEquals((await h.handler(await signed("/requests", request(-1)))).status, 400)
  assertEquals(
    (await h.handler(await signed("/requests", { ...request(301), purpose: "cvv", kind: null })))
      .status,
    400,
  )
  assertEquals(h.created.length, 0)
})

Deno.test("a malformed request row is refused field by field", async () => {
  const h = harness()
  const cases: Record<string, unknown>[] = [
    { jti: "not-a-uuid" },
    { ws: "" },
    { purpose: "something" },
    { kind: "cvv" },
    { last4: "12345" },
    { currency: "dollars" },
    { totalCents: -1 },
    { purchaseId: "pur-1" },
  ]
  for (const override of cases) {
    const response = await h.handler(await signed("/requests", request(300, override)))
    assertEquals(response.status, 400, JSON.stringify(override))
  }
  assertEquals(h.created.length, 0)
})

Deno.test("a cvv request needs a purchase and no kind", async () => {
  const h = harness()
  const ok = await h.handler(
    await signed("/requests", {
      jti: JTI,
      ws: WS,
      purpose: "cvv",
      purchaseId: "pur-1",
      merchant: "Delta",
      brand: "Visa",
      last4: "4242",
      totalCents: 1234,
      currency: "USD",
      expiresAt: NOW + 200,
    }),
  )
  assertEquals(ok.status, 201)
  assertEquals(h.created[0].kind, null)
  assertEquals(h.created[0].purchaseId, "pur-1")
})

Deno.test("a duplicate id is a conflict rather than a second link", async () => {
  const h = harness({ create: false })
  const response = await h.handler(await signed("/requests", request()))
  assertEquals(response.status, 409)
})

Deno.test("claim drains the workspace named in the signed body", async () => {
  const items: ClaimedItem[] = [{
    id: "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
    jti: JTI,
    purpose: "vault",
    kind: "password",
    alias: "united.com",
    purchaseId: null,
    ciphertext: "AQQ=",
    createdAt: "2026-09-21T00:00:00Z",
  }]
  const h = harness({ items })
  const response = await h.handler(await signed("/inbox/claim", { ws: WS }))
  assertEquals(response.status, 200)
  assertEquals(h.claimed, [WS])
  const body = await response.json()
  assertEquals(body.items.length, 1)
  assertEquals(body.items[0].alias, "united.com")
})

Deno.test("claim without a workspace is refused", async () => {
  const h = harness()
  assertEquals((await h.handler(await signed("/inbox/claim", {}))).status, 400)
  assertEquals(h.claimed.length, 0)
})

Deno.test("a GET on a signed route is not a page", async () => {
  const h = harness()
  const response = await h.handler(
    new Request(`${DEFAULT_PUBLIC_BASE_URL}/requests`, { method: "GET" }),
  )
  assertEquals(response.status, 405)
  assertEquals(response.headers.get("content-type"), "application/json")
})

Deno.test("no log line from a signed route carries a full workspace id", async () => {
  const h = harness()
  await h.handler(await signed("/requests", request()))
  await h.handler(await signed("/inbox/claim", { ws: WS }))
  assert(h.logs.length >= 2)
  for (const line of h.logs) assert(!line.includes(WS), line)
})

/**
 * The audience comes from this function's OWN variable.
 *
 * Edge secrets are set per project. `PUBLIC_BASE_URL` was already set here for `fluck-oauth`,
 * and while this function read it, every link it minted named an audience it then refused.
 */
Deno.test("the audience is not taken from the project-wide base url", async () => {
  resetRateLimits()
  const seen: string[] = []
  const deps: Dependencies = {
    env: (name) => {
      seen.push(name)
      if (name === "PUBLIC_BASE_URL") return "https://somebody-elses-host.example"
      if (name === "FLUCK_LINK_PUBLIC_KEY") return LINK_PUBLIC
      if (name === "FLUCK_SEAL_PUBLIC_KEY") return SEAL_PUBLIC
      return undefined
    },
    now: () => NOW * 1000,
    log: () => {},
    describeRequest: () => Promise.resolve(null),
    store: () => Promise.resolve({ outcome: "gone", kind: null }),
    createRequest: () => Promise.resolve(true),
    claimInbox: () => Promise.resolve([]),
  }
  const response = await createHandler(deps)(
    new Request(`${DEFAULT_PUBLIC_BASE_URL}/health`),
  )
  assertEquals(response.status, 200)
  assert(!seen.includes("PUBLIC_BASE_URL"), "the function read the project-wide base url")
})
