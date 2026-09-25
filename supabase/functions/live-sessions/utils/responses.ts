/**
 * Response construction and the security headers every response carries.
 *
 * These are applied here, at the single place responses are built, rather than
 * as middleware over `ctx.html(...)`, so that a handler cannot accidentally
 * return a bare Response and skip them.
 */

import { cspNonce } from "./html.ts"

/**
 * Headers on EVERY response, HTML or redirect. (Adapted from organisation/utils/responses.ts.)
 *
 * - `no-store`, and `Vary: Cookie` behind it, because the session lives in cookies and the
 *   page is per-user: a shared cache serving one user's list to another is the whole failure.
 * - `Referrer-Policy: no-referrer`: the page opens share links whose fragment is an E2E
 *   secret, and its own /auth landing carries tokens in the fragment; nothing here may leak
 *   into a Referer.
 * - `X-Frame-Options: DENY`: this page embeds the viewer, nothing embeds this page.
 */
function baseSecurityHeaders(): Record<string, string> {
  return {
    "Cache-Control": "no-store, max-age=0",
    "Vary": "Cookie",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
    "X-Frame-Options": "DENY",
  }
}

/**
 * Content-Security-Policy for an HTML response.
 *
 * `default-src 'none'` and then only what the page needs. No `unsafe-inline`: the nonce covers
 * our own inline style and script, an injected one has no nonce. Second line of defence behind
 * esc(): every registry value (device and session names) is escaped at interpolation.
 */
function contentSecurityPolicy(nonce: string): string {
  return [
    "default-src 'none'",
    `script-src 'nonce-${nonce}'`,
    `style-src 'nonce-${nonce}'`,
    "img-src 'self' data:",
    "connect-src 'self'",
    // The share-viewer is embedded in an iframe so the address bar stays here. Its host is the
    // user's own tunnel (a different, unpredictable https origin per share), so this is the one
    // directive that cannot be pinned to a host; the frame src is always one of the user's own
    // registry rows, filtered to http(s) by isSessionRow and safeHttpUrl.
    "frame-src https:",
    "form-action 'self'",
    "base-uri 'none'",
    "frame-ancestors 'none'",
  ].join("; ")
}

export interface HtmlOptions {
  status?: number
  /** Extra headers, e.g. Set-Cookie. */
  headers?: Record<string, string>
}

/**
 * Render an HTML response.
 *
 * `build` receives the nonce so the page can stamp it on its own <style> and
 * <script> tags. Generating the nonce here rather than in the view guarantees
 * the header and the markup can never disagree.
 */
export function htmlResponse(
  build: (nonce: string) => string,
  options: HtmlOptions = {},
): Response {
  const nonce = cspNonce()
  const headers = new Headers({
    "Content-Type": "text/html; charset=utf-8",
    "Content-Security-Policy": contentSecurityPolicy(nonce),
    ...baseSecurityHeaders(),
    ...(options.headers ?? {}),
  })
  return new Response(build(nonce), { status: options.status ?? 200, headers })
}

/** A redirect carrying the same security headers. Used for the vanity-host redirect. */
export function redirectResponse(
  location: string,
  options: { status?: number; headers?: Record<string, string> } = {},
): Response {
  const headers = new Headers({
    Location: location,
    ...baseSecurityHeaders(),
    ...(options.headers ?? {}),
  })
  return new Response(null, { status: options.status ?? 303, headers })
}

/** JSON, for /health only. Pages are always HTML. */
/**
 * JSON for the page's same-origin API. `setCookies` are appended one header each: a single
 * `Set-Cookie` string joined with commas is NOT how multiple cookies are sent.
 */
export function jsonResponse(body: unknown, status = 200, setCookies: string[] = [], extra: Record<string, string> = {}): Response {
  const headers = new Headers({
    "Content-Type": "application/json; charset=utf-8",
    ...baseSecurityHeaders(),
    ...extra,
  })
  for (const c of setCookies) headers.append("Set-Cookie", c)
  return new Response(JSON.stringify(body), { status, headers })
}
