/**
 * Tests for Mobile Service
 */

import { assertEquals, assertExists } from "jsr:@std/assert"
import type { SupabaseClient } from "@supabase/supabase-js"
import { generateMobileRegistrationPage, generateMobileAuthenticationPage } from "../services/mobile.ts"
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
      session_id: 'session-123',
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
    'api.risaboss.com',
    'BOSS'
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
    'api.risaboss.com',
    'BOSS'
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
    'api.risaboss.com',
    'BOSS'
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
    'api.risaboss.com',
    'BOSS'
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
      session_id: 'session-123',
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
    'api.risaboss.com',
    'BOSS'
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
      session_id: 'session-123',
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
      session_id: 'session-123',
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
      session_id: 'session-123',
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
      session_id: 'session-123',
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
      session_id: 'session-123',
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

// ============================================================================
// Session binding (#924): the mobile pages are public and unauthenticated,
// so the sessionId query parameter must never be able to move an
// already-bound ceremony's token handoff to another session.
// ============================================================================

Deno.test("generateMobileRegistrationPage - should refuse to rebind a bound challenge to an attacker's session", async () => {
  const mockClient = createMockSupabaseClient()

  // The victim's desktop bound the challenge to its session when the ceremony
  // started; the attacker replays the public page URL with their own session
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'registration',
      session_id: 'session-victim',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  const result = await generateMobileRegistrationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-attacker',
    'api.risaboss.com',
    'BOSS'
  )

  assertEquals(result.success, false)
  if (!result.success) {
    assertEquals(result.error, 'Challenge is already bound to another session')
  }

  // No write ran, so the row keeps the victim's binding and the completed
  // ceremony's tokens can only reach the victim's session
  assertEquals(
    mockClient.getQueryHistory().some(h => h.operation === 'update'),
    false
  )
})

Deno.test("generateMobileRegistrationPage - should bind an unbound challenge to the session that first opens it", async () => {
  const mockClient = createMockSupabaseClient()

  // Challenge created without a session: the first page load claims the row
  // through a compare-and-set on the still-null session_id column
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'registration',
      session_id: null,
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789', session_id: 'session-123' }],
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

  const update = mockClient.getQueryHistory().find(h => h.operation === 'update')
  assertExists(update)
  assertEquals((update.params.data as Record<string, unknown>).session_id, 'session-123')
  assertEquals((update.params.data as Record<string, unknown>).status, 'in_progress')
})

Deno.test("generateMobileRegistrationPage - should fail closed when the session bind write errors", async () => {
  const mockClient = createMockSupabaseClient()

  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'registration',
      session_id: null,
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  mockClient.mockResponse('passkey_challenges', {
    data: null,
    error: { message: 'connection reset' }
  }, 'update')

  const result = await generateMobileRegistrationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-123',
    'api.risaboss.com',
    'BOSS'
  )

  // A ceremony that cannot be bound to a session can never hand its tokens
  // to the waiting desktop, so the page is refused rather than served unbound
  assertEquals(result.success, false)
  if (!result.success) {
    assertEquals(result.error, 'Failed to bind challenge session')
  }
})

Deno.test("generateMobileRegistrationPage - should fail closed when the bind race is lost to a different session", async () => {
  const mockClient = createMockSupabaseClient()

  // The read sees an unbound row, but a concurrent page load for the
  // victim's session wins the compare-and-set between the read and the write
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'registration',
      session_id: null,
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  // Zero updated rows: the compare-and-set found the column no longer null
  mockClient.mockResponse('passkey_challenges', {
    data: [],
    error: null
  }, 'update')

  mockClient.mockResponse('passkey_challenges', {
    data: { challenge: 'mock-challenge-base64', session_id: 'session-victim' },
    error: null
  }, 'select', { match: { challenge: 'mock-challenge-base64' } })

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
    assertEquals(result.error, 'Challenge is already bound to another session')
  }
})

Deno.test("generateMobileRegistrationPage - should fail closed when the bind race is lost and the row cannot be re-read", async () => {
  const mockClient = createMockSupabaseClient()

  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'registration',
      session_id: null,
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  mockClient.mockResponse('passkey_challenges', {
    data: [],
    error: null
  }, 'update')

  // The re-read that would confirm who won the race fails: fail closed
  mockClient.mockResponse('passkey_challenges', {
    data: null,
    error: { code: 'PGRST116' }
  }, 'select', { match: { challenge: 'mock-challenge-base64' } })

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
    assertEquals(result.error, 'Challenge is already bound to another session')
  }
})

Deno.test("generateMobileRegistrationPage - a same-session reload never rewrites the binding", async () => {
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

  const result = await generateMobileRegistrationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-123',
    'api.risaboss.com',
    'BOSS'
  )

  assertEquals(result.success, true)

  // Only the status is rewritten; the session binding itself is immutable, so
  // no number of reloads can move the handoff target
  const update = mockClient.getQueryHistory().find(h => h.operation === 'update')
  assertExists(update)
  assertEquals('session_id' in (update.params.data as Record<string, unknown>), false)
})

Deno.test("generateMobileAuthenticationPage - should refuse to rebind a bound challenge to an attacker's session", async () => {
  const mockClient = createMockSupabaseClient()

  // The victim's desktop bound the challenge to its session when the ceremony
  // started; the attacker replays the public page URL with their own session
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'authentication',
      session_id: 'session-victim',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

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
    assertEquals(result.error, 'Challenge is already bound to another session')
  }

  // The refusal happened before the credential lookup, and no write ran, so
  // the token handoff can only ever reach the victim's session
  assertEquals(
    mockClient.getQueryHistory().some(h => h.operation === 'update'),
    false
  )
  assertEquals(
    mockClient.getQueryHistory().some(h => h.table === 'user_passkeys'),
    false
  )
})

Deno.test("generateMobileAuthenticationPage - should bind an unbound challenge to the session that first opens it", async () => {
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

  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789', session_id: 'session-123' }],
    error: null
  }, 'update')

  mockClient.mockResponse('user_passkeys', {
    data: mockPasskey,
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

  assertEquals(result.success, true)

  const update = mockClient.getQueryHistory().find(h => h.operation === 'update')
  assertExists(update)
  assertEquals((update.params.data as Record<string, unknown>).session_id, 'session-123')
})

Deno.test("generateMobileAuthenticationPage - a same-session reload never rewrites the binding", async () => {
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

  const result = await generateMobileAuthenticationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'test@example.com',
    'session-123',
    'credential-abc',
    'api.risaboss.com'
  )

  assertEquals(result.success, true)

  const update = mockClient.getQueryHistory().find(h => h.operation === 'update')
  assertExists(update)
  assertEquals('session_id' in (update.params.data as Record<string, unknown>), false)
})
