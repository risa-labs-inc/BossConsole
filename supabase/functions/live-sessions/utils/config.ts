/**
 * Deployment configuration for the live-sessions function.
 *
 * THE PUBLIC BASE PATH IS NOT DERIVABLE FROM THE REQUEST (see
 * organisation/utils/config.ts for the full story): the gateway strips
 * `/functions/v1`, so anything the browser or GoTrue will use - the magic-link
 * `redirect_to`, fetch URLs in the page - is built from `publicBasePath()`.
 *
 * HTML only renders on the custom domain (api.risaboss.com); on the *.supabase.co
 * host Supabase rewrites text/html to text/plain.
 */

const DEFAULT_BASE_PATH = "/functions/v1/live-sessions"

/** Browser-facing path prefix, without a trailing slash. */
export function publicBasePath(): string {
  const configured = Deno.env.get("LIVE_SESSIONS_PUBLIC_BASE_PATH")?.trim()
  const raw = configured && configured.length > 0 ? configured : DEFAULT_BASE_PATH
  // "/" means "the site root": the vanity host cli.risaboss.com proxies its root onto this
  // function, so browser-facing paths are just "/auth", "/api/...". Returned as "" so that
  // `${publicBasePath()}/auth` stays well-formed; cookies.ts maps "" back to Path=/.
  if (raw === "/") return ""
  const withSlash = raw.startsWith("/") ? raw : `/${raw}`
  return withSlash.endsWith("/") ? withSlash.slice(0, -1) : withSlash
}

/**
 * Absolute base URL for the GoTrue `redirect_to`, from LIVE_SESSIONS_PUBLIC_BASE_URL only.
 *
 * There is deliberately NO fallback to the request's host. GoTrue exact-matches redirect_to
 * against its allow-list and, on a miss, silently falls back to site_url (boss://auth/verify): the
 * user would get a BOSS Console email whose token opens the desktop app instead of this page. And
 * X-Forwarded-Host is caller-controlled, so a fallback would also let a request choose the value.
 * Unset => null, and /api/otp answers 503 so the misconfiguration is loud.
 */
export function publicBaseUrl(): string | null {
  const configured = Deno.env.get("LIVE_SESSIONS_PUBLIC_BASE_URL")?.trim()
  if (!configured) return null
  return configured.replace(/\/+$/, "") + publicBasePath()
}

export interface LiveSessionsConfig {
  supabaseUrl: string
  anonKey: string
}

/**
 * The anon key is all this function needs: OTP send is an anon-key endpoint,
 * and every database read is made AS THE USER with their own JWT so RLS does
 * the filtering. There is deliberately no service-role read path.
 */
export function readConfig(): LiveSessionsConfig {
  return {
    supabaseUrl: (Deno.env.get("SUPABASE_URL") ?? "").replace(/\/+$/, ""),
    anonKey: Deno.env.get("SUPABASE_ANON_KEY") ?? "",
  }
}

/** A row is live when the desktop heartbeated within this window (heartbeat is 30 s). */
export const LIVE_WINDOW_SECONDS = 90
