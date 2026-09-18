/**
 * Tests for the /auth/challenge rate limiter (BossConsole#768).
 *
 * `now` is injectable, so the window is advanced without sleeping.
 */

import { assertEquals } from "jsr:@std/assert"
import { rateLimit, clientKey, resetRateLimiter } from "../utils/rate-limit.ts"

Deno.test("rateLimit - allows up to the limit within the window", () => {
  const key = "authchallenge:1.2.3.4"
  for (let i = 0; i < 20; i++) {
    assertEquals(rateLimit(key, 20, 3600, 1_000_000).allowed, true)
  }
})

Deno.test("rateLimit - trips on the next call in the same window and reports retry-after", () => {
  const key = "authchallenge:5.6.7.8"
  for (let i = 0; i < 20; i++) {
    assertEquals(rateLimit(key, 20, 3600, 1_000_000).allowed, true)
  }
  const blocked = rateLimit(key, 20, 3600, 1_000_000)
  assertEquals(blocked.allowed, false)
  assertEquals(blocked.retryAfterSeconds > 0, true)
})

Deno.test("rateLimit - a new window resets the count", () => {
  const key = "authchallenge:9.9.9.9"
  for (let i = 0; i < 20; i++) {
    assertEquals(rateLimit(key, 20, 3600, 1_000_000).allowed, true)
  }
  assertEquals(rateLimit(key, 20, 3600, 1_000_000).allowed, false)
  // 3600s later the window has expired
  assertEquals(rateLimit(key, 20, 3600, 1_000_000 + 3600_000).allowed, true)
})

Deno.test("rateLimit - distinct clients get distinct budgets", () => {
  for (let i = 0; i < 20; i++) {
    assertEquals(rateLimit("authchallenge:1.1.1.1", 20, 3600, 1_000_000).allowed, true)
  }
  assertEquals(rateLimit("authchallenge:1.1.1.1", 20, 3600, 1_000_000).allowed, false)
  assertEquals(rateLimit("authchallenge:2.2.2.2", 20, 3600, 1_000_000).allowed, true)
})

Deno.test("clientKey - prefers the first forwarded-for hop, falls back to connecting headers", () => {
  const forwarded = new Headers({ "x-forwarded-for": "3.3.3.3, 4.4.4.4" })
  assertEquals(clientKey(forwarded), "3.3.3.3")

  const cf = new Headers({ "cf-connecting-ip": "5.5.5.5" })
  assertEquals(clientKey(cf), "5.5.5.5")

  const real = new Headers({ "x-real-ip": "6.6.6.6" })
  assertEquals(clientKey(real), "6.6.6.6")

  assertEquals(clientKey(new Headers()), "unknown")
})

Deno.test("rateLimit - an attacker spraying distinct keys is bounded by MAX_KEYS eviction", () => {
  // Review follow-up: the copied limiter must keep the organisation
  // implementation's memory bound. Without the MAX_KEYS guard, one entry
  // per source IP is an unbounded allocation driven by attacker-chosen keys -
  // a memory exhaustion bug wearing a rate limiter's clothes.
  const base = 2_000_000_000
  resetRateLimiter()
  // The canary starts exhausted. It becomes allowed again only if the
  // MAX_KEYS fallback actually clears the live-window map; merely admitting
  // fresh keys would make the weaker test below pass without a memory bound.
  assertEquals(rateLimit("authchallenge:canary", 1, 3600, base).allowed, true)
  assertEquals(rateLimit("authchallenge:canary", 1, 3600, base).allowed, false)
  // Spray 10_050 distinct keys (MAX_KEYS + 50): the guard must have evicted
  // expired windows and kept the map bounded, and a fresh key still works.
  for (let i = 0; i < 10_050; i++) {
    rateLimit(`authchallenge:10.0.${Math.floor(i / 250)}.${i % 250}`, 60, 3600, base + i)
  }
  assertEquals(rateLimit("authchallenge:canary", 1, 3600, base + 20_000).allowed, true)
  // A fresh key gets a fresh window.
  assertEquals(rateLimit("authchallenge:11.11.11.11", 60, 3600, base + 20_000).allowed, true)
  // And the same fresh key is still within its own budget on the next call.
  assertEquals(rateLimit("authchallenge:11.11.11.11", 60, 3600, base + 20_001).allowed, true)
})

Deno.test("clientKey - connecting identity wins over a spoofed forwarded prefix", () => {
  assertEquals(clientKey(new Headers({
    "cf-connecting-ip": "5.5.5.5", "x-real-ip": "6.6.6.6", "x-forwarded-for": "spoofed, 7.7.7.7",
  })), "5.5.5.5")
  assertEquals(clientKey(new Headers({
    "x-real-ip": "6.6.6.6", "x-forwarded-for": "spoofed, 7.7.7.7",
  })), "6.6.6.6")
})
