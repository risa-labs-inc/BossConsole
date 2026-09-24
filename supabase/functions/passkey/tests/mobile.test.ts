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
      type: 'registration',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
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
      type: 'registration',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
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

Deno.test("generateMobileAuthenticationPage - should reject when users lookup errors", async () => {
  // Regression for BossConsole#1282: a users-row lookup error used to fall back
  // to the attacker-controlled URL `email` parameter. The fallback is now
  // removed; the caller must see the same generic error any other challenge
  // failure produces, and the challenge must NOT be updated to in_progress
  // (its sessionId stays unbound).
  const mockClient = createMockSupabaseClient()

  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'authentication',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  // Simulated lookup error - the row may have been deleted between challenge
  // creation and the page render, or the table may be momentarily unavailable.
  mockClient.mockResponse('users', {
    data: null,
    error: { code: 'PGRST500', message: 'database unavailable' }
  }, 'select')

  // If the lookup error is ignored and the fix is wrong, an update would land
  // here. Stub it so a passing test cannot silently mean "update happened".
  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'update')

  const result = await generateMobileAuthenticationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'attacker@evil.example', // would be shown if the fallback returned it
    'session-123',
    'credential-abc',
    'api.risaboss.com'
  )

  assertEquals(result.success, false)
  if (!result.success) {
    assertEquals(result.error, 'Invalid authentication challenge')
  }
  const updateCalls = mockClient.getQueryHistory().filter(h => h.operation === 'update')
  assertEquals(updateCalls.length, 0, 'challenge must not be updated on lookup failure')
})

Deno.test("generateMobileAuthenticationPage - should reject when users lookup returns no row", async () => {
  // Regression for BossConsole#1282: a missing user row used to fall back to
  // the URL `email` parameter. The fix makes that fall-through impossible.
  const mockClient = createMockSupabaseClient()

  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'authentication',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  // Single-row query with no row found returns data:null, error: PGRST116
  mockClient.mockResponse('users', {
    data: null,
    error: { code: 'PGRST116', message: 'No rows found' }
  }, 'select')

  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'update')

  const result = await generateMobileAuthenticationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'attacker@evil.example',
    'session-123',
    'credential-abc',
    'api.risaboss.com'
  )

  assertEquals(result.success, false)
  if (!result.success) {
    assertEquals(result.error, 'Invalid authentication challenge')
  }
  const updateCalls = mockClient.getQueryHistory().filter(h => h.operation === 'update')
  assertEquals(updateCalls.length, 0)
})

Deno.test("generateMobileAuthenticationPage - should reject when stored email is blank", async () => {
  // Regression for BossConsole#1282: a stored email of "" or whitespace used
  // to fall through to the URL `email` parameter. The fix refuses blank.
  const mockClient = createMockSupabaseClient()

  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      type: 'authentication',
      expires_at: new Date(Date.now() + 60000).toISOString()
    },
    error: null
  }, 'select')

  mockClient.mockResponse('users', {
    data: { id: 'user-456', email: '' },
    error: null
  }, 'select')

  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'update')

  const result = await generateMobileAuthenticationPage(
    mockClient as unknown as SupabaseClient,
    'mock-challenge-base64',
    'attacker@evil.example',
    'session-123',
    'credential-abc',
    'api.risaboss.com'
  )

  assertEquals(result.success, false)
  if (!result.success) {
    assertEquals(result.error, 'Invalid authentication challenge')
  }
  const updateCalls = mockClient.getQueryHistory().filter(h => h.operation === 'update')
  assertEquals(updateCalls.length, 0)
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
  mockClient.mockResponse('users', {
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
  mockClient.mockResponse('users', {
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
  mockClient.mockResponse('users', {
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
  mockClient.mockResponse('users', {
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
  mockClient.mockResponse('users', {
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
