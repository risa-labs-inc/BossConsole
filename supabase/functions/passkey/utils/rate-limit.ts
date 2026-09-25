/**
 * In-memory fixed-window rate limiting.
 *
 * HONEST ABOUT WHAT THIS IS. The state lives in one edge isolate, so the
 * effective limit is per-isolate and resets when the isolate recycles. It is
 * a brake on the cheap loop -- a script walking a candidate email list
 * against /auth/challenge (BossConsole#768) -- not a defence against a
 * distributed attacker. The real protections are elsewhere: pre-auth failures
 * now return inert challenges, so a probe learns nothing regardless of rate.
 *
 * Mirrors supabase/functions/organisation/utils/rate-limit.ts deliberately:
 * edge functions are isolated deploys with no shared import path between
 * functions, so the utility is copied rather than imported.
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

function evictExpired(now: number) {
  for (const [key, window] of buckets) {
    if (window.resetAt <= now) buckets.delete(key)
  }
  // Distinct attacker-controlled keys in one live window have nothing
  // expired to evict. Clear at the ceiling so the map cannot grow without
  // bound; this deliberately mirrors the organisation limiter.
  if (buckets.size >= MAX_KEYS) buckets.clear()
}

/** Exposed for tests: drops all limiter state so suites are isolated. */
export function resetRateLimiter() {
  buckets.clear()
}

/**
 * Best-effort client identity. Prefer connecting headers supplied by the gateway.
 * Forwarded headers are not authentication and can be spoofed without a trusted ingress.
 */
export function clientKey(headers: Headers): string {
  // Prefer gateway-provided identity to an XFF chain's potentially caller-supplied prefix.
  // This remains a best-effort bucket key, not an authentication boundary.
  for (const name of ["cf-connecting-ip", "x-real-ip"]) {
    const value = headers.get(name)?.trim()
    if (value) return value
  }
  // Leftmost XFF is the caller's own claim - anyone can write a victim's address there
  // and spend the victim's budget (a targeted sign-in lockout against /auth/complete).
  // The rightmost entry is the one the gateway appended for the connection it actually
  // accepted, the only hop a caller cannot forge.
  const chain = headers.get("x-forwarded-for")?.split(",")
  const last = chain?.[chain.length - 1]?.trim()
  return last || "unknown"
}
