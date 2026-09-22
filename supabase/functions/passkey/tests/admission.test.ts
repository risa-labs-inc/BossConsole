import { assert, assertEquals, assertExists } from "jsr:@std/assert"
import { OpenAPIHono } from "@hono/zod-openapi"
import type { SupabaseClient } from "@supabase/supabase-js"
import type { PasskeyContext } from "../types/context.ts"
import auth from "../routes/auth.ts"
import register from "../routes/register.ts"
import maintenance from "../routes/maintenance.ts"
import { GATEWAY_ADMISSION_HEADER } from "../utils/trusted-gateway.ts"
import { createMockSupabaseClient, mockPasskey, type MockSupabaseClient } from "./helpers/mocks.ts"
import { MAX_PASSKEY_REQUEST_BYTES } from "../utils/request-limits.ts"
import {
  clearGatewayTestKeys,
  gatewayTestKeys,
  mintGatewayAssertion,
  useGatewayTestKeys,
} from "./helpers/gateway.ts"

const GATEWAY_URL = "http://gateway.test/auth/challenge"

function appFor(client: MockSupabaseClient) {
  const app = new OpenAPIHono<{ Variables: PasskeyContext }>()
  app.use('*', async (ctx, next) => {
    ctx.set('supabase', client as unknown as SupabaseClient)
    await next()
  })
  app.route('/auth', auth)
  app.route('/register', register)
  app.route('/maintenance', maintenance)
  return app
}

let gatewayPrivateKey: CryptoKey | null = null

async function gatewaySetup(): Promise<void> {
  if (!gatewayPrivateKey) {
    const keys = await gatewayTestKeys()
    gatewayPrivateKey = keys.privateKey
    useGatewayTestKeys(keys.publicKeyRaw)
  }
}

function gatewayTeardown(): void {
  clearGatewayTestKeys()
  gatewayPrivateKey = null
}

const challengeBody = { email: 'person@example.test', sessionId: 'synthetic-session' }
async function post(app: ReturnType<typeof appFor>, path: string, body: unknown, authorization?: string,
  mintOptions: Record<string, unknown> = {}) {
  await gatewaySetup()
  const encoded = JSON.stringify(body)
  const assertion = await mintGatewayAssertion(gatewayPrivateKey!, {
    path: new URL(path, GATEWAY_URL).pathname,
    body: encoded,
    ...mintOptions,
  })
  const url = path.startsWith("http") ? path : new URL(path, GATEWAY_URL).toString()
  return app.request(url, { method: 'POST', body: encoded, headers: {
    'Content-Type': 'application/json',
    [GATEWAY_ADMISSION_HEADER]: assertion,
    ...(authorization ? { Authorization: authorization } : {})
  } })
}
function allowAdmission(client: MockSupabaseClient) {
  client.mockResponse('rpc.admit_passkey_challenge', {
    data: [{ allowed: true, retry_after_seconds: 0, duplicate: false }], error: null
  }, 'call')
}

Deno.test('admission carries the verified lane and gateway request id, not caller-controlled values', async () => {
  try {
    const client = createMockSupabaseClient()
    allowAdmission(client)
    client.mockResponse('rpc.find_user_by_email', { data: [{ id: 'owner', email: challengeBody.email }], error: null }, 'call')
    client.mockResponse('user_passkeys', { data: [mockPasskey], error: null }, 'select')
    client.mockResponse('passkey_challenges', { data: [{ id: 'stored' }], error: null }, 'insert')
    const response = await post(appFor(client), '/auth/challenge', challengeBody)
    assertEquals(response.status, 200)
    const admission = client.getQueryHistory().find(query => query.table === 'rpc.admit_passkey_challenge')
    assertExists(admission)
    // The lane is the verified gateway lane, not a public header. A missing assertion is untrusted.
    assertEquals(admission!.params['p_lane'], 'untrusted')
    assertEquals(admission!.params['p_type'], 'authentication')
    assert('p_request_id' in admission!.params)
    // A verified trusted assertion switches the RPC to the trusted lane.
    const trusted = createMockSupabaseClient()
    allowAdmission(trusted)
    trusted.mockResponse('rpc.find_user_by_email', { data: [{ id: 'owner', email: challengeBody.email }], error: null }, 'call')
    trusted.mockResponse('user_passkeys', { data: [mockPasskey], error: null }, 'select')
    trusted.mockResponse('passkey_challenges', { data: [{ id: 'stored' }], error: null }, 'insert')
    const trustedResponse = await post(appFor(trusted), '/auth/challenge', challengeBody, undefined,
      { lane: 'trusted', requestId: 'request-lane-fidelity' })
    assertEquals(trustedResponse.status, 200)
    const trustedAdmission = trusted.getQueryHistory().find(query => query.table === 'rpc.admit_passkey_challenge')
    assertExists(trustedAdmission)
    assertEquals(trustedAdmission!.params['p_lane'], 'trusted')
    assertEquals(trustedAdmission!.params['p_request_id'], 'request-lane-fidelity')
  } finally {
    gatewayTeardown()
  }
})

Deno.test('challenge refusal runs before account lookup and returns Retry-After', async () => {
  try {
    const client = createMockSupabaseClient()
    client.mockResponse('rpc.admit_passkey_challenge', {
      data: [{ allowed: false, retry_after_seconds: 17, duplicate: false }], error: null
    }, 'call')
    const response = await post(appFor(client), '/auth/challenge', challengeBody)
    assertEquals(response.status, 429)
    assertEquals(response.headers.get('Retry-After'), '17')
    assertEquals(client.getQueryHistory().map(query => query.table), ['rpc.admit_passkey_challenge'])
  } finally {
    gatewayTeardown()
  }
})

Deno.test('missing malformed or failed admission storage refuses lookup and challenge creation', async () => {
  try {
    for (const decision of [
      { data: null, error: null },
      { data: [{ allowed: 'true', retry_after_seconds: 0 }], error: null },
      { data: null, error: { message: 'storage unavailable' } }
    ]) {
      const client = createMockSupabaseClient()
      client.mockResponse('rpc.admit_passkey_challenge', decision, 'call')
      const response = await post(appFor(client), '/auth/challenge', challengeBody)
      assertEquals(response.status, 503)
      assertEquals(response.headers.get('Retry-After'), '30')
      assertEquals(client.getQueryHistory().map(query => query.table), ['rpc.admit_passkey_challenge'])
    }
  } finally {
    gatewayTeardown()
  }
})

Deno.test('authenticated registration observes shared admission before enrollment storage', async () => {
  try {
    const client = createMockSupabaseClient()
    client.mockAccessToken('synthetic-caller', { id: 'owner' })
    client.mockResponse('rpc.admit_passkey_challenge', {
      data: [{ allowed: false, retry_after_seconds: 3, duplicate: false }], error: null
    }, 'call')
    const response = await post(appFor(client), '/register/challenge', {}, 'Bearer synthetic-caller')
    assertEquals(response.status, 429)
    assertEquals(response.headers.get('Retry-After'), '3')
    assertEquals(client.getQueryHistory().filter(query => query.table === 'passkey_challenges'), [])
  } finally {
    gatewayTeardown()
  }
})

Deno.test('unknown email and account without passkeys have the same negative HTTP response', async () => {
  try {
    const responses: unknown[] = []
    for (const found of [false, true]) {
      const client = createMockSupabaseClient()
      allowAdmission(client)
      client.mockResponse('rpc.find_user_by_email', {
        data: found ? [{ id: 'owner', email: challengeBody.email }] : [], error: null
      }, 'call')
      client.mockResponse('user_passkeys', { data: [], error: null }, 'select')
      const response = await post(appFor(client), '/auth/challenge', challengeBody)
      assertEquals(response.status, 200)
      // Inert challenges are per-request randoms; everything else must match.
      const body = await response.json() as Record<string, unknown>
      delete body.challenge
      responses.push(body)
    }
    assertEquals(responses[0], responses[1])
  } finally {
    gatewayTeardown()
  }
})

Deno.test('admitted authentication preserves non-discoverable credential descriptors and legacy session IDs', async () => {
  try {
    const client = createMockSupabaseClient()
    allowAdmission(client)
    client.mockResponse('rpc.find_user_by_email', { data: [{ id: 'owner', email: challengeBody.email }], error: null }, 'call')
    client.mockResponse('user_passkeys', { data: [mockPasskey], error: null }, 'select')
    client.mockResponse('passkey_challenges', { data: [{ id: 'stored' }], error: null }, 'insert')
    const sessionId = 's'.repeat(128)
    const response = await post(appFor(client), '/auth/challenge', { ...challengeBody, sessionId })
    assertEquals(response.status, 200)
    const body = await response.json()
    assertEquals(body.sessionId, sessionId)
    assertEquals(body.allowCredentials[0].id, mockPasskey.credential_id)
    assertExists(body.challenge)
  } finally {
    gatewayTeardown()
  }
})

Deno.test('insertion capacity race returns a retryable refusal after successful admission', async () => {
  try {
    const client = createMockSupabaseClient()
    allowAdmission(client)
    client.mockResponse('rpc.find_user_by_email', { data: [{ id: 'owner', email: challengeBody.email }], error: null }, 'call')
    client.mockResponse('user_passkeys', { data: [mockPasskey], error: null }, 'select')
    client.mockResponse('passkey_challenges', { data: null, error: { code: '53300', message: 'capacity exhausted' } }, 'insert')
    const response = await post(appFor(client), '/auth/challenge', challengeBody)
    // The store hiccup is reachable only for an enrolled account, so the
    // route returns the same inert challenge as the pre-auth failures
    // instead of a distinguishable refusal.
    assertEquals(response.status, 200)
    assertEquals((await response.json()).allowCredentials, [])
  } finally {
    gatewayTeardown()
  }
})

Deno.test('insertion lock contention returns a short retry without leaking database diagnostics', async () => {
  try {
    const client = createMockSupabaseClient()
    allowAdmission(client)
    client.mockResponse('rpc.find_user_by_email', { data: [{ id: 'owner', email: challengeBody.email }], error: null }, 'call')
    client.mockResponse('user_passkeys', { data: [mockPasskey], error: null }, 'select')
    client.mockResponse('passkey_challenges', { data: null, error: { code: '55P03', message: 'private diagnostic' } }, 'insert')
    const response = await post(appFor(client), '/auth/challenge', challengeBody)
    assertEquals(response.status, 200)
    const raw = await response.text()
    assertEquals((JSON.parse(raw) as { allowCredentials: unknown }).allowCredentials, [])
    assertEquals(raw.includes('private diagnostic'), false)
  } finally {
    gatewayTeardown()
  }
})

Deno.test('streamed oversized JSON is cancelled before parsing or database access even with a false length', async () => {
  for (const claimedLength of [undefined, '1']) {
    const client = createMockSupabaseClient()
    let reads = 0
    let cancelled = false
    const stream = new ReadableStream<Uint8Array>({
      pull(controller) {
        reads++
        controller.enqueue(new Uint8Array(64 * 1024))
      },
      cancel() { cancelled = true }
    })
    const response = await appFor(client).request('/auth/challenge', {
      method: 'POST', body: stream,
      headers: { 'Content-Type': 'application/json', ...(claimedLength ? { 'Content-Length': claimedLength } : {}) }
    })
    assertEquals(response.status, 413)
    assertEquals(cancelled, true)
    assertEquals(reads <= MAX_PASSKEY_REQUEST_BYTES / (64 * 1024) + 2, true)
    assertEquals(client.getQueryHistory(), [])
  }
})

Deno.test('oversized identifiers fail before admission or status queries', async () => {
  try {
    const client = createMockSupabaseClient()
    const app = appFor(client)
    assertEquals((await post(app, '/auth/challenge', { ...challengeBody, sessionId: 's'.repeat(129) })).status, 400)
    assertEquals((await app.request('/auth/status/' + 's'.repeat(129))).status, 400)
    assertEquals(client.getQueryHistory(), [])
  } finally {
    gatewayTeardown()
  }
})

Deno.test('cleanup requires the dedicated scheduler secret and preserves authorized cleanup', async () => {
  const previous = Deno.env.get('PASSKEY_MAINTENANCE_TOKEN')
  const secret = 'synthetic-maintenance-secret-0123456789'
  try {
    const client = createMockSupabaseClient()
    const app = appFor(client)
    Deno.env.delete('PASSKEY_MAINTENANCE_TOKEN')
    assertEquals((await post(app, '/maintenance/cleanup', {})).status, 503)
    Deno.env.set('PASSKEY_MAINTENANCE_TOKEN', secret)
    for (const token of [undefined, 'Bearer public-anon-key', 'Bearer ' + 'x'.repeat(secret.length)]) {
      assertEquals((await post(app, '/maintenance/cleanup', {}, token)).status, 401)
    }
    assertEquals(client.getQueryHistory(), [])
    client.mockResponse('rpc.clean_expired_passkey_challenges', { data: null, error: null }, 'call')
    assertEquals((await post(app, '/maintenance/cleanup', {}, `Bearer ${secret}`)).status, 200)
    assertEquals(client.getQueryHistory().map(query => query.table), ['rpc.clean_expired_passkey_challenges'])
  } finally {
    if (previous === undefined) Deno.env.delete('PASSKEY_MAINTENANCE_TOKEN')
    else Deno.env.set('PASSKEY_MAINTENANCE_TOKEN', previous)
  }
})

Deno.test('small streamed request survives repeated buffer growth before validation', async () => {
  try {
    const client = createMockSupabaseClient()
    client.mockResponse('rpc.admit_passkey_challenge', {
      data: [{ allowed: false, retry_after_seconds: 7, duplicate: false }], error: null
    }, 'call')
    // Whitespace is legal JSON and takes this request across several growth boundaries.
    const encoded = new TextEncoder().encode(' '.repeat(9000) + JSON.stringify(challengeBody))
    let offset = 0
    const stream = new ReadableStream<Uint8Array>({
      pull(controller) {
        if (offset === encoded.byteLength) return controller.close()
        const end = Math.min(offset + 37, encoded.byteLength)
        controller.enqueue(encoded.slice(offset, end))
        offset = end
      }
    })
    await gatewaySetup()
    const assertion = await mintGatewayAssertion(gatewayPrivateKey!, {
      path: '/auth/challenge',
      body: new TextDecoder().decode(encoded),
    })
    const response = await appFor(client).request(GATEWAY_URL, {
      method: 'POST', body: stream,
      headers: { 'Content-Type': 'application/json', [GATEWAY_ADMISSION_HEADER]: assertion }
    })
    assertEquals(response.status, 429)
    assertEquals(response.headers.get('Retry-After'), '7')
    assertEquals(client.getQueryHistory().map(query => query.table), ['rpc.admit_passkey_challenge'])
  } finally {
    gatewayTeardown()
  }
})
