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
import { GATEWAY_ADMISSION_HEADER } from "../utils/trusted-gateway.ts"
import {
  clearGatewayTestKeys,
  gatewayTestKeys,
  mintGatewayAssertion,
  useGatewayTestKeys,
} from "./helpers/gateway.ts"

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

async function gatewayHeaders(body: string, privateKey: CryptoKey, ip: string) {
  return {
    'content-type': 'application/json',
    'x-forwarded-for': ip,
    [GATEWAY_ADMISSION_HEADER]: await mintGatewayAssertion(privateKey, { path: '/auth/challenge', body }),
  }
}

Deno.test("auth/challenge - the limiter trips before any lookup: an over-budget client gets 429 with zero Supabase lookups", async () => {
  resetRateLimiter()
  const { privateKey, publicKeyRaw } = await gatewayTestKeys()
  useGatewayTestKeys(publicKeyRaw)
  try {
    const mockClient = createMockSupabaseClient()
    mockClient.mockResponse('rpc.find_user_by_email', { data: [], error: null }, 'call')
    for (let i = 0; i <= AUTH_CHALLENGE_LIMIT; i++) {
      mockClient.mockResponse('rpc.admit_passkey_challenge', {
        data: [{ allowed: true, retry_after_seconds: 0, duplicate: false }],
        error: null,
      }, 'call')
    }
    const app = buildApp(mockClient)

    const body = JSON.stringify({ email: 'probe@example.com' })

    for (let i = 0; i < AUTH_CHALLENGE_LIMIT; i++) {
      const res = await app.request('http://gateway.test/auth/challenge', {
        method: 'POST',
        headers: await gatewayHeaders(body, privateKey, '9.9.9.9'),
        body,
      })
      assertEquals(res.status, 200, `request ${i + 1} within budget must be served`)
    }

    const lookupsBefore = mockClient.getQueryHistory().length

    // The very next probe from the same client: 429, and no lookup spent on it.
    const res = await app.request('http://gateway.test/auth/challenge', {
      method: 'POST',
      headers: await gatewayHeaders(body, privateKey, '9.9.9.9'),
      body,
    })
    assertEquals(res.status, 429, "the 61st probe in the window must be rate-limited")
    assertEquals(
      mockClient.getQueryHistory().length,
      lookupsBefore,
      "the rate-limited probe must not reach the user lookup",
    )
    resetRateLimiter()
  } finally {
    clearGatewayTestKeys()
  }
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

  const { privateKey, publicKeyRaw } = await gatewayTestKeys()
  useGatewayTestKeys(publicKeyRaw)
  try {
    for (const client of [unknownClient, noPasskeysClient]) {
      client.mockResponse('rpc.admit_passkey_challenge', {
        data: [{ allowed: true, retry_after_seconds: 0, duplicate: false }],
        error: null,
      }, 'call')
    }
    const body = JSON.stringify({ email: 'any@example.com' })

    const resUnknown = await buildApp(unknownClient).request('http://gateway.test/auth/challenge', {
      method: 'POST',
      headers: await gatewayHeaders(body, privateKey, '8.8.4.4'),
      body,
    })
    const resNoPasskeys = await buildApp(noPasskeysClient).request('http://gateway.test/auth/challenge', {
      method: 'POST',
      headers: await gatewayHeaders(body, privateKey, '8.8.8.8'),
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
  } finally {
    clearGatewayTestKeys()
  }
})
