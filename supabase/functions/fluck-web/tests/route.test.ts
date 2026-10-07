/**
 * Same-host routing: the /portal base path, the __Host-fluck_route cookie minted by /api/open, and
 * the Worker-only GET /internal/endpoint.
 *
 * Run: cd supabase/functions/fluck-web && deno test --allow-all
 */

import { assert, assertEquals, assertMatch, assertStringIncludes } from "@std/assert"
import { app, deps, userIdFromJwt } from "../app.ts"
import { resetRateLimits } from "../utils/rate-limit.ts"
import { ROUTE_MAX_AGE_SECONDS, routeCookieValue } from "../utils/route.ts"

const BASE = "/fluck-web"
const ORIGIN = "https://fluck.risaboss.com"
const CSRF = "a".repeat(64)
const TICKET = "A".repeat(40) + "b-_"
const ALIAS_SECRET = "s".repeat(64)
const ROUTE_SECRET = "r".repeat(64)
const SERVICE_KEY = "service-role-key"
const USER = "0b6f2c1e-5d4a-4c3b-9a8f-7e6d5c4b3a21"
const OTHER_USER = "9f8e7d6c-5b4a-4321-8fed-cba987654321"
const Q = `?user=${USER}&instance=inst-a`
const ENV_VARS = [
  "SUPABASE_URL", "SUPABASE_ANON_KEY", "SUPABASE_SERVICE_ROLE_KEY", "FLUCK_WEB_PUBLIC_BASE_URL",
  "FLUCK_WEB_PUBLIC_BASE_PATH", "FLUCK_WEB_ALIAS_SECRET", "FLUCK_ROUTE_SECRET",
]
const ALIAS = { host: "api.risaboss.com", "x-fluck-web-alias": "fluck.risaboss.com", "x-fluck-web-alias-secret": ALIAS_SECRET }

type Call = { url: string; init?: RequestInit }

function withEnv(fn: () => Promise<void>, extra: Record<string, string> = {}): () => Promise<void> {
  return async () => {
    Deno.env.set("SUPABASE_URL", "https://stack.example")
    Deno.env.set("SUPABASE_ANON_KEY", "anon-key")
    Deno.env.set("SUPABASE_SERVICE_ROLE_KEY", SERVICE_KEY)
    Deno.env.set("FLUCK_WEB_PUBLIC_BASE_URL", ORIGIN)
    Deno.env.set("FLUCK_WEB_PUBLIC_BASE_PATH", "/portal")
    Deno.env.set("FLUCK_WEB_ALIAS_SECRET", ALIAS_SECRET)
    for (const [k, v] of Object.entries(extra)) Deno.env.set(k, v)
    resetRateLimits()
    try {
      await fn()
    } finally {
      for (const name of ENV_VARS) Deno.env.delete(name)
    }
  }
}

const withRoute = (fn: () => Promise<void>) => withEnv(fn, { FLUCK_ROUTE_SECRET: ROUTE_SECRET })

function stubFetch(handler: (call: Call) => Response): { calls: Call[]; restore: () => void } {
  const calls: Call[] = []
  const original = deps.fetch
  deps.fetch = (url: string, init?: RequestInit) => {
    const call = { url, init }
    calls.push(call)
    return Promise.resolve(handler(call))
  }
  return { calls, restore: () => (deps.fetch = original) }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } })
}

function b64urlDecode(s: string): Uint8Array<ArrayBuffer> {
  return Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - s.length % 4) % 4)), (c) => c.charCodeAt(0))
}

function b64url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
}

function fakeJwt(claims: Record<string, unknown>): string {
  const b = (s: string) => btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
  return `${b(JSON.stringify({ alg: "HS256" }))}.${b(JSON.stringify(claims))}.sig-sig-sig-sig-sig`
}
const JWT = fakeJwt({ sub: USER, email: "me@risalabs.ai" })

const ROW = {
  instance_id: "inst-a",
  label: "Shivanshu MacBook",
  agent_name: "Fluck",
  endpoint_url: "https://a.trycloudflare.com",
  app_version: "1.0.120",
  started_at: "2026-10-06T10:00:00Z",
  last_seen_at: "2026-10-06T10:05:00Z",
  online: true,
}

function openBackend() {
  return stubFetch((call) => {
    if (call.url.endsWith("/rest/v1/rpc/fluck_web_list_instances")) return json([ROW])
    if (call.url.endsWith("/rest/v1/rpc/fluck_web_mint_ticket")) return json(TICKET)
    return json({}, 500)
  })
}

function openRequest(jwt = JWT, body: Record<string, unknown> = { instance_id: "inst-a" }): Promise<Response> {
  return Promise.resolve(app.request(`${BASE}/api/open`, {
    method: "POST",
    headers: {
      ...ALIAS,
      "x-forwarded-proto": "https",
      "Content-Type": "application/json",
      origin: ORIGIN,
      "x-fluck-web-csrf": CSRF,
      cookie: `__Secure-boss_fluck_at=${jwt}; __Host-boss_fluck_csrf=${CSRF}`,
    },
    body: JSON.stringify(body),
  }))
}

// ---- base path ----

Deno.test("with base path /portal every link, fetch and redirect_to carries /portal", withEnv(async () => {
  const res = await app.request(`${BASE}/`, { headers: ALIAS })
  assertEquals(res.status, 200)
  const html = await res.text()
  assertStringIncludes(html, '"basePath":"/portal"')
  assertStringIncludes(html, 'href="/portal/api/oauth/google"')
  assertStringIncludes(html, 'href="/portal/api/oauth/apple"')
  assert(!/href="\/api\//.test(html), "no root-relative API link")
  assertStringIncludes(html, "fetch(base + path")

  const stub = stubFetch(() => json({}))
  try {
    const otp = await app.request(`${BASE}/api/otp`, {
      method: "POST",
      headers: { ...ALIAS, "Content-Type": "application/json" },
      body: JSON.stringify({ email: "me@risalabs.ai" }),
    })
    assertEquals(otp.status, 200)
    assertStringIncludes(stub.calls[0].url, `redirect_to=${encodeURIComponent("https://fluck.risaboss.com/portal/auth")}`)
  } finally {
    stub.restore()
  }

  const oauth = await app.request(`${BASE}/api/oauth/google`, { headers: { ...ALIAS, "x-forwarded-proto": "https" } })
  assertStringIncludes(oauth.headers.get("location")!, encodeURIComponent("https://fluck.risaboss.com/portal/auth"))
  assertStringIncludes(oauth.headers.getSetCookie()[0], "Path=/portal;")
}))

Deno.test("with base path /portal a direct hit redirects to /portal on the vanity host; session cookies are Path=/portal", withEnv(async () => {
  const direct = await app.request(`${BASE}/auth?instance=inst-a`, { headers: { host: "api.risaboss.com" } })
  assertEquals(direct.status, 302)
  assertEquals(direct.headers.get("location"), "https://fluck.risaboss.com/portal/auth?instance=inst-a")

  const stub = stubFetch(() => json({ email: "me@risalabs.ai" }))
  try {
    const res = await app.request(`${BASE}/api/session`, {
      method: "POST",
      headers: { ...ALIAS, "x-forwarded-proto": "https", "Content-Type": "application/json" },
      body: JSON.stringify({ access_token: JWT, refresh_token: "refresh-token" }),
    })
    assertEquals(res.status, 200)
    for (const c of res.headers.getSetCookie()) assertStringIncludes(c, "Path=/portal;")
  } finally {
    stub.restore()
  }
  const logout = await app.request(`${BASE}/api/logout`, {
    method: "POST",
    headers: { ...ALIAS, "x-forwarded-proto": "https", "Content-Type": "application/json" },
    body: "{}",
  })
  for (const c of logout.headers.getSetCookie()) assertStringIncludes(c, "Path=/portal;")
}))

Deno.test("the page script handles ?reopen= without auto-opening", withEnv(async () => {
  const html = await (await app.request(`${BASE}/`, { headers: ALIAS })).text()
  const script = /<script nonce="[^"]+">([\s\S]*?)<\/script>/.exec(html)![1]
  new Function(script)
  assertStringIncludes(script, 'params.get("reopen")')
  assertStringIncludes(script, "if (reopen) autoOpenDone = true;")
  assertStringIncludes(script, "Your BOSS's address changed; open it again.")
  assertStringIncludes(html, "li.reopen")
}))

// ---- /api/open ----

Deno.test("POST /api/open without FLUCK_ROUTE_SECRET returns the tunnel URL and sets no route cookie", withEnv(async () => {
  const stub = openBackend()
  try {
    const res = await openRequest()
    assertEquals(res.status, 200)
    assertEquals(await res.json(), { url: `https://a.trycloudflare.com/#/t/${TICKET}` })
    assert(!res.headers.getSetCookie().some((c) => c.startsWith("__Host-fluck_route=")))
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/open with FLUCK_ROUTE_SECRET returns the public origin URL and a signed route cookie", withRoute(async () => {
  const stub = openBackend()
  try {
    const before = Math.floor(Date.now() / 1000)
    const res = await openRequest()
    assertEquals(res.status, 200)
    assertEquals(await res.json(), { url: `${ORIGIN}/#/t/${TICKET}` })
    const cookie = res.headers.getSetCookie().find((c) => c.startsWith("__Host-fluck_route="))!
    assert(cookie, "route cookie set")
    assertEquals(cookie.split("; ").slice(1).sort(), ["HttpOnly", "Max-Age=2592000", "Path=/", "SameSite=Lax", "Secure"])
    const value = cookie.split(";")[0].slice("__Host-fluck_route=".length)
    assertMatch(value, /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]{43}$/)
    const [payload, sig] = value.split(".")
    const claims = JSON.parse(new TextDecoder().decode(b64urlDecode(payload)))
    assertEquals(Object.keys(claims), ["u", "i", "e"])
    assertEquals(claims.u, USER, "the owner is the authenticated caller")
    assertEquals(claims.i, "inst-a")
    assert(claims.e >= before + ROUTE_MAX_AGE_SECONDS && claims.e <= before + ROUTE_MAX_AGE_SECONDS + 5)
    const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(ROUTE_SECRET), { name: "HMAC", hash: "SHA-256" }, false, ["sign"])
    const expected = b64url(new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(payload))))
    assertEquals(sig, expected, "HMAC-SHA256(FLUCK_ROUTE_SECRET, payload)")
  } finally {
    stub.restore()
  }
}))

Deno.test("routeCookieValue is deterministic for a given clock and binds the owner", async () => {
  const a = await routeCookieValue(ROUTE_SECRET, USER, "inst-a", 1000)
  assertEquals(a, await routeCookieValue(ROUTE_SECRET, USER, "inst-a", 1000))
  assertEquals(JSON.parse(new TextDecoder().decode(b64urlDecode(a.split(".")[0]))), { u: USER, i: "inst-a", e: 1000 + ROUTE_MAX_AGE_SECONDS })
  assert(a !== await routeCookieValue("x".repeat(64), USER, "inst-a", 1000))
  assert(a !== await routeCookieValue(ROUTE_SECRET, OTHER_USER, "inst-a", 1000))
})

Deno.test("POST /api/open takes the owner from the session token, never from the body", withRoute(async () => {
  const stub = openBackend()
  try {
    const res = await openRequest(JWT, { instance_id: "inst-a", user_id: OTHER_USER, u: OTHER_USER })
    assertEquals(res.status, 200)
    const value = res.headers.getSetCookie().find((c) => c.startsWith("__Host-fluck_route="))!.split(";")[0].split("=")[1]
    assertEquals(JSON.parse(new TextDecoder().decode(b64urlDecode(value.split(".")[0]))).u, USER)
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/open refuses to set a route when the accepted token has no user id", withRoute(async () => {
  const original = console.error
  console.error = () => {}
  const stub = openBackend()
  try {
    const res = await openRequest(fakeJwt({ sub: "not-a-uuid", email: "me@risalabs.ai" }))
    assertEquals(res.status, 502)
    assert(!res.headers.getSetCookie().some((c) => c.startsWith("__Host-fluck_route=")))
  } finally {
    stub.restore()
    console.error = original
  }
}))

Deno.test("userIdFromJwt reads a uuid sub, lowercased, and nothing else", () => {
  assertEquals(userIdFromJwt(fakeJwt({ sub: USER.toUpperCase() })), USER)
  assertEquals(userIdFromJwt(fakeJwt({ sub: "u1" })), null)
  assertEquals(userIdFromJwt(fakeJwt({})), null)
  assertEquals(userIdFromJwt("junk"), null)
})

Deno.test("a FLUCK_ROUTE_SECRET under 32 chars is treated as unset", withEnv(async () => {
  const stub = openBackend()
  try {
    const res = await openRequest()
    assertEquals((await res.json()).url, `https://a.trycloudflare.com/#/t/${TICKET}`)
  } finally {
    stub.restore()
  }
}, { FLUCK_ROUTE_SECRET: "short" }))

// ---- /internal/endpoint ----

const internal = (query: string, secret?: string) =>
  Promise.resolve(app.request(`${BASE}/internal/endpoint${query}`, { headers: secret === undefined ? {} : { "x-fluck-route-secret": secret } }))

const live = (endpoint_url: string, ageSeconds = 5) => ({ endpoint_url, last_seen_at: new Date(Date.now() - ageSeconds * 1000).toISOString() })

Deno.test("GET /internal/endpoint is 404 when FLUCK_ROUTE_SECRET is unset, and never queries", withEnv(async () => {
  const stub = stubFetch(() => json([live("https://a.trycloudflare.com")]))
  try {
    for (const secret of [undefined, "", ROUTE_SECRET]) assertEquals((await internal(Q, secret)).status, 404)
    assertEquals(stub.calls.length, 0)
  } finally {
    stub.restore()
  }
}))

Deno.test("GET /internal/endpoint is 404 for a missing or wrong secret, or a malformed id, before any query", withRoute(async () => {
  const stub = stubFetch(() => json([live("https://a.trycloudflare.com")]))
  try {
    for (const secret of [undefined, "", "wrong", ROUTE_SECRET + "x", ROUTE_SECRET.slice(1)]) {
      assertEquals((await internal(Q, secret)).status, 404, String(secret))
    }
    for (
      const q of [
        "", "?instance=inst-a", `?user=${USER}`, `?user=${USER}&instance=`, `?user=${USER}&instance=a%2Fb`,
        `?user=${USER}&instance=${"x".repeat(129)}`, "?user=u1&instance=inst-a", `?user=${USER}x&instance=inst-a`,
      ]
    ) assertEquals((await internal(q, ROUTE_SECRET)).status, 404, q)
    assertEquals(stub.calls.length, 0)
  } finally {
    stub.restore()
  }
}))

Deno.test("GET /internal/endpoint returns the live endpoint, read with the service role", withRoute(async () => {
  const stub = stubFetch(() => json([live("https://A.trycloudflare.com:443/")]))
  try {
    const res = await internal(Q, ROUTE_SECRET)
    assertEquals(res.status, 200)
    assertEquals(await res.json(), { endpoint: "https://a.trycloudflare.com" })
    assertEquals(res.headers.get("cache-control"), "no-store, max-age=0")
    const call = stub.calls[0]
    const u = new URL(call.url)
    assertEquals(u.origin + u.pathname, "https://stack.example/rest/v1/fluck_web_instances")
    assertEquals(u.searchParams.get("user_id"), `eq.${USER}`)
    assertEquals(u.searchParams.get("instance_id"), "eq.inst-a")
    assertMatch(u.searchParams.get("last_seen_at")!, /^gt\.\d{4}-\d\d-\d\dT/)
    const h = new Headers(call.init?.headers)
    assertEquals(h.get("apikey"), SERVICE_KEY)
    assertEquals(h.get("authorization"), `Bearer ${SERVICE_KEY}`)
  } finally {
    stub.restore()
  }
}))

Deno.test("GET /internal/endpoint is 404 for an unknown, stale, non-https, or not-exactly-one row", withRoute(async () => {
  const cases: unknown[] = [
    [],
    [live("https://a.trycloudflare.com", 600)],
    [live("http://a.trycloudflare.com")],
    [live("https://a.trycloudflare.com/path")],
    [live("https://a.trycloudflare.com"), live("https://a.trycloudflare.com")],
    { not: "an array" },
  ]
  for (const rows of cases) {
    const stub = stubFetch(() => json(rows))
    try {
      assertEquals((await internal(Q, ROUTE_SECRET)).status, 404, JSON.stringify(rows))
    } finally {
      stub.restore()
    }
  }
}))

Deno.test("GET /internal/endpoint: the same instance id under two accounts resolves to each owner's own BOSS", withRoute(async () => {
  const table = [
    { user_id: USER, instance_id: "inst-a", ...live("https://mine.trycloudflare.com") },
    { user_id: OTHER_USER, instance_id: "inst-a", ...live("https://theirs.trycloudflare.com") },
  ]
  // A PostgREST stand-in that applies the eq. filters, so the test proves the query filters on both.
  const stub = stubFetch((call) => {
    const p = new URL(call.url).searchParams
    return json(table.filter((r) => `eq.${r.user_id}` === p.get("user_id") && `eq.${r.instance_id}` === p.get("instance_id"))
      .map(({ endpoint_url, last_seen_at }) => ({ endpoint_url, last_seen_at })))
  })
  try {
    assertEquals(await (await internal(`?user=${USER}&instance=inst-a`, ROUTE_SECRET)).json(), { endpoint: "https://mine.trycloudflare.com" })
    assertEquals(await (await internal(`?user=${OTHER_USER}&instance=inst-a`, ROUTE_SECRET)).json(), { endpoint: "https://theirs.trycloudflare.com" })
    assertEquals((await internal(`?user=${USER}&instance=inst-b`, ROUTE_SECRET)).status, 404)
  } finally {
    stub.restore()
  }
}))

Deno.test("GET /internal/endpoint maps an upstream failure to 502 and a missing service key to 503", withRoute(async () => {
  const original = console.error
  console.error = () => {}
  try {
    const stub = stubFetch(() => json({}, 500))
    try {
      assertEquals((await internal(Q, ROUTE_SECRET)).status, 502)
    } finally {
      stub.restore()
    }
    Deno.env.delete("SUPABASE_SERVICE_ROLE_KEY")
    assertEquals((await internal(Q, ROUTE_SECRET)).status, 503)
  } finally {
    console.error = original
  }
}))
