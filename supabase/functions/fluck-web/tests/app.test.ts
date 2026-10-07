/**
 * Route behaviour, driven through app.request() with `deps.fetch` stubbed so no network is touched.
 * Mirrors live-sessions/tests/app.test.ts.
 *
 * Run: cd supabase/functions/fluck-web && deno test --allow-all
 */

import { assert, assertEquals, assertStringIncludes } from "@std/assert"
import { app, codeChallenge, deps, emailFromJwt, httpsOrigin, isInstanceRow } from "../app.ts"
import { clientKey, resetRateLimits } from "../utils/rate-limit.ts"
import { FLUCK_MARK } from "../views/page.ts"

const BASE = "/fluck-web"
const ORIGIN = "https://fluck.risaboss.com"
const SECURE = { "x-forwarded-proto": "https" }
const CSRF = "a".repeat(64)
const TICKET = "A".repeat(40) + "b-_"
const ALIAS_SECRET = "s".repeat(64)
const ENV_VARS = ["SUPABASE_URL", "SUPABASE_ANON_KEY", "FLUCK_WEB_PUBLIC_BASE_URL", "FLUCK_WEB_PUBLIC_BASE_PATH", "FLUCK_WEB_ALIAS_SECRET"]

type Call = { url: string; init?: RequestInit }

function withEnv(fn: () => Promise<void>): () => Promise<void> {
  return async () => {
    Deno.env.set("SUPABASE_URL", "https://stack.example")
    Deno.env.set("SUPABASE_ANON_KEY", "anon-key")
    Deno.env.set("FLUCK_WEB_PUBLIC_BASE_URL", ORIGIN)
    Deno.env.set("FLUCK_WEB_PUBLIC_BASE_PATH", "/")
    Deno.env.set("FLUCK_WEB_ALIAS_SECRET", ALIAS_SECRET)
    resetRateLimits()
    try {
      await fn()
    } finally {
      for (const name of ENV_VARS) Deno.env.delete(name)
    }
  }
}

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

function fakeJwt(email: string): string {
  const b64 = (s: string) => btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
  return `${b64(JSON.stringify({ alg: "HS256" }))}.${b64(JSON.stringify({ sub: "u1", email }))}.sig-sig-sig-sig-sig`
}

function auth(call: Call): string {
  return new Headers(call.init?.headers).get("Authorization") ?? ""
}

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

const JWT = fakeJwt("me@risalabs.ai")

function openRequest(body: unknown, headers: Record<string, string> = {}): Promise<Response> {
  return Promise.resolve(app.request(`${BASE}/api/open`, {
    method: "POST",
    headers: {
      ...SECURE,
      "Content-Type": "application/json",
      origin: ORIGIN,
      "x-fluck-web-csrf": CSRF,
      cookie: `__Secure-boss_fluck_at=${JWT}; __Host-boss_fluck_csrf=${CSRF}`,
      ...headers,
    },
    body: JSON.stringify(body),
  }))
}

/** list -> [ROW], mint -> TICKET, unless overridden. */
function backend(over: { list?: () => Response; mint?: () => Response } = {}) {
  return stubFetch((call) => {
    if (call.url.endsWith("/rest/v1/rpc/fluck_web_list_instances")) return over.list ? over.list() : json([ROW])
    if (call.url.endsWith("/rest/v1/rpc/fluck_web_mint_ticket")) return over.mint ? over.mint() : json(TICKET)
    return json({}, 500)
  })
}

// ---- page ----

Deno.test("GET / renders the Fluck page with nonce'd script and style, strict CSP, no-store, no framing", withEnv(async () => {
  const res = await app.request(`${BASE}/`)
  assertEquals(res.status, 200)
  const csp = res.headers.get("content-security-policy") ?? ""
  const nonce = /script-src 'nonce-([^']+)'/.exec(csp)?.[1]
  assert(nonce, "CSP must carry a script nonce")
  assertStringIncludes(csp, "default-src 'none'")
  assertStringIncludes(csp, "frame-ancestors 'none'")
  assert(!csp.includes("frame-src"), "the page never frames a Fluck")
  const html = await res.text()
  assertStringIncludes(html, `<script nonce="${nonce}">`)
  assertStringIncludes(html, `<style nonce="${nonce}">`)
  assertStringIncludes(html, "<title>Fluck</title>")
  assertStringIncludes(html, FLUCK_MARK)
  assertStringIncludes(html, 'id="signin-form"')
  assertStringIncludes(html, 'href="/api/oauth/google"')
  assertStringIncludes(html, 'href="/api/oauth/apple"')
  assertStringIncludes(html, "Turn on Web chat in Fluck → Settings, and choose a way to reach it")
  assertStringIncludes(html, '"basePath":""')
  assertEquals(res.headers.get("cache-control"), "no-store, max-age=0")
  assertEquals(res.headers.get("x-frame-options"), "DENY")
  assertEquals(res.headers.get("referrer-policy"), "no-referrer")
  assertEquals(res.headers.get("access-control-allow-origin"), null, "no CORS")
  assert(!html.includes("unsafe-inline"))
  assert(!/\p{Extended_Pictographic}/u.test(html), "no emoji")
  assert(!html.includes("\u2014"), "no em-dash")
}))

Deno.test("the page script parses and navigates top-level only to an https /#/t/ URL", withEnv(async () => {
  const html = await (await app.request(`${BASE}/`)).text()
  const script = /<script nonce="[^"]+">([\s\S]*?)<\/script>/.exec(html)![1]
  new Function(script) // syntax check; throws on a parse error
  assertStringIncludes(script, "location.assign(data.url)")
  assert(!script.includes("iframe"))
  const re = /^https:\/\/[A-Za-z0-9.-]+(:[0-9]{1,5})?\/#\/t\/[A-Za-z0-9_-]{43}$/
  assertStringIncludes(script, "var OPEN_URL_RE = " + re.toString())
}))

Deno.test("GET /auth and the bare base path serve the same page", withEnv(async () => {
  for (const path of [`${BASE}/auth`, BASE]) {
    const res = await app.request(path)
    assertEquals(res.status, 200, path)
    assertStringIncludes(await res.text(), 'id="signin-form"')
  }
}))

Deno.test("page loads that did not come through the alias go to fluck.risaboss.com; alias requests are served", withEnv(async () => {
  const direct = await app.request(`${BASE}/?instance=inst-a`, { headers: { host: "api.risaboss.com" } })
  assertEquals(direct.status, 302)
  assertEquals(direct.headers.get("location"), "https://fluck.risaboss.com/?instance=inst-a")
  const viaAlias = await app.request(`${BASE}/auth`, {
    headers: { host: "api.risaboss.com", "x-fluck-web-alias": "fluck.risaboss.com", "x-fluck-web-alias-secret": ALIAS_SECRET },
  })
  assertEquals(viaAlias.status, 200)
  const api = await app.request(`${BASE}/api/instances`, { headers: { host: "api.risaboss.com" } })
  assertEquals(api.status, 401, "API routes are never redirected")
}))

Deno.test("?_alias=1 no longer serves the page off the base host (its API calls would all 404)", withEnv(async () => {
  for (const path of [`${BASE}/?_alias=1`, `${BASE}/auth?_alias=1&instance=inst-a`]) {
    const res = await app.request(path, { headers: { host: "api.risaboss.com" } })
    assertEquals(res.status, 302, path)
    assert(!res.headers.get("location")!.includes("_alias"), path)
  }
}))

Deno.test("a forged or unverifiable alias marker is refused, not served and not redirected", withEnv(async () => {
  const cases: Record<string, string>[] = [
    { "x-fluck-web-alias": "fluck.risaboss.com" },
    { "x-fluck-web-alias": "fluck.risaboss.com", "x-fluck-web-alias-secret": "wrong" },
    { "x-fluck-web-alias": "fluck.risaboss.com", "x-fluck-web-alias-secret": ALIAS_SECRET + "x" },
  ]
  for (const headers of cases) {
    const res = await app.request(`${BASE}/`, { headers: { host: "api.risaboss.com", ...headers } })
    assertEquals(res.status, 503, JSON.stringify(headers))
    assertEquals((await res.json()).error, "alias_unverified")
  }
  Deno.env.set("FLUCK_WEB_ALIAS_SECRET", "short")
  const weak = await app.request(`${BASE}/`, { headers: { host: "api.risaboss.com", "x-fluck-web-alias": "fluck.risaboss.com", "x-fluck-web-alias-secret": "short" } })
  assertEquals(weak.status, 503, "a secret under 32 chars is treated as unset")
}))

Deno.test("GET /api/oauth/:provider off the base host goes to the vanity host first", withEnv(async () => {
  const direct = await app.request(`${BASE}/api/oauth/google`, { headers: { ...SECURE, host: "api.risaboss.com" } })
  assertEquals(direct.status, 302)
  assertEquals(direct.headers.get("location"), "https://fluck.risaboss.com/api/oauth/google")
  assertEquals(direct.headers.getSetCookie().length, 0)
  const viaAlias = await app.request(`${BASE}/api/oauth/google`, {
    headers: { ...SECURE, host: "api.risaboss.com", "x-fluck-web-alias": "fluck.risaboss.com", "x-fluck-web-alias-secret": ALIAS_SECRET },
  })
  assertStringIncludes(viaAlias.headers.get("location")!, "/auth/v1/authorize?provider=google")
}))

Deno.test("GET /health", withEnv(async () => {
  const res = await app.request(`${BASE}/health`)
  assertEquals(res.status, 200)
  assertEquals(await res.json(), { status: "healthy" })
}))

// ---- sign-in ----

Deno.test("POST /api/otp sends a magic link to <base>/auth, never creates accounts, hides existence", withEnv(async () => {
  const stub = stubFetch(() => json({ msg: "user not found" }, 400))
  try {
    const res = await app.request(`${BASE}/api/otp`, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ email: "Me@Example.com" }),
    })
    assertEquals(res.status, 200)
    assertEquals(await res.json(), { sent: true })
    const url = new URL(stub.calls[0].url)
    assertEquals(url.pathname, "/auth/v1/otp")
    assertEquals(url.searchParams.get("redirect_to"), "https://fluck.risaboss.com/auth")
    const body = JSON.parse(String(stub.calls[0].init?.body))
    assertEquals(body, { email: "me@example.com", create_user: false })
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/otp is 503 and sends nothing when FLUCK_WEB_PUBLIC_BASE_URL is unset", withEnv(async () => {
  Deno.env.delete("FLUCK_WEB_PUBLIC_BASE_URL")
  const stub = stubFetch(() => json({}))
  try {
    const res = await app.request(`${BASE}/api/otp`, {
      method: "POST", headers: { "Content-Type": "application/json", "x-forwarded-host": "evil.example" }, body: JSON.stringify({ email: "a@b.co" }),
    })
    assertEquals(res.status, 503)
    assertEquals(stub.calls.length, 0)
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/otp rejects malformed email, cross-site callers, and rate-limits the 6th attempt", withEnv(async () => {
  const stub = stubFetch(() => json({}))
  try {
    const bad = await app.request(`${BASE}/api/otp`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ email: "nope" }) })
    assertEquals(bad.status, 400)
    const xs = await app.request(`${BASE}/api/otp`, {
      method: "POST", headers: { "Content-Type": "application/json", "sec-fetch-site": "cross-site" }, body: JSON.stringify({ email: "a@b.co" }),
    })
    assertEquals(xs.status, 403)
    const headers = { "Content-Type": "application/json", "x-forwarded-for": "203.0.113.9" }
    for (let i = 0; i < 5; i++) {
      assertEquals((await app.request(`${BASE}/api/otp`, { method: "POST", headers, body: JSON.stringify({ email: "a@b.co" }) })).status, 200)
    }
    const blocked = await app.request(`${BASE}/api/otp`, { method: "POST", headers, body: JSON.stringify({ email: "a@b.co" }) })
    assertEquals(blocked.status, 429)
    assert(Number(blocked.headers.get("retry-after")) > 0)
    assertEquals(stub.calls.length, 5)
  } finally {
    stub.restore()
  }
}))

Deno.test("the OTP brake ignores a client-supplied X-Fluck-Web-Client-Ip unless the Worker proved itself", withEnv(async () => {
  const stub = stubFetch(() => json({}))
  try {
    const send = (headers: Record<string, string>) =>
      app.request(`${BASE}/api/otp`, { method: "POST", headers: { "Content-Type": "application/json", ...headers }, body: JSON.stringify({ email: "a@b.co" }) })
    // Direct caller rotating the header: one bucket (the platform's address), so the 6th is refused.
    for (let i = 0; i < 5; i++) assertEquals((await send({ "x-forwarded-for": "203.0.113.9", "x-fluck-web-client-ip": `10.0.0.${i}` })).status, 200)
    assertEquals((await send({ "x-forwarded-for": "203.0.113.9", "x-fluck-web-client-ip": "10.0.0.99" })).status, 429)
    // Forged marker plus header without the secret: still the platform's bucket.
    assertEquals((await send({ "x-forwarded-for": "203.0.113.9", "x-fluck-web-client-ip": "10.0.1.1", "x-fluck-web-alias-secret": "nope" })).status, 429)
    // Through the Worker (secret present) each visitor gets their own bucket, even though all
    // arrive from the same Cloudflare egress.
    const alias = { "x-forwarded-for": "203.0.113.9", "x-fluck-web-alias-secret": ALIAS_SECRET }
    assertEquals((await send({ ...alias, "x-fluck-web-client-ip": "198.51.100.1" })).status, 200)
    assertEquals((await send({ ...alias, "x-fluck-web-client-ip": "198.51.100.2" })).status, 200)
  } finally {
    stub.restore()
  }
}))

Deno.test("clientKey trusts the Worker header only with the secret, and bounds the key", withEnv(() => {
  const h = (o: Record<string, string>) => new Headers(o)
  assertEquals(clientKey(h({ "x-fluck-web-client-ip": "1.1.1.1", "x-forwarded-for": "9.9.9.9, 8.8.8.8" })), "8.8.8.8")
  assertEquals(clientKey(h({ "x-fluck-web-client-ip": "1.1.1.1", "cf-connecting-ip": "7.7.7.7" })), "7.7.7.7")
  assertEquals(clientKey(h({ "x-fluck-web-client-ip": "1.1.1.1", "x-fluck-web-alias-secret": ALIAS_SECRET, "cf-connecting-ip": "7.7.7.7" })), "1.1.1.1")
  assertEquals(clientKey(h({ "x-fluck-web-client-ip": "x".repeat(500), "x-fluck-web-alias-secret": ALIAS_SECRET })).length, 64)
  Deno.env.delete("FLUCK_WEB_ALIAS_SECRET")
  assertEquals(clientKey(h({ "x-fluck-web-client-ip": "1.1.1.1", "x-fluck-web-alias-secret": "", "x-real-ip": "6.6.6.6" })), "6.6.6.6")
  return Promise.resolve()
}))

Deno.test("POST /api/otp maps GoTrue's own 429 to 429 with Retry-After", withEnv(async () => {
  const stub = stubFetch(() => json({ msg: "email rate limit exceeded" }, 429))
  try {
    const res = await app.request(`${BASE}/api/otp`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ email: "a@b.co" }) })
    assertEquals(res.status, 429)
    assertEquals(res.headers.get("retry-after"), "60")
  } finally {
    stub.restore()
  }
}))

Deno.test("every local 429 carries Retry-After: /api/session, /api/instances, /api/open", withEnv(async () => {
  const stub = stubFetch(() => json({}, 401))
  try {
    const ip = { "x-forwarded-for": "203.0.113.50" }
    const session = () => app.request(`${BASE}/api/session`, { method: "POST", headers: { "Content-Type": "application/json", ...ip }, body: "{}" })
    for (let i = 0; i < 30; i++) await session()
    const s = await session()
    assertEquals(s.status, 429)
    assert(Number(s.headers.get("retry-after")) > 0)
    const list = () => app.request(`${BASE}/api/instances`, { headers: ip })
    for (let i = 0; i < 120; i++) await list()
    const l = await list()
    assertEquals(l.status, 429)
    assert(Number(l.headers.get("retry-after")) > 0)
    for (let i = 0; i < 30; i++) await openRequest({ instance_id: "inst-a" }, ip)
    const o = await openRequest({ instance_id: "inst-a" }, ip)
    assertEquals(o.status, 429)
    assert(Number(o.headers.get("retry-after")) > 0)
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/session verifies with GoTrue and sets two __Secure- HttpOnly Lax cookies at Path=/", withEnv(async () => {
  const stub = stubFetch((call) => call.url.endsWith("/auth/v1/user") ? json({ email: "me@risalabs.ai" }) : json({}, 500))
  try {
    const res = await app.request(`${BASE}/api/session`, {
      method: "POST", headers: { "Content-Type": "application/json", ...SECURE },
      body: JSON.stringify({ access_token: JWT, refresh_token: "ujxg5ngyirim" }),
    })
    assertEquals(res.status, 200)
    assertEquals(await res.json(), { ok: true, email: "me@risalabs.ai" })
    const cookies = res.headers.getSetCookie()
    assertEquals(cookies.length, 2)
    for (const c of cookies) {
      assert(c.startsWith("__Secure-boss_fluck_"), c)
      for (const attr of ["HttpOnly", "Secure", "SameSite=Lax", "Path=/;"]) assertStringIncludes(c, attr)
    }
    assertEquals(auth(stub.calls[0]), `Bearer ${JWT}`)
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/session refuses an unknown token; session and logout refuse cross-site", withEnv(async () => {
  const stub = stubFetch(() => json({ msg: "invalid" }, 401))
  try {
    const res = await app.request(`${BASE}/api/session`, {
      method: "POST", headers: { "Content-Type": "application/json", ...SECURE }, body: JSON.stringify({ access_token: JWT }),
    })
    assertEquals(res.status, 401)
    assertEquals(res.headers.getSetCookie().length, 0)
    for (const path of ["/api/session", "/api/logout"]) {
      const xs = await app.request(`${BASE}${path}`, { method: "POST", headers: { "Content-Type": "application/json", "sec-fetch-site": "cross-site" }, body: "{}" })
      assertEquals(xs.status, 403, path)
    }
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/logout clears both cookies", withEnv(async () => {
  const res = await app.request(`${BASE}/api/logout`, { method: "POST", headers: SECURE })
  assertEquals(res.status, 200)
  const cookies = res.headers.getSetCookie()
  assertEquals(cookies.length, 2)
  for (const c of cookies) assertStringIncludes(c, "Max-Age=0")
}))

Deno.test("over plain http the cookies drop the __Secure- prefix and Secure", withEnv(async () => {
  const stub = stubFetch(() => json({ email: "a@b.c" }))
  try {
    const res = await app.request(`${BASE}/api/session`, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ access_token: JWT }),
    })
    const cookies = res.headers.getSetCookie()
    assertEquals(cookies.length, 1)
    assert(cookies[0].startsWith("boss_fluck_at="), cookies[0])
    assert(!cookies[0].includes("Secure"))
  } finally {
    stub.restore()
  }
}))

Deno.test("GET /api/oauth/google sets a verifier cookie and redirects to GoTrue with S256 and redirect_to=<base>/auth", withEnv(async () => {
  const res = await app.request(`${BASE}/api/oauth/google`, { headers: SECURE })
  assertEquals(res.status, 302)
  const location = new URL(res.headers.get("location")!)
  assertEquals(location.origin + location.pathname, "https://stack.example/auth/v1/authorize")
  assertEquals(location.searchParams.get("provider"), "google")
  assertEquals(location.searchParams.get("redirect_to"), "https://fluck.risaboss.com/auth")
  assertEquals(location.searchParams.get("code_challenge_method"), "s256")
  const cookie = res.headers.getSetCookie().find((c) => c.startsWith("__Secure-boss_fluck_pkce="))!
  assertStringIncludes(cookie, "HttpOnly")
  const verifier = cookie.split(";")[0].split("=")[1]
  assertEquals(location.searchParams.get("code_challenge"), await codeChallenge(verifier))
  assertEquals((await app.request(`${BASE}/api/oauth/github`, { headers: SECURE })).status, 404)
  assertEquals((await app.request(`${BASE}/api/oauth/apple`, { headers: { ...SECURE, "sec-fetch-site": "cross-site" } })).status, 403)
}))

Deno.test("GET /auth?code= exchanges with the verifier cookie, sets the session and returns to the page", withEnv(async () => {
  const stub = stubFetch((call) => call.url.includes("grant_type=pkce") ? json({ access_token: JWT, refresh_token: "ujxg5ngyirim" }) : json({}, 500))
  try {
    const res = await app.request(`${BASE}/auth?code=abc-123`, { headers: { ...SECURE, cookie: "__Secure-boss_fluck_pkce=verifier-verifier-verifier" } })
    assertEquals(res.status, 302)
    assertEquals(res.headers.get("location"), "/")
    const body = JSON.parse(String(stub.calls[0].init?.body))
    assertEquals(body, { auth_code: "abc-123", code_verifier: "verifier-verifier-verifier" })
    const cookies = res.headers.getSetCookie()
    assert(cookies.some((c) => c.startsWith(`__Secure-boss_fluck_at=${JWT};`)))
    assert(cookies.some((c) => c.startsWith("__Secure-boss_fluck_pkce=;") && c.includes("Max-Age=0")))
    const noVerifier = await app.request(`${BASE}/auth?code=abc-123`, { headers: SECURE })
    assertEquals(noVerifier.headers.get("location"), "/auth?oauth_error=expired")
  } finally {
    stub.restore()
  }
}))

// ---- list ----

Deno.test("GET /api/instances without cookies is 401 and never touches PostgREST", withEnv(async () => {
  const stub = backend()
  try {
    const res = await app.request(`${BASE}/api/instances`, { headers: SECURE })
    assertEquals(res.status, 401)
    assertEquals(stub.calls.length, 0)
  } finally {
    stub.restore()
  }
}))

Deno.test("GET /api/instances calls fluck_web_list_instances as the user, filters rows, hides endpoints, issues a CSRF nonce", withEnv(async () => {
  const stub = backend({ list: () => json([ROW, { ...ROW, instance_id: "x", endpoint_url: "http://plain.example" }, { junk: true }]) })
  try {
    const res = await app.request(`${BASE}/api/instances`, { headers: { ...SECURE, cookie: `__Secure-boss_fluck_at=${JWT}` } })
    assertEquals(res.status, 200)
    const body = await res.json()
    assertEquals(body.email, "me@risalabs.ai")
    assertEquals(body.instances.length, 1)
    assertEquals(body.instances[0].instance_id, "inst-a")
    assertEquals(body.instances[0].endpoint_url, undefined, "the page never sees an endpoint")
    assert(/^[a-f0-9]{64}$/.test(body.csrf))
    const csrfCookie = res.headers.getSetCookie().find((c) => c.startsWith("__Host-boss_fluck_csrf="))!
    assertStringIncludes(csrfCookie, `=${body.csrf};`)
    for (const attr of ["Path=/;", "HttpOnly", "SameSite=Strict", "Secure"]) assertStringIncludes(csrfCookie, attr)
    assertEquals(auth(stub.calls[0]), `Bearer ${JWT}`)
    assertEquals(new Headers(stub.calls[0].init?.headers).get("apikey"), "anon-key")
    assertEquals(stub.calls[0].init?.method, "POST")
  } finally {
    stub.restore()
  }
}))

Deno.test("GET /api/instances keeps an existing CSRF nonce so two tabs agree", withEnv(async () => {
  const stub = backend()
  try {
    const res = await app.request(`${BASE}/api/instances`, { headers: { ...SECURE, cookie: `__Secure-boss_fluck_at=${JWT}; __Host-boss_fluck_csrf=${CSRF}` } })
    assertEquals((await res.json()).csrf, CSRF)
  } finally {
    stub.restore()
  }
}))

Deno.test("GET /api/instances rotates an expired access cookie via the refresh cookie on the same response", withEnv(async () => {
  const fresh = fakeJwt("me@risalabs.ai") + "n"
  const stub = stubFetch((call) => {
    if (call.url.endsWith("/rpc/fluck_web_list_instances")) return auth(call) === `Bearer ${fresh}` ? json([ROW]) : json({ message: "JWT expired" }, 401)
    if (call.url.includes("grant_type=refresh_token")) return json({ access_token: fresh, refresh_token: "refresh-token-value-5678" })
    return json({}, 500)
  })
  try {
    const res = await app.request(`${BASE}/api/instances`, {
      headers: { ...SECURE, cookie: `__Secure-boss_fluck_at=${JWT}; __Secure-boss_fluck_rt=ujxg5ngyirim` },
    })
    assertEquals(res.status, 200)
    assertEquals((await res.json()).instances.length, 1)
    const cookies = res.headers.getSetCookie()
    assert(cookies.some((c) => c.startsWith(`__Secure-boss_fluck_at=${fresh};`)), "new access cookie")
    assert(cookies.some((c) => c.startsWith("__Secure-boss_fluck_rt=refresh-token-value-5678;")), "rotated refresh cookie")
  } finally {
    stub.restore()
  }
}))

Deno.test("GET /api/instances with a dead refresh cookie is 401 and clears both cookies", withEnv(async () => {
  const stub = stubFetch((call) => call.url.includes("grant_type=refresh_token") ? json({ msg: "invalid" }, 400) : json({}, 401))
  try {
    const res = await app.request(`${BASE}/api/instances`, { headers: { ...SECURE, cookie: "__Secure-boss_fluck_rt=refresh-token-value-dead" } })
    assertEquals(res.status, 401)
    const cookies = res.headers.getSetCookie()
    assertEquals(cookies.length, 2)
    for (const c of cookies) assertStringIncludes(c, "Max-Age=0")
  } finally {
    stub.restore()
  }
}))

Deno.test("a rotated refresh token of the wrong shape never reaches a cookie", withEnv(async () => {
  const fresh = fakeJwt("me@risalabs.ai") + "n"
  for (const refresh_token of ["bad token\r\nSet-Cookie: x=y", "short", 42]) {
    const stub = stubFetch((call) => {
      if (call.url.includes("grant_type=refresh_token")) return json({ access_token: fresh, refresh_token })
      return auth(call) === `Bearer ${fresh}` ? json([ROW]) : json({ message: "JWT expired" }, 401)
    })
    try {
      const res = await app.request(`${BASE}/api/instances`, { headers: { ...SECURE, cookie: "__Secure-boss_fluck_rt=refresh-token-value-old" } })
      assertEquals(res.status, 401, String(refresh_token))
      assert(!res.headers.getSetCookie().some((c) => c.includes(fresh)), String(refresh_token))
    } finally {
      stub.restore()
    }
  }
}))

Deno.test("GET /api/instances refuses cross-site callers", withEnv(async () => {
  const stub = backend()
  try {
    const res = await app.request(`${BASE}/api/instances`, { headers: { ...SECURE, cookie: `__Secure-boss_fluck_at=${JWT}`, "sec-fetch-site": "cross-site" } })
    assertEquals(res.status, 403)
    assertEquals(stub.calls.length, 0)
  } finally {
    stub.restore()
  }
}))

// ---- open ----

Deno.test("POST /api/open mints a ticket as the user and returns <endpoint>/#/t/<ticket>", withEnv(async () => {
  const stub = backend()
  try {
    const res = await openRequest({ instance_id: "inst-a" })
    assertEquals(res.status, 200)
    assertEquals(await res.json(), { url: `https://a.trycloudflare.com/#/t/${TICKET}` })
    const mint = stub.calls.find((c) => c.url.endsWith("/rpc/fluck_web_mint_ticket"))!
    assertEquals(JSON.parse(String(mint.init?.body)), { p_instance_id: "inst-a" })
    assertEquals(auth(mint), `Bearer ${JWT}`, "the USER's token, not a service key")
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/open refuses a wrong or missing Origin, CSRF header, CSRF cookie, or cross-site fetch, before any upstream call", withEnv(async () => {
  const stub = backend()
  try {
    const cases: Record<string, string>[] = [
      { origin: "https://evil.example" },
      { origin: "https://fluck.risaboss.com.evil.example" },
      { origin: "" },
      { "x-fluck-web-csrf": "b".repeat(64) },
      { "x-fluck-web-csrf": "" },
      { cookie: `__Secure-boss_fluck_at=${JWT}` },
      { cookie: `__Secure-boss_fluck_at=${JWT}; __Host-boss_fluck_csrf=short`, "x-fluck-web-csrf": "short" },
      { "sec-fetch-site": "cross-site" },
    ]
    for (const headers of cases) {
      const res = await openRequest({ instance_id: "inst-a" }, headers)
      assertEquals(res.status, 403, JSON.stringify(headers))
    }
    assertEquals(stub.calls.length, 0)
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/open requires JSON and a well-formed instance id", withEnv(async () => {
  const stub = backend()
  try {
    assertEquals((await openRequest({ instance_id: "inst-a" }, { "Content-Type": "text/plain" })).status, 415)
    assertEquals((await openRequest({ instance_id: "bad id" })).status, 400)
    assertEquals((await openRequest({})).status, 400)
    assertEquals(stub.calls.length, 0)
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/open is 409 for an offline or unknown instance and never mints", withEnv(async () => {
  for (const rows of [[{ ...ROW, online: false }], []]) {
    const stub = backend({ list: () => json(rows) })
    try {
      const res = await openRequest({ instance_id: "inst-a" })
      assertEquals(res.status, 409)
      assertEquals((await res.json()).error, "instance_unavailable")
      assert(!stub.calls.some((c) => c.url.endsWith("/rpc/fluck_web_mint_ticket")))
    } finally {
      stub.restore()
    }
  }
}))

Deno.test("POST /api/open maps the RPC's instance_unavailable (went offline between list and mint) to 409", withEnv(async () => {
  const stub = backend({ mint: () => json({ code: "P0001", message: "instance_unavailable" }, 400) })
  try {
    assertEquals((await openRequest({ instance_id: "inst-a" })).status, 409)
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/open maps the RPC's too_many_tickets cap to 429 with Retry-After", withEnv(async () => {
  const stub = backend({ mint: () => json({ code: "54000", message: "too_many_tickets" }, 500) })
  try {
    const res = await openRequest({ instance_id: "inst-a" })
    assertEquals(res.status, 429)
    assertEquals((await res.json()).error, "rate_limited")
    assertEquals(res.headers.get("retry-after"), "60")
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/open opens a row the table CHECK accepts with an uppercase host or :443, normalised", withEnv(async () => {
  for (const endpoint of ["https://Mac.Tail1234.ts.net", "https://a.trycloudflare.com:443", "https://A.trycloudflare.com/"]) {
    const stub = backend({ list: () => json([{ ...ROW, endpoint_url: endpoint }]) })
    try {
      const res = await openRequest({ instance_id: "inst-a" })
      assertEquals(res.status, 200, endpoint)
      const url = new URL((await res.json()).url)
      assertEquals(url.origin, new URL(endpoint).origin, endpoint)
    } finally {
      stub.restore()
    }
  }
}))

Deno.test("POST /api/open never navigates to a non-https or non-origin endpoint", withEnv(async () => {
  for (const endpoint of ["http://a.example", "javascript:alert(1)", "https://a.example/path", "https://u:p@a.example", "https://a.example?x=1"]) {
    const stub = backend({ list: () => json([{ ...ROW, endpoint_url: endpoint }]) })
    try {
      const res = await openRequest({ instance_id: "inst-a" })
      assertEquals(res.status, 409, endpoint) // the shape guard drops the row, so it is "not available"
      assert(!stub.calls.some((c) => c.url.endsWith("/rpc/fluck_web_mint_ticket")), endpoint)
    } finally {
      stub.restore()
    }
  }
}))

Deno.test("POST /api/open refuses a malformed ticket from upstream", withEnv(async () => {
  const stub = backend({ mint: () => json("not-a-ticket") })
  try {
    assertEquals((await openRequest({ instance_id: "inst-a" })).status, 502)
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/open rotates an expired access token once and mints with the fresh one", withEnv(async () => {
  const fresh = fakeJwt("me@risalabs.ai") + "n"
  const stub = stubFetch((call) => {
    if (call.url.includes("grant_type=refresh_token")) return json({ access_token: fresh, refresh_token: "refresh-token-value-5678" })
    if (auth(call) !== `Bearer ${fresh}`) return json({ message: "JWT expired" }, 401)
    if (call.url.endsWith("/rpc/fluck_web_list_instances")) return json([ROW])
    if (call.url.endsWith("/rpc/fluck_web_mint_ticket")) return json(TICKET)
    return json({}, 500)
  })
  try {
    const res = await openRequest({ instance_id: "inst-a" }, {
      cookie: `__Secure-boss_fluck_at=${JWT}; __Secure-boss_fluck_rt=ujxg5ngyirim; __Host-boss_fluck_csrf=${CSRF}`,
    })
    assertEquals(res.status, 200)
    assert(res.headers.getSetCookie().some((c) => c.startsWith(`__Secure-boss_fluck_at=${fresh};`)))
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/open without a session is 401", withEnv(async () => {
  const stub = backend()
  try {
    const res = await openRequest({ instance_id: "inst-a" }, { cookie: `__Host-boss_fluck_csrf=${CSRF}` })
    assertEquals(res.status, 401)
    assertEquals(stub.calls.length, 0)
  } finally {
    stub.restore()
  }
}))

// ---- helpers ----

Deno.test("httpsOrigin accepts only bare https origins", () => {
  assertEquals(httpsOrigin("https://a.trycloudflare.com"), "https://a.trycloudflare.com")
  assertEquals(httpsOrigin("https://mac.tail.ts.net:8443/"), "https://mac.tail.ts.net:8443")
  assertEquals(httpsOrigin("https://Mac.Tail.ts.net"), "https://mac.tail.ts.net")
  assertEquals(httpsOrigin("https://a.example:443"), "https://a.example")
  for (const bad of ["http://a.example", "https://a.example/x", "https://a.example/#x", "https://a.example?y", "https://u@a.example", "ftp://a", "https://a.example//", "https://a.example:99999", "https://%61.example", "", 42, null]) {
    assertEquals(httpsOrigin(bad), null, String(bad))
  }
})

Deno.test("isInstanceRow requires the full shape and an https origin", () => {
  assert(isInstanceRow(ROW))
  assert(!isInstanceRow({ ...ROW, online: "yes" }))
  assert(!isInstanceRow({ ...ROW, instance_id: "a b" }))
  assert(!isInstanceRow({ ...ROW, endpoint_url: "http://a.example" }))
})

Deno.test("emailFromJwt reads the claim and tolerates junk", () => {
  assertEquals(emailFromJwt(JWT), "me@risalabs.ai")
  assertEquals(emailFromJwt("not.a.jwt"), "")
})
