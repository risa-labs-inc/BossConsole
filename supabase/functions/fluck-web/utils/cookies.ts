/**
 * Session cookies for the fluck-web page (copied from live-sessions/utils/cookies.ts).
 *
 * The magic link lands GoTrue's tokens in the URL fragment. The page posts them ONCE to
 * /api/session and from then on holds nothing: the access and refresh tokens live in two
 * HttpOnly cookies scoped to this function's browser-facing path, so
 *
 *   - a reload, a new tab or a restart of the browser stays signed in (the refresh cookie
 *     lasts REFRESH_MAX_AGE_SECONDS; the server rotates the access token when it expires);
 *   - no script can read them - not this page's, and not a sibling function's on the shared
 *     api.risaboss.com origin, which was the weakness of the sessionStorage design;
 *   - `Path` keeps them off every other function on the origin.
 *
 * `__Secure-` rather than `__Host-`: the latter mandates `Path=/`. The cost is that `__Secure-`
 * does not pin the host, so any HTTPS host under the parent domain can set one of these names
 * for the whole domain; these cookies are protected from other sites, not from a compromised
 * sibling subdomain. Over plain http (a local stack) the prefix is dropped, because a browser
 * silently discards a Secure cookie set over http and the flow would loop forever with no
 * visible error.
 *
 * SameSite=Lax is the right setting, not Strict: the magic link is a TOP-LEVEL navigation from
 * the mail client into /auth, and Strict would withhold the cookies on that very landing.
 * Cookie-authenticated routes additionally refuse `Sec-Fetch-Site: cross-site`, so Lax's
 * one gap (a cross-site top-level GET) cannot reach the data routes, which are fetches.
 */

const ACCESS = "boss_fluck_at"
const REFRESH = "boss_fluck_rt"
const PKCE = "boss_fluck_pkce"

/**
 * PKCE verifier for a Google / Apple sign-in in flight. Lives only between the start route and
 * the provider's return to /auth, so it expires with the attempt. Lax, not Strict: the return is a
 * top-level navigation that began on the provider's site (Apple posts a form to GoTrue), and
 * Strict would withhold the verifier on exactly that request.
 */
export const PKCE_MAX_AGE_SECONDS = 10 * 60

/** Access cookie: bounded by the JWT's own expiry, so a stale one just 401s and gets rotated. */
export const ACCESS_MAX_AGE_SECONDS = 60 * 60
/** Refresh cookie: how long "stay signed in" lasts without a new magic link. */
export const REFRESH_MAX_AGE_SECONDS = 30 * 24 * 60 * 60

export function accessCookieName(secure: boolean): string {
  return secure ? `__Secure-${ACCESS}` : ACCESS
}

export function refreshCookieName(secure: boolean): string {
  return secure ? `__Secure-${REFRESH}` : REFRESH
}

/** Every value the request sent under `name` (browsers may send several on overlapping paths). */
export function cookieValues(cookieHeader: string | null, name: string): string[] {
  if (!cookieHeader) return []
  const out: string[] = []
  for (const part of cookieHeader.split(";")) {
    const eq = part.indexOf("=")
    if (eq < 0) continue
    if (part.slice(0, eq).trim() !== name) continue
    const value = part.slice(eq + 1).trim()
    if (value) out.push(value)
  }
  return out
}

/**
 * First plausible token under `name`. URL-safe base64 plus dots; the floor is 8, not 20, because
 * Supabase refresh tokens are 12-character opaque strings and a JWT-sized floor silently dropped
 * every refresh cookie.
 */
export function cookieToken(cookieHeader: string | null, name: string): string | null {
  return cookieValues(cookieHeader, name).find((v) => /^[A-Za-z0-9._~+/=-]{8,4096}$/.test(v)) ?? null
}

function setCookie(name: string, value: string, maxAge: number, secure: boolean, path: string): string {
  const attrs = [`${name}=${value}`, `Path=${path || "/"}`, `Max-Age=${maxAge}`, "HttpOnly", "SameSite=Lax"]
  if (secure) attrs.push("Secure")
  return attrs.join("; ")
}

export function sessionCookieHeaders(
  accessToken: string,
  refreshToken: string | null,
  secure: boolean,
  path: string,
): string[] {
  const headers = [setCookie(accessCookieName(secure), accessToken, ACCESS_MAX_AGE_SECONDS, secure, path)]
  if (refreshToken) headers.push(setCookie(refreshCookieName(secure), refreshToken, REFRESH_MAX_AGE_SECONDS, secure, path))
  return headers
}

export function pkceCookieName(secure: boolean): string {
  return secure ? `__Secure-${PKCE}` : PKCE
}

export function pkceCookieHeader(verifier: string, secure: boolean, path: string): string {
  return setCookie(pkceCookieName(secure), verifier, PKCE_MAX_AGE_SECONDS, secure, path)
}

export function clearPkceCookieHeader(secure: boolean, path: string): string {
  return setCookie(pkceCookieName(secure), "", 0, secure, path)
}

export function clearCookieHeaders(secure: boolean, path: string): string[] {
  return [accessCookieName(secure), refreshCookieName(secure)].map((name) => {
    const attrs = [`${name}=`, `Path=${path || "/"}`, "Max-Age=0", "HttpOnly", "SameSite=Lax"]
    if (secure) attrs.push("Secure")
    return attrs.join("; ")
  })
}

/** True when the browser reached us over https (the gateway terminates TLS; read X-Forwarded-Proto). */
export function isSecureRequest(requestUrl: string, forwardedProto: string | null): boolean {
  if (forwardedProto) return forwardedProto.split(",")[0].trim().toLowerCase() === "https"
  try {
    return new URL(requestUrl).protocol === "https:"
  } catch {
    return false
  }
}

/**
 * CSRF nonce for POST /api/open (the app-sharing-browser.ts pattern): an HttpOnly SameSite=Strict
 * cookie the page cannot read, echoed back in a header the page learned from GET /api/instances.
 * `__Host-` here is fine (and stronger) because it is Path=/ by design; plain name over http.
 */
const CSRF = "boss_fluck_csrf"
export const CSRF_MAX_AGE_SECONDS = 30 * 60
export const CSRF_RE = /^[a-f0-9]{64}$/

export function csrfCookieName(secure: boolean): string {
  return secure ? `__Host-${CSRF}` : CSRF
}

export function csrfCookieHeader(value: string, secure: boolean): string {
  const attrs = [`${csrfCookieName(secure)}=${value}`, "Path=/", `Max-Age=${CSRF_MAX_AGE_SECONDS}`, "HttpOnly", "SameSite=Strict"]
  if (secure) attrs.push("Secure")
  return attrs.join("; ")
}

export function newCsrfToken(): string {
  return Array.from(crypto.getRandomValues(new Uint8Array(32)), (b) => b.toString(16).padStart(2, "0")).join("")
}
