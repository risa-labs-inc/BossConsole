/**
 * Route-level tests for the /auth/challenge hardening (BossConsole#768,
 * review follow-ups).
 *
 * Two properties only the route can prove:
 * - the rate limit is consumed BEFORE any lookup: an over-budget client is
 *   429'd with zero Supabase lookups spent on their probe
 * - the serialized response is shape-identical across the pre-auth failure
 *   states (unknown email, no passkeys): a prober cannot tell them apart
 *   from the bytes on the wire
 */

import { assertEquals } from "jsr:@std/assert"
import { OpenAPIHono } from "@hono/zod-openapi"
import type { PasskeyContext } from "../types/context.ts"
import auth from "../routes/auth.ts"
import { createMockSupabaseClient, type MockSupabaseClient } from "./helpers/mocks.ts"
import { resetRateLimiter } from "../utils/rate-limit.ts"

Deno.env.set('PASSKEY_RP_ID', 'api.risaboss.com')

function buildApp(mockClient: MockSupabaseClient) {
  const app = new OpenAPIHono<{ Variables: PasskeyContext }>()
  app.use("*", async (ctx, next) => {
    // deno-lint-ignore no-explicit-any
    ctx.set("supabase", mockClient as any)
    await next()
  })
  app.route("/auth", auth)
  return app
}

// Mirrors the route's AUTH_CHALLENGE_LIMIT (60/hour per client).
const AUTH_CHALLENGE_LIMIT = 60

Deno.test("auth/challenge - the limiter trips before any lookup: an over-budget client gets 429 with zero Supabase lookups", async () => {
  resetRateLimiter()
  const mockClient = createMockSupabaseClient()
  mockClient.mockResponse('rpc.find_user_by_email', { data: [], error: null }, 'call')
  const app = buildApp(mockClient)

  const headers = {
    'content-type': 'application/json',
    'x-forwarded-for': '9.9.9.9',
  }
  const body = JSON.stringify({ email: 'probe@example.com' })

  for (let i = 0; i < AUTH_CHALLENGE_LIMIT; i++) {
    const res = await app.request('/auth/challenge', { method: 'POST', headers, body })
    assertEquals(res.status, 200, `request ${i + 1} within budget must be served`)
  }

  const lookupsBefore = mockClient.getQueryHistory().length

  // The very next probe from the same client: 429, and no lookup spent on it.
  const res = await app.request('/auth/challenge', { method: 'POST', headers, body })
  assertEquals(res.status, 429, "the 61st probe in the window must be rate-limited")
  assertEquals(
    mockClient.getQueryHistory().length,
    lookupsBefore,
    "the rate-limited probe must not reach the user lookup",
  )
  resetRateLimiter()
})

Deno.test("auth/challenge - unknown email and no-passkeys produce identical response shapes", async () => {
  resetRateLimiter()
  // Unknown email: the RPC returns an empty row set.
  const unknownClient = createMockSupabaseClient()
  unknownClient.mockResponse('rpc.find_user_by_email', { data: [], error: null }, 'call')
  // Known email, no passkeys: the RPC resolves a user, the passkey select is empty.
  const noPasskeysClient = createMockSupabaseClient()
  noPasskeysClient.mockResponse('rpc.find_user_by_email', {
    data: [{ id: 'user-456', email: 'known@example.com' }],
    error: null,
  }, 'call')
  noPasskeysClient.mockResponse('user_passkeys', { data: [], error: null }, 'select')

  const body = JSON.stringify({ email: 'any@example.com' })
  const headers = (ip: string) => ({
    'content-type': 'application/json',
    'x-forwarded-for': ip,
  })

  const resUnknown = await buildApp(unknownClient).request('/auth/challenge', {
    method: 'POST',
    headers: headers('8.8.4.4'),
    body,
  })
  const resNoPasskeys = await buildApp(noPasskeysClient).request('/auth/challenge', {
    method: 'POST',
    headers: headers('8.8.8.8'),
    body,
  })

  assertEquals(resUnknown.status, 200)
  assertEquals(resNoPasskeys.status, 200)

  const unknown = await resUnknown.json()
  const noPasskeys = await resNoPasskeys.json()

  // Same keys, same types, same content class - only the opaque challenge
  // value differs (both are fresh randoms a prober cannot distinguish).
  assertEquals(
    Object.keys(unknown).sort(),
    Object.keys(noPasskeys).sort(),
    "the response shape must be identical for unknown email and no-passkeys",
  )
  assertEquals(unknown.success, noPasskeys.success)
  assertEquals(unknown.allowCredentials, [], "unknown email yields an empty allow list")
  assertEquals(noPasskeys.allowCredentials, [], "no-passkeys yields the same empty allow list")
  assertEquals(typeof unknown.challenge, typeof noPasskeys.challenge)
  assertEquals(unknown.challenge !== noPasskeys.challenge, true, "challenges are per-request randoms, not a static tell")
  resetRateLimiter()
})
