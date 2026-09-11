import { assertEquals, assertExists } from "jsr:@std/assert"
import { OpenAPIHono } from "@hono/zod-openapi"
import type { SupabaseClient } from "@supabase/supabase-js"
import type { PasskeyContext } from "../types/context.ts"
import auth from "../routes/auth.ts"
import register from "../routes/register.ts"
import maintenance from "../routes/maintenance.ts"
import { createMockSupabaseClient, mockPasskey, type MockSupabaseClient } from "./helpers/mocks.ts"
import { MAX_PASSKEY_REQUEST_BYTES } from "../utils/request-limits.ts"

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

const challengeBody = { email: 'person@example.test', sessionId: 'synthetic-session' }
function post(app: ReturnType<typeof appFor>, path: string, body: unknown, authorization?: string) {
  return app.request(path, { method: 'POST', body: JSON.stringify(body), headers: {
    'Content-Type': 'application/json', ...(authorization ? { Authorization: authorization } : {})
  } })
}
function allowAdmission(client: MockSupabaseClient) {
  client.mockResponse('rpc.admit_passkey_challenge', {
    data: [{ allowed: true, retry_after_seconds: 0 }], error: null
  }, 'call')
}

Deno.test('challenge refusal runs before account lookup and returns Retry-After', async () => {
  const client = createMockSupabaseClient()
  client.mockResponse('rpc.admit_passkey_challenge', {
    data: [{ allowed: false, retry_after_seconds: 17 }], error: null
  }, 'call')
  const response = await post(appFor(client), '/auth/challenge', challengeBody)
  assertEquals(response.status, 429)
  assertEquals(response.headers.get('Retry-After'), '17')
  assertEquals(client.getQueryHistory().map(query => query.table), ['rpc.admit_passkey_challenge'])
})

Deno.test('missing malformed or failed admission storage refuses lookup and challenge creation', async () => {
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
})

Deno.test('authenticated registration observes shared admission before enrollment storage', async () => {
  const client = createMockSupabaseClient()
  client.mockAccessToken('synthetic-caller', { id: 'owner' })
  client.mockResponse('rpc.admit_passkey_challenge', {
    data: [{ allowed: false, retry_after_seconds: 3 }], error: null
  }, 'call')
  const response = await post(appFor(client), '/register/challenge', {}, 'Bearer synthetic-caller')
  assertEquals(response.status, 429)
  assertEquals(response.headers.get('Retry-After'), '3')
  assertEquals(client.getQueryHistory().filter(query => query.table === 'passkey_challenges'), [])
})

Deno.test('unknown email and account without passkeys have the same negative HTTP response', async () => {
  const responses: unknown[] = []
  for (const found of [false, true]) {
    const client = createMockSupabaseClient()
    allowAdmission(client)
    client.mockResponse('rpc.find_user_by_email', {
      data: found ? [{ id: 'owner', email: challengeBody.email }] : [], error: null
    }, 'call')
    client.mockResponse('user_passkeys', { data: [], error: null }, 'select')
    const response = await post(appFor(client), '/auth/challenge', challengeBody)
    assertEquals(response.status, 400)
    responses.push(await response.json())
  }
  assertEquals(responses[0], responses[1])
})

Deno.test('admitted authentication preserves non-discoverable credential descriptors and legacy session IDs', async () => {
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
})

Deno.test('insertion capacity race returns a retryable refusal after successful admission', async () => {
  const client = createMockSupabaseClient()
  allowAdmission(client)
  client.mockResponse('rpc.find_user_by_email', { data: [{ id: 'owner', email: challengeBody.email }], error: null }, 'call')
  client.mockResponse('user_passkeys', { data: [mockPasskey], error: null }, 'select')
  client.mockResponse('passkey_challenges', { data: null, error: { code: '53300', message: 'capacity exhausted' } }, 'insert')
  const response = await post(appFor(client), '/auth/challenge', challengeBody)
  assertEquals(response.status, 429)
  assertEquals(response.headers.get('Retry-After'), '30')
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
  const client = createMockSupabaseClient()
  const app = appFor(client)
  assertEquals((await post(app, '/auth/challenge', { ...challengeBody, sessionId: 's'.repeat(129) })).status, 400)
  assertEquals((await app.request('/auth/status/' + 's'.repeat(129))).status, 400)
  assertEquals(client.getQueryHistory(), [])
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
