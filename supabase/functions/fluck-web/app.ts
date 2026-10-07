/**
 * Fluck Web Edge Function - routing (fluck.risaboss.com).
 *
 * A BOSS account holder signs in (Google, Apple, or a magic link), sees every BOSS of theirs whose
 * Fluck has web chat on (`fluck_web_instances`, heartbeated by the Fluck plugin), picks one, and
 * lands signed in on that Fluck's own web chat. Structure and security are copied from
 * live-sessions; read its app.ts for the long-form reasoning behind each choice.
 *
 * Routes (browser-facing base is /functions/v1/fluck-web, or "/" behind the alias Worker):
 *   GET  /, /auth           the page. /auth is the magic-link and Google / Apple landing.
 *   POST /api/otp           {email} -> magic link (create_user: false), redirect_to=<base>/auth
 *   POST /api/session       {access_token, refresh_token} -> HttpOnly session cookies
 *   GET  /api/oauth/{google|apple}  server-side PKCE start
 *   GET  /api/instances     cookie -> fluck_web_list_instances() as the user, plus the CSRF nonce
 *   POST /api/open          {instance_id} + CSRF nonce + exact Origin -> fluck_web_mint_ticket()
 *                           as the user -> {url: <endpoint_url>/#/t/<ticket>}
 *   POST /api/logout        clears the cookies
 *   GET  /health
 *
 * Trust model: no service-role key. Every database call carries the caller's own JWT, so
 * PostgREST validates it and RLS / the RPCs' auth.uid() checks decide what is visible and
 * mintable. Cookie routes refuse `Sec-Fetch-Site: cross-site`; /api/open additionally requires
 * the exact public Origin and a CSRF nonce. NO CORS MIDDLEWARE, deliberately.
 */

import { OpenAPIHono } from "@hono/zod-openapi"
import { authPublicUrl, LIVE_WINDOW_SECONDS, publicBasePath, publicBaseUrl, publicOrigin, readConfig, viaAlias } from "./utils/config.ts"
import { htmlResponse, jsonResponse, redirectResponse } from "./utils/responses.ts"
import { clientKey, rateLimit } from "./utils/rate-limit.ts"
import {
  accessCookieName,
  clearCookieHeaders,
  clearPkceCookieHeader,
  cookieToken,
  CSRF_RE,
  csrfCookieHeader,
  csrfCookieName,
  isSecureRequest,
  newCsrfToken,
  pkceCookieHeader,
  pkceCookieName,
  refreshCookieName,
  sessionCookieHeaders,
} from "./utils/cookies.ts"
import { fluckPage } from "./views/page.ts"

export const app = new OpenAPIHono().basePath("/fluck-web")

/** Test seam: the suite swaps this for a stub so no network is touched. */
export const deps = { fetch: (input: string, init?: RequestInit) => fetch(input, init) }

const OTP_LIMIT = 5
const OTP_WINDOW_SECONDS = 600
const LIST_LIMIT = 120
const LIST_WINDOW_SECONDS = 60
const OPEN_LIMIT = 30
const OPEN_WINDOW_SECONDS = 60
const SESSION_LIMIT = 30
const SESSION_WINDOW_SECONDS = 300
const OAUTH_LIMIT = 20
const OAUTH_WINDOW_SECONDS = 300
const OAUTH_RETURN_LIMIT = 20
const OAUTH_RETURN_WINDOW_SECONDS = 300
const ACCESS_TOKEN_RE = /^[A-Za-z0-9._~+/=-]{20,4096}$/
const REFRESH_TOKEN_RE = /^[A-Za-z0-9._~+/=-]{8,4096}$/
const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/
const OAUTH_PROVIDERS = new Set(["google", "apple"])
const AUTH_CODE_RE = /^[A-Za-z0-9._~-]{1,512}$/
const OAUTH_ERROR_RE = /^[a-z_]{1,64}$/
/** Same shape as the fluck_web_instances.instance_id CHECK. */
export const INSTANCE_ID_RE = /^[A-Za-z0-9._:-]{1,128}$/
/** fluck_web_mint_ticket returns 32 bytes in unpadded base64url. */
const TICKET_RE = /^[A-Za-z0-9_-]{43}$/
const UPSTREAM_TIMEOUT_MS = 5000
/** fluck_web_mint_ticket lifetime: pending tickets (the too_many_tickets cap) drain within it. */
const TICKET_TTL_SECONDS = 60

type Cfg = { supabaseUrl: string; anonKey: string }

function page(): Response {
  return htmlResponse((nonce) => fluckPage({ basePath: publicBasePath(), liveWindowSeconds: LIVE_WINDOW_SECONDS }, nonce))
}

/**
 * Page loads that did not come through the alias Worker go to the vanity host, so cookies, links
 * and the address bar agree on one host (copied from live-sessions). The page is built for the
 * configured base path, so serving it on any other host would leave every API call 404ing and
 * /api/open failing the Origin check. There is no opt-out: a request either proves it came via the
 * Worker (viaAlias), is already on the base host, or is redirected. One that claims the alias but
 * cannot prove it is refused rather than redirected, which would loop through the Worker.
 */
function aliasRedirect(ctx: { req: { url: string; raw: Request; header: (n: string) => string | undefined } }, route: string): Response | null {
  if (viaAlias(ctx.req.raw.headers)) return null
  if (ctx.req.header("x-fluck-web-alias") !== undefined) {
    console.error("alias request without a valid FLUCK_WEB_ALIAS_SECRET; check the Worker and function secrets")
    return jsonResponse({ error: "alias_unverified" }, 503)
  }
  const base = publicBaseUrl()
  if (!base) return null
  let baseHost = ""
  try {
    baseHost = new URL(base).host
  } catch { /* unparseable base: no redirect */ }
  if (!baseHost) return null
  const reqHost = (ctx.req.header("host") ?? "").split(",")[0].trim()
  if (!reqHost || reqHost === baseHost) return null
  let search = ""
  try {
    const params = new URL(ctx.req.url).searchParams
    params.delete("_alias")
    search = params.size > 0 ? `?${params.toString()}` : ""
  } catch { /* drop the query */ }
  return redirectResponse(`${base}${route || "/"}${search}`, { status: 302 })
}

app.get("/", (ctx) => aliasRedirect(ctx, "") ?? page())
app.get("", (ctx) => aliasRedirect(ctx, "") ?? page())
app.get("/auth", async (ctx) => aliasRedirect(ctx, "/auth") ?? (await oauthReturn(ctx)) ?? page())

/** Google / Apple sign-in start: PKCE verifier in an HttpOnly cookie, then GoTrue /authorize. */
app.get("/api/oauth/:provider", async (ctx) => {
  if (ctx.req.header("sec-fetch-site") === "cross-site") return jsonResponse({ error: "forbidden" }, 403)
  const provider = ctx.req.param("provider")
  if (!OAUTH_PROVIDERS.has(provider)) return jsonResponse({ error: "not_found" }, 404)
  const alias = aliasRedirect(ctx, `/api/oauth/${provider}`)
  if (alias) return alias
  const limit = rateLimit(`oauth:${clientKey(ctx.req.raw.headers)}`, OAUTH_LIMIT, OAUTH_WINDOW_SECONDS)
  if (!limit.allowed) return oauthErrorRedirect("rate_limited")

  const base = publicBaseUrl()
  const authUrl = authPublicUrl()
  if (!base || !authUrl) {
    console.error("FLUCK_WEB_PUBLIC_BASE_URL or the auth URL is not set; refusing to start OAuth")
    return jsonResponse({ error: "not_configured" }, 503)
  }
  const verifier = base64Url(crypto.getRandomValues(new Uint8Array(32)))
  const secure = isSecure(ctx.req)
  const authorize = `${authUrl}/auth/v1/authorize?provider=${provider}` +
    `&redirect_to=${encodeURIComponent(`${base}/auth`)}` +
    `&code_challenge=${await codeChallenge(verifier)}&code_challenge_method=s256`
  return redirectResponse(authorize, { status: 302, setCookies: [pkceCookieHeader(verifier, secure, publicBasePath())] })
})

app.get("/health", () => jsonResponse({ status: "healthy" }))

/** Magic link. Always 200 {sent:true} on a well-formed email so accounts are not enumerable. */
app.post("/api/otp", async (ctx) => {
  if (ctx.req.header("sec-fetch-site") === "cross-site") return jsonResponse({ error: "forbidden" }, 403)
  if (!isJson(ctx.req)) return jsonResponse({ error: "invalid_request" }, 415)
  const limit = rateLimit(`otp:${clientKey(ctx.req.raw.headers)}`, OTP_LIMIT, OTP_WINDOW_SECONDS)
  if (!limit.allowed) return tooMany(limit.retryAfterSeconds)
  const body = await readJson(ctx.req.raw)
  const email = typeof body?.email === "string" ? body.email.trim().toLowerCase() : ""
  if (!EMAIL_RE.test(email) || email.length > 254) return jsonResponse({ error: "invalid_email" }, 400)

  const cfg = readConfig()
  if (!cfg.supabaseUrl || !cfg.anonKey) return jsonResponse({ error: "not_configured" }, 503)
  const base = publicBaseUrl()
  if (!base) {
    console.error("FLUCK_WEB_PUBLIC_BASE_URL is not set; refusing to send a magic link with an unlisted redirect_to")
    return jsonResponse({ error: "not_configured" }, 503)
  }
  try {
    const resp = await deps.fetch(`${cfg.supabaseUrl}/auth/v1/otp?redirect_to=${encodeURIComponent(`${base}/auth`)}`, {
      method: "POST",
      headers: { apikey: cfg.anonKey, "Content-Type": "application/json" },
      // The account the user's BOSS is signed in as already exists; never create one here.
      body: JSON.stringify({ email, create_user: false }),
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    })
    await resp.body?.cancel()
    if (resp.status === 429) return tooMany(60)
    if (resp.status >= 500) {
      console.error("otp upstream", resp.status)
      return jsonResponse({ error: "upstream" }, 502)
    }
  } catch (err) {
    console.error("otp send failed", err)
    return jsonResponse({ error: "upstream" }, 502)
  }
  return jsonResponse({ sent: true })
})

/** Turn the magic link's fragment tokens into cookies, after GoTrue vouches for the access token. */
app.post("/api/session", async (ctx) => {
  if (ctx.req.header("sec-fetch-site") === "cross-site") return jsonResponse({ error: "forbidden" }, 403)
  if (!isJson(ctx.req)) return jsonResponse({ error: "invalid_request" }, 415)
  const limit = rateLimit(`session:${clientKey(ctx.req.raw.headers)}`, SESSION_LIMIT, SESSION_WINDOW_SECONDS)
  if (!limit.allowed) return tooMany(limit.retryAfterSeconds)
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
  return jsonResponse(
    { ok: true, email: user.email ?? "" },
    200,
    sessionCookieHeaders(accessToken, refreshToken || null, isSecure(ctx.req), publicBasePath()),
  )
})

app.post("/api/logout", (ctx) => {
  if (ctx.req.header("sec-fetch-site") === "cross-site") return jsonResponse({ error: "forbidden" }, 403)
  if (!isJson(ctx.req)) return jsonResponse({ error: "invalid_request" }, 415)
  return jsonResponse({ ok: true }, 200, clearCookieHeaders(isSecure(ctx.req), publicBasePath()))
})

/**
 * The caller's Fluck instances (online and recently offline) plus the CSRF nonce for /api/open.
 * The nonce cookie is reused while valid so two tabs do not invalidate each other.
 */
app.get("/api/instances", async (ctx) => {
  if (ctx.req.header("sec-fetch-site") === "cross-site") return jsonResponse({ error: "forbidden" }, 403)
  const limit = rateLimit(`instances:${clientKey(ctx.req.raw.headers)}`, LIST_LIMIT, LIST_WINDOW_SECONDS)
  if (!limit.allowed) return tooMany(limit.retryAfterSeconds)
  const cfg = readConfig()
  if (!cfg.supabaseUrl || !cfg.anonKey) return jsonResponse({ error: "not_configured" }, 503)

  const secure = isSecure(ctx.req)
  const result = await withUserToken(ctx.req, cfg, (token) => rpc(cfg, token, "fluck_web_list_instances", {}))
  if (result.kind === "unauthorized") return jsonResponse({ error: "unauthorized" }, 401, result.setCookies)
  const resp = result.value
  if (!resp.ok) {
    await resp.body?.cancel()
    console.error("list instances upstream", resp.status)
    return jsonResponse({ error: "upstream" }, 502, result.setCookies)
  }
  const rows = await resp.json().catch(() => null)
  const instances = Array.isArray(rows) ? rows.filter(isInstanceRow).map(publicInstance) : []

  const existing = cookieToken(ctx.req.header("cookie") ?? null, csrfCookieName(secure))
  const csrf = existing && CSRF_RE.test(existing) ? existing : newCsrfToken()
  return jsonResponse(
    { instances, email: emailFromJwt(result.token), csrf },
    200,
    [...result.setCookies, csrfCookieHeader(csrf, secure)],
  )
})

/**
 * Mint a ticket for one of the caller's online instances and hand back the URL the page
 * navigates to. The endpoint comes from the caller's own row (read with their JWT, so RLS), and
 * is re-validated as a bare https origin by isInstanceRow: the row's CHECK is the first guard,
 * that shape guard the second.
 */
app.post("/api/open", async (ctx) => {
  if (ctx.req.header("sec-fetch-site") === "cross-site") return jsonResponse({ error: "forbidden" }, 403)
  const origin = publicOrigin()
  if (!origin) return jsonResponse({ error: "not_configured" }, 503)
  const secure = isSecure(ctx.req)
  const expected = cookieToken(ctx.req.header("cookie") ?? null, csrfCookieName(secure))
  const supplied = ctx.req.header("x-fluck-web-csrf")
  if (ctx.req.header("origin") !== origin || !expected || !CSRF_RE.test(expected) || expected !== supplied) {
    return jsonResponse({ error: "forbidden" }, 403)
  }
  if (!isJson(ctx.req)) return jsonResponse({ error: "invalid_request" }, 415)
  const limit = rateLimit(`open:${clientKey(ctx.req.raw.headers)}`, OPEN_LIMIT, OPEN_WINDOW_SECONDS)
  if (!limit.allowed) return tooMany(limit.retryAfterSeconds)

  const body = await readJson(ctx.req.raw)
  const instanceId = typeof body?.instance_id === "string" ? body.instance_id : ""
  if (!INSTANCE_ID_RE.test(instanceId)) return jsonResponse({ error: "invalid_request" }, 400)
  const cfg = readConfig()
  if (!cfg.supabaseUrl || !cfg.anonKey) return jsonResponse({ error: "not_configured" }, 503)

  const result = await withUserToken(ctx.req, cfg, async (token) => {
    const list = await rpc(cfg, token, "fluck_web_list_instances", {})
    if (!list.ok) {
      await list.body?.cancel()
      return { status: list.status, body: null as OpenOutcome | null }
    }
    const rows = await list.json().catch(() => null)
    const row = Array.isArray(rows) ? rows.find((r) => isInstanceRow(r) && r.instance_id === instanceId) : null
    if (!row || !row.online) return { status: 200, body: { error: "instance_unavailable" } as OpenOutcome }
    const endpoint = httpsOrigin(row.endpoint_url) as string // non-null: isInstanceRow checked it
    const minted = await rpc(cfg, token, "fluck_web_mint_ticket", { p_instance_id: instanceId })
    if (!minted.ok) {
      const err = await minted.json().catch(() => null) as Record<string, unknown> | null
      const message = typeof err?.message === "string" ? err.message : ""
      if (message === "instance_unavailable") return { status: 200, body: { error: "instance_unavailable" } as OpenOutcome }
      if (message === "too_many_tickets") return { status: 200, body: { error: "rate_limited" } as OpenOutcome }
      return { status: minted.status, body: null }
    }
    const ticket = await minted.json().catch(() => null)
    if (typeof ticket !== "string" || !TICKET_RE.test(ticket)) return { status: 502, body: null }
    return { status: 200, body: { url: `${endpoint}/#/t/${ticket}` } as OpenOutcome }
  }, (out) => out.status === 401 || out.status === 403)

  if (result.kind === "unauthorized") return jsonResponse({ error: "unauthorized" }, 401, result.setCookies)
  const out = result.value
  if (!out.body) {
    console.error("open upstream", out.status)
    return jsonResponse({ error: "upstream" }, 502, result.setCookies)
  }
  if ("url" in out.body) return jsonResponse(out.body, 200, result.setCookies)
  if (out.body.error === "rate_limited") return tooMany(TICKET_TTL_SECONDS, result.setCookies)
  return jsonResponse(out.body, 409, result.setCookies)
})

app.notFound(() => jsonResponse({ error: "not_found" }, 404))

app.onError((err, _ctx) => {
  console.error("fluck-web error", err)
  return jsonResponse({ error: "internal" }, 500)
})

// ---- helpers ----

type OpenOutcome = { url: string } | { error: "instance_unavailable" | "rate_limited" }

/** Every 429 carries Retry-After as well as the body field. */
function tooMany(retryAfterSeconds: number, setCookies: string[] = []): Response {
  return jsonResponse({ error: "rate_limited", retryAfterSeconds }, 429, setCookies, { "Retry-After": String(retryAfterSeconds) })
}

type RouteReq = {
  url: string
  header: (n: string) => string | undefined
  query: (n: string) => string | undefined
  raw: Request
}

/** A JSON body forces a CORS preflight, so a cross-origin form (text/plain) can never reach a POST route. */
function isJson(req: { header: (n: string) => string | undefined }): boolean {
  return req.header("content-type")?.toLowerCase().startsWith("application/json") ?? false
}

function isSecure(req: { url: string; header: (n: string) => string | undefined }): boolean {
  return isSecureRequest(req.url, req.header("x-forwarded-proto") ?? null)
}

type TokenResult<T> =
  | { kind: "ok"; value: T; token: string; setCookies: string[] }
  | { kind: "unauthorized"; setCookies: string[] }

/**
 * Run `call` with the cookie session's access token, rotating it once via the refresh cookie when
 * the call says 401 (or there is no access cookie). A dead refresh clears both cookies.
 */
async function withUserToken<T extends Response | { status: number }>(
  req: RouteReq,
  cfg: Cfg,
  call: (token: string) => Promise<T>,
  isUnauthorized: (value: T) => boolean = (v) => v.status === 401 || v.status === 403,
): Promise<TokenResult<T>> {
  const secure = isSecure(req)
  const cookieHeader = req.header("cookie") ?? null
  const access = cookieToken(cookieHeader, accessCookieName(secure))
  const refresh = cookieToken(cookieHeader, refreshCookieName(secure))
  const clear = clearCookieHeaders(secure, publicBasePath())
  if (!access && !refresh) return { kind: "unauthorized", setCookies: [] }
  if (access) {
    const value = await call(access)
    if (!isUnauthorized(value)) return { kind: "ok", value, token: access, setCookies: [] }
    if (value instanceof Response) await value.body?.cancel()
  }
  if (!refresh) return { kind: "unauthorized", setCookies: clear }
  const rotated = await gotrueRefresh(cfg, refresh)
  if (!rotated) return { kind: "unauthorized", setCookies: clear }
  const setCookies = sessionCookieHeaders(rotated.accessToken, rotated.refreshToken, secure, publicBasePath())
  const value = await call(rotated.accessToken)
  if (isUnauthorized(value)) {
    if (value instanceof Response) await value.body?.cancel()
    return { kind: "unauthorized", setCookies: clear }
  }
  return { kind: "ok", value, token: rotated.accessToken, setCookies }
}

async function rpc(cfg: Cfg, token: string, name: string, args: Record<string, unknown>): Promise<Response> {
  try {
    return await deps.fetch(`${cfg.supabaseUrl}/rest/v1/rpc/${name}`, {
      method: "POST",
      headers: { apikey: cfg.anonKey, Authorization: `Bearer ${token}`, "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify(args),
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    })
  } catch (err) {
    console.error(`${name} failed`, err)
    return new Response(null, { status: 502 })
  }
}

interface InstanceRow {
  instance_id: string
  label: string
  agent_name: string
  endpoint_url: string
  app_version: string | null
  started_at: string | null
  last_seen_at: string
  online: boolean
}

/** Shape guard so a surprising row can never reach the page as something else. */
export function isInstanceRow(row: unknown): row is InstanceRow {
  if (!row || typeof row !== "object") return false
  const r = row as Record<string, unknown>
  return typeof r.instance_id === "string" && INSTANCE_ID_RE.test(r.instance_id) &&
    typeof r.label === "string" && typeof r.agent_name === "string" &&
    typeof r.endpoint_url === "string" && httpsOrigin(r.endpoint_url) !== null &&
    typeof r.last_seen_at === "string" && typeof r.online === "boolean"
}

/** What the page sees. The endpoint is withheld: the page only ever navigates to a minted URL. */
function publicInstance(r: InstanceRow) {
  return {
    instance_id: r.instance_id,
    label: r.label,
    agent_name: r.agent_name,
    app_version: typeof r.app_version === "string" ? r.app_version : null,
    started_at: typeof r.started_at === "string" ? r.started_at : null,
    last_seen_at: r.last_seen_at,
    online: r.online,
  }
}

/** The fluck_web_instances.endpoint_url CHECK, so every row the table accepts is openable. */
const ENDPOINT_RE = /^https:\/\/[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:[0-9]{1,5})?$/

/**
 * `value` as a normalised bare https origin (host lowercased, default :443 dropped), or null.
 * Same shape as the table CHECK; a single trailing slash is tolerated.
 */
export function httpsOrigin(value: unknown): string | null {
  if (typeof value !== "string" || value.length > 512) return null
  const bare = value.endsWith("/") ? value.slice(0, -1) : value
  if (!ENDPOINT_RE.test(bare)) return null
  try {
    const u = new URL(bare)
    return u.protocol === "https:" ? u.origin : null
  } catch {
    return null
  }
}

/** The provider's return to /auth, or null for an ordinary page load (see live-sessions). */
async function oauthReturn(ctx: { req: RouteReq }): Promise<Response | null> {
  const code = ctx.req.query("code")
  const providerError = ctx.req.query("error")
  if (!code && !providerError) return null
  const secure = isSecure(ctx.req)
  const clearVerifier = clearPkceCookieHeader(secure, publicBasePath())
  if (code && providerError) return oauthErrorRedirect("failed", [clearVerifier])
  const verifier = cookieToken(ctx.req.header("cookie") ?? null, pkceCookieName(secure))
  if (providerError) {
    // Without a verifier this is GoTrue reporting a spent magic link (normally in the fragment).
    if (!verifier) return oauthErrorRedirect(oauthErrorReason(providerError, ctx.req.query("error_code")))
    console.warn("oauth provider error", OAUTH_ERROR_RE.test(providerError) ? providerError : "<malformed>")
    return oauthErrorRedirect(oauthErrorReason(providerError, ctx.req.query("error_code")), [clearVerifier])
  }
  if (!code || !AUTH_CODE_RE.test(code) || !verifier) return oauthErrorRedirect("expired", [clearVerifier])
  const limit = rateLimit(`oauth-return:${clientKey(ctx.req.raw.headers)}`, OAUTH_RETURN_LIMIT, OAUTH_RETURN_WINDOW_SECONDS)
  if (!limit.allowed) return oauthErrorRedirect("rate_limited", [clearVerifier])
  const cfg = readConfig()
  if (!cfg.supabaseUrl || !cfg.anonKey) return jsonResponse({ error: "not_configured" }, 503, [clearVerifier])
  const session = await gotruePkceExchange(cfg, code, verifier)
  if (!session) return oauthErrorRedirect("failed", [clearVerifier])
  return redirectResponse(`${publicBasePath()}/`, {
    status: 302,
    setCookies: [...sessionCookieHeaders(session.accessToken, session.refreshToken, secure, publicBasePath()), clearVerifier],
  })
}

function oauthErrorReason(error: string, errorCode: string | undefined): string {
  if (errorCode === "otp_expired" || errorCode === "flow_state_expired" || errorCode === "flow_state_not_found") return "expired"
  return error === "access_denied" ? "cancelled" : "failed"
}

function oauthErrorRedirect(reason: string, setCookies: string[] = []): Response {
  return redirectResponse(`${publicBasePath()}/auth?oauth_error=${reason}`, { status: 302, setCookies })
}

async function gotruePkceExchange(cfg: Cfg, code: string, verifier: string): Promise<{ accessToken: string; refreshToken: string | null } | null> {
  try {
    const resp = await deps.fetch(`${cfg.supabaseUrl}/auth/v1/token?grant_type=pkce`, {
      method: "POST",
      headers: { apikey: cfg.anonKey, "Content-Type": "application/json" },
      body: JSON.stringify({ auth_code: code, code_verifier: verifier }),
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    })
    if (!resp.ok) {
      await resp.body?.cancel()
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

export async function codeChallenge(verifier: string): Promise<string> {
  return base64Url(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier))))
}

function base64Url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
}

async function gotrueUser(cfg: Cfg, token: string): Promise<{ email?: string } | null> {
  try {
    const resp = await deps.fetch(`${cfg.supabaseUrl}/auth/v1/user`, {
      headers: { apikey: cfg.anonKey, Authorization: `Bearer ${token}` },
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    })
    if (!resp.ok) {
      await resp.body?.cancel()
      return null
    }
    const json = await resp.json() as Record<string, unknown>
    return { email: typeof json.email === "string" ? json.email : undefined }
  } catch (err) {
    console.error("gotrue user lookup failed", err)
    return null
  }
}

async function gotrueRefresh(cfg: Cfg, refreshToken: string): Promise<{ accessToken: string; refreshToken: string } | null> {
  try {
    const resp = await deps.fetch(`${cfg.supabaseUrl}/auth/v1/token?grant_type=refresh_token`, {
      method: "POST",
      headers: { apikey: cfg.anonKey, "Content-Type": "application/json" },
      body: JSON.stringify({ refresh_token: refreshToken }),
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    })
    if (!resp.ok) {
      await resp.body?.cancel()
      return null
    }
    const json = await resp.json() as Record<string, unknown>
    if (typeof json.access_token !== "string" || !ACCESS_TOKEN_RE.test(json.access_token)) return null
    if (json.refresh_token === undefined) return { accessToken: json.access_token, refreshToken }
    if (typeof json.refresh_token !== "string" || !REFRESH_TOKEN_RE.test(json.refresh_token)) return null
    return { accessToken: json.access_token, refreshToken: json.refresh_token }
  } catch (err) {
    console.error("refresh failed", err)
    return null
  }
}

async function readJson(req: Request): Promise<Record<string, unknown> | null> {
  try {
    const declared = Number(req.headers.get("content-length") ?? "0")
    if (declared > 8192) return null
    const text = await req.text()
    if (text.length > 8192) return null
    const parsed = JSON.parse(text)
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed as Record<string, unknown> : null
  } catch {
    return null
  }
}

/** Display-only email claim; read only after PostgREST accepted the same token. */
export function emailFromJwt(token: string): string {
  try {
    const payload = token.split(".")[1]
    const padded = payload.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - payload.length % 4) % 4)
    const json = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(padded), (c) => c.charCodeAt(0))))
    return typeof json.email === "string" ? json.email : ""
  } catch {
    return ""
  }
}
