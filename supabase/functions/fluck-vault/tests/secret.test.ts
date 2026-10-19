/**
 * The `secret` kind: minting with connector metadata, the page, the POST, and the claim.
 *
 * Run: cd supabase/functions/fluck-vault && deno task test
 */
import { assert, assertEquals, assertStringIncludes } from "@std/assert"
import {
  type ClaimedItem,
  CONNECTOR_LABELS,
  cookieName,
  createHandler,
  type CreateOutcome,
  type CreateRequest,
  DEFAULT_PUBLIC_BASE_URL,
  type Dependencies,
  ENV_PATTERN,
  PAGES,
  resetRateLimits,
  shortId,
  type StoreRequest,
  type VaultRequestRow,
} from "../app.ts"
import { SEAL_VERSION } from "../seal.ts"
import { bodyDigest, signingString } from "../signed.ts"
import { encodeBase64Url } from "../token.ts"

const NOW = 1_800_000_000
const WS = "ws-abcdefghij"
const JTI = "11111111-2222-3333-4444-555555555555"
const PATH = `/v/${shortId(JTI)}`
const BROWSER = {
  accept: "text/html",
  "user-agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Safari/605.1.15",
}

const LINK = await crypto.subtle.generateKey({ name: "Ed25519" }, true, [
  "sign",
  "verify",
]) as CryptoKeyPair
const LINK_PUBLIC = btoa(
  String.fromCharCode(...new Uint8Array(await crypto.subtle.exportKey("raw", LINK.publicKey))),
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

function secretRow(overrides: Partial<VaultRequestRow> = {}): VaultRequestRow {
  return {
    jti: JTI,
    ws: WS,
    purpose: "vault",
    kind: "secret",
    alias: null,
    purchaseId: null,
    merchant: null,
    brand: null,
    last4: null,
    totalCents: null,
    currency: null,
    instance: null,
    connector: null,
    ...overrides,
  }
}

interface Harness {
  handler: (request: Request) => Promise<Response>
  created: CreateRequest[]
  stored: StoreRequest[]
  logs: string[]
}

function harness(
  options: { row?: VaultRequestRow; items?: ClaimedItem[]; create?: CreateOutcome } = {},
): Harness {
  resetRateLimits()
  const created: CreateRequest[] = []
  const stored: StoreRequest[] = []
  const logs: string[] = []
  const env: Record<string, string> = {
    FLUCK_LINK_PUBLIC_KEY: LINK_PUBLIC,
    FLUCK_SEAL_PUBLIC_KEY: SEAL_PUBLIC,
  }
  const deps: Dependencies = {
    env: (name) => env[name],
    now: () => NOW * 1000,
    log: (line) => logs.push(line),
    describeRequest: () => Promise.resolve(options.row ?? secretRow()),
    store: (request) => {
      stored.push(request)
      return Promise.resolve({ outcome: "stored", kind: "secret" })
    },
    createRequest: (request) => {
      created.push(request)
      return Promise.resolve(options.create ?? "created")
    },
    claimInbox: () => Promise.resolve(options.items ?? []),
    instance: () => Promise.resolve(null),
    registerInstance: () => Promise.resolve("unavailable"),
    rotateInstance: () => Promise.resolve("unavailable"),
    userFromToken: () => Promise.resolve(null),
  }
  return { handler: createHandler(deps), created, stored, logs }
}

async function signed(path: string, body: unknown): Promise<Request> {
  const raw = JSON.stringify(body)
  const message = signingString("POST", path, NOW, await bodyDigest(raw))
  const signature = await crypto.subtle.sign(
    { name: "Ed25519" },
    LINK.privateKey,
    new TextEncoder().encode(message) as BufferSource,
  )
  return new Request(`${DEFAULT_PUBLIC_BASE_URL}${path}`, {
    method: "POST",
    body: raw,
    headers: {
      "content-type": "application/json",
      "x-fluck-timestamp": String(NOW),
      "x-fluck-signature": encodeBase64Url(new Uint8Array(signature)),
    },
  })
}

const MINT = { jti: JTI, ws: WS, purpose: "vault", kind: "secret", expiresAt: NOW + 300 }

function blob(bytes = 200): string {
  const out = new Uint8Array(bytes)
  crypto.getRandomValues(out)
  out[0] = SEAL_VERSION
  out[1] = 0x04
  return btoa(String.fromCharCode(...out))
}

/** One `<input …>` tag by name, or null. */
function input(html: string, name: string): string | null {
  return new RegExp(`<input name="${name}"[^>]*>`).exec(html)?.[0] ?? null
}

// --------------------------------------------------------------------------------------------
// Minting
// --------------------------------------------------------------------------------------------

Deno.test("a secret is minted with no metadata", async () => {
  const h = harness()
  const response = await h.handler(await signed("/requests", MINT))
  assertEquals(response.status, 201)
  assertEquals(h.created[0].kind, "secret")
  assertEquals(h.created[0].connector, null)
  assertEquals(h.created[0].env, null)
})

Deno.test("a secret is minted with a connector and an env name", async () => {
  const h = harness()
  const response = await h.handler(
    await signed("/requests", { ...MINT, connector: "notion", env: "NOTION_TOKEN" }),
  )
  assertEquals(response.status, 201)
  assertEquals(h.created[0].connector, "notion")
  assertEquals(h.created[0].env, "NOTION_TOKEN")
})

Deno.test("a secret with a bad connector or env name is refused", async () => {
  const cases: [Record<string, unknown>, string][] = [
    [{ connector: "slack" }, "connector"],
    [{ connector: "" }, "connector"],
    [{ connector: 7 }, "connector"],
    [{ connector: "toString" }, "connector"],
    [{ env: "notion_token" }, "env"],
    [{ env: "1TOKEN" }, "env"],
    [{ env: "A" }, "env"],
    [{ env: `A${"B".repeat(64)}` }, "env"],
    [{ env: "NOTION-TOKEN" }, "env"],
    [{ env: 12 }, "env"],
  ]
  for (const [extra, error] of cases) {
    const h = harness()
    const response = await h.handler(await signed("/requests", { ...MINT, ...extra }))
    assertEquals(response.status, 400, JSON.stringify(extra))
    assertEquals(await response.json(), { error })
    assertEquals(h.created.length, 0)
  }
})

Deno.test("metadata on the other kinds is ignored exactly as before", async () => {
  const h = harness()
  const response = await h.handler(
    await signed("/requests", { ...MINT, kind: "password", connector: "slack", env: "x" }),
  )
  assertEquals(response.status, 201)
  assertEquals(h.created[0].kind, "password")
  assertEquals(h.created[0].connector, null)
  assertEquals(h.created[0].env, null)
})

Deno.test("a database behind the function is a schema error, not a taken id", async () => {
  const cases: [CreateOutcome, number, string][] = [
    ["schema", 503, "schema"],
    ["unavailable", 503, "unavailable"],
    ["taken", 409, "taken"],
  ]
  for (const [outcome, status, error] of cases) {
    const h = harness({ create: outcome })
    const response = await h.handler(
      await signed("/requests", { ...MINT, connector: "notion", env: "NOTION_TOKEN" }),
    )
    assertEquals(response.status, status, outcome)
    assertEquals(await response.json(), { error })
    if (outcome === "schema") assert(h.logs.some((l) => l.includes("schema")), outcome)
  }
})

Deno.test("the connector vocabulary and env rule match the migration's check", async () => {
  const migrations = new URL("../../../migrations/", import.meta.url)
  let sql = ""
  for await (const entry of Deno.readDir(migrations)) {
    if (entry.name.endsWith("_fluck_vault_secret_kind.sql")) {
      sql = await Deno.readTextFile(new URL(entry.name, migrations))
    }
  }
  const check = /ADD CONSTRAINT "fluck_vault_requests_secret_meta_check"[\s\S]*?;/.exec(sql)?.[0] ??
    ""
  const listed = /"connector" IN \(([^)]*)\)/.exec(check)?.[1] ?? ""
  assertEquals(
    listed.split(",").map((v) => v.trim().replace(/^'|'$/g, "")).sort(),
    Object.keys(CONNECTOR_LABELS).sort(),
  )
  assertStringIncludes(check, `"env" ~ '${ENV_PATTERN.source}'`)
})

// --------------------------------------------------------------------------------------------
// The page
// --------------------------------------------------------------------------------------------

Deno.test("a secret page has a password-type key field and no value in it", async () => {
  const h = harness()
  const response = await h.handler(
    new Request(`${DEFAULT_PUBLIC_BASE_URL}${PATH}`, { headers: BROWSER }),
  )
  assertEquals(response.status, 200)
  const html = await response.text()
  assertStringIncludes(html, 'data-kind="secret"')
  assertStringIncludes(html, `<h1>${PAGES.secretTitle}</h1>`)
  const key = input(html, "f2")!
  assertStringIncludes(key, 'type="password"')
  assert(!key.includes("value="), key)
  const label = input(html, "f1")!
  assert(!label.includes("value="), label)
  assert(!label.includes("readonly"), label)
  assert(response.headers.get("content-security-policy")!.includes("frame-ancestors 'none'"))
})

Deno.test("a connector's page prefills its label read only", async () => {
  const h = harness({ row: secretRow({ connector: "notion" }) })
  const html = await (await h.handler(
    new Request(`${DEFAULT_PUBLIC_BASE_URL}${PATH}`, { headers: BROWSER }),
  )).text()
  assertStringIncludes(html, "<h1>Connect Notion</h1>")
  const label = input(html, "f1")!
  assertStringIncludes(label, 'value="Notion token"')
  assertStringIncludes(label, "readonly")
  assert(!input(html, "f2")!.includes("value="))
})

Deno.test("a replace page never prefills the alias or the key, only a connector's label", async () => {
  for (const connector of [null, "github" as const]) {
    const h = harness({ row: secretRow({ alias: "notion-work", connector }) })
    const html = await (await h.handler(
      new Request(`${DEFAULT_PUBLIC_BASE_URL}${PATH}`, { headers: BROWSER }),
    )).text()
    assertStringIncludes(html, "<h1>Replace notion-work</h1>")
    assert(!input(html, "f2")!.includes("value="))
    // Only a connector's own constant label is ever filled in; the alias never is.
    const label = input(html, "f1")!
    assert(!label.includes("notion-work"), label)
    if (connector === null) assert(!label.includes("value="), label)
  }
})

// --------------------------------------------------------------------------------------------
// The POST
// --------------------------------------------------------------------------------------------

async function opened(h: Harness): Promise<string> {
  const response = await h.handler(
    new Request(`${DEFAULT_PUBLIC_BASE_URL}${PATH}`, { headers: BROWSER }),
  )
  const header = response.headers.getSetCookie().find((c) => c.startsWith(`${cookieName(JTI)}=`))
  assert(header)
  return header.split(";")[0]
}

function submit(fields: Record<string, string>, cookie: string): Request {
  const form = new FormData()
  for (const [k, v] of Object.entries(fields)) form.append(k, v)
  return new Request(`${DEFAULT_PUBLIC_BASE_URL}${PATH}`, {
    method: "POST",
    body: form,
    headers: { cookie },
  })
}

Deno.test("a secret POST stores the sealed blob and nothing else", async () => {
  const h = harness()
  const cookie = await opened(h)
  const sealed = blob()
  const response = await h.handler(submit({ j: JTI, c: sealed }, cookie))
  assertEquals(response.status, 200)
  assertStringIncludes(await response.text(), PAGES.saved)
  assertEquals(h.stored.length, 1)
  assertEquals(h.stored[0].ciphertext, sealed)
  assertEquals(Object.keys(h.stored[0]).sort(), ["ciphertext", "cookieHash", "jti", "ttlMinutes"])
})

Deno.test("a secret POST with the key in the clear is refused and never logged", async () => {
  const h = harness()
  const cookie = await opened(h)
  const response = await h.handler(
    submit({ j: JTI, c: blob(), f1: "Notion token", f2: "secret_abc123" }, cookie),
  )
  assertEquals(response.status, 400)
  assertEquals(h.stored.length, 0)
  assert(!h.logs.join("\n").includes("secret_abc123"))
})

// --------------------------------------------------------------------------------------------
// The claim
// --------------------------------------------------------------------------------------------

Deno.test("a claim returns a secret's metadata beside its blob, and nothing extra otherwise", async () => {
  const items: ClaimedItem[] = [
    {
      id: "i1",
      jti: JTI,
      purpose: "vault",
      kind: "secret",
      alias: null,
      purchaseId: null,
      ciphertext: "AQ==",
      createdAt: "2026-09-30T00:00:00Z",
      connector: "notion",
      env: "NOTION_TOKEN",
    },
    {
      id: "i2",
      jti: JTI,
      purpose: "vault",
      kind: "password",
      alias: "site.com",
      purchaseId: null,
      ciphertext: "AQ==",
      createdAt: "2026-09-30T00:00:00Z",
    },
  ]
  const h = harness({ items })
  const response = await h.handler(await signed("/inbox/claim", { ws: WS }))
  assertEquals(response.status, 200)
  const body = await response.json()
  assertEquals(body.items[0].connector, "notion")
  assertEquals(body.items[0].env, "NOTION_TOKEN")
  assertEquals(Object.keys(body.items[1]).sort(), [
    "alias",
    "ciphertext",
    "createdAt",
    "id",
    "jti",
    "kind",
    "purchaseId",
    "purpose",
  ])
})
