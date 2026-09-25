/**
 * The rate limiter, including the failure mode a naive cap would have.
 */

import { assert, assertEquals } from "@std/assert"
import { clientKey, rateLimit, resetRateLimits } from "../utils/rate-limit.ts"

Deno.test("requests are allowed up to the limit and refused after", () => {
  resetRateLimits()
  const now = 1_000_000

  for (let i = 0; i < 5; i++) {
    assertEquals(rateLimit("k", 5, 60, now).allowed, true, `request ${i + 1} should pass`)
  }

  const refused = rateLimit("k", 5, 60, now)
  assertEquals(refused.allowed, false)
  assert(refused.retryAfterSeconds > 0 && refused.retryAfterSeconds <= 60)
})

Deno.test("the window resets", () => {
  resetRateLimits()
  const now = 1_000_000
  for (let i = 0; i < 5; i++) rateLimit("k", 5, 60, now)
  assertEquals(rateLimit("k", 5, 60, now).allowed, false)
  assertEquals(rateLimit("k", 5, 60, now + 60_001).allowed, true)
})

Deno.test("keys are independent", () => {
  resetRateLimits()
  const now = 1_000_000
  for (let i = 0; i < 5; i++) rateLimit("a", 5, 60, now)
  assertEquals(rateLimit("a", 5, 60, now).allowed, false)
  assertEquals(rateLimit("b", 5, 60, now).allowed, true)
})

Deno.test("a flood of distinct keys does not disable the limiter", () => {
  resetRateLimits()
  const now = 1_000_000

  // Every key is fresh and nothing has expired, so the eviction pass frees
  // nothing. Without the clear-on-full fallback the map would sit at MAX_KEYS
  // and every subsequent key would bypass the limiter entirely.
  for (let i = 0; i < 10_050; i++) rateLimit(`flood-${i}`, 1, 60, now)

  const victim = "still-limited"
  assertEquals(rateLimit(victim, 1, 60, now).allowed, true)
  assertEquals(rateLimit(victim, 1, 60, now).allowed, false)
})

Deno.test("clientKey prefers the RIGHTMOST forwarded address (leftmost is caller-controlled)", () => {
  // "client-forgery, real-client" - trusted proxies append on the right, so
  // the rightmost entry is the one the gateway observed (#974).
  assertEquals(
    clientKey(new Headers({ "x-forwarded-for": "203.0.113.5, 70.41.3.18" })),
    "70.41.3.18",
  )
  assertEquals(clientKey(new Headers({ "cf-connecting-ip": "203.0.113.9" })), "203.0.113.9")
  assertEquals(clientKey(new Headers()), "unknown")
})

Deno.test("a forged leftmost XFF entry cannot rotate the limiter key", () => {
  // The #974 attack: rotate the caller-controlled leftmost entry and the
  // bucket must stay the same, because the key derives from the rightmost
  // (gateway-appended) entry.
  const first = clientKey(new Headers({ "x-forwarded-for": "10.0.0.1, 70.41.3.18" }))
  const second = clientKey(new Headers({ "x-forwarded-for": "10.0.0.2, 70.41.3.18" }))
  assertEquals(first, second)
})

Deno.test("an empty or whitespace-padded XFF chain falls back to unknown", () => {
  assertEquals(clientKey(new Headers({ "x-forwarded-for": "  " })), "unknown")
  assertEquals(clientKey(new Headers({ "x-forwarded-for": "  ,  " })), "unknown")
})
