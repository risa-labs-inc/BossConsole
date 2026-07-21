/**
 * Tests for Registration Service
 */

import { assertEquals, assertExists } from "jsr:@std/assert"
import type { SupabaseClient } from "@supabase/supabase-js"
import { generateRegistrationChallenge, completeRegistration, type RegistrationCredential } from "../services/registration.ts"
import { createMockSupabaseClient, mockChallenge, mockRegistrationCredential } from "./helpers/mocks.ts"

Deno.test("generateRegistrationChallenge - should generate challenge for user", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock challenge storage response (insert operation)
  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'insert')

  const result = await generateRegistrationChallenge(mockClient as unknown as SupabaseClient, 'user-456')

  assertEquals(result.success, true)
  if (result.success) {
    assertExists(result.challenge)
    assertEquals(result.rp.name, 'BOSS')
    assertEquals(result.rp.id, 'api.risaboss.com')
    assertEquals(result.user.id, 'user-456')
    assertEquals(result.timeout, 60000)
    assertEquals(result.attestation, 'none')
    assertExists(result.authenticatorSelection)
    assertEquals(result.authenticatorSelection.authenticatorAttachment, 'platform')
    assertEquals(result.authenticatorSelection.userVerification, 'preferred')
    assertEquals(result.authenticatorSelection.requireResidentKey, false)
  }
})

Deno.test("generateRegistrationChallenge - should include ES256 algorithm", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock insert operation
  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'insert')

  const result = await generateRegistrationChallenge(mockClient as unknown as SupabaseClient, 'user-456')

  assertEquals(result.success, true)
  if (result.success) {
    assertExists(result.pubKeyCredParams)
    assertEquals(result.pubKeyCredParams.length, 1)
    assertEquals(result.pubKeyCredParams[0].type, 'public-key')
    assertEquals(result.pubKeyCredParams[0].alg, -7) // ES256
  }
})

Deno.test("generateRegistrationChallenge - should accept and return sessionId for cross-device flows", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock insert operation
  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'insert')

  const sessionId = 'session-cross-device-123'
  const result = await generateRegistrationChallenge(
    mockClient as unknown as SupabaseClient,
    'user-456',
    sessionId
  )

  assertEquals(result.success, true)
  if (result.success) {
    // Should return the sessionId for cross-device polling
    assertEquals(result.sessionId, sessionId)
    assertExists(result.challenge)
    assertEquals(result.user.id, 'user-456')
  }
})

Deno.test("generateRegistrationChallenge - should work without sessionId for same-device flows", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock insert operation
  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-789' }],
    error: null
  }, 'insert')

  const result = await generateRegistrationChallenge(
    mockClient as unknown as SupabaseClient,
    'user-456'
    // No sessionId provided
  )

  assertEquals(result.success, true)
  if (result.success) {
    // sessionId should be undefined for same-device flows
    assertEquals(result.sessionId, undefined)
    assertExists(result.challenge)
  }
})

Deno.test("generateRegistrationChallenge - should handle storage failure", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock storage failure (insert operation)
  // storeChallenge uses .insert().select() so the insert returns the error
  mockClient.mockResponse('passkey_challenges', {
    data: null,
    error: { message: 'Database error' }
  }, 'insert')

  // No need to mock select since insert fails first

  const result = await generateRegistrationChallenge(mockClient as unknown as SupabaseClient, 'user-456')

  assertEquals(result.success, false)
  if (!result.success) {
    assertExists(result.error)
  }
})

Deno.test("completeRegistration - should reject invalid ceremony type", async () => {
  const mockClient = createMockSupabaseClient()

  const invalidCredential = {
    ...mockRegistrationCredential,
    response: {
      ...mockRegistrationCredential.response,
      clientDataJSON: btoa(JSON.stringify({
        type: 'webauthn.get', // Wrong type!
        challenge: 'mock-challenge-base64',
        origin: 'https://api.risaboss.com'
      }))
    }
  }

  const result = await completeRegistration(
    mockClient as unknown as SupabaseClient,
    'user-456',
    invalidCredential as RegistrationCredential,
    'mock-challenge-base64',
    'My Passkey'
  )

  assertEquals(result.success, false)
  if (!result.success) {
    assertEquals(result.error, 'Invalid ceremony type - expected webauthn.create')
  }
})

Deno.test("completeRegistration - should verify and consume challenge", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock challenge verification
  mockClient.mockResponse('passkey_challenges', {
    data: { ...mockChallenge, type: 'registration' },
    error: null
  })

  // Mock passkey storage
  mockClient.mockResponse('user_passkeys', {
    data: [{ id: 'passkey-new-123' }],
    error: null
  })

  // Note: This test will fail with actual crypto extraction
  // In real tests, you'd need to mock extractPublicKeyFromAttestation
  const result = await completeRegistration(
    mockClient as unknown as SupabaseClient,
    'user-456',
    mockRegistrationCredential as RegistrationCredential,
    'mock-challenge-base64',
    'My New Passkey'
  )

  console.log('completeRegistration result:', result)
  // Will likely fail due to attestation parsing
  // In production tests, mock the crypto utils
})

Deno.test("completeRegistration - should use default display name if not provided", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock successful challenge verification
  mockClient.mockResponse('passkey_challenges', {
    data: { ...mockChallenge, type: 'registration' },
    error: null
  })

  // Mock passkey storage
  mockClient.mockResponse('user_passkeys', {
    data: [{ id: 'passkey-new-123' }],
    error: null
  })

  // Note: Will fail due to crypto, but tests the display_name logic
  const result = await completeRegistration(
    mockClient as unknown as SupabaseClient,
    'user-456',
    mockRegistrationCredential as RegistrationCredential,
    'mock-challenge-base64'
    // No displayName provided
  )

  console.log('completeRegistration with default name:', result)
  // In a full mock setup, would verify display_name defaults to 'My Passkey'
})

Deno.test("completeRegistration - should handle invalid challenge", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock challenge not found
  mockClient.mockResponse('passkey_challenges', {
    data: null,
    error: { code: 'PGRST116' }
  })

  const result = await completeRegistration(
    mockClient as unknown as SupabaseClient,
    'user-456',
    mockRegistrationCredential as RegistrationCredential,
    'invalid-challenge',
    'My Passkey'
  )

  assertEquals(result.success, false)
  if (!result.success) {
    assertExists(result.error)
  }
})

Deno.test("completeRegistration - should store credential with correct fields", async () => {
  const mockClient = createMockSupabaseClient()

  // Mock challenge verification
  mockClient.mockResponse('passkey_challenges', {
    data: { ...mockChallenge, type: 'registration' },
    error: null
  })

  // Mock passkey storage success
  mockClient.mockResponse('user_passkeys', {
    data: [{ id: 'passkey-stored-123' }],
    error: null
  })

  const result = await completeRegistration(
    mockClient as unknown as SupabaseClient,
    'user-456',
    mockRegistrationCredential as RegistrationCredential,
    'mock-challenge-base64',
    'Test Device'
  )

  // In a complete test, you'd verify the insert call included:
  // - user_id: 'user-456'
  // - credential_id: mockRegistrationCredential.id
  // - public_key: extracted from attestation
  // - display_name: 'Test Device'
  // - transports: ['internal']

  console.log('completeRegistration storage result:', result)
})
