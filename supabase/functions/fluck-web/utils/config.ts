/**
 * Deployment configuration for the fluck-web function (shape copied from live-sessions/utils/config.ts).
 *
 * The browser-facing base path is not derivable from the request (the gateway strips
 * `/functions/v1`), so the magic-link `redirect_to` and the page's fetch URLs are built from
 * `publicBasePath()` / `publicBaseUrl()`.
 */

const DEFAULT_BASE_PATH = "/functions/v1/fluck-web"

/** Browser-facing path prefix, without a trailing slash. "/" (the vanity host) maps to "". */
export function publicBasePath(): string {
  const configured = Deno.env.get("FLUCK_WEB_PUBLIC_BASE_PATH")?.trim()
  const raw = configured && configured.length > 0 ? configured : DEFAULT_BASE_PATH
  if (raw === "/") return ""
  const withSlash = raw.startsWith("/") ? raw : `/${raw}`
  return withSlash.endsWith("/") ? withSlash.slice(0, -1) : withSlash
}

/**
 * Absolute base URL for the GoTrue `redirect_to`, from FLUCK_WEB_PUBLIC_BASE_URL only. No fallback
 * to the request's host: GoTrue exact-matches redirect_to and silently falls back to site_url on a
 * miss, and X-Forwarded-Host is caller-controlled. Unset => null, and sign-in answers 503.
 */
export function publicBaseUrl(): string | null {
  const configured = Deno.env.get("FLUCK_WEB_PUBLIC_BASE_URL")?.trim()
  if (!configured) return null
  return configured.replace(/\/+$/, "") + publicBasePath()
}

/** Exact origin a same-origin POST must carry (the CSRF check compares against it). */
export function publicOrigin(): string | null {
  const base = publicBaseUrl()
  if (!base) return null
  try {
    return new URL(base).origin
  } catch {
    return null
  }
}

/**
 * Browser-facing Supabase URL for the Google / Apple `/auth/v1/authorize` hop.
 * FLUCK_WEB_AUTH_PUBLIC_URL wins (https://api.risaboss.com in production); otherwise SUPABASE_URL.
 */
export function authPublicUrl(): string {
  const configured = Deno.env.get("FLUCK_WEB_AUTH_PUBLIC_URL")?.trim()
  const raw = configured && configured.length > 0 ? configured : (Deno.env.get("SUPABASE_URL") ?? "")
  return raw.replace(/\/+$/, "")
}

export interface FluckWebConfig {
  supabaseUrl: string
  anonKey: string
}

/** Anon key only: every database call is made AS THE USER with their own JWT. No service role. */
export function readConfig(): FluckWebConfig {
  return {
    supabaseUrl: (Deno.env.get("SUPABASE_URL") ?? "").replace(/\/+$/, ""),
    anonKey: Deno.env.get("SUPABASE_ANON_KEY") ?? "",
  }
}

/** Mirrors the 90 s window in fluck_web_list_instances / fluck_web_mint_ticket (heartbeat is 30 s). */
export const LIVE_WINDOW_SECONDS = 90
