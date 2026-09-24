/**
 * In-memory fixed-window rate limiting.
 *
 * HONEST ABOUT WHAT THIS IS. The state lives in one edge isolate, so the
 * effective limit is per-isolate and resets when the isolate recycles. It is a
 * brake on the cheap loop -- a script hammering `?t=` guesses, or a stuck admin
 * page retrying a DNS probe -- not a defence against a distributed attacker.
 * The real protections are elsewhere: handoff tokens are single-use, 5-minute
 * and 256-bit, so guessing is not a realistic path in the first place.
 *
 * A shared limiter (a table, or Redis) would be strictly better and is worth
 * doing if any of these endpoints ever becomes interesting to attack. It is not
 * done here because a per-request DB round trip to rate-limit a page render is
 * a worse trade than the protection is worth at this scale.
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
 * X-Forwarded-For is caller-controlled in general, but behind the Supabase
 * gateway the LEFTMOST entry is the one the gateway observed. It is still
 * spoofable by anyone who can reach the origin directly, which is another
 * reason this is a brake and not a control.
 */
export function clientKey(headers: Headers): string {
  const forwarded = headers.get("x-forwarded-for")
  if (forwarded) {
    const first = forwarded.split(",")[0].trim()
    if (first) return first
  }
  return headers.get("cf-connecting-ip") ?? headers.get("x-real-ip") ?? "unknown"
}
