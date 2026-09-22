/**
 * Route-level tests for the trusted gateway admission on POST /auth/challenge.
 *
 * The gateway is the only network route to the challenge endpoint. Its JWS
 * assertion is verified before any credential parsing or database RPC. Each
 * refusal below must therefore show zero Supabase calls: neither the
 * admission RPC nor the user lookup may run.
 *
 * The replay case reaches the atomic admission RPC by design (a valid
 * signature reused). It proves the replay inserts no challenge; the proof
 * that it consumes no budget lives in the pgTAP suite, which observes the
 * used counter directly.
 */

import { assertEquals } from "jsr:@std/assert"
import { OpenAPIHono } from "@hono/zod-openapi"
import type { SupabaseClient } from "@supabase/supabase-js"
import type { PasskeyContext } from "../types/context.ts"
import auth from "../routes/auth.ts"
import { GATEWAY_ADMISSION_HEADER } from "../utils/trusted-gateway.ts"
import { createMockSupabaseClient, type MockSupabaseClient } from "./helpers/mocks.ts"
import {
  clearGatewayTestKeys,
  gatewayTestKeys,
  mintGatewayAssertion,
  sha256BodyDigest,
  useGatewayTestKeys,
} from "./helpers/gateway.ts"

Deno.env.set("PASSKEY_RP_ID", "api.risaboss.com")

const GATEWAY_URL = "http://gateway.test/auth/challenge"
const DIRECT_URL = "https://xyz.supabase.co/auth/challenge"

function appFor(client: MockSupabaseClient) {
  const app = new OpenAPIHono<{ Variables: PasskeyContext }>()
  app.use("*", async (ctx, next) => {
    // deno-lint-ignore no-explicit-any
    ctx.set("supabase", client as any)
    await next()
  })
  app.route("/auth", auth)
  return app
}

function tables(client: MockSupabaseClient): string[] {
  return client.getQueryHistory().map((query) => query.table)
}

async function postChallenge(
  app: ReturnType<typeof appFor>,
  url: string,
  body: string,
  assertion?: string,
) {
  return await app.request(url, {
    method: "POST",
    body,
    headers: {
      "Content-Type": "application/json",
      ...(assertion ? { [GATEWAY_ADMISSION_HEADER]: assertion } : {}),
    },
  })
}

Deno.test("gateway admission - missing assertion is refused with no database call", async () => {
  const { publicKeyRaw } = await gatewayTestKeys()
  useGatewayTestKeys(publicKeyRaw)
  try {
    const client = createMockSupabaseClient()
    const body = JSON.stringify({ email: "person@example.test" })
    const response = await postChallenge(appFor(client), GATEWAY_URL, body)
    assertEquals(response.status, 403)
    assertEquals(tables(client), [])
  } finally {
    clearGatewayTestKeys()
  }
})

Deno.test("gateway admission - forged assertion is refused with no database call", async () => {
  const { publicKeyRaw } = await gatewayTestKeys()
  const other = await gatewayTestKeys()
  useGatewayTestKeys(publicKeyRaw)
  try {
    const client = createMockSupabaseClient()
    const body = JSON.stringify({ email: "person@example.test" })
    const forged = await mintGatewayAssertion(other.privateKey, {
      path: "/auth/challenge",
      body,
    })
    const response = await postChallenge(appFor(client), GATEWAY_URL, body, forged)
    assertEquals(response.status, 403)
    assertEquals(tables(client), [])
  } finally {
    clearGatewayTestKeys()
  }
})

Deno.test("gateway admission - expired assertion is refused with no database call", async () => {
  const { privateKey, publicKeyRaw } = await gatewayTestKeys()
  useGatewayTestKeys(publicKeyRaw)
  try {
    const client = createMockSupabaseClient()
    const body = JSON.stringify({ email: "person@example.test" })
    const expired = await mintGatewayAssertion(privateKey, {
      path: "/auth/challenge",
      body,
      issuedAgoSeconds: 300,
      ttlSeconds: 60,
    })
    const response = await postChallenge(appFor(client), GATEWAY_URL, body, expired)
    assertEquals(response.status, 403)
    assertEquals(tables(client), [])
  } finally {
    clearGatewayTestKeys()
  }
})

Deno.test("gateway admission - replayed assertion is refused without inserting a challenge", async () => {
  const { privateKey, publicKeyRaw } = await gatewayTestKeys()
  useGatewayTestKeys(publicKeyRaw)
  try {
    const client = createMockSupabaseClient()
    client.mockResponse("rpc.find_user_by_email", {
      data: [{ id: "user-1", email: "person@example.test" }],
      error: null,
    }, "call")
    client.mockResponse("user_passkeys", { data: [], error: null }, "select")
    client.mockResponse("rpc.admit_passkey_challenge", {
      data: [{ allowed: true, retry_after_seconds: 0, duplicate: false }],
      error: null,
    }, "call")
    client.mockResponse("rpc.admit_passkey_challenge", {
      data: [{ allowed: false, retry_after_seconds: 0, duplicate: true }],
      error: null,
    }, "call")
    const body = JSON.stringify({ email: "person@example.test" })
    const assertion = await mintGatewayAssertion(privateKey, { path: "/auth/challenge", body })
    const app = appFor(client)

    const first = await postChallenge(app, GATEWAY_URL, body, assertion)
    assertEquals(first.status, 200)

    const replay = await postChallenge(app, GATEWAY_URL, body, assertion)
    assertEquals(replay.status, 403)
    assertEquals(replay.headers.get("Retry-After"), null)

    const history = tables(client)
    assertEquals(history.filter((table) => table === "rpc.admit_passkey_challenge").length, 2)
    assertEquals(
      history.filter((table) => table === "passkey_challenges").length,
      0,
      "the replay must not insert a challenge",
    )
  } finally {
    clearGatewayTestKeys()
  }
})

Deno.test("gateway admission - wrong lane is refused with no database call", async () => {
  const { privateKey, publicKeyRaw } = await gatewayTestKeys()
  useGatewayTestKeys(publicKeyRaw)
  try {
    const client = createMockSupabaseClient()
    const body = JSON.stringify({ email: "person@example.test" })
    const wrongLane = await mintGatewayAssertion(privateKey, {
      path: "/auth/challenge",
      body,
      lane: "admin",
    })
    const response = await postChallenge(appFor(client), GATEWAY_URL, body, wrongLane)
    assertEquals(response.status, 403)
    assertEquals(tables(client), [])
  } finally {
    clearGatewayTestKeys()
  }
})

Deno.test("gateway admission - body mismatch is refused with no database call", async () => {
  const { privateKey, publicKeyRaw } = await gatewayTestKeys()
  useGatewayTestKeys(publicKeyRaw)
  try {
    const client = createMockSupabaseClient()
    const minted = JSON.stringify({ email: "person@example.test" })
    const assertion = await mintGatewayAssertion(privateKey, { path: "/auth/challenge", body: minted })
    const tampered = JSON.stringify({ email: "other@example.test" })
    // Sanity: the digests really differ, so this proves binding, not schema luck.
    assertEquals((await sha256BodyDigest(minted)) === (await sha256BodyDigest(tampered)), false)
    const response = await postChallenge(appFor(client), GATEWAY_URL, tampered, assertion)
    assertEquals(response.status, 403)
    assertEquals(tables(client), [])
  } finally {
    clearGatewayTestKeys()
  }
})

Deno.test("gateway admission - direct provider origin is refused with no database call", async () => {
  const { privateKey, publicKeyRaw } = await gatewayTestKeys()
  useGatewayTestKeys(publicKeyRaw)
  try {
    const client = createMockSupabaseClient()
    const body = JSON.stringify({ email: "person@example.test" })
    const assertion = await mintGatewayAssertion(privateKey, { path: "/auth/challenge", body })
    const response = await postChallenge(appFor(client), DIRECT_URL, body, assertion)
    assertEquals(response.status, 403)
    assertEquals(tables(client), [])
  } finally {
    clearGatewayTestKeys()
  }
})

Deno.test("gateway admission - missing verification key fails closed with 503", async () => {
  clearGatewayTestKeys()
  Deno.env.set("GATEWAY_PUBLIC_HOSTS", "gateway.test")
  try {
    const client = createMockSupabaseClient()
    const body = JSON.stringify({ email: "person@example.test" })
    const response = await postChallenge(appFor(client), GATEWAY_URL, body, "anything")
    assertEquals(response.status, 503)
    assertEquals(response.headers.get("Retry-After"), "30")
    assertEquals(tables(client), [])
  } finally {
    clearGatewayTestKeys()
  }
})

Deno.test("gateway admission - trusted lane reaches the atomic admission RPC", async () => {
  const { privateKey, publicKeyRaw } = await gatewayTestKeys()
  useGatewayTestKeys(publicKeyRaw)
  try {
    const client = createMockSupabaseClient()
    client.mockResponse("rpc.find_user_by_email", { data: [], error: null }, "call")
    client.mockResponse("rpc.admit_passkey_challenge", {
      data: [{ allowed: true, retry_after_seconds: 0, duplicate: false }],
      error: null,
    }, "call")
    const body = JSON.stringify({ email: "person@example.test" })
    const assertion = await mintGatewayAssertion(privateKey, {
      path: "/auth/challenge",
      body,
      lane: "trusted",
    })
    const response = await postChallenge(appFor(client), GATEWAY_URL, body, assertion)
    assertEquals(response.status, 200)
    assertEquals(tables(client)[0], "rpc.admit_passkey_challenge")
  } finally {
    clearGatewayTestKeys()
  }
})
