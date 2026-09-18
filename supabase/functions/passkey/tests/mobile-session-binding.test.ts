/**
 * Session binding regression tests for the public mobile pages (#924).
 *
 * The sessionId query parameter on /register/mobile and /auth/mobile may only
 * *agree* with the session already bound to the challenge row. Until this fix
 * the pages wrote their sessionId into the row on every load, so a second load
 * of the same page URL with a different sessionId silently rebound the
 * ceremony - the completed handoff (the minted session) went to whoever last
 * won the write.
 *
 * These pin every branch of the new binding rule:
 *   1. a second load with a *different* sessionId gets nothing and writes
 *      nothing (the rebinding attack),
 *   2. the bound session reloading the page still works, via a write that
 *      carries no session_id and is scoped to the bound session,
 *   3. the legit first binding still works, and
 *   4. a first binding that loses the compare-and-set (.is('session_id', null)
 *      updating zero rows) is an error, not a silent takeover.
 */

import { assertEquals, assertExists, assertStringIncludes, assert } from "jsr:@std/assert"
import type { SupabaseClient } from "@supabase/supabase-js"
import { OpenAPIHono } from "@hono/zod-openapi"
import type { PasskeyContext } from "../types/context.ts"
import mobile from "../routes/mobile.ts"
import { generateMobileRegistrationPage, generateMobileAuthenticationPage } from "../services/mobile.ts"
import { createMockSupabaseClient, type MockSupabaseClient, mockChallenge, mockPasskey } from "./helpers/mocks.ts"

/**
 * The routes are what an attacker actually hits, so the rebinding refusal
 * also has to surface as a 4xx error page - not a 200 page, and not a 500
 * that a monitoring pipeline would flag as a server fault instead of an
 * refused request.
 */
function buildMobileApp(mockClient: MockSupabaseClient) {
  const app = new OpenAPIHono<{ Variables: PasskeyContext }>()
  app.use("*", async (ctx, next) => {
    // deno-lint-ignore no-explicit-any
    ctx.set("supabase", mockClient as any)
    await next()
  })
  // Mounted at root, exactly like index.ts mounts the mobile router.
  app.route("/", mobile)
  return app
}


// ============================================================================
// Registration page
// ============================================================================

Deno.test("generateMobileRegistrationPage - a second page load with a different sessionId must not rebind the row", async () => {
  const mockClient = createMockSupabaseClient()

  // The row is already bound to the victim's session.
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'registration',
      session_id: 'session-victim',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'update')

  // The attacker loads the same page URL with their own sessionId.
  const result = await generateMobileRegistrationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-attacker',
    'api.risaboss.com',
    'BOSS'
  )

  // The rebinding load gets nothing - no page data, no success.
  assertEquals(result.success, false)
  if (!result.success) {
    assertExists(result.error)
    assert(
      result.error.includes('Session mismatch'),
      `expected a session mismatch error, got: ${result.error}`
    )
  }

  // And critically: no update was ever issued against the row.
  const updates = mockClient.getQueryHistory().filter(h => h.operation === 'update')
  assertEquals(updates.length, 0, 'a rebinding page load must not write to the challenge row at all')
})

Deno.test("generateMobileRegistrationPage - the bound session reloading the page refreshes status without moving the binding", async () => {
  const mockClient = createMockSupabaseClient()

  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'registration',
      session_id: 'session-123',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'update')

  const result = await generateMobileRegistrationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-123',
    'api.risaboss.com',
    'BOSS'
  )

  assertEquals(result.success, true)
  if (result.success) {
    assertEquals(result.sessionId, 'session-123')
  }

  // The status refresh carries no session_id and is scoped to the bound
  // session, so it cannot move the binding.
  const updates = mockClient.getQueryHistory().filter(h => h.operation === 'update')
  assertEquals(updates.length, 1)
  const update = updates[0]
  assertEquals(
    'session_id' in (update.params.data ?? {}),
    false,
    'the refresh write must not carry a session_id'
  )
  const filters = (update.params.eq ?? []) as Array<{ column: string; value: unknown }>
  assertEquals(
    filters.some(f => f.column === 'session_id' && f.value === 'session-123'),
    true,
    'the refresh write must be scoped to the bound session'
  )
})

Deno.test("generateMobileRegistrationPage - a first binding that loses the compare-and-set is an error, not a silent takeover", async () => {
  const mockClient = createMockSupabaseClient()

  // The read saw an unbound row (both concurrent loads did)...
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'registration',
      session_id: null,
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  // ...but the compare-and-set updated zero rows: another load bound it first.
  mockClient.mockResponse('passkey_challenges', {
    data: [],
    error: null
  }, 'update')

  const result = await generateMobileRegistrationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-123',
    'api.risaboss.com',
    'BOSS'
  )

  assertEquals(result.success, false)
  if (!result.success) {
    assertExists(result.error)
    assert(
      result.error.includes('Session binding conflict'),
      `expected a binding-conflict error, got: ${result.error}`
    )
  }

  // The compare-and-set must carry the guard that makes it one-shot.
  const updates = mockClient.getQueryHistory().filter(h => h.operation === 'update')
  assertEquals(updates.length, 1)
  const isFilters = (updates[0].params.is ?? []) as Array<{ column: string; value: unknown }>
  assertEquals(
    isFilters.some(f => f.column === 'session_id' && f.value === null),
    true,
    "the first binding must be guarded by .is('session_id', null)"
  )
})

Deno.test("generateMobileRegistrationPage - the legit first binding still works", async () => {
  const mockClient = createMockSupabaseClient()

  // An unbound row: the QR flow's challenge row before any page load.
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'registration',
      session_id: null,
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  // The compare-and-set wins.
  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'update')

  const result = await generateMobileRegistrationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-123',
    'api.risaboss.com',
    'BOSS'
  )

  assertEquals(result.success, true)
  if (result.success) {
    assertEquals(result.sessionId, 'session-123')
  }

  const updates = mockClient.getQueryHistory().filter(h => h.operation === 'update')
  assertEquals(updates.length, 1)
  const write = updates[0].params.data as Record<string, unknown>
  assertEquals(write.session_id, 'session-123')
  assertEquals(write.status, 'in_progress')
})

// ============================================================================
// Authentication page
// ============================================================================

Deno.test("generateMobileAuthenticationPage - a second page load with a different sessionId must not rebind the row", async () => {
  const mockClient = createMockSupabaseClient()

  // The row is already bound to the victim's session.
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'authentication',
      session_id: 'session-victim',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  mockClient.mockResponse('user_passkeys', {
    data: mockPasskey,
    error: null
  }, 'select')

  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'update')

  // The attacker loads the same page URL with their own sessionId.
  const result = await generateMobileAuthenticationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-attacker',
    'credential-abc',
    'api.risaboss.com'
  )

  assertEquals(result.success, false)
  if (!result.success) {
    assertExists(result.error)
    assert(
      result.error.includes('Session mismatch'),
      `expected a session mismatch error, got: ${result.error}`
    )
  }

  const updates = mockClient.getQueryHistory().filter(h => h.operation === 'update')
  assertEquals(updates.length, 0, 'a rebinding page load must not write to the challenge row at all')
})

Deno.test("generateMobileAuthenticationPage - the bound session reloading the page refreshes status without moving the binding", async () => {
  const mockClient = createMockSupabaseClient()

  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'authentication',
      session_id: 'session-123',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  mockClient.mockResponse('user_passkeys', {
    data: mockPasskey,
    error: null
  }, 'select')

  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'update')

  const result = await generateMobileAuthenticationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-123',
    'credential-abc',
    'api.risaboss.com'
  )

  assertEquals(result.success, true)

  const updates = mockClient.getQueryHistory().filter(h => h.operation === 'update')
  assertEquals(updates.length, 1)
  const update = updates[0]
  assertEquals(
    'session_id' in (update.params.data ?? {}),
    false,
    'the refresh write must not carry a session_id'
  )
  const filters = (update.params.eq ?? []) as Array<{ column: string; value: unknown }>
  assertEquals(
    filters.some(f => f.column === 'session_id' && f.value === 'session-123'),
    true,
    'the refresh write must be scoped to the bound session'
  )
})

Deno.test("generateMobileAuthenticationPage - a first binding that loses the compare-and-set is an error, not a silent takeover", async () => {
  const mockClient = createMockSupabaseClient()

  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'authentication',
      session_id: null,
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  mockClient.mockResponse('user_passkeys', {
    data: mockPasskey,
    error: null
  }, 'select')

  // The compare-and-set updated zero rows: another concurrent load won.
  mockClient.mockResponse('passkey_challenges', {
    data: [],
    error: null
  }, 'update')

  const result = await generateMobileAuthenticationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-123',
    'credential-abc',
    'api.risaboss.com'
  )

  assertEquals(result.success, false)
  if (!result.success) {
    assertExists(result.error)
    assert(
      result.error.includes('Session binding conflict'),
      `expected a binding-conflict error, got: ${result.error}`
    )
  }

  const updates = mockClient.getQueryHistory().filter(h => h.operation === 'update')
  assertEquals(updates.length, 1)
  const isFilters = (updates[0].params.is ?? []) as Array<{ column: string; value: unknown }>
  assertEquals(
    isFilters.some(f => f.column === 'session_id' && f.value === null),
    true,
    "the first binding must be guarded by .is('session_id', null)"
  )
})

Deno.test("generateMobileAuthenticationPage - the legit first binding still works", async () => {
  const mockClient = createMockSupabaseClient()

  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'authentication',
      session_id: null,
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  mockClient.mockResponse('user_passkeys', {
    data: mockPasskey,
    error: null
  }, 'select')

  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'update')

  const result = await generateMobileAuthenticationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-123',
    'credential-abc',
    'api.risaboss.com'
  )

  assertEquals(result.success, true)
  if (result.success) {
    assertEquals(result.sessionId, 'session-123')
  }

  const updates = mockClient.getQueryHistory().filter(h => h.operation === 'update')
  assertEquals(updates.length, 1)
  const write = updates[0].params.data as Record<string, unknown>
  assertEquals(write.session_id, 'session-123')
  assertEquals(write.status, 'in_progress')
})


// ============================================================================
// Route level - the attacker hits the page URL, not the service
// ============================================================================

Deno.test("GET /register/mobile - a sessionId that disagrees with the bound row returns a 4xx error page, not the ceremony page", async () => {
  const mockClient = createMockSupabaseClient()

  // The challenge row is already bound to the victim's session.
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'registration',
      session_id: 'session-victim',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'update')

  const app = buildMobileApp(mockClient)

  const response = await app.request(
    '/register/mobile?challenge=mock-challenge-base64&email=test@example.com' +
    '&sessionId=session-attacker&rpId=api.risaboss.com'
  )

  // 400, not 200 (which would render the ceremony page for the attacker) and
  // not 500 (which would misread as a server fault).
  assertEquals(response.status, 400)
  const body = await response.text()
  assertStringIncludes(body, 'Session mismatch')

  // And the row was never touched.
  const updates = mockClient.getQueryHistory().filter(h => h.operation === 'update')
  assertEquals(updates.length, 0, 'the route must not write to the challenge row on a rebinding load')
})

Deno.test("GET /auth/mobile - a sessionId that disagrees with the bound row returns a 4xx error page, not the ceremony page", async () => {
  const mockClient = createMockSupabaseClient()

  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'authentication',
      session_id: 'session-victim',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  mockClient.mockResponse('user_passkeys', {
    data: mockPasskey,
    error: null
  }, 'select')

  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'update')

  const app = buildMobileApp(mockClient)

  const response = await app.request(
    '/auth/mobile?challenge=mock-challenge-base64&email=test@example.com' +
    '&sessionId=session-attacker&credentialId=credential-abc&rpId=api.risaboss.com'
  )

  assertEquals(response.status, 400)
  const body = await response.text()
  assertStringIncludes(body, 'Session mismatch')

  const updates = mockClient.getQueryHistory().filter(h => h.operation === 'update')
  assertEquals(updates.length, 0, 'the route must not write to the challenge row on a rebinding load')
})
