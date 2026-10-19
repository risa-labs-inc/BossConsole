/**
 * Deployment configuration for the organisation function.
 *
 * Two things here are load-bearing and easy to get wrong.
 *
 * 1. THE PUBLIC BASE PATH IS NOT DERIVABLE FROM THE REQUEST. The Supabase edge
 *    gateway strips `/functions/v1` before the function sees the URL, so
 *    `ctx.req.url` reports `/organisation/o/acme` while the browser is at
 *    `/functions/v1/organisation/o/acme`. Anything the BROWSER will use -- the
 *    cookie `Path`, the `Location` of the token-strip redirect, form actions,
 *    copyable invite links -- must be built from `publicBasePath()`, never from
 *    the request. Building a cookie Path from the stripped URL yields
 *    `Path=/organisation`, the browser never sends the cookie back to
 *    `/functions/v1/organisation/...`, and every page looks logged out.
 *
 * 2. A MISSING SESSION SECRET IS FATAL, DELIBERATELY. `plugin-store`'s signing
 *    util fails OPEN when its key is absent (publishing keeps working, the host
 *    treats unsigned as warn-only). The opposite is correct here: with no secret
 *    there is no way to authenticate a cookie, and any fallback -- a random
 *    per-isolate key, an empty key -- either silently logs everyone out on
 *    isolate recycle or accepts a forged cookie. `requireSessionSecrets()`
 *    throws, and app.ts turns that into a 503.
 */

/** Runtime knobs, read once per isolate. */
export interface OrgFunctionConfig {
  supabaseUrl: string
  serviceRoleKey: string
  /** Browser-facing path prefix, e.g. `/functions/v1/organisation`. */
  basePath: string
  /** Deep-link scheme the desktop app registers, e.g. `boss`. */
  deepLinkScheme: string
}

const DEFAULT_BASE_PATH = "/functions/v1/organisation"
const DEFAULT_DEEP_LINK_SCHEME = "boss"

/**
 * Path prefix the browser sees, WITHOUT a trailing slash.
 *
 * Override with ORG_PUBLIC_BASE_PATH when the function is mounted somewhere
 * else (a reverse proxy, a custom route). The default matches every current
 * Supabase deployment.
 */
export function publicBasePath(): string {
  const configured = Deno.env.get("ORG_PUBLIC_BASE_PATH")?.trim()
  const raw = configured && configured.length > 0 ? configured : DEFAULT_BASE_PATH
  const withSlash = raw.startsWith("/") ? raw : `/${raw}`
  return withSlash.endsWith("/") ? withSlash.slice(0, -1) : withSlash
}

/** Absolute, browser-usable path for a route within this function. */
export function publicPath(route: string): string {
  const suffix = route.startsWith("/") ? route : `/${route}`
  return `${publicBasePath()}${suffix}`
}

/**
 * Browser-usable base for links that LEAVE this browser: absolute when the
 * deployment has configured its public origin, otherwise the RELATIVE path.
 *
 * publicBasePath() returns a path, which is right for a Location header or a form
 * action and wrong for anything copied and sent to someone else: an invite link
 * rendered as `/functions/v1/organisation/join/...` has no host and is useless
 * the moment it is pasted anywhere. Since the invite token is shown exactly
 * once, getting this wrong costs a revoke and a re-mint.
 *
 * ORG_PUBLIC_BASE_URL is the ONLY source of that host, deliberately. The
 * alternative is the request's own Host/X-Forwarded-Host, and a header the
 * CLIENT can set cannot carry the authority of a credential-bearing URL:
 * wherever the edge is not behind a strict proxy that overwrites them, a
 * poisoned `X-Forwarded-Host: attacker.example` used to mint
 * `https://attacker.example/join/<one-time token>` and hand the join token to
 * the attacker's host. "The admin is reading the page at that origin" is true of
 * the BROWSER, but the host the function sees is client-suppliable and says
 * nothing about where the admin is.
 *
 * UNSET therefore fails CLOSED, not to the header: with no configured origin
 * there is no absolute link to mint, and the return value drops to the relative
 * publicBasePath(). A relative `/join/<token>` still works pasted into the
 * origin the admin is reading, and -- unlike the header-derived host -- it
 * carries no authority for anyone to poison. An unconfigured deployment
 * costing a re-mint is the right failure; a leaked token is not.
 */
export function publicBaseUrl(): string {
  const configured = Deno.env.get("ORG_PUBLIC_BASE_URL")?.trim()
  if (configured) return configured.replace(/\/+$/, "") + publicBasePath()

  return publicBasePath()
}

export function deepLinkScheme(): string {
  const configured = Deno.env.get("ORG_DEEP_LINK_SCHEME")?.trim()
  return configured && configured.length > 0 ? configured : DEFAULT_DEEP_LINK_SCHEME
}

/**
 * The HMAC keys for the session cookie, newest first.
 *
 * ORG_SESSION_SECRET signs; ORG_SESSION_SECRET_PREV only verifies, so a
 * rotation does not log every open page out. Verification tries them all, deliberately without an early break, so the
 * loop cost stays independent of WHICH key matched - see session.ts.
 *
 * @throws if ORG_SESSION_SECRET is missing or too short to be a real key.
 */
export function requireSessionSecrets(): { signing: string; verifying: string[] } {
  const current = Deno.env.get("ORG_SESSION_SECRET")?.trim()

  // 32 chars is not cryptographic rigour, it is a typo guard: the realistic
  // failure is someone setting ORG_SESSION_SECRET=changeme, not someone
  // choosing a 31-character key on purpose.
  if (!current || current.length < 32) {
    throw new MissingSessionSecretError()
  }

  const previous = Deno.env.get("ORG_SESSION_SECRET_PREV")?.trim()
  const verifying = previous && previous.length >= 32 ? [current, previous] : [current]
  return { signing: current, verifying }
}

/** Thrown by requireSessionSecrets; app.ts maps it to 503. */
export class MissingSessionSecretError extends Error {
  constructor() {
    super("ORG_SESSION_SECRET is not configured")
    this.name = "MissingSessionSecretError"
  }
}

/**
 * True when this deployment serves over https, which decides whether the
 * `__Secure-` cookie prefix is usable.
 *
 * A `__Secure-` cookie MUST carry the Secure attribute, and a browser silently
 * DROPS a Secure cookie sent over plain http. On a local stack
 * (http://localhost:54321) that means every page would set a cookie the browser
 * throws away, and the whole flow would loop through the handoff exchange
 * forever with no visible error. So locally we use the unprefixed name.
 *
 * Derived from the incoming request rather than the environment, because the
 * edge runtime's SUPABASE_URL points at the internal gateway and says nothing
 * about how the browser reached us.
 */
export function isSecureRequest(requestUrl: string, forwardedProto: string | null): boolean {
  // The gateway terminates TLS, so the scheme the function sees is http even in
  // production. X-Forwarded-Proto is what actually reports the browser's scheme.
  if (forwardedProto) {
    return forwardedProto.split(",")[0].trim().toLowerCase() === "https"
  }
  try {
    return new URL(requestUrl).protocol === "https:"
  } catch {
    return false
  }
}

export function readConfig(): OrgFunctionConfig {
  return {
    supabaseUrl: Deno.env.get("SUPABASE_URL") ?? "",
    serviceRoleKey: Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "",
    basePath: publicBasePath(),
    deepLinkScheme: deepLinkScheme(),
  }
}
