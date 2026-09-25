/**
 * clientKey derives a rate-limit bucket from request headers the same way the
 * passkey limiter does (#1628): the gateway's connecting
 * headers, else the RIGHTMOST X-Forwarded-For entry. Everything left of that
 * entry is the caller's own text.
 */

import { assertEquals } from "@std/assert"
import { clientKey } from "../utils/rate-limit.ts"

Deno.test("clientKey - the gateway-appended X-Forwarded-For hop keys the bucket, not the caller's prefix", () => {
  assertEquals(clientKey(new Headers({ "x-forwarded-for": "3.3.3.3, 4.4.4.4" })), "4.4.4.4")
  assertEquals(clientKey(new Headers({ "x-forwarded-for": "9.9.9.9, 8.8.8.8, 7.7.7.7" })), "7.7.7.7")
  assertEquals(clientKey(new Headers({ "x-forwarded-for": " 6.6.6.6 " })), "6.6.6.6")
})

Deno.test("clientKey - a connecting header wins over any forwarded chain", () => {
  assertEquals(
    clientKey(new Headers({
      "cf-connecting-ip": "5.5.5.5",
      "x-real-ip": "6.6.6.6",
      "x-forwarded-for": "spoofed, 7.7.7.7",
    })),
    "5.5.5.5",
  )
  assertEquals(
    clientKey(new Headers({ "x-real-ip": "6.6.6.6", "x-forwarded-for": "spoofed, 7.7.7.7" })),
    "6.6.6.6",
  )
})

Deno.test("clientKey - blank or missing headers fall through to unknown", () => {
  assertEquals(clientKey(new Headers()), "unknown")
  assertEquals(clientKey(new Headers({ "cf-connecting-ip": "  ", "x-forwarded-for": "1.1.1.1, " })), "unknown")
  assertEquals(clientKey(new Headers({ "x-real-ip": "", "x-forwarded-for": "2.2.2.2" })), "2.2.2.2")
})
