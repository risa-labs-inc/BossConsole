/**
 * Routing, the token gate, the device cookie and the refusals, against fakes.
 *
 * Run: cd supabase/functions/fluck-vault && deno task test
 *
 * Every case drives `createHandler` from app.ts, never index.ts, so no listener binds and no
 * Supabase client is constructed. `describeRequest` and `store` are recorded, which is what
 * makes "the GET consumed nothing" a fact about the calls rather than a reading of the source.
 */
import { assert, assertEquals, assertStringIncludes } from "@std/assert"
import {
  COOKIE_NAME,
  cookieName,
  createHandler,
  cvvIntro,
  DEFAULT_PUBLIC_BASE_URL,
  type Dependencies,
  looksLikePlaintext,
  money,
  PAGES,
  rateLimitEntries,
  resetRateLimits,
  routePath,
  type StoreRequest,
  type StoreResult,
  type VaultRequestRow,
} from "../app.ts"
import { SCRIPT, sha256Base64, STYLE } from "../page.ts"
import { SEAL_VERSION } from "../seal.ts"
import { isKnownCurrency, minorUnitExponent } from "../currency.ts"
import { type LinkClaims, mintLink } from "../token.ts"

const AUD = new URL(DEFAULT_PUBLIC_BASE_URL).host
const NOW = 1_800_000_000
const JTI = "11111111-2222-3333-4444-555555555555"
const CVV_JTI = "66666666-7777-8888-9999-000000000000"
const WS = "ws-abcdefghij"
const BROWSER = {
  accept: "text/html,application/xhtml+xml",
  "user-agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Safari/605.1.15",
}

const keyPair = await crypto.subtle.generateKey({ name: "Ed25519" }, true, [
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

/** A blob of the right shape. The handler cannot open one, so its contents do not matter. */
function blob(bytes = 200): string {
  const out = new Uint8Array(bytes)
  crypto.getRandomValues(out)
  out[0] = SEAL_VERSION
  out[1] = 0x04
  return btoa(String.fromCharCode(...out))
}

function vaultRow(overrides: Partial<VaultRequestRow> = {}): VaultRequestRow {
  return {
    jti: JTI,
    ws: WS,
    purpose: "vault",
    kind: "card",
    alias: "amex",
    purchaseId: null,
    merchant: null,
    brand: null,
    last4: null,
    totalCents: null,
    currency: null,
    instance: null,
    ...overrides,
  }
}

function cvvRow(overrides: Partial<VaultRequestRow> = {}): VaultRequestRow {
  return {
    jti: CVV_JTI,
    ws: WS,
    purpose: "cvv",
    kind: null,
    alias: null,
    purchaseId: "pur-1",
    merchant: "Delta Air Lines",
    brand: "Visa",
    last4: "4242",
    totalCents: 48_732,
    currency: "USD",
    instance: null,
    ...overrides,
  }
}

interface Harness {
  handler: (request: Request) => Promise<Response>
  described: string[]
  stored: StoreRequest[]
  logs: string[]
}

function harness(options: {
  row?: VaultRequestRow | null
  env?: Record<string, string | undefined>
  result?: StoreResult
} = {}): Harness {
  resetRateLimits()
  const described: string[] = []
  const stored: StoreRequest[] = []
  const logs: string[] = []
  const env: Record<string, string | undefined> = {
    FLUCK_LINK_PUBLIC_KEY: LINK_PUBLIC,
    FLUCK_SEAL_PUBLIC_KEY: SEAL_PUBLIC,
    ...(options.env ?? {}),
  }
  const deps: Dependencies = {
    env: (name) => env[name],
    now: () => NOW * 1000,
    log: (line) => logs.push(line),
    describeRequest: (jti) => {
      described.push(jti)
      const row = options.row === undefined ? vaultRow() : options.row
      // The real describe keys the row by the jti it was asked for.
      return Promise.resolve(row && { ...row, jti })
    },
    store: (request) => {
      stored.push(request)
      return Promise.resolve(options.result ?? { outcome: "stored", kind: "card" })
    },
    // The DGX routes have their own suite; here they only have to exist.
    createRequest: () => Promise.resolve(true),
    claimInbox: () => Promise.resolve([]),
    instance: () => Promise.resolve(null),
    registerInstance: () => Promise.resolve("unavailable"),
    rotateInstance: () => Promise.resolve("unavailable"),
    userFromToken: () => Promise.resolve(null),
  }
  return { handler: createHandler(deps), described, stored, logs }
}

async function token(overrides: Partial<LinkClaims> = {}): Promise<string> {
  return await mintLink(keyPair.privateKey, {
    jti: JTI,
    ws: WS,
    purpose: "vault",
    kind: "card",
    alias: "amex",
    aud: AUD,
    iat: NOW - 10,
    exp: NOW + 500,
    ...overrides,
  })
}

async function cvvToken(overrides: Partial<LinkClaims> = {}): Promise<string> {
  return await mintLink(keyPair.privateKey, {
    jti: CVV_JTI,
    ws: WS,
    purpose: "cvv",
    purchase_id: "pur-1",
    aud: AUD,
    iat: NOW - 10,
    exp: NOW + 240,
    ...overrides,
  })
}

function get(path: string, t: string, headers: Record<string, string> = BROWSER): Request {
  return new Request(`https://${AUD}/fluck-vault${path}?t=${encodeURIComponent(t)}`, { headers })
}

function post(
  path: string,
  body: Record<string, string>,
  headers: Record<string, string> = {},
): Request {
  const form = new FormData()
  for (const [k, v] of Object.entries(body)) form.append(k, v)
  return new Request(`https://${AUD}/fluck-vault${path}`, { method: "POST", body: form, headers })
}

function cookieFrom(response: Response, jti: string = JTI): string {
  const header = response.headers.getSetCookie().find((c) => c.startsWith(`${cookieName(jti)}=`))
  assert(header, "no device cookie was set")
  return header.split(";")[0]
}

// ---------------------------------------------------------------------------------------------
// Routing and health
// ---------------------------------------------------------------------------------------------

Deno.test("the path routes the same under all three deployments", () => {
  assertEquals(routePath("/functions/v1/fluck-vault/vault"), "/vault")
  assertEquals(routePath("/fluck-vault/cvv"), "/cvv")
  assertEquals(routePath("/vault/"), "/vault")
  assertEquals(routePath("/fluck-vault"), "/")
})

Deno.test("health reports booleans and never a value", async () => {
  const { handler } = harness()
  const response = await handler(new Request(`https://${AUD}/fluck-vault/health`))
  assertEquals(response.status, 200)
  const body = await response.json()
  assertEquals(body, { ok: true, configured: { linkKey: true, sealKey: true, baseUrl: true } })
})

Deno.test("health is 503 when a key is missing", async () => {
  const { handler } = harness({ env: { FLUCK_SEAL_PUBLIC_KEY: undefined } })
  const response = await handler(new Request(`https://${AUD}/fluck-vault/health`))
  assertEquals(response.status, 503)
  assertEquals((await response.json()).configured.sealKey, false)
})

Deno.test("pubkey serves the sealing key so the DGX can check what pages were given", async () => {
  const { handler } = harness()
  const response = await handler(new Request(`https://${AUD}/fluck-vault/pubkey`))
  const body = await response.json()
  assertEquals(body.key, SEAL_PUBLIC)
  assertEquals(body.alg, "ECDH-P256-HKDF-SHA256-AES256GCM")
})

// ---------------------------------------------------------------------------------------------
// The GET
// ---------------------------------------------------------------------------------------------

Deno.test("a browser GET renders the card form and consumes nothing", async () => {
  const { handler, described, stored } = harness()
  const response = await handler(get("/vault", await token()))
  assertEquals(response.status, 200)
  const html = await response.text()
  assertStringIncludes(html, PAGES.cardTitle)
  assertStringIncludes(html, `data-jti="${JTI}"`)
  assertStringIncludes(html, `data-key="${SEAL_PUBLIC}"`)
  // Any card: no virtual-card checkbox or wording. The spending limit is still required.
  assert(!html.includes('name="f8"'))
  assert(!/virtual/i.test(html.replace(SCRIPT, "")))
  assertStringIncludes(
    html,
    'name="f9" type="text" inputmode="decimal" autocomplete="off" required',
  )
  assertStringIncludes(html, 'name="f9"')
  assertStringIncludes(html, 'name="f10"')
  assertStringIncludes(html, 'name="f11"')
  assertStringIncludes(html, "only your Fluck can read them")
  assertEquals(described, [JTI])
  assertEquals(stored.length, 0)
})

Deno.test("a password link renders the password form", async () => {
  const { handler } = harness({ row: vaultRow({ kind: "password", alias: "airline" }) })
  const response = await handler(get("/vault", await token({ kind: "password" })))
  assertStringIncludes(await response.text(), "Save your airline login")
})

Deno.test("the cvv page names the brand, the last four, the total and the merchant", async () => {
  const { handler } = harness({ row: cvvRow() })
  const response = await handler(get("/cvv", await cvvToken()))
  const html = await response.text()
  assertStringIncludes(html, "<h1>Card security code</h1>")
  assertStringIncludes(html, "<p>Visa ••4242 · $487.32 · Delta Air Lines</p>")
  assertStringIncludes(html, ">Send code</button>")
  assertStringIncludes(html, PAGES.cvvNote)
})

Deno.test("the cvv page says what it does and does not do", async () => {
  const { handler } = harness({ row: cvvRow() })
  const html = await (await handler(get("/cvv", await cvvToken()))).text()
  // No charge, check the details, do not forward, and the code is staged encrypted.
  assertStringIncludes(html, "This does not charge your card.")
  assertStringIncludes(html, "Only continue if these details match a purchase you asked Fluck")
  assertStringIncludes(html, "do not forward this link")
  assertStringIncludes(html, "held for up to ten minutes")
  for (const claim of ["Confirm payment", ">Pay<", "Pay now", "never stored", "Payment sent"]) {
    assert(!html.includes(claim), claim)
  }
  assertStringIncludes(PAGES.cvvDone, "Your card has not been charged.")
  assert(!/\bpaid\b|payment (sent|complete)/i.test(PAGES.cvvDone + PAGES.cvvDoneTitle))
})

Deno.test("the cvv total is shown in the row's own currency", () => {
  // The live report: an INR checkout rendered as $290.69.
  assertEquals(
    cvvIntro(
      cvvRow({
        brand: "HSBC",
        last4: "3585",
        totalCents: 29_069,
        currency: "INR",
        merchant: "zomato.com",
      }),
    ),
    "HSBC ••3585 · ₹290.69 · zomato.com",
  )
  assertEquals(money(1_050, "GBP"), "£10.50")
  assertEquals(money(1_050, "EUR"), "€10.50")
  // An unknown, malformed or missing code shows no amount rather than a guessed one.
  assertEquals(money(29_069, "XQZ"), null)
  assertEquals(money(29_069, "1AB"), null)
  assertEquals(money(29_069, null), null)
  assertEquals(money(null, "USD"), null)
  assertEquals(money(1.5, "USD"), null)
  assertEquals(money(-1, "USD"), null)
})

Deno.test("amounts are ISO 4217 minor units: the shared wire vectors (USD, JPY, KWD, ...)", async () => {
  const fixture = JSON.parse(
    await Deno.readTextFile(new URL("./fixtures/amount-vectors.json", import.meta.url)),
  ) as {
    vectors: { currency: string; exponent: number; major: string; minor: number; display: string }[]
  }
  const seen = new Set(fixture.vectors.map((v) => v.exponent))
  for (const exponent of [0, 2, 3]) assert(seen.has(exponent), `no ${exponent}-decimal vector`)
  for (const v of fixture.vectors) {
    assertEquals(minorUnitExponent(v.currency), v.exponent, v.currency)
    // major -> minor, the way a minter must do it: digits, not floating point.
    const [whole, fraction = ""] = v.major.split(".")
    assertEquals(fraction.length, v.exponent, v.currency)
    assertEquals(Number(whole + fraction), v.minor, v.currency)
    assertEquals(money(v.minor, v.currency)!.replace(/\u00a0/g, " "), v.display, v.currency)
  }
})

Deno.test("known currencies do not depend on Intl, and codes with no minor unit are unknown", async () => {
  const intl = Intl as unknown as { supportedValuesOf?: unknown }
  const original = intl.supportedValuesOf
  intl.supportedValuesOf = () => {
    throw new RangeError("not supported")
  }
  try {
    // A fresh module instance, evaluated while Intl.supportedValuesOf is unavailable.
    const fresh = await import(`../currency.ts?no-intl=${crypto.randomUUID()}`)
    for (const code of ["USD", "EUR", "GBP", "INR", "CAD"]) {
      assert(fresh.isKnownCurrency(code), code)
      assertEquals(fresh.minorUnitExponent(code), 2, code)
    }
  } finally {
    intl.supportedValuesOf = original
  }
  for (const code of ["XAU", "XAG", "XDR", "XXX", "XTS", "ZZZ"]) {
    assertEquals(isKnownCurrency(code), false, code)
    assertEquals(money(100, code), null, code)
  }
})

Deno.test("the cvv line omits what the row does not have", () => {
  assertEquals(cvvIntro(cvvRow({ brand: null })), "••4242 · $487.32 · Delta Air Lines")
  assertEquals(cvvIntro(cvvRow({ last4: null, merchant: null })), "Visa · $487.32")
  assertEquals(
    cvvIntro(cvvRow({ brand: null, last4: null, merchant: null, totalCents: null })),
    PAGES.cvvIntro,
  )
})

Deno.test("five GETs on one link all work, because unfurling must not spend it", async () => {
  const { handler, stored } = harness()
  const t = await token()
  for (let i = 0; i < 5; i++) {
    assertEquals((await handler(get("/vault", t))).status, 200)
  }
  assertEquals(stored.length, 0)
})

Deno.test("an unfurler gets 204 and no body at all", async () => {
  for (
    const headers of [
      { accept: "*/*", "user-agent": "facebookexternalhit/1.1" },
      { accept: "text/html", "user-agent": "Slackbot-LinkExpanding 1.0" },
      { accept: "application/json", "user-agent": "Mozilla/5.0" },
      { accept: "text/html", "user-agent": "" },
      { accept: "text/html", "user-agent": "curl/8.4.0" },
    ]
  ) {
    const { handler, described } = harness()
    const response = await handler(get("/vault", await token(), headers))
    assertEquals(response.status, 204)
    assertEquals(await response.text(), "")
    assertEquals(response.headers.get("X-Robots-Tag"), "noindex, nofollow, noarchive")
    // Not even the row is read: an unfurl is answered before anything is looked up.
    assertEquals(described.length, 0)
  }
})

Deno.test("a GET sets a device cookie scoped to this link", async () => {
  const { handler } = harness()
  const response = await handler(get("/vault", await token()))
  const header = response.headers.getSetCookie()[0]
  // NAME as well as value: one name for every link is a jar that holds only the newest.
  assertStringIncludes(header, `${cookieName(JTI)}=${JTI}.`)
  assertStringIncludes(header, "Secure")
  assertStringIncludes(header, "HttpOnly")
  assertStringIncludes(header, "SameSite=Strict")
})

Deno.test("the form posts to the public URL, not the path the runtime sees", async () => {
  const { handler } = harness()
  const response = await handler(get("/vault", await token()))
  const html = await response.text()
  // The runtime serves `/fluck-vault/vault`; a relative action posts there and the gateway
  // answers "requested path is invalid" before the function ever runs.
  assertStringIncludes(html, `action="${DEFAULT_PUBLIC_BASE_URL}/vault"`)
})

Deno.test("every GET carries the headers the page's safety depends on", async () => {
  const { handler } = harness()
  const response = await handler(get("/vault", await token()))
  const csp = response.headers.get("Content-Security-Policy") ?? ""
  assertStringIncludes(csp, "default-src 'none'")
  assertStringIncludes(csp, `script-src 'self' 'sha256-${await sha256Base64(SCRIPT)}'`)
  assertStringIncludes(csp, `style-src 'sha256-${await sha256Base64(STYLE)}'`)
  assertStringIncludes(csp, "form-action 'self'")
  assertStringIncludes(csp, "frame-ancestors 'none'")
  assertStringIncludes(csp, "base-uri 'none'")
  assertEquals(response.headers.get("Cache-Control"), "no-store")
  assertEquals(response.headers.get("Referrer-Policy"), "no-referrer")
  assertStringIncludes(response.headers.get("Strict-Transport-Security") ?? "", "max-age=")
})

Deno.test("the inline script the CSP pins is the script that was served", async () => {
  const { handler } = harness()
  const html = await (await handler(get("/vault", await token()))).text()
  assertStringIncludes(html, `<script>${SCRIPT}</script>`)
})

Deno.test("a bad token, a wrong purpose and a missing row all render one fixed page", async () => {
  const cases: [string, string][] = [
    ["/vault", "not-a-token"],
    ["/vault", await cvvToken()],
    ["/cvv", await token()],
  ]
  for (const [path, t] of cases) {
    const { handler } = harness()
    const response = await handler(get(path, t))
    assertEquals(response.status, 400)
    const html = await response.text()
    assertStringIncludes(html, PAGES.bad)
    // Nothing from the token or the row reaches the page.
    assertEquals(html.includes(WS), false)
    assertEquals(html.includes("amex"), false)
  }
  const missing = harness({ row: null })
  assertEquals((await missing.handler(get("/vault", await token()))).status, 400)
})

Deno.test("a row for another workspace than the signature names is refused", async () => {
  const { handler } = harness({ row: vaultRow({ ws: "ws-somebodyelse" }) })
  assertEquals((await handler(get("/vault", await token()))).status, 400)
})

Deno.test("an unconfigured function says so and renders no form", async () => {
  const { handler } = harness({ env: { FLUCK_LINK_PUBLIC_KEY: undefined } })
  const response = await handler(get("/vault", await token()))
  assertEquals(response.status, 503)
  assertStringIncludes(await response.text(), PAGES.unset)
})

// ---------------------------------------------------------------------------------------------
// The POST
// ---------------------------------------------------------------------------------------------

async function opened(
  h: Harness,
  path = "/vault",
  t?: string,
  jti: string = JTI,
): Promise<string> {
  const response = await h.handler(get(path, t ?? await token()))
  return cookieFrom(response, jti)
}

Deno.test("a sealed POST with the device cookie is stored", async () => {
  const h = harness()
  const cookie = await opened(h)
  const response = await h.handler(
    post("/vault", { j: JTI, c: blob() }, { cookie }),
  )
  assertEquals(response.status, 200)
  assertStringIncludes(await response.text(), PAGES.cardSaved)
  assertEquals(h.stored.length, 1)
  assertEquals(h.stored[0].jti, JTI)
  assertEquals(h.stored[0].ttlMinutes, 15)
  assertEquals(h.stored[0].cookieHash.length, 64)
})

Deno.test("a cvv POST gets the shorter ttl and its own sentence", async () => {
  const h = harness({ row: cvvRow(), result: { outcome: "stored", kind: "cvv" } })
  const cookie = await opened(h, "/cvv", await cvvToken(), CVV_JTI)
  const response = await h.handler(post("/cvv", { j: CVV_JTI, c: blob() }, { cookie }))
  assertStringIncludes(await response.text(), PAGES.cvvDone)
  assertEquals(h.stored[0].ttlMinutes, 10)
})

/**
 * The 2026-09-20 report, as a test.
 *
 * The owner was texted three links a minute and a half apart, opened more than one, filled in
 * a page and submitted it. Under one cookie NAME for every link, the second GET replaced the
 * first link's cookie and the first page's POST was refused with `no device cookie` — for a
 * link that was signed, unexpired and unconsumed. A browser jar holds every cookie it has been
 * set, so the test carries BOTH and asserts each page still posts.
 */
Deno.test("two links open at once both keep their binding", async () => {
  const h = harness()
  const first = await opened(h)
  const secondJti = "99999999-8888-7777-6666-555555555555"
  const secondResponse = await h.handler(get("/vault", await token({ jti: secondJti })))
  const second = cookieFrom(secondResponse, secondJti)

  // The names differ, which is the fix; under the old scheme these were the same name and the
  // jar would have held only `second`.
  assertEquals(first.split("=")[0], cookieName(JTI))
  assertEquals(second.split("=")[0], cookieName(secondJti))

  const jar = `${first}; ${second}`
  const older = await h.handler(post("/vault", { j: JTI, c: blob() }, { cookie: jar }))
  assertEquals(older.status, 200)
  assertEquals(h.stored[0].jti, JTI)

  resetRateLimits()
  const newer = await h.handler(post("/vault", { j: secondJti, c: blob() }, { cookie: jar }))
  assertEquals(newer.status, 200)
  assertEquals(h.stored[1].jti, secondJti)
})

/** A page rendered by the previous deployment is in somebody's browser right now. */
Deno.test("the legacy bare cookie from the previous deployment is still accepted", async () => {
  const h = harness()
  const cookie = await opened(h)
  const legacy = `${COOKIE_NAME}=${cookie.split("=").slice(1).join("=")}`
  const response = await h.handler(post("/vault", { j: JTI, c: blob() }, { cookie: legacy }))
  assertEquals(response.status, 200)
  assertEquals(h.stored.length, 1)
})

/** The refusal says WHICH of the two it was, and says it without a value. */
Deno.test("a cookie for another link is logged apart from no cookie at all", async () => {
  const h = harness()
  const cookie = await opened(h)
  const foreign = `${cookieName(CVV_JTI)}=${CVV_JTI}.${cookie.split(".")[1]}`

  await h.handler(post("/vault", { j: JTI, c: blob() }, { cookie: foreign }))
  resetRateLimits()
  await h.handler(post("/vault", { j: JTI, c: blob() }))

  assert(h.logs.some((l) => l.includes("refused: device cookie for another link")))
  assert(h.logs.some((l) => l.endsWith(`refused: no device cookie [${JTI.slice(0, 8)}]`)))
  // Nothing logged carries a cookie value.
  assert(h.logs.every((l) => !l.includes(cookie.split(".")[1])))
  assertEquals(h.stored.length, 0)
})

Deno.test("a POST without the device cookie is refused and stores nothing", async () => {
  const h = harness()
  await opened(h)
  const response = await h.handler(post("/vault", { j: JTI, c: blob() }))
  assertEquals(response.status, 400)
  assertEquals(h.stored.length, 0)
})

Deno.test("a cookie issued for another link does not satisfy this one", async () => {
  const h = harness()
  const cookie = await opened(h)
  const other = cookie.replace(JTI, CVV_JTI)
  const response = await h.handler(post("/vault", { j: JTI, c: blob() }, { cookie: other }))
  assertEquals(response.status, 400)
  assertEquals(h.stored.length, 0)
})

Deno.test("a replay after a successful POST is refused", async () => {
  const h = harness({ result: { outcome: "gone", kind: null } })
  const cookie = await opened(h)
  const response = await h.handler(post("/vault", { j: JTI, c: blob() }, { cookie }))
  assertEquals(response.status, 400)
  assertStringIncludes(await response.text(), PAGES.bad)
})

Deno.test("a store that cannot be reached says try again rather than pretending", async () => {
  const h = harness({ result: { outcome: "unavailable", kind: null } })
  const cookie = await opened(h)
  const response = await h.handler(post("/vault", { j: JTI, c: blob() }, { cookie }))
  assertEquals(response.status, 503)
  assertStringIncludes(await response.text(), PAGES.down)
})

Deno.test("a successful POST clears the cookie and the site data", async () => {
  const h = harness()
  const cookie = await opened(h)
  const response = await h.handler(post("/vault", { j: JTI, c: blob() }, { cookie }))
  assertStringIncludes(response.headers.getSetCookie().join(" "), "Max-Age=0")
  assertEquals(response.headers.get("Clear-Site-Data"), '"cache", "storage"')
})

// ---------------------------------------------------------------------------------------------
// Plaintext refusal
// ---------------------------------------------------------------------------------------------

Deno.test("a body carrying a plaintext field is refused before anything is stored", async () => {
  const h = harness()
  const cookie = await opened(h)
  const response = await h.handler(
    post("/vault", { j: JTI, c: blob(), f2: "4111111111111111" }, { cookie }),
  )
  assertEquals(response.status, 400)
  assertEquals(h.stored.length, 0)
  // The value itself is never written down, not even truncated.
  assertEquals(h.logs.some((line) => line.includes("4111")), false)
})

Deno.test("a body whose ciphertext is really a card number is refused", async () => {
  const h = harness()
  const cookie = await opened(h)
  for (const value of ["4111111111111111", "4111 1111 1111 1111", "not-base64!!"]) {
    const response = await h.handler(post("/vault", { j: JTI, c: value }, { cookie }))
    assertEquals(response.status, 400)
  }
  assertEquals(h.stored.length, 0)
})

Deno.test("the plaintext test catches a Luhn number and a bare long digit run", () => {
  assert(looksLikePlaintext("4111111111111111"))
  assert(looksLikePlaintext("pan is 4242424242424242 thanks"))
  assert(looksLikePlaintext("1234567890123"))
  assertEquals(looksLikePlaintext(blob()), false)
  assertEquals(looksLikePlaintext("123"), false)
})

// ---------------------------------------------------------------------------------------------
// Rate limits
// ---------------------------------------------------------------------------------------------

Deno.test("a sixth GET on one link is refused with a generic page", async () => {
  const { handler } = harness()
  const t = await token()
  for (let i = 0; i < 5; i++) assertEquals((await handler(get("/vault", t))).status, 200)
  const response = await handler(get("/vault", t))
  assertEquals(response.status, 429)
  const html = await response.text()
  assertStringIncludes(html, PAGES.busy)
  assertEquals(html.includes(JTI), false)
})

Deno.test("a fourth POST on one link is refused without reaching the store", async () => {
  const h = harness({ result: { outcome: "gone", kind: null } })
  const cookie = await opened(h)
  for (let i = 0; i < 3; i++) {
    assertEquals((await h.handler(post("/vault", { j: JTI, c: blob() }, { cookie }))).status, 400)
  }
  assertEquals(h.stored.length, 3)
  const response = await h.handler(post("/vault", { j: JTI, c: blob() }, { cookie }))
  assertEquals(response.status, 429)
  assertEquals(h.stored.length, 3)
})

Deno.test("one address is capped across links", async () => {
  const { handler } = harness()
  const headers = { ...BROWSER, "x-forwarded-for": "203.0.113.7, 10.0.0.1" }
  let limited = 0
  for (let i = 0; i < 25; i++) {
    const request = new Request(
      `https://${AUD}/fluck-vault/vault?t=${await token({ jti: JTI })}`,
      { headers },
    )
    if ((await handler(request)).status === 429) limited++
  }
  assert(limited > 0)
})

Deno.test("spoofed addresses are capped and cannot evict a verified link's bucket", async () => {
  const { handler } = harness()
  const t = await token()
  // Spend this link's GET budget, which is keyed by its verified jti.
  for (let i = 0; i < 5; i++) assertEquals((await handler(get("/vault", t))).status, 200)
  // An unsigned flood of distinct spoofed addresses on a signed route: each one is a new
  // caller-chosen key, inserted before any signature is checked.
  for (let i = 0; i < 5000; i++) {
    await handler(
      new Request(`https://${AUD}/fluck-vault/inbox/claim`, {
        method: "POST",
        body: "{}",
        headers: {
          "content-type": "application/json",
          "x-forwarded-for": `10.${i >> 16}.${(i >> 8) & 255}.${i & 255}`,
        },
      }),
    )
  }
  const [spoofed, verified] = rateLimitEntries()
  assert(spoofed > 3000 && spoofed <= 4096, String(spoofed))
  assert(verified >= 1)
  // The flood did not reset the link's budget.
  assertEquals((await handler(get("/vault", t))).status, 429)
})

Deno.test("an oversized POST is refused before it is parsed or stored", async () => {
  const h = harness()
  const cookie = await opened(h)
  const huge = post("/vault", { j: JTI, c: "A".repeat(64 * 1024) }, { cookie })
  assertEquals((await h.handler(huge)).status, 400)
  assertEquals(h.stored.length, 0)
  for (const path of ["/requests", "/inbox/claim", "/instances"]) {
    const response = await h.handler(
      new Request(`https://${AUD}/fluck-vault${path}`, {
        method: "POST",
        body: JSON.stringify({ pad: "x".repeat(64 * 1024) }),
        headers: { "content-type": "application/json" },
      }),
    )
    assertEquals(response.status, 413, path)
  }
})

// ---------------------------------------------------------------------------------------------
// Everything else
// ---------------------------------------------------------------------------------------------

Deno.test("an unknown route is a fixed page and a PUT is refused", async () => {
  const { handler } = harness()
  assertEquals((await handler(new Request(`https://${AUD}/fluck-vault/nope`))).status, 404)
  const put = new Request(`https://${AUD}/fluck-vault/vault`, { method: "PUT", body: "x" })
  assertEquals((await handler(put)).status, 405)
})

Deno.test("no log line ever carries a body, a merchant or a full workspace id", async () => {
  const h = harness({ row: cvvRow(), result: { outcome: "stored", kind: "cvv" } })
  const cookie = await opened(h, "/cvv", await cvvToken(), CVV_JTI)
  await h.handler(post("/cvv", { j: CVV_JTI, c: blob() }, { cookie }))
  const joined = h.logs.join("\n")
  assert(joined.length > 0)
  assertEquals(joined.includes("Delta"), false)
  assertEquals(joined.includes("4242"), false)
  assertEquals(joined.includes(WS), false)
  assertStringIncludes(joined, WS.slice(0, 8))
})

Deno.test("a password page names the site it is saving a login for", async () => {
  const { handler } = harness({ row: vaultRow({ kind: "password", alias: "united.com" }) })
  const response = await handler(get("/vault", await token({ kind: "password" })))
  const html = await response.text()
  assertStringIncludes(html, "Save your united.com login")
  assertStringIncludes(html, PAGES.passwordIntro)
})
