/**
 * Session cookies for the live-sessions page.
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
 * `__Secure-` rather than `__Host-`: the latter mandates `Path=/`. Over plain http (a local
 * stack) the prefix is dropped, because a browser silently discards a Secure cookie set over
 * http and the flow would loop forever with no visible error.
 *
 * SameSite=Lax is the right setting, not Strict: the magic link is a TOP-LEVEL navigation from
 * the mail client into /auth, and Strict would withhold the cookies on that very landing.
 * Cookie-authenticated routes additionally refuse `Sec-Fetch-Site: cross-site`, so Lax's
 * one gap (a cross-site top-level GET) cannot reach the data routes, which are fetches.
 */

const ACCESS = "boss_live_at"
const REFRESH = "boss_live_rt"

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
