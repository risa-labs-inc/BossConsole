/**
 * In-memory fixed-window rate limiting. (Adapted from organisation/utils/rate-limit.ts.)
 *
 * HONEST ABOUT WHAT THIS IS. The state lives in one edge isolate, so the effective limit is
 * per-isolate and resets when the isolate recycles. It is a brake on the cheap loop - a script
 * hammering /api/otp to send mail, or a stuck page polling /api/sessions - not a defence against
 * a distributed attacker. The real backstop for mail is GoTrue's own `email_sent` limit; for the
 * data routes it is that every call is authenticated by the caller's own JWT and RLS.
 */

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

/**
 * Best-effort client identity for rate-limit keys.
 *
 * Proxies APPEND the address they observed to X-Forwarded-For, so the RIGHTMOST entry is the
 * one our gateway saw and the leftmost is whatever the client chose to send. Cloudflare's
 * cf-connecting-ip is the single observed address and is preferred when present. Still a
 * brake and not a control: anyone reaching the origin directly can set either header.
 */
export function clientKey(headers: Headers): string {
  // Through the cli.risaboss.com Worker the visitor's address arrives in this header (the Worker
  // reads CF-Connecting-IP on its own inbound request). Spoofable on a direct hit, which only lets
  // a caller pick their own bucket - the same power X-Forwarded-For already gives them.
  const viaAlias = headers.get("x-live-sessions-client-ip")?.trim()
  if (viaAlias) return viaAlias
  const cf = headers.get("cf-connecting-ip")?.trim()
  if (cf) return cf
  const forwarded = headers.get("x-forwarded-for")
  if (forwarded) {
    const parts = forwarded.split(",").map((p) => p.trim()).filter(Boolean)
    if (parts.length > 0) return parts[parts.length - 1]
  }
  return headers.get("x-real-ip") ?? "unknown"
}
