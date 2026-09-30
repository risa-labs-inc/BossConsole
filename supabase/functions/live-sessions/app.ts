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
 *   GET  /api/oauth/{google|apple}  starts a Google / Apple sign-in: sets a short-lived PKCE
 *                     verifier cookie and 302s to GoTrue's /authorize with redirect_to=<base>/auth
 *   GET  /auth?code=  the provider's return: exchanges the code with the verifier cookie, sets
 *                     the session cookies and 302s to the page without the code
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
import { authPublicUrl, LIVE_WINDOW_SECONDS, publicBasePath, publicBaseUrl, readConfig } from "./utils/config.ts"
import { htmlResponse, jsonResponse, redirectResponse } from "./utils/responses.ts"
import { clientKey, rateLimit } from "./utils/rate-limit.ts"
import {
  accessCookieName,
  clearCookieHeaders,
  clearPkceCookieHeader,
  cookieToken,
  isSecureRequest,
  pkceCookieHeader,
  pkceCookieName,
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
const OAUTH_PROVIDERS = new Set(["google", "apple"])
const OAUTH_LIMIT = 20
const OAUTH_WINDOW_SECONDS = 300
/** GoTrue's PKCE auth codes: a UUID today; bounded and URL-safe so a format change still parses. */
const AUTH_CODE_RE = /^[A-Za-z0-9._~-]{1,512}$/
/** An OAuth `error` code as GoTrue and the providers write it; anything else is not logged. */
const OAUTH_ERROR_RE = /^[a-z_]{1,64}$/
/**
 * Provider returns get their own budget rather than the `session:` one: a burst of returns
 * (retries, a user bouncing between providers) must not lock the page out of establishing a
 * session from a magic link, and vice versa.
 */
const OAUTH_RETURN_LIMIT = 20
const OAUTH_RETURN_WINDOW_SECONDS = 300

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
app.get("/auth", async (ctx) => aliasRedirect(ctx, "/auth") ?? (await oauthReturn(ctx)) ?? page())

/**
 * Start a Google or Apple sign-in.
 *
 * PKCE, with the verifier in an HttpOnly cookie: the page keeps its no-third-party-script CSP
 * (no supabase-js in the browser), and the verifier never reaches page script or storage. The
 * start is a top-level navigation from the page's own link, so a cross-site request here is
 * refused like every other route that sets cookies.
 *
 * New accounts are allowed: unlike the magic link (create_user: false), a first Google or Apple
 * sign-in creates the BOSS account, the same as the desktop apps.
 */
app.get("/api/oauth/:provider", async (ctx) => {
  if (ctx.req.header("sec-fetch-site") === "cross-site") return jsonResponse({ error: "forbidden" }, 403)
  const provider = ctx.req.param("provider")
  if (!OAUTH_PROVIDERS.has(provider)) return jsonResponse({ error: "not_found" }, 404)
  // The verifier cookie is set on the host that serves this request and the provider returns to
  // publicBaseUrl(), so a start on any other host would plant the cookie where the return never
  // looks. Send it to the canonical host first, as the page routes do.
  const alias = aliasRedirect(ctx, `/api/oauth/${provider}`)
  if (alias) return alias
  const limit = rateLimit(`oauth:${clientKey(ctx.req.raw.headers)}`, OAUTH_LIMIT, OAUTH_WINDOW_SECONDS)
  if (!limit.allowed) return oauthErrorRedirect("rate_limited")

  const base = publicBaseUrl()
  const authUrl = authPublicUrl()
  if (!base || !authUrl) {
    console.error("LIVE_SESSIONS_PUBLIC_BASE_URL or the auth URL is not set; refusing to start OAuth")
    return jsonResponse({ error: "not_configured" }, 503)
  }

  const verifier = randomVerifier()
  const secure = isSecureRequest(ctx.req.url, ctx.req.header("x-forwarded-proto") ?? null)
  const challenge = await codeChallenge(verifier)
  const authorize = `${authUrl}/auth/v1/authorize?provider=${provider}` +
    `&redirect_to=${encodeURIComponent(`${base}/auth`)}` +
    `&code_challenge=${challenge}&code_challenge_method=s256`
  return redirectResponse(authorize, { status: 302, setCookies: [pkceCookieHeader(verifier, secure, publicBasePath())] })
})

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

type RouteCtx = {
  req: {
    url: string
    header: (n: string) => string | undefined
    query: (n: string) => string | undefined
    raw: Request
  }
}

/**
 * The provider's return to /auth, or null for an ordinary page load.
 *
 * No Sec-Fetch-Site check here, deliberately: the return legitimately arrives as a navigation that
 * started on accounts.google.com or appleid.apple.com. What protects it is the verifier cookie -
 * a code planted by someone else was issued against THEIR verifier, so it cannot be exchanged
 * with this browser's, and a browser with no sign-in in flight has no verifier at all. That holds
 * against other sites, not against a sibling subdomain: a `__Secure-` cookie (it cannot be
 * `__Host-`, which requires `Path=/`) can still be set for the parent domain by any HTTPS host
 * under it, the same exposure the session cookies already have.
 *
 * A return carrying both a code and an error is ambiguous and is treated as a failure, as the
 * desktop parser refuses the same shape.
 */
async function oauthReturn(ctx: RouteCtx): Promise<Response | null> {
  const code = ctx.req.query("code")
  const providerError = ctx.req.query("error")
  if (!code && !providerError) return null

  const secure = isSecureRequest(ctx.req.url, ctx.req.header("x-forwarded-proto") ?? null)
  const clearVerifier = clearPkceCookieHeader(secure, publicBasePath())
  if (code && providerError) return oauthErrorRedirect("failed", [clearVerifier])
  if (providerError) {
    // Untrusted text: logged only in the shape a real error code has, so it cannot forge log lines.
    console.warn("oauth provider error", OAUTH_ERROR_RE.test(providerError) ? providerError : "<malformed>")
    return oauthErrorRedirect(providerError === "access_denied" ? "cancelled" : "failed", [clearVerifier])
  }
  const verifier = cookieToken(ctx.req.header("cookie") ?? null, pkceCookieName(secure))
  if (!code || !AUTH_CODE_RE.test(code) || !verifier) return oauthErrorRedirect("expired", [clearVerifier])

  const limit = rateLimit(
    `oauth-return:${clientKey(ctx.req.raw.headers)}`,
    OAUTH_RETURN_LIMIT,
    OAUTH_RETURN_WINDOW_SECONDS,
  )
  if (!limit.allowed) return oauthErrorRedirect("rate_limited", [clearVerifier])

  const cfg = readConfig()
  if (!cfg.supabaseUrl || !cfg.anonKey) return jsonResponse({ error: "not_configured" }, 503)
  const session = await gotruePkceExchange(cfg, code, verifier)
  if (!session) return oauthErrorRedirect("failed", [clearVerifier])

  return redirectResponse(`${publicBasePath()}/`, {
    status: 302,
    setCookies: [...sessionCookieHeaders(session.accessToken, session.refreshToken, secure, publicBasePath()), clearVerifier],
  })
}

/** Back to the page with a reason the page script turns into a notice. Codes, never free text. */
function oauthErrorRedirect(reason: string, setCookies: string[] = []): Response {
  return redirectResponse(`${publicBasePath()}/auth?oauth_error=${reason}`, { status: 302, setCookies })
}

async function gotruePkceExchange(
  cfg: { supabaseUrl: string; anonKey: string },
  code: string,
  verifier: string,
): Promise<{ accessToken: string; refreshToken: string | null } | null> {
  try {
    const resp = await deps.fetch(`${cfg.supabaseUrl}/auth/v1/token?grant_type=pkce`, {
      method: "POST",
      headers: { apikey: cfg.anonKey, "Content-Type": "application/json" },
      body: JSON.stringify({ auth_code: code, code_verifier: verifier }),
    })
    if (!resp.ok) {
      console.warn("pkce exchange refused", resp.status)
      return null
    }
    const json = await resp.json() as Record<string, unknown>
    if (typeof json.access_token !== "string" || !ACCESS_TOKEN_RE.test(json.access_token)) return null
    const refresh = typeof json.refresh_token === "string" && REFRESH_TOKEN_RE.test(json.refresh_token) ? json.refresh_token : null
    return { accessToken: json.access_token, refreshToken: refresh }
  } catch (err) {
    console.error("pkce exchange failed", err)
    return null
  }
}

/** RFC 7636 verifier: 43 unpadded base64url characters from 32 random bytes. */
function randomVerifier(): string {
  return base64Url(crypto.getRandomValues(new Uint8Array(32)))
}

export async function codeChallenge(verifier: string): Promise<string> {
  return base64Url(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier))))
}

function base64Url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
}

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
 * here; call only after PostgREST has accepted the same token for the data call.
 * Email is only a label. The shared decoder also reads `sub` as the browser
 * preference cache/account-change key; neither claim authorizes server access.
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
