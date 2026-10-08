/**
 * Shared HTTP cache-control policies for the plugin-store function.
 *
 * Implements private response caching (#1634):
 *
 * - Caller-dependent endpoints (download-info routes returning signed URLs,
 *   admin routes, API keys, publishing, and user ratings) must never be cached
 *   by shared caches or CDNs. Every success, error (401, 403, 404, 429, 500),
 *   thrown exception, and validation failure must carry:
 *     `Cache-Control: private, no-store`
 *
 * - Public catalogue responses (/list, /:pluginId) for genuinely anonymous
 *   callers retain their intended cache header (`public, max-age=60`).
 *   An authenticated catalogue response (JWT or API key present), or any
 *   error response (4xx, 5xx), uses the private policy:
 *     `Cache-Control: private, no-store`
 *
 * - Deliberately cacheable caller-independent routes: `/tags/popular` and
 *   `/:pluginId/ratings` return caller-independent data for anonymous requests
 *   and do not set `private, no-store` on anonymous 200s; they are marked
 *   `private, no-store` only when caller credentials are provided or on error.
 */

import type { MiddlewareHandler } from "hono"
import type { PluginStoreContext } from "../types/context.ts"

export const PRIVATE_NO_STORE = "private, no-store"
export const PUBLIC_CATALOGUE_CACHE = "public, max-age=60"

/**
 * Check if the request carries caller credentials (JWT Bearer token or X-API-Key).
 *
 * Intentionally ignores `apikey`: the Supabase anonymous key (`apikey: <anon>`)
 * is sent by all callers (including unauthenticated desktop clients) to pass
 * the gateway and does not identify the caller. Real caller identity is carried
 * by `Authorization: Bearer <token>` or `X-API-Key: <key>`.
 */
export function hasCallerCredentials(req: { header: (name: string) => string | undefined }): boolean {
  const auth = req.header("authorization")
  if (auth && auth.trim().length > 0) return true
  const apiKey = req.header("x-api-key")
  if (apiKey && apiKey.trim().length > 0) return true
  return false
}

/**
 * Middleware that guarantees Cache-Control: private, no-store on caller-dependent responses.
 *
 * Uses fill-in semantics: if the response does not already carry a Cache-Control header
 * (for example, from a handler or route policy), or on error (status >= 400), it stamps
 * `Cache-Control: private, no-store`. No caller-dependent route may set its own weaker header.
 *
 * Used for routes that are always caller-dependent:
 * - download-info routes returning signed URLs
 * - admin management routes
 * - API key management routes
 * - publishing routes
 */
export function privateNoStore(): MiddlewareHandler<{ Variables: PluginStoreContext }> {
  return async (ctx, next) => {
    try {
      await next()
    } finally {
      if (!ctx.res.headers.has("Cache-Control")) {
        ctx.res.headers.set("Cache-Control", PRIVATE_NO_STORE)
      }
    }
  }
}

/**
 * Middleware for catalogue/browse routes.
 *
 * Genuinely anonymous callers on public catalogue routes retain their intended
 * cache header (for example, `public, max-age=60`).
 *
 * Authenticated requests, validation failures, rate limits, 404s, and server
 * errors are marked `Cache-Control: private, no-store`.
 *
 * Appends `Vary: Authorization, X-API-Key` without clobbering any pre-existing
 * `Vary: Origin` set by CORS middleware.
 */
export function catalogueCachePolicy(): MiddlewareHandler<{ Variables: PluginStoreContext }> {
  return async (ctx, next) => {
    try {
      await next()
    } finally {
      const hasAuth = hasCallerCredentials(ctx.req)
      const isError = ctx.res.status >= 400

      if (hasAuth || isError) {
        ctx.res.headers.set("Cache-Control", PRIVATE_NO_STORE)
      }

      const existingVary = ctx.res.headers.get("Vary")
      if (!existingVary) {
        ctx.res.headers.set("Vary", "Authorization, X-API-Key")
      } else if (!existingVary.includes("Authorization")) {
        ctx.res.headers.append("Vary", "Authorization, X-API-Key")
      }
    }
  }
}

/**
 * Middleware for rating routes.
 *
 * User-specific ratings (/rate, /rating) are always caller-dependent and marked
 * `private, no-store`.
 *
 * General plugin reviews (/ratings) retain cacheability for anonymous callers
 * on 200 OK, but are marked `private, no-store` if the caller is authenticated
 * or an error occurs.
 */
export function ratingCachePolicy(): MiddlewareHandler<{ Variables: PluginStoreContext }> {
  return async (ctx, next) => {
    try {
      await next()
    } finally {
      const normPath = ctx.req.path.replace(/\/+$/, "")
      const isUserRating = normPath.endsWith("/rate") || normPath.endsWith("/rating")
      const hasAuth = hasCallerCredentials(ctx.req)
      const isError = ctx.res.status >= 400

      if (isUserRating || hasAuth || isError) {
        ctx.res.headers.set("Cache-Control", PRIVATE_NO_STORE)
      }
    }
  }
}
