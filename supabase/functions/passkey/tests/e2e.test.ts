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

// Test JWT secret
const TEST_JWT_SECRET = "test-secret-key-for-e2e-testing-only-minimum-32-characters-required-for-security"

/**
 * Helper to create a complete WebAuthn registration credential with real crypto
 */
async function createRealRegistrationCredential(challenge: string, userId: string) {
  // Generate a real ECDSA P-256 key pair
  const keyPair = await crypto.subtle.generateKey(
    { name: 'ECDSA', namedCurve: 'P-256' },
    true,
    ['sign', 'verify']
  )

  // Export the public key
  const publicKeyBytes = new Uint8Array(await crypto.subtle.exportKey('raw', keyPair.publicKey))

  // Extract x and y coordinates (skip first byte 0x04)
  const x = publicKeyBytes.slice(1, 33)
  const y = publicKeyBytes.slice(33, 65)

  // Create COSE key (ES256)
  const coseKey = createCOSEKey(x, y)

  // Create authenticator data
  const credentialId = crypto.getRandomValues(new Uint8Array(16))
  const authData = createAuthenticatorData(credentialId, coseKey)

  // Create attestation object
  const attestationObject = createAttestationObject(authData)

  // Create client data JSON
  const clientDataJSON = {
    type: 'webauthn.create',
    challenge: challenge,
    origin: 'https://api.risaboss.com'
  }

  return {
    credential: {
      id: encodeBase64Url(credentialId),
      rawId: encodeBase64Url(credentialId),
      type: 'public-key',
      response: {
        clientDataJSON: btoa(JSON.stringify(clientDataJSON)),
        attestationObject: encodeBase64Url(attestationObject)
      }
    },
    keyPair,
    credentialId
  }
}

/**
 * Helper to create a real authentication credential with valid signature
 */
async function createRealAuthenticationCredential(
  challenge: string,
  credentialId: Uint8Array,
  privateKey: CryptoKey
) {
  // Create authenticator data (minimal)
  const rpIdHash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode('api.risaboss.com'))
  const flags = new Uint8Array([0x05]) // User present + User verified
  const signCount = new Uint8Array([0x00, 0x00, 0x00, 0x01])
  const authenticatorData = new Uint8Array([...new Uint8Array(rpIdHash), ...flags, ...signCount])

  // Create client data JSON
  const clientDataJSON = {
    type: 'webauthn.get',
    challenge: challenge,
    origin: 'https://api.risaboss.com'
  }
  const clientDataJSONString = JSON.stringify(clientDataJSON)

  // Create data to sign
  const clientDataHash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(clientDataJSONString))
  const signedData = new Uint8Array([...authenticatorData, ...new Uint8Array(clientDataHash)])

  // Sign the data
  const rawSignature = await crypto.subtle.sign(
    { name: 'ECDSA', hash: 'SHA-256' },
    privateKey,
    signedData
  )

  // Convert to DER format
  const signature = convertRawToDERSignature(new Uint8Array(rawSignature))

  return {
    id: encodeBase64Url(credentialId),
    rawId: encodeBase64Url(credentialId),
    type: 'public-key',
    response: {
      clientDataJSON: btoa(clientDataJSONString),
      authenticatorData: encodeBase64Url(authenticatorData),
      signature: encodeBase64Url(signature),
      userHandle: 'user-e2e-test'
    }
  }
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

  // STEP 1: Generate registration challenge
  console.log("📝 Step 1: Generating registration challenge")

  mockClient.mockResponse('passkey_challenges', {
    data: [{ id: 'challenge-reg-1' }],
    error: null
  }, 'insert')

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
    registrationChallenge,
    testUserId
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
    testUserId,
    regCredential,
    registrationChallenge,
    'E2E Test Passkey'
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

  // STEP 5: Verify JWT tokens
  console.log("🎫 Step 5: Verifying JWT tokens")

  if (authCompleteResult.success) {
    const result = authCompleteResult as {
      userId: string
      email: string
      accessToken?: string
      refreshToken?: string
      expiresAt?: number
    }

    assertExists(result.accessToken, "Access token should be present")
    assertExists(result.refreshToken, "Refresh token should be present")
    assertExists(result.expiresAt, "expiresAt should be present")

    // Verify access token structure
    const secret = new TextEncoder().encode(TEST_JWT_SECRET)
    const { payload } = await jwtVerify(result.accessToken!, secret, {
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

// ===== Helper Functions =====

function createCOSEKey(x: Uint8Array, y: Uint8Array): Uint8Array {
  const coseKeyMap: number[] = [
    0xA5, // map(5)
    0x01, 0x02, // kty: EC2 (1: 2)
    0x03, 0x26, // alg: ES256 (3: -7)
    0x20, 0x01, // crv: P-256 (-1: 1)
    0x21, 0x58, 0x20, // x coordinate (-2: bytes(32))
    ...Array.from(x),
    0x22, 0x58, 0x20, // y coordinate (-3: bytes(32))
    ...Array.from(y)
  ]
  return new Uint8Array(coseKeyMap)
}

function createAuthenticatorData(credentialId: Uint8Array, coseKey: Uint8Array): Uint8Array {
  const rpIdHash = new Uint8Array(32).fill(0x00)
  const flags = new Uint8Array([0x45]) // UP + UV + AT
  const signCount = new Uint8Array([0x00, 0x00, 0x00, 0x00])
  const aaguid = new Uint8Array(16).fill(0x00)
  const credentialIdLength = new Uint8Array([
    (credentialId.length >> 8) & 0xFF,
    credentialId.length & 0xFF
  ])

  return new Uint8Array([
    ...rpIdHash,
    ...flags,
    ...signCount,
    ...aaguid,
    ...credentialIdLength,
    ...credentialId,
    ...coseKey
  ])
}

function createAttestationObject(authData: Uint8Array): Uint8Array {
  // Simplified attestation object: {authData: bytes}
  const attestationCBOR: number[] = [
    0xA1, // map(1)
    0x68, // text string, length 8
    ...Array.from(new TextEncoder().encode("authData")), // "authData" in UTF-8
  ]

  // Encode authData as CBOR byte string
  if (authData.length <= 23) {
    attestationCBOR.push(0x40 | authData.length)
  } else if (authData.length <= 255) {
    attestationCBOR.push(0x58, authData.length)
  } else {
    attestationCBOR.push(0x59, (authData.length >> 8) & 0xFF, authData.length & 0xFF)
  }

  attestationCBOR.push(...Array.from(authData))
  return new Uint8Array(attestationCBOR)
}

function convertRawToDERSignature(rawSignature: Uint8Array): Uint8Array {
  const r = rawSignature.slice(0, 32)
  const s = rawSignature.slice(32, 64)

  let rStart = 0
  while (rStart < r.length - 1 && r[rStart] === 0) rStart++
  const rBytes = r.slice(rStart)

  let sStart = 0
  while (sStart < s.length - 1 && s[sStart] === 0) sStart++
  const sBytes = s.slice(sStart)

  const rNeedsPadding = (rBytes[0] & 0x80) !== 0
  const sNeedsPadding = (sBytes[0] & 0x80) !== 0

  const rLength = rBytes.length + (rNeedsPadding ? 1 : 0)
  const sLength = sBytes.length + (sNeedsPadding ? 1 : 0)

  const totalLength = 2 + rLength + 2 + sLength
  const der = new Uint8Array(2 + totalLength)

  let offset = 0
  der[offset++] = 0x30
  der[offset++] = totalLength
  der[offset++] = 0x02
  der[offset++] = rLength
  if (rNeedsPadding) der[offset++] = 0x00
  der.set(rBytes, offset)
  offset += rBytes.length
  der[offset++] = 0x02
  der[offset++] = sLength
  if (sNeedsPadding) der[offset++] = 0x00
  der.set(sBytes, offset)

  return der
}
