/**
 * Route behaviour, driven through app.request() with `deps.fetch` stubbed so
 * no network is touched.
 *
 * Run: cd supabase/functions/live-sessions && deno test --allow-all
 */

import { assert, assertEquals, assertStringIncludes } from "@std/assert"
import { app, bearerToken, deps, emailFromJwt, isSessionRow } from "../app.ts"
import { resetRateLimits } from "../utils/rate-limit.ts"

const BASE = "/live-sessions"

type Call = { url: string; init?: RequestInit }

function withEnv(fn: () => Promise<void>): () => Promise<void> {
  return async () => {
    Deno.env.set("SUPABASE_URL", "https://stack.example")
    Deno.env.set("SUPABASE_ANON_KEY", "anon-key")
    Deno.env.set("LIVE_SESSIONS_PUBLIC_BASE_URL", "https://api.risaboss.com")
    resetRateLimits()
    try {
      await fn()
    } finally {
      Deno.env.delete("LIVE_SESSIONS_PUBLIC_BASE_URL")
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

/** A syntactically valid unsigned JWT carrying an email claim (display only). */
function fakeJwt(email: string): string {
  const b64 = (s: string) => btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
  return `${b64(JSON.stringify({ alg: "HS256" }))}.${b64(JSON.stringify({ sub: "u1", email }))}.sig-sig-sig-sig-sig`
}

const ROW = {
  share_id: "0123456789abcdef",
  device_name: "shivang_mac",
  session_name: "deploy",
  scope: "TAB",
  view_url: "https://x.trycloudflare.com/?t=view#k=abc",
  control_url: "https://x.trycloudflare.com/?t=acct#k=abc",
  secure: true,
  e2e_code: "deadbeef",
  app_version: "1.2.140",
  started_at: "2026-09-20T10:00:00Z",
  last_seen_at: "2026-09-20T10:05:00Z",
}

// ---- page ----

Deno.test("GET / renders the page with a nonce'd script and CSP, no-store", withEnv(async () => {
  const res = await app.request(`${BASE}/`)
  assertEquals(res.status, 200)
  const csp = res.headers.get("content-security-policy") ?? ""
  const nonce = /script-src 'nonce-([^']+)'/.exec(csp)?.[1]
  assert(nonce, "CSP must carry a script nonce")
  const html = await res.text()
  assertStringIncludes(html, `<script nonce="${nonce}">`)
  assertStringIncludes(html, `<style nonce="${nonce}">`)
  assertStringIncludes(html, 'id="signin-form"')
  assertStringIncludes(html, 'id="opening"') // single-session auto-open state exists
  assertStringIncludes(html, '"basePath":"/functions/v1/live-sessions"')
  assertEquals(res.headers.get("cache-control"), "no-store, max-age=0")
  assertEquals(res.headers.get("x-frame-options"), "DENY")
  assert(!html.includes("unsafe-inline"))
}))

Deno.test("GET /auth and the bare base path serve the same nonce'd page", withEnv(async () => {
  for (const path of [`${BASE}/auth`, BASE]) {
    const res = await app.request(path)
    assertEquals(res.status, 200, path)
    const nonce = /script-src 'nonce-([^']+)'/.exec(res.headers.get("content-security-policy") ?? "")?.[1]
    assert(nonce, `${path}: CSP nonce`)
    assertStringIncludes(await res.text(), `<script nonce="${nonce}">`)
  }
}))

Deno.test("POST /api/otp is 503 and sends nothing when LIVE_SESSIONS_PUBLIC_BASE_URL is unset", withEnv(async () => {
  Deno.env.delete("LIVE_SESSIONS_PUBLIC_BASE_URL")
  const stub = stubFetch(() => json({}))
  try {
    const res = await app.request(`${BASE}/api/otp`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-forwarded-host": "evil.example" },
      body: JSON.stringify({ email: "a@b.co" }),
    })
    assertEquals(res.status, 503)
    assertEquals(stub.calls.length, 0)
  } finally {
    stub.restore()
  }
}))

Deno.test("rate-limit key is the gateway-observed address, not a client-chosen leftmost XFF entry", withEnv(async () => {
  const stub = stubFetch(() => json({}))
  try {
    for (let i = 0; i < 5; i++) {
      // Attacker rotates the leftmost entry; the gateway-appended rightmost stays the same.
      const headers = { "Content-Type": "application/json", "x-forwarded-for": `10.0.0.${i}, 203.0.113.9` }
      const ok = await app.request(`${BASE}/api/otp`, { method: "POST", headers, body: JSON.stringify({ email: "a@b.co" }) })
      assertEquals(ok.status, 200)
    }
    const headers = { "Content-Type": "application/json", "x-forwarded-for": "10.0.0.99, 203.0.113.9" }
    const blocked = await app.request(`${BASE}/api/otp`, { method: "POST", headers, body: JSON.stringify({ email: "a@b.co" }) })
    assertEquals(blocked.status, 429)
  } finally {
    stub.restore()
  }
}))

// ---- otp ----

Deno.test("POST /api/otp sends a magic link with redirect_to=<base>/auth and hides account existence", withEnv(async () => {
  const stub = stubFetch(() => json({ msg: "user not found" }, 400)) // GoTrue 4xx
  try {
    const res = await app.request(`${BASE}/api/otp`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: "Someone@Example.com" }),
    })
    assertEquals(res.status, 200)
    assertEquals(await res.json(), { sent: true })
    assertEquals(stub.calls.length, 1)
    const url = new URL(stub.calls[0].url)
    assertEquals(url.pathname, "/auth/v1/otp")
    assertEquals(url.searchParams.get("redirect_to"), "https://api.risaboss.com/functions/v1/live-sessions/auth")
    assertEquals(JSON.parse(String(stub.calls[0].init?.body)).email, "someone@example.com")
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/otp rejects malformed email without calling GoTrue", withEnv(async () => {
  const stub = stubFetch(() => json({}))
  try {
    const res = await app.request(`${BASE}/api/otp`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: "nope" }),
    })
    assertEquals(res.status, 400)
    assertEquals(stub.calls.length, 0)
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/otp rate-limits the 6th attempt from one client", withEnv(async () => {
  const stub = stubFetch(() => json({}))
  try {
    const headers = { "Content-Type": "application/json", "x-forwarded-for": "203.0.113.9" }
    for (let i = 0; i < 5; i++) {
      const ok = await app.request(`${BASE}/api/otp`, { method: "POST", headers, body: JSON.stringify({ email: "a@b.co" }) })
      assertEquals(ok.status, 200)
    }
    const blocked = await app.request(`${BASE}/api/otp`, { method: "POST", headers, body: JSON.stringify({ email: "a@b.co" }) })
    assertEquals(blocked.status, 429)
    assertEquals(stub.calls.length, 5)
  } finally {
    stub.restore()
  }
}))

// ---- sessions ----

Deno.test("GET /api/sessions without a Bearer is 401 and never touches PostgREST", withEnv(async () => {
  const stub = stubFetch(() => json([ROW]))
  try {
    const res = await app.request(`${BASE}/api/sessions`)
    assertEquals(res.status, 401)
    assertEquals(stub.calls.length, 0)
  } finally {
    stub.restore()
  }
}))

Deno.test("GET /api/sessions forwards the user's JWT, applies the freshness filter, returns rows + email", withEnv(async () => {
  const stub = stubFetch(() => json([ROW, { junk: true }]))
  try {
    const jwt = fakeJwt("me@risalabs.ai")
    const res = await app.request(`${BASE}/api/sessions`, { headers: { Authorization: `Bearer ${jwt}` } })
    assertEquals(res.status, 200)
    const body = await res.json()
    assertEquals(body.sessions.length, 1) // shape guard drops the junk row
    assertEquals(body.sessions[0].control_url, ROW.control_url)
    assertEquals(body.email, "me@risalabs.ai")

    const call = stub.calls[0]
    const url = new URL(call.url)
    assertEquals(url.pathname, "/rest/v1/terminal_sessions")
    assert(url.searchParams.get("last_seen_at")?.startsWith("gt."), "must filter on freshness server-side")
    assertEquals(url.searchParams.get("order"), "last_seen_at.desc")
    const headers = call.init?.headers as Record<string, string>
    assertEquals(headers.Authorization, `Bearer ${jwt}`) // the USER's token, not a service key
    assertEquals(headers.apikey, "anon-key")
  } finally {
    stub.restore()
  }
}))

Deno.test("GET /api/sessions maps a PostgREST 401 (bad/expired JWT) to 401", withEnv(async () => {
  const stub = stubFetch(() => json({ message: "JWT expired" }, 401))
  try {
    const res = await app.request(`${BASE}/api/sessions`, { headers: { Authorization: `Bearer ${fakeJwt("x@y.z")}` } })
    assertEquals(res.status, 401)
  } finally {
    stub.restore()
  }
}))

// ---- cookie session ----

const SECURE = { "x-forwarded-proto": "https" }

function cookiePairs(res: Response): string[] {
  return res.headers.getSetCookie()
}

Deno.test("POST /api/session verifies the token with GoTrue and sets two HttpOnly path-scoped cookies", withEnv(async () => {
  const jwt = fakeJwt("me@risalabs.ai")
  const stub = stubFetch((call) => call.url.endsWith("/auth/v1/user") ? json({ email: "me@risalabs.ai" }) : json({}, 500))
  try {
    const res = await app.request(`${BASE}/api/session`, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...SECURE },
      // 12 chars: the length Supabase actually issues. A JWT-sized floor rejected real landings.
      body: JSON.stringify({ access_token: jwt, refresh_token: "ujxg5ngyirim" }),
    })
    assertEquals(res.status, 200)
    assertEquals(await res.json(), { ok: true, email: "me@risalabs.ai" })
    const cookies = cookiePairs(res)
    assertEquals(cookies.length, 2)
    for (const c of cookies) {
      assertStringIncludes(c, "HttpOnly")
      assertStringIncludes(c, "Secure")
      assertStringIncludes(c, "SameSite=Lax")
      assertStringIncludes(c, "Path=/functions/v1/live-sessions")
      assert(c.startsWith("__Secure-boss_live_"), c)
    }
    // the GoTrue lookup carried the token being vetted, not a service key
    const headers = stub.calls[0].init?.headers as Record<string, string>
    assertEquals(headers.Authorization, `Bearer ${jwt}`)
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/session refuses a token GoTrue does not recognise and sets nothing", withEnv(async () => {
  const stub = stubFetch(() => json({ msg: "invalid" }, 401))
  try {
    const res = await app.request(`${BASE}/api/session`, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...SECURE },
      body: JSON.stringify({ access_token: fakeJwt("x@y.z") }),
    })
    assertEquals(res.status, 401)
    assertEquals(cookiePairs(res).length, 0)
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/session and /api/logout refuse cross-site callers", withEnv(async () => {
  for (const path of ["/api/session", "/api/logout"]) {
    const res = await app.request(`${BASE}${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "sec-fetch-site": "cross-site" },
      body: "{}",
    })
    assertEquals(res.status, 403, path)
  }
}))

Deno.test("GET /api/sessions authenticates from the access cookie", withEnv(async () => {
  const jwt = fakeJwt("me@risalabs.ai")
  const stub = stubFetch(() => json([ROW]))
  try {
    const res = await app.request(`${BASE}/api/sessions`, {
      headers: { ...SECURE, cookie: `__Secure-boss_live_at=${jwt}; other=1` },
    })
    assertEquals(res.status, 200)
    const body = await res.json()
    assertEquals(body.sessions.length, 1)
    assertEquals(body.email, "me@risalabs.ai")
    const headers = stub.calls[0].init?.headers as Record<string, string>
    assertEquals(headers.Authorization, `Bearer ${jwt}`)
  } finally {
    stub.restore()
  }
}))

Deno.test("GET /api/sessions rotates an expired access cookie via the refresh cookie on the same response", withEnv(async () => {
  const oldJwt = fakeJwt("me@risalabs.ai")
  const newJwt = fakeJwt("me@risalabs.ai") + "n"
  const stub = stubFetch((call) => {
    if (call.url.includes("/rest/v1/terminal_sessions")) {
      const auth = (call.init?.headers as Record<string, string>).Authorization
      return auth === `Bearer ${newJwt}` ? json([ROW]) : json({ message: "JWT expired" }, 401)
    }
    if (call.url.includes("grant_type=refresh_token")) return json({ access_token: newJwt, refresh_token: "refresh-token-value-5678" })
    return json({}, 500)
  })
  try {
    const res = await app.request(`${BASE}/api/sessions`, {
      headers: { ...SECURE, cookie: `__Secure-boss_live_at=${oldJwt}; __Secure-boss_live_rt=ujxg5ngyirim` },
    })
    assertEquals(res.status, 200)
    assertEquals((await res.json()).sessions.length, 1)
    const cookies = cookiePairs(res)
    assertEquals(cookies.length, 2)
    assert(cookies.some((c) => c.startsWith(`__Secure-boss_live_at=${newJwt};`)), "new access cookie")
    assert(cookies.some((c) => c.startsWith("__Secure-boss_live_rt=refresh-token-value-5678;")), "rotated refresh cookie")
  } finally {
    stub.restore()
  }
}))

Deno.test("GET /api/sessions with a dead refresh cookie is 401 and clears both cookies", withEnv(async () => {
  const stub = stubFetch((call) => call.url.includes("grant_type=refresh_token") ? json({ msg: "invalid" }, 400) : json({}, 401))
  try {
    const res = await app.request(`${BASE}/api/sessions`, {
      headers: { ...SECURE, cookie: `__Secure-boss_live_rt=refresh-token-value-dead` },
    })
    assertEquals(res.status, 401)
    const cookies = cookiePairs(res)
    assertEquals(cookies.length, 2)
    for (const c of cookies) assertStringIncludes(c, "Max-Age=0")
  } finally {
    stub.restore()
  }
}))

Deno.test("GET /api/sessions with cookies refuses cross-site, but a Bearer caller is not gated on it", withEnv(async () => {
  const stub = stubFetch(() => json([ROW]))
  try {
    const blocked = await app.request(`${BASE}/api/sessions`, {
      headers: { ...SECURE, cookie: `__Secure-boss_live_at=${fakeJwt("a@b.c")}`, "sec-fetch-site": "cross-site" },
    })
    assertEquals(blocked.status, 403)
    const bearer = await app.request(`${BASE}/api/sessions`, {
      headers: { Authorization: `Bearer ${fakeJwt("a@b.c")}`, "sec-fetch-site": "cross-site" },
    })
    assertEquals(bearer.status, 200)
  } finally {
    stub.restore()
  }
}))

Deno.test("POST /api/logout clears both cookies", withEnv(async () => {
  const res = await app.request(`${BASE}/api/logout`, { method: "POST", headers: SECURE })
  assertEquals(res.status, 200)
  const cookies = cookiePairs(res)
  assertEquals(cookies.length, 2)
  for (const c of cookies) assertStringIncludes(c, "Max-Age=0")
}))

Deno.test("over plain http the cookies drop the __Secure- prefix and the Secure attribute", withEnv(async () => {
  const stub = stubFetch(() => json({ email: "a@b.c" }))
  try {
    const res = await app.request(`${BASE}/api/session`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ access_token: fakeJwt("a@b.c") }),
    })
    const cookies = cookiePairs(res)
    assertEquals(cookies.length, 1)
    assert(cookies[0].startsWith("boss_live_at="), cookies[0])
    assert(!cookies[0].includes("Secure"))
  } finally {
    stub.restore()
  }
}))

// ---- helpers ----

Deno.test("bearerToken accepts a JWT-shaped token and rejects garbage", () => {
  assertEquals(bearerToken(`Bearer ${fakeJwt("a@b.c")}`), fakeJwt("a@b.c"))
  assertEquals(bearerToken("Bearer short"), null)
  assertEquals(bearerToken("Basic abc"), null)
  assertEquals(bearerToken(undefined), null)
})

Deno.test("emailFromJwt reads the claim and tolerates junk", () => {
  assertEquals(emailFromJwt(fakeJwt("me@risalabs.ai")), "me@risalabs.ai")
  assertEquals(emailFromJwt("not.a.jwt"), "")
})

Deno.test("isSessionRow requires an http(s) control_url", () => {
  assert(isSessionRow(ROW))
  assert(!isSessionRow({ ...ROW, control_url: "javascript:alert(1)" }))
  assert(!isSessionRow({ ...ROW, control_url: undefined }))
})

Deno.test("LIVE_SESSIONS_PUBLIC_BASE_PATH=/ (vanity host) makes browser paths root-relative and cookies Path=/", withEnv(async () => {
  Deno.env.set("LIVE_SESSIONS_PUBLIC_BASE_URL", "https://cli.risaboss.com")
  Deno.env.set("LIVE_SESSIONS_PUBLIC_BASE_PATH", "/")
  const stub = stubFetch((call) => call.url.endsWith("/auth/v1/user") ? json({ email: "a@b.c" }) : json({}))
  try {
    const page = await (await app.request(`${BASE}/`)).text()
    assertStringIncludes(page, '"basePath":""')
    const otp = await app.request(`${BASE}/api/otp`, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ email: "a@b.co" }),
    })
    assertEquals(otp.status, 200)
    assertEquals(new URL(stub.calls[0].url).searchParams.get("redirect_to"), "https://cli.risaboss.com/auth")
    const sess = await app.request(`${BASE}/api/session`, {
      method: "POST", headers: { "Content-Type": "application/json", ...SECURE }, body: JSON.stringify({ access_token: fakeJwt("a@b.c") }),
    })
    assertStringIncludes(sess.headers.getSetCookie()[0], "Path=/;")
  } finally {
    stub.restore()
    Deno.env.delete("LIVE_SESSIONS_PUBLIC_BASE_PATH")
  }
}))

Deno.test("on a vanity host, page loads that did not come through the alias are redirected there; alias requests are served", withEnv(async () => {
  Deno.env.set("LIVE_SESSIONS_PUBLIC_BASE_URL", "https://cli.risaboss.com")
  Deno.env.set("LIVE_SESSIONS_PUBLIC_BASE_PATH", "/")
  try {
    const direct = await app.request(`${BASE}/auth?x=1`, { headers: { host: "api.risaboss.com" } })
    assertEquals(direct.status, 302)
    assertEquals(direct.headers.get("location"), "https://cli.risaboss.com/auth?x=1&_alias=1")
    assertEquals(direct.headers.get("referrer-policy"), "no-referrer", "redirects carry the security headers too")
    // Loop breaker: the marked request is served wherever it lands, and X-Forwarded-Host is ignored.
    const marked = await app.request(`${BASE}/auth?x=1&_alias=1`, { headers: { host: "api.risaboss.com", "x-forwarded-host": "elsewhere.example" } })
    assertEquals(marked.status, 200)
    const xfh = await app.request(`${BASE}/auth`, { headers: { host: "cli.risaboss.com", "x-forwarded-host": "api.risaboss.com" } })
    assertEquals(xfh.status, 200, "a rewritten X-Forwarded-Host must not trigger a redirect")
    const viaAlias = await app.request(`${BASE}/auth`, { headers: { host: "api.risaboss.com", "x-live-sessions-alias": "cli.risaboss.com" } })
    assertEquals(viaAlias.status, 200)
    assertStringIncludes(await viaAlias.text(), 'id="signin-form"')
    // API routes are never redirected: old pages keep working until they reload.
    const api = await app.request(`${BASE}/api/sessions`, { headers: { host: "api.risaboss.com" } })
    assertEquals(api.status, 401)
  } finally {
    Deno.env.delete("LIVE_SESSIONS_PUBLIC_BASE_PATH")
  }
}))

Deno.test("without a vanity host configured, page loads are served wherever they arrive", withEnv(async () => {
  const res = await app.request(`${BASE}/`, { headers: { host: "api.risaboss.com" } })
  assertEquals(res.status, 200)
}))

Deno.test("POST /api/otp refuses cross-site callers and never creates accounts", withEnv(async () => {
  const stub = stubFetch(() => json({}))
  try {
    const xs = await app.request(`${BASE}/api/otp`, {
      method: "POST", headers: { "Content-Type": "application/json", "sec-fetch-site": "cross-site" }, body: JSON.stringify({ email: "a@b.co" }),
    })
    assertEquals(xs.status, 403)
    assertEquals(stub.calls.length, 0)
    const ok = await app.request(`${BASE}/api/otp`, {
      method: "POST", headers: { "Content-Type": "application/json", "sec-fetch-site": "same-origin" }, body: JSON.stringify({ email: "a@b.co" }),
    })
    assertEquals(ok.status, 200)
    assertEquals(JSON.parse(String(stub.calls[0].init?.body)).create_user, false)
  } finally {
    stub.restore()
  }
}))

Deno.test("429 responses carry Retry-After", withEnv(async () => {
  const stub = stubFetch(() => json({}))
  try {
    const headers = { "Content-Type": "application/json", "x-forwarded-for": "198.51.100.7" }
    for (let i = 0; i < 5; i++) await app.request(`${BASE}/api/otp`, { method: "POST", headers, body: JSON.stringify({ email: "a@b.co" }) })
    const blocked = await app.request(`${BASE}/api/otp`, { method: "POST", headers, body: JSON.stringify({ email: "a@b.co" }) })
    assertEquals(blocked.status, 429)
    assert(Number(blocked.headers.get("retry-after")) > 0)
  } finally {
    stub.restore()
  }
}))

Deno.test("oversized bodies are refused by Content-Length before being read", withEnv(async () => {
  const res = await app.request(`${BASE}/api/session`, {
    method: "POST", headers: { "Content-Type": "application/json", "Content-Length": "999999" }, body: "{}",
  })
  assertEquals(res.status, 400)
}))
