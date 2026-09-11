/**
 * End-to-End Authentication Flow Tests
 *
 * Tests the complete authentication flow from registration to authentication
 * with real cryptographic operations (no mocks for crypto)
 */

import { assertEquals, assertExists } from "jsr:@std/assert"
import type { SupabaseClient } from "@supabase/supabase-js"
import { createMockSupabaseClient } from "./helpers/mocks.ts"
import { generateRegistrationChallenge, completeRegistration } from "../services/registration.ts"
import { generateAuthChallenge, completeAuthentication } from "../services/auth.ts"
import { encodeBase64Url } from "@std/encoding/base64url"
import { jwtVerify } from "jose"
import { createAssertion, createRegistrationCredential, TEST_RP_ID } from "./helpers/webauthn.ts"

// Test JWT secret
const TEST_JWT_SECRET = "test-secret-key-for-e2e-testing-only-minimum-32-characters-required-for-security"

// Pin the relying party: SUPABASE_URL is not set in tests
Deno.env.set('PASSKEY_RP_ID', TEST_RP_ID)

/**
 * Helper to create a complete WebAuthn registration credential with real crypto
 */
function createRealRegistrationCredential(challenge: string) {
  return createRegistrationCredential({ challenge })
}

/**
 * Helper to create a real authentication credential with valid signature
 */
function createRealAuthenticationCredential(
  challenge: string,
  credentialId: Uint8Array,
  privateKey: CryptoKey,
  signCount = 1
) {
  return createAssertion({ challenge, credentialId, privateKey, signCount })
}

/**
 * E2E Test: Complete Registration → Authentication Flow
 */
Deno.test("E2E - Complete registration and authentication flow with real crypto", async () => {
  const mockClient = createMockSupabaseClient()
  const testEmail = "e2e-test@example.com"
  const testUserId = "user-e2e-test-123"

  // Set JWT_SECRET for token generation
  Deno.env.set('JWT_SECRET', TEST_JWT_SECRET)
  mockClient.mockAuthUser(testEmail, testUserId)

  // STEP 1: Generate registration challenge
  console.log("📝 Step 1: Generating registration challenge")

  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-reg-1' }],
    error: null
  }, 'insert')

  mockClient.mockResponse('rpc.admit_passkey_challenge', { data: [{ allowed: true, retry_after_seconds: 0 }], error: null }, 'call')

  const regChallengeResult = await generateRegistrationChallenge(
    mockClient as unknown as SupabaseClient,
    testUserId,
    'session-reg-123'
  )

  assertEquals(regChallengeResult.success, true)
  assertExists((regChallengeResult as { challenge?: string }).challenge)

  const registrationChallenge = (regChallengeResult as { challenge: string }).challenge

  // STEP 2: Complete registration with real credential
  console.log("🔐 Step 2: Completing registration with real credential")

  const { credential: regCredential, keyPair, credentialId } = await createRealRegistrationCredential(
    registrationChallenge
  )

  // Mock challenge verification
  mockClient.mockResponse('passkey_challenges', {
    data: {
      id: 'challenge-reg-1',
      challenge: registrationChallenge,
      type: 'registration',
      user_id: testUserId,
      session_id: 'session-reg-123',
      expires_at: new Date(Date.now() + 300000).toISOString(),
      consumed: false,
      created_at: new Date().toISOString()
    },
    error: null
  }, 'select')

  // Mock challenge deletion
  mockClient.mockResponse('passkey_challenges', {
    data: { id: 'challenge-reg-1' },
    error: null
  }, 'delete')

  // Mock passkey storage
  mockClient.mockResponse('user_passkeys', {
    data: [{
      id: 'passkey-new-123',
      user_id: testUserId,
      credential_id: regCredential.id,
      public_key: 'stored-key',
      display_name: 'E2E Test Passkey',
      transports: ['internal'],
      created_at: new Date().toISOString()
    }],
    error: null
  }, 'insert')

  const regCompleteResult = await completeRegistration(
    mockClient as unknown as SupabaseClient,
    regCredential,
    registrationChallenge,
    { claimedUserId: testUserId, displayName: 'E2E Test Passkey' }
  )

  assertEquals(regCompleteResult.success, true)
  console.log("✅ Registration completed successfully")

  // STEP 3: Generate authentication challenge
  console.log("🔑 Step 3: Generating authentication challenge")

  mockClient.mockResponse('rpc.find_user_by_email', {
    data: [{ id: testUserId, email: testEmail }],
    error: null
  }, 'call')

  mockClient.mockResponse('user_passkeys', {
    data: [{
      id: 'passkey-123',
      user_id: testUserId,
      credential_id: encodeBase64Url(credentialId),
      public_key: encodeBase64Url(new Uint8Array(await crypto.subtle.exportKey('raw', keyPair.publicKey))),
      display_name: 'E2E Test Passkey',
      transports: ['internal'],
      created_at: new Date().toISOString(),
      last_used_at: new Date().toISOString(),
      active: true
    }],
    error: null
  }, 'select')

  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-auth-1' }],
    error: null
  }, 'insert')

  mockClient.mockResponse('rpc.admit_passkey_challenge', { data: [{ allowed: true, retry_after_seconds: 0 }], error: null }, 'call')

  const authChallengeResult = await generateAuthChallenge(
    mockClient as unknown as SupabaseClient,
    testEmail,
    'session-e2e-123'
  )

  assertEquals(authChallengeResult.success, true)
  assertExists((authChallengeResult as { challenge?: string }).challenge)

  const authenticationChallenge = (authChallengeResult as { challenge: string }).challenge

  // STEP 4: Complete authentication with real signature
  console.log("🔐 Step 4: Completing authentication with real signature")

  const authCredential = await createRealAuthenticationCredential(
    authenticationChallenge,
    credentialId,
    keyPair.privateKey
  )

  // Mock challenge verification
  mockClient.mockResponse('passkey_challenges', {
    data: {
      id: 'challenge-auth-1',
      challenge: authenticationChallenge,
      type: 'authentication',
      user_id: testUserId,
      session_id: 'session-e2e-123',
      expires_at: new Date(Date.now() + 300000).toISOString(),
      consumed: false,
      created_at: new Date().toISOString()
    },
    error: null
  }, 'select')

  // Mock passkey lookup
  mockClient.mockResponse('user_passkeys', {
    data: {
      id: 'passkey-123',
      user_id: testUserId,
      credential_id: encodeBase64Url(credentialId),
      public_key: encodeBase64Url(new Uint8Array(await crypto.subtle.exportKey('raw', keyPair.publicKey))),
      display_name: 'E2E Test Passkey',
      transports: ['internal'],
      created_at: new Date().toISOString(),
      last_used_at: new Date().toISOString(),
      active: true
    },
    error: null
  }, 'select')

  // Mock last_used_at update
  mockClient.mockResponse('user_passkeys', {
    data: { id: 'passkey-123' },
    error: null
  }, 'update')

  // Mock completed_authentications insert
  mockClient.mockResponse('completed_authentications', {
    data: [{
      session_id: 'session-e2e-123',
      user_id: testUserId,
      challenge: authenticationChallenge,
      created_at: new Date().toISOString()
    }],
    error: null
  }, 'insert')

  // Mock challenge deletion
  mockClient.mockResponse('passkey_challenges', {
    data: { id: 'challenge-auth-1' },
    error: null
  }, 'delete')

  // Mock user fetch for email (needed by completeAuthentication) - uses .single()
  mockClient.mockResponse('users', {
    data: { id: testUserId, email: testEmail },
    error: null
  }, 'select')

  const authCompleteResult = await completeAuthentication(
    mockClient as unknown as SupabaseClient,
    authCredential,
    authenticationChallenge
  )

  assertEquals(authCompleteResult.success, true, "Authentication should succeed")
  console.log("✅ Authentication completed successfully")

  // STEP 5: Verify the JWT parked for the polling desktop
  console.log("🎫 Step 5: Verifying JWT tokens")

  {
    // This is the cross-device path (the challenge carries a session_id), so the
    // session is written to completed_authentications for the desktop to claim
    // via /auth/status — it is deliberately not returned to the device that ran
    // the ceremony.
    const completion = mockClient.getQueryHistory().find(
      entry => entry.table === 'completed_authentications' && entry.operation === 'insert'
    )
    assertExists(completion, "The completion row should be written")
    const parked = completion!.params.data as {
      access_token?: string
      refresh_token?: string
      expires_at?: number
    }

    assertExists(parked.access_token, "Access token should be parked for the poller")
    assertExists(parked.refresh_token, "Refresh token should be parked for the poller")
    assertExists(parked.expires_at, "expires_at should be parked for the poller")

    assertEquals(
      (authCompleteResult as { accessToken?: string }).accessToken,
      undefined,
      "The ceremony device must not receive the desktop's session"
    )

    // Verify access token structure
    const secret = new TextEncoder().encode(TEST_JWT_SECRET)
    const { payload } = await jwtVerify(parked.access_token!, secret, {
      issuer: 'supabase',
      audience: 'authenticated'
    })

    assertEquals(payload.sub, testUserId, "Token should contain user ID")
    assertEquals(payload.email, testEmail, "Token should contain email")
    assertEquals(payload.role, 'authenticated', "Token should have authenticated role")
    assertEquals(payload.aal, 'aal1', "Token should have AAL1")

    // Check AMR for passkey authentication
    const amr = payload.amr as Array<{ method: string }>
    assertEquals(amr[0].method, 'passkey', "AMR should indicate passkey authentication")

    console.log("✅ JWT tokens verified successfully")
  }

  console.log("🎉 E2E test completed successfully!")
})

/**
 * E2E Test: Replay Attack Prevention
 */
Deno.test("E2E - Should prevent replay attacks by rejecting reused challenges", async () => {
  const mockClient = createMockSupabaseClient()
  const testEmail = "replay-test@example.com"
  const testUserId = "user-replay-test"

  Deno.env.set('JWT_SECRET', TEST_JWT_SECRET)

  // Generate auth challenge
  mockClient.mockResponse('rpc.find_user_by_email', {
    data: [{ id: testUserId, email: testEmail }],
    error: null
  }, 'call')

  mockClient.mockResponse('user_passkeys', {
    data: [{
      id: 'passkey-replay',
      user_id: testUserId,
      credential_id: 'cred-replay',
      public_key: 'key-replay',
      display_name: 'Test',
      transports: ['internal']
    }],
    error: null
  }, 'select')

  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-replay' }],
    error: null
  }, 'insert')

  mockClient.mockResponse('rpc.admit_passkey_challenge', { data: [{ allowed: true, retry_after_seconds: 0 }], error: null }, 'call')

  const challengeResult = await generateAuthChallenge(
    mockClient as unknown as SupabaseClient,
    testEmail,
    'session-replay'
  )

  assertEquals(challengeResult.success, true)
  const challenge = (challengeResult as { challenge: string }).challenge

  // Create a valid authentication credential
  const keyPair = await crypto.subtle.generateKey(
    { name: 'ECDSA', namedCurve: 'P-256' },
    true,
    ['sign', 'verify']
  )
  const credentialId = crypto.getRandomValues(new Uint8Array(16))
  const authCredential = await createRealAuthenticationCredential(challenge, credentialId, keyPair.privateKey)

  // First authentication attempt - should succeed
  mockClient.mockResponse('passkey_challenges', {
    data: {
      id: 'challenge-replay',
      challenge: challenge,
      type: 'authentication',
      user_id: testUserId,
      session_id: 'session-replay',
      expires_at: new Date(Date.now() + 300000).toISOString()
    },
    error: null
  }, 'select')

  mockClient.mockResponse('user_passkeys', {
    data: {
      id: 'passkey-replay',
      user_id: testUserId,
      credential_id: encodeBase64Url(credentialId),
      public_key: encodeBase64Url(new Uint8Array(await crypto.subtle.exportKey('raw', keyPair.publicKey)))
    },
    error: null
  }, 'select')

  mockClient.mockResponse('user_passkeys', { data: {}, error: null }, 'update')
  mockClient.mockResponse('completed_authentications', { data: [{}], error: null }, 'insert')
  mockClient.mockResponse('passkey_challenges', { data: {}, error: null }, 'delete')
  mockClient.mockResponse('users', {
    data: { id: testUserId, email: testEmail },
    error: null
  }, 'select')

  const firstAttempt = await completeAuthentication(
    mockClient as unknown as SupabaseClient,
    authCredential,
    challenge
  )

  assertEquals(firstAttempt.success, true, "First authentication should succeed")

  // Second authentication attempt with SAME challenge - should fail
  mockClient.mockResponse('passkey_challenges', {
    data: null,
    error: { code: 'PGRST116', message: 'Challenge not found' }
  }, 'select')

  const secondAttempt = await completeAuthentication(
    mockClient as unknown as SupabaseClient,
    authCredential,
    challenge
  )

  assertEquals(secondAttempt.success, false, "Replay attack should be prevented")
  // The error message may include "Invalid or expired challenge" or just "Invalid challenge"
  const errorMsg = (secondAttempt as { error: string }).error
  assertEquals(
    errorMsg.includes('Invalid') && errorMsg.includes('challenge'),
    true,
    "Should return invalid challenge error"
  )

  console.log("✅ Replay attack prevention verified")
})
