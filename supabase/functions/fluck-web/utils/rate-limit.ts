/**
 * In-memory fixed-window rate limiting. (Adapted from organisation/utils/rate-limit.ts.)
 *
 * HONEST ABOUT WHAT THIS IS. The state lives in one edge isolate, so the effective limit is
 * per-isolate and resets when the isolate recycles. It is a brake on the cheap loop - a script
 * hammering /api/otp to send mail, or a stuck page polling /api/instances - not a defence against
 * a distributed attacker. The real backstop for mail is GoTrue's own `email_sent` limit; for the
 * data routes it is that every call is authenticated by the caller's own JWT and RLS.
 */

import { viaAlias } from "./config.ts"

interface Window {
  count: number
  resetAt: number
}

const buckets = new Map<string, Window>()

/**
 * Cap on distinct keys held. Without it the map is an unbounded allocation
 * driven by attacker-chosen keys (one entry per source IP), which is a memory
 * exhaustion bug wearing a rate limiter's clothes.
 */
const MAX_KEYS = 10_000

export interface RateLimitResult {
  allowed: boolean
  /** Seconds until the window resets. Only meaningful when !allowed. */
  retryAfterSeconds: number
}

/**
 * Consume one unit against `key`.
 *
 * `now` is injectable so tests can advance time without sleeping.
 */
export function rateLimit(
  key: string,
  limit: number,
  windowSeconds: number,
  now: number = Date.now(),
): RateLimitResult {
  const existing = buckets.get(key)

  if (!existing || existing.resetAt <= now) {
    if (buckets.size >= MAX_KEYS) evictExpired(now)
    buckets.set(key, { count: 1, resetAt: now + windowSeconds * 1000 })
    return { allowed: true, retryAfterSeconds: 0 }
  }

  if (existing.count >= limit) {
    return {
      allowed: false,
      retryAfterSeconds: Math.max(1, Math.ceil((existing.resetAt - now) / 1000)),
    }
  }

  existing.count += 1
  return { allowed: true, retryAfterSeconds: 0 }
}

/**
 * Drop expired windows; if that frees nothing, drop the whole map.
 *
 * The fallback matters: under a flood of distinct keys inside one window,
 * nothing is expired yet, and without the clear the map would sit at MAX_KEYS
 * and every new key would silently bypass the limiter. Clearing forfeits the
 * in-flight counts, which is the right way to fail for a best-effort brake.
 */
function evictExpired(now: number): void {
  for (const [key, window] of buckets) {
    if (window.resetAt <= now) buckets.delete(key)
  }
  if (buckets.size >= MAX_KEYS) buckets.clear()
}

/** Test hook. Never called in production. */
export function resetRateLimits(): void {
  buckets.clear()
}

/** Bounds the Map key a header value can produce. */
const MAX_KEY_LENGTH = 64

/**
 * Best-effort client identity for rate-limit keys.
 *
 * Through the fluck.risaboss.com Worker every request reaches us from Cloudflare's egress, so the
 * visitor's address travels in X-Fluck-Web-Client-Ip. That header is honoured ONLY when the Worker
 * proved itself with the alias secret (viaAlias); otherwise a direct caller could pick a fresh
 * bucket per request. Without that proof the platform's own observation is used: Cloudflare's
 * cf-connecting-ip, else the RIGHTMOST X-Forwarded-For entry (proxies append what they saw; the
 * leftmost is whatever the client sent). Still a brake, not a control.
 */
export function clientKey(headers: Headers): string {
  const proxied = viaAlias(headers) ? headers.get("x-fluck-web-client-ip")?.trim() : ""
  return (proxied || platformClientIp(headers)).slice(0, MAX_KEY_LENGTH)
}

function platformClientIp(headers: Headers): string {
  const cf = headers.get("cf-connecting-ip")?.trim()
  if (cf) return cf
  const forwarded = headers.get("x-forwarded-for")
  if (forwarded) {
    const parts = forwarded.split(",").map((p) => p.trim()).filter(Boolean)
    if (parts.length > 0) return parts[parts.length - 1]
  }
  return headers.get("x-real-ip") ?? "unknown"
}
