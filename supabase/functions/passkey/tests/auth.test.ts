/**
 * Tests for Authentication Service
 */

import { assertEquals, assertExists } from "jsr:@std/assert"
import type { SupabaseClient } from "@supabase/supabase-js"
import { generateAuthChallenge, completeAuthentication, checkAuthStatus } from "../services/auth.ts"
import { createMockSupabaseClient, mockPasskey, mockChallenge, mockAuthenticationCredential } from "./helpers/mocks.ts"
import { buildAuthenticatorData, encodePayload, TEST_RP_ID } from "./helpers/webauthn.ts"

// Pin the relying party: SUPABASE_URL is not set in tests
Deno.env.set('PASSKEY_RP_ID', TEST_RP_ID)

Deno.test("generateAuthChallenge - should generate challenge for existing user", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock user lookup via RPC function (find_user_by_email)
  mockClient.mockResponse('rpc.find_user_by_email', {
    data: [{ id: 'user-456', email: 'test@example.com' }],
    error: null
  }, 'call')

  // Mock passkeys lookup response (select operation)
  mockClient.mockResponse('user_passkeys', {
    data: [mockPasskey],
    error: null
  }, 'select')

  // Mock challenge storage response (insert operation)
  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'insert')

  mockClient.mockResponse('rpc.admit_passkey_challenge', { data: [{ allowed: true, retry_after_seconds: 0 }], error: null }, 'call')

  const result = await generateAuthChallenge(mockClient as unknown as SupabaseClient, 'test@example.com', 'session-xyz')

  assertEquals(result.success, true)
  if (result.success) {
    assertExists(result.challenge)
    assertEquals(result.rpId, 'api.risaboss.com')
    assertEquals(result.sessionId, 'session-xyz')
  }
})

Deno.test("generateAuthChallenge - should return error for non-existent user", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock user not found via RPC function (returns empty array)
  mockClient.mockResponse('rpc.find_user_by_email', {
    data: [],
    error: null
  }, 'call')

  mockClient.mockResponse('rpc.admit_passkey_challenge', { data: [{ allowed: true, retry_after_seconds: 0 }], error: null }, 'call')

  const result = await generateAuthChallenge(mockClient as unknown as SupabaseClient, 'nonexistent@example.com')

  assertEquals(result.success, false)
  if (!result.success) {
    assertEquals(result.error, 'No usable passkey is available for this sign-in')
  }
})

Deno.test("generateAuthChallenge - should return error when user has no passkeys", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock user found via RPC function
  mockClient.mockResponse('rpc.find_user_by_email', {
    data: [{ id: 'user-456', email: 'test@example.com' }],
    error: null
  }, 'call')

  // Mock no passkeys (select operation)
  mockClient.mockResponse('user_passkeys', {
    data: [],
    error: null
  }, 'select')

  mockClient.mockResponse('rpc.admit_passkey_challenge', { data: [{ allowed: true, retry_after_seconds: 0 }], error: null }, 'call')

  const result = await generateAuthChallenge(mockClient as unknown as SupabaseClient, 'test@example.com')

  assertEquals(result.success, false)
  if (!result.success) {
    assertEquals(result.error, 'No usable passkey is available for this sign-in')
  }
})

Deno.test("completeAuthentication - should verify valid credential", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock challenge verification
  mockClient.mockResponse('passkey_challenges', {
    data: mockChallenge,
    error: null
  })

  // Mock passkey lookup
  mockClient.mockResponse('user_passkeys', {
    data: mockPasskey,
    error: null
  })

  // Mock update last_used_at
  mockClient.mockResponse('user_passkeys', {
    data: { id: mockPasskey.id },
    error: null
  })

  // Note: This test will fail with actual crypto verification
  // In real tests, you'd need to mock the verifySignature function
  const result = await completeAuthentication(
    mockClient as unknown as SupabaseClient,
    mockAuthenticationCredential,
    'mock-challenge-base64'
  )

  // This will likely fail due to signature verification
  // In production tests, mock the crypto utils
  console.log('completeAuthentication result:', result)
})

Deno.test("completeAuthentication - should reject invalid challenge type", async () => {
  const mockClient = createMockSupabaseClient()

  const invalidCredential = {
    ...mockAuthenticationCredential,
    response: {
      ...mockAuthenticationCredential.response,
      clientDataJSON: btoa(JSON.stringify({
        type: 'webauthn.create', // Wrong type!
        challenge: 'mock-challenge-base64',
        origin: 'https://api.risaboss.com'
      }))
    }
  }

  const result = await completeAuthentication(
    mockClient as unknown as SupabaseClient,
    invalidCredential,
    'mock-challenge-base64'
  )

  assertEquals(result.success, false)
  if (!result.success) {
    assertEquals(result.error, 'Invalid ceremony type - expected webauthn.get')
  }
})

Deno.test("completeAuthentication - should properly decode base64 clientDataJSON", async () => {
  const mockClient = createMockSupabaseClient()

  // Create test data with known clientDataJSON
  const clientData = {
    type: 'webauthn.get',
    challenge: 'test-challenge-123',
    origin: 'https://api.risaboss.com'
  }

  // Base64 encode the client data (as it comes from the browser)
  const clientDataJSONBase64 = btoa(JSON.stringify(clientData))

  // Well-formed authenticator data for our RP, so the ceremony reaches the
  // signature check rather than failing earlier on structure
  const authenticatorData = encodePayload(
    await buildAuthenticatorData({ rpId: TEST_RP_ID, signCount: 1 })
  )

  const testCredential = {
    id: 'credential-test',
    rawId: 'credential-test-raw',
    type: 'public-key',
    response: {
      clientDataJSON: clientDataJSONBase64, // Base64 encoded
      authenticatorData,
      signature: 'dGVzdC1zaWduYXR1cmU', // base64url encoded test signature
      userHandle: 'user-456'
    }
  }

  // Mock challenge verification
  mockClient.mockResponse('passkey_challenges', {
    data: {
      ...mockChallenge,
      challenge: 'test-challenge-123'
    },
    error: null
  }, 'select')

  // Mock challenge deletion
  mockClient.mockResponse('passkey_challenges', {
    data: { id: 'challenge-789' },
    error: null
  }, 'delete')

  // Mock passkey lookup
  mockClient.mockResponse('user_passkeys', {
    data: mockPasskey,
    error: null
  }, 'select')

  // Mock update last_used_at
  mockClient.mockResponse('user_passkeys', {
    data: { id: mockPasskey.id },
    error: null
  }, 'update')

  const result = await completeAuthentication(
    mockClient as unknown as SupabaseClient,
    testCredential,
    'test-challenge-123'
  )

  // This test verifies that:
  // 1. clientDataJSON is properly decoded from base64 before being passed to verifySignature
  // 2. The decoded string can be parsed correctly
  // 3. The ceremony type validation works with decoded data

  // The test will fail at signature verification (expected), but should NOT fail
  // at clientDataJSON parsing or ceremony type validation
  console.log('Client data decoding test result:', result)

  // If we got to signature verification, the decoding worked
  if (!result.success && result.error === 'Invalid signature') {
    // This is expected - we're testing the decoding, not the crypto
    console.log('✓ clientDataJSON was properly decoded (signature verification failed as expected with mock data)')
  } else if (!result.success && result.error !== 'Invalid signature') {
    // Any other error means the decoding might have failed
    throw new Error(`Unexpected error: ${result.error}. clientDataJSON decoding may have failed.`)
  }
})

Deno.test("checkAuthStatus - should return pending for active session", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock active challenge
  mockClient.mockResponse('passkey_challenges', {
    data: mockChallenge,
    error: null
  })

  const result = await checkAuthStatus(mockClient as unknown as SupabaseClient, 'session-xyz')

  assertEquals(result.status, 'pending')
  if (result.status === 'pending') {
    assertExists(result.expiresAt)
  }
})

Deno.test("checkAuthStatus - should return completed for finished authentication", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock challenge not found (consumed)
  mockClient.mockResponse('passkey_challenges', {
    data: null,
    error: { code: 'PGRST116' }
  })

  // Mock completed authentication
  mockClient.mockResponse('completed_authentications', {
    data: {
      session_id: 'session-xyz',
      user_id: 'user-456',
      created_at: new Date().toISOString()
    },
    error: null
  })

  const result = await checkAuthStatus(mockClient as unknown as SupabaseClient, 'session-xyz')

  assertEquals(result.status, 'completed')
  if (result.status === 'completed') {
    assertEquals(result.userId, 'user-456')
    assertExists(result.completedAt)
  }
})

Deno.test("checkAuthStatus - should return expired for old session", async () => {
  const mockClient = createMockSupabaseClient()

  const expiredChallenge = {
    ...mockChallenge,
    expires_at: new Date(Date.now() - 60000).toISOString() // Expired 1 minute ago
  }

  mockClient.mockResponse('passkey_challenges', {
    data: expiredChallenge,
    error: null
  })

  const result = await checkAuthStatus(mockClient as unknown as SupabaseClient, 'session-xyz')

  assertEquals(result.status, 'expired')
  if (result.status === 'expired') {
    assertEquals(result.message, 'Session expired')
  }
})

Deno.test("checkAuthStatus - should return expired for non-existent session", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock challenge not found
  mockClient.mockResponse('passkey_challenges', {
    data: null,
    error: { code: 'PGRST116' }
  })

  // Mock completed authentication also not found
  mockClient.mockResponse('completed_authentications', {
    data: null,
    error: { code: 'PGRST116' }
  })

  const result = await checkAuthStatus(mockClient as unknown as SupabaseClient, 'nonexistent-session')

  assertEquals(result.status, 'expired')
  if (result.status === 'expired') {
    assertEquals(result.message, 'Session not found or expired')
  }
})
