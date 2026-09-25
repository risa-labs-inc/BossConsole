/**
 * Tests for Mobile Service
 */

import { assertEquals, assertExists, assertStringIncludes } from "jsr:@std/assert"
import { OpenAPIHono } from "@hono/zod-openapi"
import type { SupabaseClient } from "@supabase/supabase-js"
import type { PasskeyContext } from "../types/context.ts"
import { generateMobileRegistrationPage, generateMobileAuthenticationPage } from "../services/mobile.ts"
import { maskUserId } from "../utils/logging.ts"
import mobile from "../routes/mobile.ts"
import { createMockSupabaseClient, mockChallenge, mockPasskey } from "./helpers/mocks.ts"

// ============================================================================
// Mobile Registration Tests
// ============================================================================

Deno.test("generateMobileRegistrationPage - should generate valid registration page data", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock valid registration challenge
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'registration',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  // Mock user lookup from auth.users table
  mockClient.mockResponse('auth.users', {
    data: { id: 'user-456', email: 'test@example.com' },
    error: null
  }, 'select')

  // Mock update challenge with session
  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'update')

  const result = await generateMobileRegistrationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-123',
    'api.risaboss.com'
  )

  assertEquals(result.success, true)
  if (result.success) {
    assertEquals(result.challenge, 'mock-challenge-base64')
    assertEquals(result.email, 'test@example.com')
    assertEquals(result.sessionId, 'session-123')
    assertEquals(result.rpId, 'api.risaboss.com')
    assertEquals(result.rpName, 'BOSS')
    assertExists(result.userId)
  }
})

Deno.test("generateMobileRegistrationPage - should reject expired challenge", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock expired challenge
  mockClient.mockResponse('passkey_challenges', {
    data: null,
    error: { code: 'PGRST116' }
  }, 'select')

  const result = await generateMobileRegistrationPage(
    mockClient as unknown as SupabaseClient,
    'expired-challenge',
    'test@example.com',
    'session-123',
    'api.risaboss.com'
  )

  assertEquals(result.success, false)
  if (!result.success) {
    assertExists(result.error)
    assertEquals(result.error, 'Invalid or expired registration link')
  }
})

Deno.test("generateMobileRegistrationPage - should reject wrong challenge type", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock authentication challenge instead of registration (database would not return this due to .eq('type', 'registration'))
  // Our mock doesn't enforce filters, so we simulate the database behavior by returning null
  mockClient.mockResponse('passkey_challenges', {
    data: null,
    error: { code: 'PGRST116' }
  }, 'select')

  const result = await generateMobileRegistrationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-123',
    'api.risaboss.com'
  )

  assertEquals(result.success, false)
  if (!result.success) {
    assertExists(result.error)
  }
})

Deno.test("generateMobileRegistrationPage - should reject challenge without user_id", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock challenge without user_id
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      user_id: null, // Challenge doesn't have user_id
      type: 'registration',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  const result = await generateMobileRegistrationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-123',
    'api.risaboss.com'
  )

  assertEquals(result.success, false)
  if (!result.success) {
    assertExists(result.error)
    assertEquals(result.error, 'Invalid registration challenge')
  }
})

Deno.test("generateMobileRegistrationPage - should update challenge status to in_progress", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock valid challenge
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'registration',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  // Mock user lookup
  mockClient.mockResponse('auth.users', {
    data: { id: 'user-456', email: 'test@example.com' },
    error: null
  }, 'select')

  // Mock update with in_progress status
  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789', status: 'in_progress' }],
    error: null
  }, 'update')

  const result = await generateMobileRegistrationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-123',
    'api.risaboss.com'
  )

  assertEquals(result.success, true)

  // Verify update was called
  const history = mockClient.getQueryHistory()
  const updateCall = history.find(h => h.operation === 'update')
  assertExists(updateCall)
})

// ============================================================================
// Mobile Authentication Tests
// ============================================================================

Deno.test("generateMobileAuthenticationPage - should generate valid authentication page data", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock valid authentication challenge
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'authentication',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  // Mock user lookup
  mockClient.mockResponse('auth.users', {
    data: { id: 'user-456', email: 'test@example.com' },
    error: null
  }, 'select')

  // Mock passkey lookup
  mockClient.mockResponse('user_passkeys', {
    data: mockPasskey,
    error: null
  }, 'select')

  // Mock challenge update
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
    assertEquals(result.challenge, 'mock-challenge-base64')
    assertEquals(result.email, 'test@example.com')
    assertEquals(result.sessionId, 'session-123')
    assertEquals(result.rpId, 'api.risaboss.com')
    assertEquals(result.credentialId, 'credential-abc')
    assertEquals(result.credentialDisplayName, mockPasskey.display_name)
    assertExists(result.credentialCreatedAt)
  }
})

Deno.test("generateMobileAuthenticationPage - should reject expired challenge", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock expired challenge
  mockClient.mockResponse('passkey_challenges', {
    data: null,
    error: { code: 'PGRST116' }
  }, 'select')

  const result = await generateMobileAuthenticationPage(
    mockClient as unknown as SupabaseClient,
    'expired-challenge',
    'test@example.com',
    'session-123',
    'credential-abc',
    'api.risaboss.com'
  )

  assertEquals(result.success, false)
  if (!result.success) {
    assertExists(result.error)
    assertEquals(result.error, 'Invalid or expired authentication challenge')
  }
})

Deno.test("generateMobileAuthenticationPage - should reject wrong challenge type", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock registration challenge instead of authentication (database would not return this due to .eq('type', 'authentication'))
  // Our mock doesn't enforce filters, so we simulate the database behavior by returning null
  mockClient.mockResponse('passkey_challenges', {
    data: null,
    error: { code: 'PGRST116' }
  }, 'select')

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
  }
})

Deno.test("generateMobileAuthenticationPage - should reject challenge without user_id", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock challenge without user_id
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      user_id: null, // Challenge doesn't have user_id
      type: 'authentication',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

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
    assertEquals(result.error, 'Invalid authentication challenge')
  }
})

Deno.test("generateMobileAuthenticationPage - should reject non-existent credential", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock valid challenge
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'authentication',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  // Mock user lookup
  mockClient.mockResponse('auth.users', {
    data: { id: 'user-456', email: 'test@example.com' },
    error: null
  }, 'select')

  // Mock credential not found
  mockClient.mockResponse('user_passkeys', {
    data: null,
    error: { code: 'PGRST116' }
  }, 'select')

  const result = await generateMobileAuthenticationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-123',
    'nonexistent-credential',
    'api.risaboss.com'
  )

  assertEquals(result.success, false)
  if (!result.success) {
    assertExists(result.error)
    assertEquals(result.error, 'Authentication credential not found')
  }
})

Deno.test("generateMobileAuthenticationPage - should reject inactive credential", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock valid challenge
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'authentication',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  // Mock user lookup
  mockClient.mockResponse('auth.users', {
    data: { id: 'user-456', email: 'test@example.com' },
    error: null
  }, 'select')

  // Mock inactive passkey (won't be returned due to active=true filter)
  mockClient.mockResponse('user_passkeys', {
    data: null,
    error: { code: 'PGRST116' }
  }, 'select')

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
    assertEquals(result.error, 'Authentication credential not found')
  }
})

Deno.test("generateMobileAuthenticationPage - should update challenge status to in_progress", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock valid challenge
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'authentication',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  // Mock user lookup
  mockClient.mockResponse('auth.users', {
    data: { id: 'user-456', email: 'test@example.com' },
    error: null
  }, 'select')

  // Mock passkey lookup
  mockClient.mockResponse('user_passkeys', {
    data: mockPasskey,
    error: null
  }, 'select')

  // Mock challenge update
  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789', status: 'in_progress' }],
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

  // Verify update was called
  const history = mockClient.getQueryHistory()
  const updateCall = history.find(h => h.operation === 'update')
  assertExists(updateCall)
})

Deno.test("generateMobileAuthenticationPage - should return credential metadata", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock valid challenge
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'authentication',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  // Mock user lookup
  mockClient.mockResponse('auth.users', {
    data: { id: 'user-456', email: 'test@example.com' },
    error: null
  }, 'select')

  // Mock passkey with custom display name and creation time
  mockClient.mockResponse('user_passkeys', {
    data: {
      ...mockPasskey,
      display_name: 'iPhone 15 Pro',
      created_at: '2024-10-01T12:00:00Z'
    },
    error: null
  }, 'select')

  // Mock challenge update
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
    assertEquals(result.credentialDisplayName, 'iPhone 15 Pro')
    assertEquals(result.credentialCreatedAt, '2024-10-01T12:00:00Z')
  }
})

Deno.test("GET /register/mobile - a request-supplied rpName cannot spoof the relying party name", async () => {
  const mockClient = createMockSupabaseClient()

  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'registration',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')
  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'update')

  const app = new OpenAPIHono<{ Variables: PasskeyContext }>()
  app.use("*", async (ctx, next) => {
    // deno-lint-ignore no-explicit-any
    ctx.set("supabase", mockClient as any)
    await next()
  })
  app.route("/", mobile)

  const response = await app.request(
    "/register/mobile?challenge=mock-challenge-base64&email=test@example.com" +
    "&sessionId=session-123&rpId=api.risaboss.com&rpName=Microsoft%20Security"
  )

  assertEquals(response.status, 200)
  const body = await response.text()
  // The OS passkey prompt renders rp.name; it must be the server-derived name.
  assertEquals(body.includes("Microsoft Security"), false)
  assertStringIncludes(body, "const rpName = 'BOSS'")
})

Deno.test("generateMobileRegistrationPage - logs carry no raw email or user id", async () => {
  const mockClient = createMockSupabaseClient()

  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'registration',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')
  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'update')

  const logged: string[] = []
  const originals = {
    log: console.log,
    error: console.error,
    warn: console.warn,
    info: console.info,
    debug: console.debug
  }
  const capture = (...args: unknown[]) => {
    logged.push(args.map(arg => arg instanceof Error ? `${arg.message}\n${arg.stack ?? ''}` : Deno.inspect(arg)).join(' '))
  }
  console.log = capture
  console.error = capture
  console.warn = capture
  console.info = capture
  console.debug = capture
  try {
    const result = await generateMobileRegistrationPage(
      mockClient as unknown as SupabaseClient,
      'mock-challenge-base64',
      'victim@example.com',
      'session-123',
      'api.risaboss.com'
    )
    assertEquals(result.success, true)
  } finally {
    console.log = originals.log
    console.error = originals.error
    console.warn = originals.warn
    console.info = originals.info
    console.debug = originals.debug
  }

  assertExists(logged.find(line => line.includes('v***@example.com')))
  assertExists(logged.find(line => line.includes(maskUserId('user-456'))))
  for (const line of logged) {
    assertEquals(line.includes('victim@example.com'), false, `log leaked raw email: ${line}`)
    assertEquals(line.includes('user-456'), false, `log leaked raw user id: ${line}`)
  }
})
