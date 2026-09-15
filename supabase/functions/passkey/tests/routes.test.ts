/**
 * Route-level tests
 *
 * The services own ceremony verification; these cover what only the routes
 * decide — the HTTP status a bad request comes back as. Both complete routes
 * declare 400 and 403, so a payload the server cannot decode must not surface as
 * a 500 carrying a raw exception message.
 */

import { assertEquals, assertExists } from "jsr:@std/assert"
import { OpenAPIHono } from "@hono/zod-openapi"
import type { PasskeyContext } from "../types/context.ts"
import auth from "../routes/auth.ts"
import register from "../routes/register.ts"
import management from "../routes/management.ts"
import { createMockSupabaseClient, type MockSupabaseClient } from "./helpers/mocks.ts"
import { TEST_ORIGIN } from "./helpers/webauthn.ts"

function buildApp(mockClient: MockSupabaseClient) {
  const app = new OpenAPIHono<{ Variables: PasskeyContext }>()
  app.use("*", async (ctx, next) => {
    // deno-lint-ignore no-explicit-any
    ctx.set("supabase", mockClient as any)
    await next()
  })
  app.route("/auth", auth)
  app.route("/register", register)
  app.route("/manage", management)
  return app
}

function postJson(
  app: ReturnType<typeof buildApp>,
  path: string,
  body: unknown,
  token?: string
) {
  const headers: Record<string, string> = { 'content-type': 'application/json' }
  if (token) headers['Authorization'] = `Bearer ${token}`

  return app.request(path, {
    method: 'POST',
    headers,
    body: JSON.stringify(body)
  })
}

const encode = (value: unknown) => btoa(JSON.stringify(value))

Deno.test("POST /auth/complete - undecodable clientDataJSON is a 400, not a 500", async () => {
  const app = buildApp(createMockSupabaseClient())

  const response = await postJson(app, '/auth/complete', {
    challenge: 'some-challenge',
    credential: {
      id: 'cred',
      rawId: 'cred',
      type: 'public-key',
      response: {
        clientDataJSON: 'not!!!valid!!!base64!!',
        authenticatorData: 'dGVzdA',
        signature: 'dGVzdA'
      }
    }
  })

  assertEquals(response.status, 400)
  assertEquals((await response.json()).error, 'Invalid clientDataJSON')
})

Deno.test("POST /register/complete - undecodable clientDataJSON is a 400, not a 500", async () => {
  const app = buildApp(createMockSupabaseClient())

  const response = await postJson(app, '/register/complete', {
    userId: 'user-1',
    challenge: 'some-challenge',
    credential: {
      id: 'cred',
      rawId: 'cred',
      type: 'public-key',
      response: {
        clientDataJSON: 'not!!!valid!!!base64!!',
        attestationObject: 'dGVzdA'
      }
    }
  })

  assertEquals(response.status, 400)
  assertEquals((await response.json()).error, 'Invalid clientDataJSON')
})

Deno.test("POST /auth/complete - a disallowed origin is a 403", async () => {
  const app = buildApp(createMockSupabaseClient())

  const response = await postJson(app, '/auth/complete', {
    challenge: 'some-challenge',
    credential: {
      id: 'cred',
      rawId: 'cred',
      type: 'public-key',
      response: {
        clientDataJSON: encode({
          type: 'webauthn.get',
          challenge: 'some-challenge',
          origin: 'https://evil.example.com'
        }),
        authenticatorData: 'dGVzdA',
        signature: 'dGVzdA'
      }
    }
  })

  assertEquals(response.status, 403)
  assertEquals((await response.json()).error, 'Invalid origin')
})

Deno.test("POST /auth/complete - an allowed origin reaches the service", async () => {
  const mockClient = createMockSupabaseClient()
  const app = buildApp(mockClient)

  // No challenge row queued, so the service rejects — the point is that the
  // route let it through rather than short-circuiting on origin.
  const response = await postJson(app, '/auth/complete', {
    challenge: 'some-challenge',
    credential: {
      id: 'cred',
      rawId: 'cred',
      type: 'public-key',
      response: {
        clientDataJSON: encode({
          type: 'webauthn.get',
          challenge: 'some-challenge',
          origin: TEST_ORIGIN
        }),
        authenticatorData: 'dGVzdA',
        signature: 'dGVzdA'
      }
    }
  })

  assertEquals(response.status, 400)
  assertEquals(
    mockClient.getQueryHistory().some(query => query.table === 'passkey_challenges'),
    true,
    'The service should have been reached'
  )
})

// ============================================================================
// Registration requires a caller identity
//
// Enrolling a passkey grants a permanent way into an account. Before this, both
// register endpoints were unauthenticated and took `userId` from the body, so
// the ceremony bindings were all satisfiable by someone who simply named
// another account.
// ============================================================================

const VICTIM_ID = 'user-victim'
const ATTACKER_ID = 'user-attacker'
const ATTACKER_TOKEN = 'attacker-access-token'

function registrationChallengeBody(userId?: string) {
  return userId === undefined ? {} : { userId }
}

Deno.test("POST /register/challenge - rejects an unauthenticated caller", async () => {
  const mockClient = createMockSupabaseClient()
  const app = buildApp(mockClient)

  const response = await postJson(app, '/register/challenge', registrationChallengeBody(VICTIM_ID))

  assertEquals(response.status, 401)
  assertEquals((await response.json()).error, 'Authentication required')
  // Nothing was minted for the named account
  assertEquals(
    mockClient.getQueryHistory().some(query => query.table === 'passkey_challenges'),
    false,
    'No challenge may be stored for an unauthenticated request'
  )
})

Deno.test("POST /register/challenge - rejects an unrecognised token", async () => {
  const mockClient = createMockSupabaseClient()
  const app = buildApp(mockClient)

  const response = await postJson(
    app,
    '/register/challenge',
    registrationChallengeBody(VICTIM_ID),
    'not-a-real-token'
  )

  assertEquals(response.status, 401)
  assertEquals(
    mockClient.getQueryHistory().some(query => query.table === 'passkey_challenges'),
    false
  )
})

Deno.test("POST /register/challenge - a token for one user cannot mint a challenge for another", async () => {
  const mockClient = createMockSupabaseClient()
  mockClient.mockAccessToken(ATTACKER_TOKEN, { id: ATTACKER_ID, email: 'attacker@example.com' })
  const app = buildApp(mockClient)

  const response = await postJson(
    app,
    '/register/challenge',
    registrationChallengeBody(VICTIM_ID),
    ATTACKER_TOKEN
  )

  assertEquals(response.status, 403)
  assertEquals((await response.json()).error, 'userId does not match the authenticated user')
  assertEquals(
    mockClient.getQueryHistory().some(query => query.table === 'passkey_challenges'),
    false,
    'A challenge for the named account must never be stored'
  )
})

Deno.test("POST /register/challenge - binds the challenge to the token, not the body", async () => {
  const mockClient = createMockSupabaseClient()
  mockClient.mockResponse('rpc.admit_passkey_challenge', { data: [{ allowed: true, retry_after_seconds: 0 }], error: null }, 'call')

  mockClient.mockAccessToken(ATTACKER_TOKEN, { id: ATTACKER_ID, email: 'attacker@example.com' })
  mockClient.mockResponse('passkey_challenges', { data: [{ id: 'challenge-1' }], error: null }, 'insert')
  const app = buildApp(mockClient)

  // Body omits userId entirely: the server must still know who this is
  const response = await postJson(app, '/register/challenge', registrationChallengeBody(), ATTACKER_TOKEN)

  assertEquals(response.status, 200)

  const insert = mockClient.getQueryHistory().find(
    query => query.table === 'passkey_challenges' && query.operation === 'insert'
  )
  assertExists(insert)
  const stored = insert!.params.data as { user_id?: string; type?: string }
  assertEquals(stored.user_id, ATTACKER_ID, 'The challenge belongs to the authenticated caller')
  assertEquals(stored.type, 'registration')
})

Deno.test("POST /register/challenge - accepts a body userId that agrees with the token", async () => {
  const mockClient = createMockSupabaseClient()
  mockClient.mockResponse('rpc.admit_passkey_challenge', { data: [{ allowed: true, retry_after_seconds: 0 }], error: null }, 'call')

  mockClient.mockAccessToken(ATTACKER_TOKEN, { id: ATTACKER_ID, email: 'attacker@example.com' })
  mockClient.mockResponse('passkey_challenges', { data: [{ id: 'challenge-1' }], error: null }, 'insert')
  const app = buildApp(mockClient)

  const response = await postJson(
    app,
    '/register/challenge',
    registrationChallengeBody(ATTACKER_ID),
    ATTACKER_TOKEN
  )

  assertEquals(response.status, 200)
  const body = await response.json()
  assertExists(body.challenge)
})

Deno.test("POST /register/challenge - the project anon key is not a caller identity", async () => {
  const previousAnonKey = Deno.env.get('SUPABASE_ANON_KEY')
  Deno.env.set('SUPABASE_ANON_KEY', 'project-anon-key')

  try {
    const mockClient = createMockSupabaseClient()
    const app = buildApp(mockClient)

    // Supabase clients conventionally send the anon key as a bearer token when
    // no user is signed in; that must not read as a session.
    const response = await postJson(
      app,
      '/register/challenge',
      registrationChallengeBody(VICTIM_ID),
      'project-anon-key'
    )

    assertEquals(response.status, 401)
  } finally {
    if (previousAnonKey === undefined) Deno.env.delete('SUPABASE_ANON_KEY')
    else Deno.env.set('SUPABASE_ANON_KEY', previousAnonKey)
  }
})

Deno.test("POST /register/complete - a session for another user is rejected", async () => {
  const mockClient = createMockSupabaseClient()
  mockClient.mockAccessToken(ATTACKER_TOKEN, { id: ATTACKER_ID, email: 'attacker@example.com' })

  // A challenge that belongs to the victim (only they could have minted it)
  mockClient.mockResponse('passkey_challenges', {
    data: {
      id: 'challenge-victim',
      challenge: 'victim-challenge',
      type: 'registration',
      user_id: VICTIM_ID,
      expires_at: new Date(Date.now() + 300_000).toISOString()
    },
    error: null
  }, 'select')
  mockClient.mockResponse('passkey_challenges', { data: [{ id: 'challenge-victim' }], error: null }, 'delete')

  const app = buildApp(mockClient)
  const response = await postJson(app, '/register/complete', {
    userId: ATTACKER_ID,
    challenge: 'victim-challenge',
    credential: {
      id: 'cred',
      rawId: 'cred',
      type: 'public-key',
      response: {
        clientDataJSON: encode({
          type: 'webauthn.create',
          challenge: 'victim-challenge',
          origin: TEST_ORIGIN
        }),
        attestationObject: 'dGVzdA'
      }
    }
  }, ATTACKER_TOKEN)

  assertEquals(response.status, 400)
  assertEquals((await response.json()).error, 'Challenge does not belong to this user')

  // ...and nothing was enrolled
  assertEquals(
    mockClient.getQueryHistory().some(
      query => query.table === 'user_passkeys' && query.operation === 'insert'
    ),
    false
  )
})

// ============================================================================
// Managing credentials requires the account's own session
//
// These routes list, disable and rename credentials against the service-role
// client on a function deployed with verify_jwt = false. Taking `userId` from
// the body let any caller enumerate and de-enrol another account's passkeys.
// ============================================================================

Deno.test("POST /manage/list - rejects an unauthenticated caller", async () => {
  const mockClient = createMockSupabaseClient()
  const app = buildApp(mockClient)

  const response = await postJson(app, '/manage/list', { userId: VICTIM_ID })

  assertEquals(response.status, 401)
  assertEquals(
    mockClient.getQueryHistory().some(query => query.table === 'user_passkeys'),
    false,
    'No credential may be read for an unauthenticated request'
  )
})

Deno.test("POST /manage/delete - rejects an unauthenticated caller", async () => {
  const mockClient = createMockSupabaseClient()
  const app = buildApp(mockClient)

  const response = await postJson(app, '/manage/delete', {
    userId: VICTIM_ID,
    passkeyId: 'passkey-1'
  })

  assertEquals(response.status, 401)
  assertEquals(
    mockClient.getQueryHistory().some(query => query.table === 'user_passkeys'),
    false,
    'Nothing may be de-enrolled without a session'
  )
})

Deno.test("POST /manage/update - rejects an unauthenticated caller", async () => {
  const mockClient = createMockSupabaseClient()
  const app = buildApp(mockClient)

  const response = await postJson(app, '/manage/update', {
    userId: VICTIM_ID,
    passkeyId: 'passkey-1',
    displayName: 'renamed'
  })

  assertEquals(response.status, 401)
})

Deno.test("POST /manage/delete - a token for one user cannot act on another", async () => {
  const mockClient = createMockSupabaseClient()
  mockClient.mockAccessToken(ATTACKER_TOKEN, { id: ATTACKER_ID })
  const app = buildApp(mockClient)

  const response = await postJson(app, '/manage/delete', {
    userId: VICTIM_ID,
    passkeyId: 'passkey-1'
  }, ATTACKER_TOKEN)

  assertEquals(response.status, 403)
  assertEquals((await response.json()).error, 'userId does not match the authenticated user')
  assertEquals(
    mockClient.getQueryHistory().some(query => query.table === 'user_passkeys'),
    false
  )
})

Deno.test("POST /manage/list - scopes the query to the authenticated caller", async () => {
  const mockClient = createMockSupabaseClient()
  mockClient.mockAccessToken(ATTACKER_TOKEN, { id: ATTACKER_ID })
  mockClient.mockResponse('user_passkeys', { data: [], error: null }, 'select')
  const app = buildApp(mockClient)

  // No userId in the body at all: the session decides whose credentials these are
  const response = await postJson(app, '/manage/list', {}, ATTACKER_TOKEN)

  assertEquals(response.status, 200)
  const select = mockClient.getQueryHistory().find(
    query => query.table === 'user_passkeys' && query.operation === 'select'
  )
  assertExists(select)
  assertEquals(
    (select!.params.eq as Array<{ column: string; value: unknown }>)
      .some(filter => filter.column === 'user_id' && filter.value === ATTACKER_ID),
    true,
    'The listing is scoped to the token subject'
  )
})
