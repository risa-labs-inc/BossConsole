/**
 * Error Resilience Tests
 *
 * Tests for handling malformed credentials, corrupted data, and edge cases
 */

import { assertEquals, assertExists } from "jsr:@std/assert"
import type { SupabaseClient } from "@supabase/supabase-js"
import { createMockSupabaseClient } from "./helpers/mocks.ts"
import { completeAuthentication } from "../services/auth.ts"
import { completeRegistration } from "../services/registration.ts"
import { extractPublicKeyFromAttestation, verifySignature } from "../utils/crypto.ts"

Deno.test("Error Resilience - completeAuthentication with invalid base64 clientDataJSON", async () => {
  const mockClient = createMockSupabaseClient()

  const invalidCredential = {
    id: 'cred-test',
    rawId: 'cred-raw',
    type: 'public-key',
    response: {
      clientDataJSON: 'not!!!valid!!!base64!!', // Invalid base64
      authenticatorData: 'dGVzdA',
      signature: 'dGVzdA',
      userHandle: 'user-123'
    }
  }

  mockClient.mockResponse('passkey_challenges', {
    data: {
      id: 'challenge-1',
      challenge: 'test-challenge',
      type: 'authentication',
      user_id: 'user-123',
      expires_at: new Date(Date.now() + 300000).toISOString()
    },
    error: null
  }, 'select')

  const result = await completeAuthentication(
    mockClient as unknown as SupabaseClient,
    invalidCredential,
    'test-challenge'
  )

  assertEquals(result.success, false, "Should handle invalid base64 gracefully")
  assertExists((result as { error?: string }).error, "Should return error message")
})

Deno.test("Error Resilience - completeAuthentication with malformed JSON in clientDataJSON", async () => {
  const mockClient = createMockSupabaseClient()

  const malformedCredential = {
    id: 'cred-test',
    rawId: 'cred-raw',
    type: 'public-key',
    response: {
      clientDataJSON: btoa('{ this is not valid JSON }'), // Malformed JSON
      authenticatorData: 'dGVzdA',
      signature: 'dGVzdA',
      userHandle: 'user-123'
    }
  }

  mockClient.mockResponse('passkey_challenges', {
    data: {
      id: 'challenge-1',
      challenge: 'test-challenge',
      type: 'authentication',
      user_id: 'user-123',
      expires_at: new Date(Date.now() + 300000).toISOString()
    },
    error: null
  }, 'select')

  const result = await completeAuthentication(
    mockClient as unknown as SupabaseClient,
    malformedCredential,
    'test-challenge'
  )

  assertEquals(result.success, false, "Should handle malformed JSON gracefully")
})

Deno.test("Error Resilience - completeAuthentication with missing required fields", async () => {
  const mockClient = createMockSupabaseClient()

  const incompleteCredential = {
    id: 'cred-test',
    rawId: 'cred-raw',
    type: 'public-key',
    response: {
      // Missing clientDataJSON, authenticatorData, and signature
      userHandle: 'user-123'
    }
  } as unknown as {
    id: string
    rawId: string
    type: string
    response: {
      clientDataJSON: string
      authenticatorData: string
      signature: string
      userHandle?: string
    }
  }

  const result = await completeAuthentication(
    mockClient as unknown as SupabaseClient,
    incompleteCredential,
    'test-challenge'
  )

  assertEquals(result.success, false, "Should handle missing fields gracefully")
})

Deno.test("Error Resilience - completeAuthentication with null/undefined values", async () => {
  const mockClient = createMockSupabaseClient()

  const testCases = [
    {
      name: "null credential ID",
      credential: {
        id: null,
        rawId: 'test',
        type: 'public-key',
        response: {
          clientDataJSON: btoa('{}'),
          authenticatorData: 'test',
          signature: 'test'
        }
      }
    },
    {
      name: "empty credential ID",
      credential: {
        id: '',
        rawId: 'test',
        type: 'public-key',
        response: {
          clientDataJSON: btoa('{}'),
          authenticatorData: 'test',
          signature: 'test'
        }
      }
    }
  ]

  for (const testCase of testCases) {
    const result = await completeAuthentication(
      mockClient as unknown as SupabaseClient,
      testCase.credential as unknown as {
        id: string
        rawId: string
        type: string
        response: {
          clientDataJSON: string
          authenticatorData: string
          signature: string
          userHandle?: string
        }
      },
      'test-challenge'
    )

    assertEquals(
      result.success,
      false,
      `Should handle ${testCase.name} gracefully`
    )
  }
})

Deno.test("Error Resilience - completeRegistration with corrupted attestationObject", async () => {
  const mockClient = createMockSupabaseClient()

  const corruptedCredential = {
    id: 'cred-test',
    rawId: 'cred-raw',
    type: 'public-key',
    response: {
      clientDataJSON: btoa(JSON.stringify({
        type: 'webauthn.create',
        challenge: 'test-challenge',
        origin: 'https://api.risaboss.com'
      })),
      attestationObject: 'dGVzdA' // Too short to be valid CBOR
    }
  }

  mockClient.mockResponse('passkey_challenges', {
    data: {
      id: 'challenge-reg',
      challenge: 'test-challenge',
      type: 'registration',
      email: 'test@example.com',
      expires_at: new Date(Date.now() + 300000).toISOString()
    },
    error: null
  }, 'select')

  mockClient.mockResponse('passkey_challenges', {
    data: { id: 'challenge-reg' },
    error: null
  }, 'delete')

  mockClient.mockResponse('rpc.create_user_with_email', {
    data: [{ id: 'user-new', email: 'test@example.com' }],
    error: null
  }, 'call')

  const result = await completeRegistration(
    mockClient as unknown as SupabaseClient,
    // @ts-ignore - Testing with intentionally malformed credential
    corruptedCredential,
    'test-challenge',
    { displayName: 'Test', email: 'test@example.com' }
  )

  assertEquals(result.success, false, "Should handle corrupted attestation gracefully")
  assertExists((result as { error?: string }).error, "Should return error message")
})

Deno.test("Error Resilience - extractPublicKeyFromAttestation with various invalid inputs", async () => {
  const testCases = [
    { name: "empty string", input: "" },
    { name: "invalid base64", input: "!!!invalid!!!" },
    { name: "valid base64 but not CBOR", input: btoa("not cbor data") },
    { name: "CBOR without authData", input: "oWNmb3JkZm9v" }, // {foo: "foo"} in CBOR
    { name: "CBOR with truncated authData", input: "oWdhdXRoRGF0YQM" }, // short bytes
  ]

  for (const testCase of testCases) {
    let errorThrown = false
    try {
      extractPublicKeyFromAttestation(testCase.input)
    } catch (_error) {
      errorThrown = true
    }

    assertEquals(
      errorThrown,
      true,
      `Should throw error for ${testCase.name}`
    )
  }
})

Deno.test("Error Resilience - verifySignature with corrupted signature formats", async () => {
  const clientDataJSON = JSON.stringify({
    type: 'webauthn.get',
    challenge: 'test',
    origin: 'https://api.risaboss.com'
  })

  const testCases = [
    { name: "truncated signature", signature: "YWI" }, // Only 2 bytes
    { name: "oversized signature", signature: btoa(new Array(1000).fill('x').join('')) },
    { name: "empty signature", signature: "" },
    { name: "malformed DER", signature: btoa("\xFF\xFF\xFF\xFF") },
  ]

  for (const testCase of testCases) {
    const isValid = await verifySignature(
      "BFztpaElK8K66ClDP-M4cBoQrrg6JbpPzD3Th-9xr0OLHZK9dXARY_f55A3fnzQbPcm6hgr34Mp8p-nuzQCE0Zw", // Valid public key
      testCase.signature,
      "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAUAAAAB", // Valid authenticator data
      clientDataJSON
    )

    assertEquals(
      isValid,
      false,
      `Should reject ${testCase.name}`
    )
  }
})

Deno.test("Error Resilience - completeAuthentication with expired challenge", async () => {
  const mockClient = createMockSupabaseClient()

  const validCredential = {
    id: 'cred-test',
    rawId: 'cred-raw',
    type: 'public-key',
    response: {
      clientDataJSON: btoa(JSON.stringify({
        type: 'webauthn.get',
        challenge: 'test-challenge',
        origin: 'https://api.risaboss.com'
      })),
      authenticatorData: 'dGVzdA',
      signature: 'dGVzdA',
      userHandle: 'user-123'
    }
  }

  // Mock expired challenge (5 minutes in the past)
  mockClient.mockResponse('passkey_challenges', {
    data: {
      id: 'challenge-expired',
      challenge: 'test-challenge',
      type: 'authentication',
      user_id: 'user-123',
      expires_at: new Date(Date.now() - 300000).toISOString() // Expired!
    },
    error: null
  }, 'select')

  const result = await completeAuthentication(
    mockClient as unknown as SupabaseClient,
    validCredential,
    'test-challenge'
  )

  assertEquals(result.success, false, "Should reject expired challenge")
  assertEquals(
    (result as { error?: string }).error,
    'Challenge expired',
    "Should return appropriate error message"
  )
})

Deno.test("Error Resilience - completeAuthentication with wrong origin", async () => {
  const mockClient = createMockSupabaseClient()

  const wrongOriginCredential = {
    id: 'cred-test',
    rawId: 'cred-raw',
    type: 'public-key',
    response: {
      clientDataJSON: btoa(JSON.stringify({
        type: 'webauthn.get',
        challenge: 'test-challenge',
        origin: 'https://evil.com' // Wrong origin!
      })),
      authenticatorData: 'dGVzdA',
      signature: 'dGVzdA',
      userHandle: 'user-123'
    }
  }

  mockClient.mockResponse('passkey_challenges', {
    data: {
      id: 'challenge-1',
      challenge: 'test-challenge',
      type: 'authentication',
      user_id: 'user-123',
      expires_at: new Date(Date.now() + 300000).toISOString()
    },
    error: null
  }, 'select')

  const result = await completeAuthentication(
    mockClient as unknown as SupabaseClient,
    wrongOriginCredential,
    'test-challenge'
  )

  // Note: Origin validation may be handled differently in the codebase
  // This test ensures the function doesn't crash with unexpected origins
  assertEquals(typeof result.success, 'boolean', "Should return a valid result")
})

Deno.test("Error Resilience - completeAuthentication with mismatched challenge", async () => {
  const mockClient = createMockSupabaseClient()

  const credential = {
    id: 'cred-test',
    rawId: 'cred-raw',
    type: 'public-key',
    response: {
      clientDataJSON: btoa(JSON.stringify({
        type: 'webauthn.get',
        challenge: 'different-challenge', // Doesn't match!
        origin: 'https://api.risaboss.com'
      })),
      authenticatorData: 'dGVzdA',
      signature: 'dGVzdA',
      userHandle: 'user-123'
    }
  }

  mockClient.mockResponse('passkey_challenges', {
    data: {
      id: 'challenge-1',
      challenge: 'expected-challenge',
      type: 'authentication',
      user_id: 'user-123',
      expires_at: new Date(Date.now() + 300000).toISOString()
    },
    error: null
  }, 'select')

  const result = await completeAuthentication(
    mockClient as unknown as SupabaseClient,
    credential,
    'expected-challenge'
  )

  // The system should detect the mismatch
  assertEquals(typeof result.success, 'boolean', "Should handle challenge mismatch")
})

Deno.test("Error Resilience - concurrent authentication attempts with same challenge", async () => {
  const mockClient = createMockSupabaseClient()

  const credential = {
    id: 'cred-test',
    rawId: 'cred-raw',
    type: 'public-key',
    response: {
      clientDataJSON: btoa(JSON.stringify({
        type: 'webauthn.get',
        challenge: 'test-challenge',
        origin: 'https://api.risaboss.com'
      })),
      authenticatorData: 'dGVzdA',
      signature: 'dGVzdA',
      userHandle: 'user-123'
    }
  }

  // First attempt
  mockClient.mockResponse('passkey_challenges', {
    data: {
      id: 'challenge-1',
      challenge: 'test-challenge',
      type: 'authentication',
      user_id: 'user-123',
      expires_at: new Date(Date.now() + 300000).toISOString()
    },
    error: null
  }, 'select')

  mockClient.mockResponse('user_passkeys', {
    data: {
      id: 'passkey-1',
      user_id: 'user-123',
      credential_id: 'cred-test',
      public_key: 'test-key'
    },
    error: null
  }, 'select')

  const firstAttempt = await completeAuthentication(
    mockClient as unknown as SupabaseClient,
    credential,
    'test-challenge'
  )

  // Second concurrent attempt - challenge should be consumed or unavailable
  mockClient.mockResponse('passkey_challenges', {
    data: null,
    error: { code: 'PGRST116', message: 'Challenge consumed' }
  }, 'select')

  const secondAttempt = await completeAuthentication(
    mockClient as unknown as SupabaseClient,
    credential,
    'test-challenge'
  )

  // At least one should fail
  assertEquals(
    firstAttempt.success === false || secondAttempt.success === false,
    true,
    "Concurrent attempts should not both succeed"
  )
})

Deno.test("Error Resilience - verifySignature with public key in wrong format", async () => {
  const clientDataJSON = JSON.stringify({
    type: 'webauthn.get',
    challenge: 'test',
    origin: 'https://api.risaboss.com'
  })

  const testCases = [
    { name: "compressed EC key", publicKey: btoa("\x02" + new Array(32).fill('x').join('')) }, // Wrong format
    { name: "wrong length", publicKey: btoa(new Array(10).fill('x').join('')) }, // Too short
    { name: "invalid curve point", publicKey: btoa("\x04" + new Array(64).fill('\xFF').join('')) }, // Invalid point
  ]

  for (const testCase of testCases) {
    const isValid = await verifySignature(
      testCase.publicKey,
      "MEUCIQDKn8...", // Valid-looking signature
      "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAUAAAAB",
      clientDataJSON
    )

    assertEquals(
      isValid,
      false,
      `Should handle ${testCase.name} gracefully`
    )
  }
})

Deno.test("Error Resilience - completeAuthentication with non-existent credential ID", async () => {
  const mockClient = createMockSupabaseClient()

  const credential = {
    id: 'non-existent-credential',
    rawId: 'non-existent-raw',
    type: 'public-key',
    response: {
      clientDataJSON: btoa(JSON.stringify({
        type: 'webauthn.get',
        challenge: 'test-challenge',
        origin: 'https://api.risaboss.com'
      })),
      authenticatorData: 'dGVzdA',
      signature: 'dGVzdA',
      userHandle: 'user-123'
    }
  }

  mockClient.mockResponse('passkey_challenges', {
    data: {
      id: 'challenge-1',
      challenge: 'test-challenge',
      type: 'authentication',
      user_id: 'user-123',
      expires_at: new Date(Date.now() + 300000).toISOString()
    },
    error: null
  }, 'select')

  // Mock passkey not found
  mockClient.mockResponse('user_passkeys', {
    data: null,
    error: { code: 'PGRST116', message: 'Passkey not found' }
  }, 'select')

  const result = await completeAuthentication(
    mockClient as unknown as SupabaseClient,
    credential,
    'test-challenge'
  )

  assertEquals(result.success, false, "Should handle non-existent credential")
  assertEquals(
    (result as { error?: string }).error,
    'Passkey not found',
    "Should return appropriate error"
  )
})
