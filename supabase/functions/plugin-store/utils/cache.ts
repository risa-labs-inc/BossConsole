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
 */

import type { MiddlewareHandler } from "hono"
import type { PluginStoreContext } from "../types/context.ts"

export const PRIVATE_NO_STORE = "private, no-store"
export const PUBLIC_CATALOGUE_CACHE = "public, max-age=60"

/**
 * Check if the request carries caller credentials (JWT Bearer token or API key).
 */
export function hasCallerCredentials(req: { header: (name: string) => string | undefined }): boolean {
  const auth = req.header("Authorization")
  if (auth && auth.trim().length > 0) return true
  const apiKey = req.header("x-api-key") ?? req.header("X-API-Key")
  if (apiKey && apiKey.trim().length > 0) return true
  return false
}

/**
 * Middleware that guarantees Cache-Control: private, no-store on all responses,
 * including thrown errors and validation failures.
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
      if (ctx.res) {
        ctx.res.headers.set("Cache-Control", PRIVATE_NO_STORE)
      }
    }
  }
}

/**
 * Middleware for catalogue/browse routes.
 *
 * Genuinely anonymous callers on public catalogue routes retain their intended
 * cache header (e.g. `public, max-age=60`).
 *
 * Authenticated requests, validation failures, rate limits, 404s, and server
 * errors are marked `Cache-Control: private, no-store`.
 */
export function catalogueCachePolicy(): MiddlewareHandler<{ Variables: PluginStoreContext }> {
  return async (ctx, next) => {
    try {
      await next()
    } finally {
      if (ctx.res) {
        const hasAuth = hasCallerCredentials(ctx.req)
        const isError = ctx.res.status >= 400

        if (hasAuth || isError) {
          ctx.res.headers.set("Cache-Control", PRIVATE_NO_STORE)
        }
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
      if (ctx.res) {
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
}
