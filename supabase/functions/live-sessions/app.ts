/**
 * Live Sessions Edge Function - routing.
 *
 * Lets a BOSS account holder sign in with a magic link from any browser, see
 * the terminal shares their signed-in BossTerm instances are publishing to
 * `terminal_sessions`, and open one in the share-viewer.
 *
 * Routes (browser-facing base is /functions/v1/live-sessions):
 *   GET  /            the page (sign-in form or session list)
 *   GET  /auth        same page; GoTrue's magic-link redirect target. The tokens
 *                     arrive in the URL FRAGMENT and are read by the page script.
 *   POST /api/otp     {email} -> sends the magic link with redirect_to=<base>/auth
 *   POST /api/session {access_token, refresh_token} -> verifies the pair with GoTrue and
 *                     sets the HttpOnly session cookies (see utils/cookies.ts)
 *   GET  /api/sessions  cookie (or Bearer) -> live rows for that user; rotates the access
 *                     cookie via the refresh cookie when it has expired
 *   POST /api/logout  clears the cookies
 *   GET  /health
 *
 * Trust model. This function never holds a service-role key. `/api/sessions`
 * forwards the caller's JWT to PostgREST, which validates it and applies the
 * owner-only RLS on terminal_sessions; a bad or foreign token is a 401 from
 * PostgREST that we pass straight back. There is nothing for this function to
 * get wrong about WHO may see WHAT. The cookies only move that JWT from the
 * page's hands into the browser's cookie jar.
 *
 * Cookie-authenticated routes refuse `Sec-Fetch-Site: cross-site`. The header is
 * not settable by page script, so with SameSite=Lax this is what stops another
 * origin from riding the cookies.
 *
 * verify_jwt is false (config.toml): `/` and `/auth` are header-less browser
 * page loads and `/api/otp` is pre-authentication.
 *
 * NO CORS MIDDLEWARE, DELIBERATELY - every caller is the page itself, same
 * origin. HTML only renders on the custom domain (see organisation/app.ts).
 */

import { OpenAPIHono } from "@hono/zod-openapi"
import { LIVE_WINDOW_SECONDS, publicBasePath, publicBaseUrl, readConfig } from "./utils/config.ts"
import { htmlResponse, jsonResponse, redirectResponse } from "./utils/responses.ts"
import { clientKey, rateLimit } from "./utils/rate-limit.ts"
import {
  accessCookieName,
  clearCookieHeaders,
  cookieToken,
  isSecureRequest,
  refreshCookieName,
  sessionCookieHeaders,
} from "./utils/cookies.ts"
import { livePage } from "./views/page.ts"

export const app = new OpenAPIHono().basePath("/live-sessions")

/** Test seam: the suite swaps this for a stub so no network is touched. */
export const deps = { fetch: (input: string, init?: RequestInit) => fetch(input, init) }

const OTP_LIMIT = 5
const OTP_WINDOW_SECONDS = 600
const SESSIONS_LIMIT = 120
const SESSION_LIMIT = 30
const SESSION_WINDOW_SECONDS = 300
// Access tokens are JWTs (hundreds of chars); Supabase refresh tokens are SHORT opaque strings
// (12 chars today), so the two cannot share one minimum. A 20-char floor on both rejected every
// real magic-link landing with 400.
const ACCESS_TOKEN_RE = /^[A-Za-z0-9._~+/=-]{20,4096}$/
const REFRESH_TOKEN_RE = /^[A-Za-z0-9._~+/=-]{8,4096}$/
const SESSIONS_WINDOW_SECONDS = 60
const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/
const BEARER_RE = /^Bearer\s+([A-Za-z0-9._~+/=-]{20,4096})$/

/** Columns the page needs. `id` is omitted: the page keys nothing on it. */
const SESSION_COLUMNS =
  "share_id,device_name,session_name,scope,view_url,control_url,secure,e2e_code,app_version,started_at,last_seen_at"

function page(): Response {
  return htmlResponse((nonce) => livePage({ basePath: publicBasePath(), liveWindowSeconds: LIVE_WINDOW_SECONDS }, nonce))
}

/**
 * When the page lives on a vanity host (LIVE_SESSIONS_PUBLIC_BASE_URL set to a host other than
 * the one the function is reached on), a page load that did not come through the alias Worker
 * is sent there, so cookies, links and the address bar all agree on one host. The Worker marks
 * its requests with X-Live-Sessions-Alias. The URL fragment (the magic link's tokens) survives a
 * 302 whose Location carries no fragment, so /auth landings on the old host still work.
 */
function aliasRedirect(ctx: { req: { url: string; header: (n: string) => string | undefined } }, route: string): Response | null {
  const base = publicBaseUrl()
  if (!base) return null
  const baseHost = (() => {
    try {
      return new URL(base).host
    } catch {
      return ""
    }
  })()
  // The Worker marks its requests. The header is client-settable, and that is fine: forging it
  // only skips this convenience redirect, it grants nothing.
  const viaAlias = ctx.req.header("x-live-sessions-alias")
  if (!baseHost || viaAlias === baseHost) return null
  // Host only - X-Forwarded-Host is rewritten by gateways and would make this loop. No host at all
  // (tests, odd clients) or the vanity host itself: nothing to correct.
  const reqHost = (ctx.req.header("host") ?? "").split(",")[0].trim()
  if (!reqHost || reqHost === baseHost) return null
  const url = (() => {
    try {
      return new URL(ctx.req.url)
    } catch {
      return null
    }
  })()
  // Loop breaker: a request that already carries the marker is served wherever it landed.
  if (url?.searchParams.get("_alias") === "1") return null
  const params = new URLSearchParams(url?.search ?? "")
  params.set("_alias", "1")
  return redirectResponse(`${base}${route}?${params.toString()}`, { status: 302 })
}

// Both spellings: the gateway hands us `/live-sessions` for the bare URL and `/live-sessions/` when
// the browser was given a trailing slash, and Hono matches them as different routes.
app.get("/", (ctx) => aliasRedirect(ctx, "") ?? page())
app.get("", (ctx) => aliasRedirect(ctx, "") ?? page())
app.get("/auth", (ctx) => aliasRedirect(ctx, "/auth") ?? page())

app.get("/health", () => jsonResponse({ status: "healthy" }))

/**
 * Send a magic link whose redirect_to points back at /auth.
 *
 * Always answers 200 {sent:true} on a well-formed email, whatever GoTrue said
 * about the account, so the endpoint cannot be used to enumerate users. GoTrue's
 * own `email_sent` rate limit still applies underneath ours.
 */
app.post("/api/otp", async (ctx) => {
  // A magic-link request is always a same-origin fetch from our own page; refuse other sites
  // driving a visitor's browser (and IP) at this mail-sending endpoint.
  if (ctx.req.header("sec-fetch-site") === "cross-site") return jsonResponse({ error: "forbidden" }, 403)
  const limit = rateLimit(`otp:${clientKey(ctx.req.raw.headers)}`, OTP_LIMIT, OTP_WINDOW_SECONDS)
  if (!limit.allowed) {
    return jsonResponse({ error: "rate_limited", retryAfterSeconds: limit.retryAfterSeconds }, 429, [], { "Retry-After": String(limit.retryAfterSeconds) })
  }

  const body = await readJson(ctx.req.raw)
  const email = typeof body?.email === "string" ? body.email.trim().toLowerCase() : ""
  if (!EMAIL_RE.test(email) || email.length > 254) {
    return jsonResponse({ error: "invalid_email" }, 400)
  }

  const cfg = readConfig()
  if (!cfg.supabaseUrl || !cfg.anonKey) return jsonResponse({ error: "not_configured" }, 503)

  const base = publicBaseUrl()
  if (!base) {
    console.error("LIVE_SESSIONS_PUBLIC_BASE_URL is not set; refusing to send a magic link with an unlisted redirect_to")
    return jsonResponse({ error: "not_configured" }, 503)
  }
  const redirectTo = `${base}/auth`

  try {
    const resp = await deps.fetch(`${cfg.supabaseUrl}/auth/v1/otp?redirect_to=${encodeURIComponent(redirectTo)}`, {
      method: "POST",
      headers: { apikey: cfg.anonKey, "Content-Type": "application/json" },
      // No account creation: this page is for the account the user already signs into BossTerm
      // with. An unknown address gets no mail and the same 200, so nothing is enumerable.
      body: JSON.stringify({ email, create_user: false }),
    })
    if (resp.status === 429) return jsonResponse({ error: "rate_limited", retryAfterSeconds: 60 }, 429)
    if (resp.status >= 500) {
      console.error("otp upstream", resp.status)
      return jsonResponse({ error: "upstream" }, 502)
    }
  } catch (err) {
    console.error("otp send failed", err)
    return jsonResponse({ error: "upstream" }, 502)
  }
  // 4xx from GoTrue (unknown user with signups disabled, etc.) is folded into
  // success on purpose - see the enumeration note above.
  return jsonResponse({ sent: true })
})

/**
 * Establish the cookie session from the tokens the magic link left in the fragment.
 *
 * The access token is checked against GoTrue (`/auth/v1/user`) before anything is set, so a
 * garbage or foreign token never becomes a cookie, and the response can tell the page who it
 * signed in as without the page decoding a JWT.
 */
app.post("/api/session", async (ctx) => {
  if (ctx.req.header("sec-fetch-site") === "cross-site") return jsonResponse({ error: "forbidden" }, 403)
  const limit = rateLimit(`session:${clientKey(ctx.req.raw.headers)}`, SESSION_LIMIT, SESSION_WINDOW_SECONDS)
  if (!limit.allowed) return jsonResponse({ error: "rate_limited", retryAfterSeconds: limit.retryAfterSeconds }, 429)

  const body = await readJson(ctx.req.raw)
  const accessToken = typeof body?.access_token === "string" ? body.access_token.trim() : ""
  const refreshToken = typeof body?.refresh_token === "string" ? body.refresh_token.trim() : ""
  if (!ACCESS_TOKEN_RE.test(accessToken) || (refreshToken && !REFRESH_TOKEN_RE.test(refreshToken))) {
    return jsonResponse({ error: "invalid_request" }, 400)
  }

  const cfg = readConfig()
  if (!cfg.supabaseUrl || !cfg.anonKey) return jsonResponse({ error: "not_configured" }, 503)

  const user = await gotrueUser(cfg, accessToken)
  if (!user) return jsonResponse({ error: "unauthorized" }, 401)

  const secure = isSecureRequest(ctx.req.url, ctx.req.header("x-forwarded-proto") ?? null)
  return jsonResponse(
    { ok: true, email: user.email ?? "" },
    200,
    sessionCookieHeaders(accessToken, refreshToken || null, secure, publicBasePath()),
  )
})

/** Sign out of the page: drop both cookies. The desktop app's own session is untouched. */
app.post("/api/logout", (ctx) => {
  if (ctx.req.header("sec-fetch-site") === "cross-site") return jsonResponse({ error: "forbidden" }, 403)
  const secure = isSecureRequest(ctx.req.url, ctx.req.header("x-forwarded-proto") ?? null)
  return jsonResponse({ ok: true }, 200, clearCookieHeaders(secure, publicBasePath()))
})

/**
 * Live sessions for the caller. The JWT goes to PostgREST untouched; RLS
 * restricts the rows to `auth.uid() = user_id`, and the freshness filter
 * hides anything the desktop stopped heartbeating.
 */
app.get("/api/sessions", async (ctx) => {
  const secure = isSecureRequest(ctx.req.url, ctx.req.header("x-forwarded-proto") ?? null)
  const cookieHeader = ctx.req.header("cookie") ?? null
  const bearer = bearerToken(ctx.req.header("authorization"))
  let token = bearer ?? cookieToken(cookieHeader, accessCookieName(secure))
  const refreshToken = bearer ? null : cookieToken(cookieHeader, refreshCookieName(secure))
  const viaCookie = !bearer

  if (viaCookie && ctx.req.header("sec-fetch-site") === "cross-site") return jsonResponse({ error: "forbidden" }, 403)
  if (!token && !refreshToken) return jsonResponse({ error: "unauthorized" }, 401)

  const limit = rateLimit(`sessions:${clientKey(ctx.req.raw.headers)}`, SESSIONS_LIMIT, SESSIONS_WINDOW_SECONDS)
  if (!limit.allowed) return jsonResponse({ error: "rate_limited", retryAfterSeconds: limit.retryAfterSeconds }, 429)

  const cfg = readConfig()
  if (!cfg.supabaseUrl || !cfg.anonKey) return jsonResponse({ error: "not_configured" }, 503)

  // One rotation: an expired access cookie (or none, after ACCESS_MAX_AGE) is exchanged via the
  // refresh cookie, and the fresh pair rides back on this same response.
  let setCookies: string[] = []
  let rows = token ? await fetchRows(cfg, token) : { status: 401, rows: null }
  if (rows.status === 401 && viaCookie && refreshToken) {
    const rotated = await gotrueRefresh(cfg, refreshToken)
    if (rotated) {
      token = rotated.accessToken
      setCookies = sessionCookieHeaders(rotated.accessToken, rotated.refreshToken, secure, publicBasePath())
      rows = await fetchRows(cfg, token)
    }
  }
  if (rows.status === 401) {
    return jsonResponse({ error: "unauthorized" }, 401, viaCookie ? clearCookieHeaders(secure, publicBasePath()) : [])
  }
  if (rows.status !== 200 || !rows.rows) return jsonResponse({ error: "upstream" }, 502)
  const preferences = ctx.req.query("terminal_preferences") === "1" ? await fetchTerminalPreferences(cfg, token!) : null
  return jsonResponse({ sessions: rows.rows, email: emailFromJwt(token!),
    ...(ctx.req.query("terminal_preferences") === "1" ? { terminal_preferences_owner: jwtDisplayClaim(token!, "sub") } : {}),
    ...(preferences ? { terminal_preferences: preferences } : {}),
  }, 200, setCookies)
})

app.notFound(() => jsonResponse({ error: "not_found" }, 404))

app.onError((err, _ctx) => {
  console.error("live-sessions error", err)
  return jsonResponse({ error: "internal" }, 500)
})

// ---- helpers ----

async function fetchRows(cfg: { supabaseUrl: string; anonKey: string }, token: string): Promise<{ status: number; rows: unknown[] | null }> {
  const since = new Date(Date.now() - LIVE_WINDOW_SECONDS * 1000).toISOString()
  const url = `${cfg.supabaseUrl}/rest/v1/terminal_sessions?select=${SESSION_COLUMNS}` +
    `&last_seen_at=gt.${encodeURIComponent(since)}&order=last_seen_at.desc&limit=100`
  try {
    const resp = await deps.fetch(url, {
      headers: { apikey: cfg.anonKey, Authorization: `Bearer ${token}`, Accept: "application/json" },
    })
    if (resp.status === 401 || resp.status === 403) return { status: 401, rows: null }
    if (!resp.ok) {
      console.error("sessions upstream", resp.status)
      return { status: resp.status, rows: null }
    }
    const body = await resp.json()
    return { status: 200, rows: Array.isArray(body) ? body.filter(isSessionRow) : [] }
  } catch (err) {
    console.error("sessions fetch failed", err)
    return { status: 502, rows: null }
  }
}

/** Optional additive RPC: an old backend or an outage leaves the viewer's defaults/cache intact. */
async function fetchTerminalPreferences(cfg: { supabaseUrl: string; anonKey: string }, token: string) {
  try {
    const response = await deps.fetch(`${cfg.supabaseUrl}/rest/v1/rpc/get_user_terminal_preferences`, {
      method: "POST",
      headers: { apikey: cfg.anonKey, Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: "{}",
      signal: AbortSignal.timeout(4000),
    })
    if (!response.ok) return null
    const value = await response.json()
    if (!value || !["batch", "preview"].includes(value.unfocused_mode) ||
      !Number.isInteger(value.unfocused_fps) || value.unfocused_fps < 1 || value.unfocused_fps > 30 ||
      !Number.isSafeInteger(value.revision) || value.revision < 0) return null
    return { unfocused_mode: value.unfocused_mode, unfocused_fps: value.unfocused_fps, revision: value.revision }
  } catch { return null }
}

/** GoTrue's view of the token's user, or null when it is not a valid live token. */
async function gotrueUser(cfg: { supabaseUrl: string; anonKey: string }, token: string): Promise<{ email?: string } | null> {
  try {
    const resp = await deps.fetch(`${cfg.supabaseUrl}/auth/v1/user`, {
      headers: { apikey: cfg.anonKey, Authorization: `Bearer ${token}` },
    })
    if (!resp.ok) return null
    const json = await resp.json() as Record<string, unknown>
    return { email: typeof json.email === "string" ? json.email : undefined }
  } catch (err) {
    console.error("gotrue user lookup failed", err)
    return null
  }
}

async function gotrueRefresh(cfg: { supabaseUrl: string; anonKey: string }, refreshToken: string): Promise<{ accessToken: string; refreshToken: string } | null> {
  try {
    const resp = await deps.fetch(`${cfg.supabaseUrl}/auth/v1/token?grant_type=refresh_token`, {
      method: "POST",
      headers: { apikey: cfg.anonKey, "Content-Type": "application/json" },
      body: JSON.stringify({ refresh_token: refreshToken }),
    })
    if (!resp.ok) return null
    const json = await resp.json() as Record<string, unknown>
    if (typeof json.access_token !== "string") return null
    return { accessToken: json.access_token, refreshToken: typeof json.refresh_token === "string" ? json.refresh_token : refreshToken }
  } catch (err) {
    console.error("refresh failed", err)
    return null
  }
}

async function readJson(req: Request): Promise<Record<string, unknown> | null> {
  try {
    // Refuse oversized bodies before buffering them; the length check after reading is the
    // backstop for a missing Content-Length.
    const declared = Number(req.headers.get("content-length") ?? "0")
    if (declared > 8192) return null
    const text = await req.text()
    if (text.length > 8192) return null
    const parsed = JSON.parse(text)
    return parsed && typeof parsed === "object" ? parsed as Record<string, unknown> : null
  } catch {
    return null
  }
}

export function bearerToken(header: string | undefined | null): string | null {
  if (!header) return null
  const match = BEARER_RE.exec(header.trim())
  return match ? match[1] : null
}

/**
 * Display-only: the email claim of the caller's JWT. The token is NOT verified
 * here (PostgREST did that for the data), so this is never used for a decision,
 * only for the "signed in as" label after the data call has already succeeded.
 */
export function emailFromJwt(token: string): string { return jwtDisplayClaim(token, "email") }

function jwtDisplayClaim(token: string, claim: string): string {
  try {
    const payload = token.split(".")[1]
    const padded = payload.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - payload.length % 4) % 4)
    const json = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(padded), (c) => c.charCodeAt(0))))
    return typeof json[claim] === "string" ? json[claim] : ""
  } catch {
    return ""
  }
}

/** Shape guard so a surprising row can never reach the page as something else. */
export function isSessionRow(row: unknown): boolean {
  if (!row || typeof row !== "object") return false
  const r = row as Record<string, unknown>
  return typeof r.share_id === "string" && typeof r.device_name === "string" &&
    typeof r.control_url === "string" && /^https?:\/\//.test(r.control_url) &&
    typeof r.last_seen_at === "string"
}
