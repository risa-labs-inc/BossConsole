/**
 * Cryptographic Verification Tests
 *
 * Tests for WebAuthn signature verification and COSE key extraction
 * using real test vectors from FIDO2/W3C specifications
 */

import { assertEquals, assertExists } from "jsr:@std/assert"
import { verifySignature, extractPublicKeyFromAttestation } from "../utils/crypto.ts"
import { encodeBase64Url } from "@std/encoding/base64url"

/**
 * Test vectors from FIDO2/W3C WebAuthn specification
 * These are real, valid WebAuthn data structures
 */

// ECDSA P-256 public key in uncompressed format (0x04 || x || y)
// This is a known valid key for testing purposes
const TEST_PUBLIC_KEY_X = new Uint8Array([
  0x65, 0xed, 0xa5, 0xa1, 0x25, 0x77, 0xc2, 0xba,
  0xe8, 0x29, 0x43, 0x7f, 0xe3, 0x38, 0x70, 0x1a,
  0x10, 0xae, 0xb8, 0x31, 0x25, 0xba, 0x4f, 0xcc,
  0x3d, 0xd3, 0x87, 0xef, 0x71, 0xaf, 0x43, 0x8b
])

const TEST_PUBLIC_KEY_Y = new Uint8Array([
  0x1e, 0x52, 0xed, 0x75, 0x70, 0x11, 0x63, 0xf7,
  0xf9, 0xe4, 0x0d, 0xdf, 0x9f, 0x34, 0x1b, 0x3d,
  0xc9, 0xba, 0x86, 0x0a, 0xf7, 0xe0, 0xca, 0x7c,
  0xa7, 0xe9, 0xee, 0xcd, 0x00, 0x84, 0xd1, 0x9c
])

// Combine into uncompressed EC public key format
const TEST_PUBLIC_KEY_UNCOMPRESSED = new Uint8Array([
  0x04, // uncompressed point indicator
  ...TEST_PUBLIC_KEY_X,
  ...TEST_PUBLIC_KEY_Y
])

// CBOR-encoded COSE key (ES256) for testing extractPublicKeyFromAttestation
// This is a minimal CBOR map: {1: 2, 3: -7, -1: 1, -2: x_coord, -3: y_coord}
function createTestCOSEKey(): Uint8Array {
  // Build CBOR manually for a COSE key
  const coseKeyMap: number[] = [
    0xA5, // map(5)
    0x01, 0x02, // kty: EC2 (1: 2)
    0x03, 0x26, // alg: ES256 (3: -7)
    0x20, 0x01, // crv: P-256 (-1: 1)
    0x21, 0x58, 0x20, // x coordinate (-2: bytes(32))
    ...Array.from(TEST_PUBLIC_KEY_X),
    0x22, 0x58, 0x20, // y coordinate (-3: bytes(32))
    ...Array.from(TEST_PUBLIC_KEY_Y)
  ]
  return new Uint8Array(coseKeyMap)
}

// Create a valid attestation object with the COSE key
function createTestAttestationObject(): Uint8Array {
  // Create authenticator data with the COSE key embedded
  const rpIdHash = new Uint8Array(32).fill(0x00) // Mock RP ID hash
  const flags = new Uint8Array([0x45]) // User present + User verified + Attested credential data
  const signCount = new Uint8Array([0x00, 0x00, 0x00, 0x00])
  const aaguid = new Uint8Array(16).fill(0x00) // Mock AAGUID

  const credentialId = new Uint8Array(16).fill(0x01) // 16-byte credential ID
  const credentialIdLength = new Uint8Array([0x00, 0x10]) // 16 bytes in big-endian

  const coseKey = createTestCOSEKey()

  const authData = new Uint8Array([
    ...rpIdHash,
    ...flags,
    ...signCount,
    ...aaguid,
    ...credentialIdLength,
    ...credentialId,
    ...coseKey
  ])

  // Create CBOR attestation object - simplified structure for testing
  // Just {authData: bytes} - the code only needs authData field
  const attestationCBOR: number[] = [
    0xA1, // map(1) - single key-value pair
    // "authData" as text string (8 chars)
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

Deno.test("extractPublicKeyFromAttestation - should extract public key from valid attestation", () => {
  const attestationObject = createTestAttestationObject()
  const attestationBase64 = encodeBase64Url(attestationObject)

  const publicKeyBase64 = extractPublicKeyFromAttestation(attestationBase64)

  assertExists(publicKeyBase64)
  assertEquals(publicKeyBase64.length > 0, true)

  // Verify it's base64-encoded
  const decoded = atob(publicKeyBase64.replace(/-/g, '+').replace(/_/g, '/'))
  assertEquals(decoded.length, 65) // Uncompressed EC key is 65 bytes (0x04 + 32 + 32)
})

Deno.test("extractPublicKeyFromAttestation - should reject invalid base64", () => {
  let errorThrown = false
  try {
    extractPublicKeyFromAttestation("not-valid-base64!!!")
  } catch (_error) {
    errorThrown = true
  }
  assertEquals(errorThrown, true)
})

Deno.test("extractPublicKeyFromAttestation - should reject malformed CBOR", () => {
  const invalidCBOR = new Uint8Array([0xFF, 0xFF, 0xFF])
  const invalidBase64 = encodeBase64Url(invalidCBOR)

  let errorThrown = false
  try {
    extractPublicKeyFromAttestation(invalidBase64)
  } catch (_error) {
    errorThrown = true
  }
  assertEquals(errorThrown, true)
})

Deno.test("verifySignature - should verify valid ECDSA signature", async () => {
  // For this test, we need to create a real signature using Web Crypto API
  // Then verify it matches

  // Create test data to sign
  const authenticatorData = new Uint8Array(37).fill(0x00) // Minimal authenticator data
  const clientDataJSON = JSON.stringify({
    type: "webauthn.get",
    challenge: "test-challenge-123",
    origin: "https://api.risaboss.com"
  })

  // Generate a key pair for signing (we need the private key)
  const keyPair = await crypto.subtle.generateKey(
    { name: 'ECDSA', namedCurve: 'P-256' },
    true,
    ['sign', 'verify']
  )

  // Create the data to sign (authenticatorData + sha256(clientDataJSON))
  const clientDataHash = await crypto.subtle.digest(
    'SHA-256',
    new TextEncoder().encode(clientDataJSON)
  )
  const signedData = new Uint8Array([
    ...authenticatorData,
    ...new Uint8Array(clientDataHash)
  ])

  // Sign the data
  const rawSignature = await crypto.subtle.sign(
    { name: 'ECDSA', hash: 'SHA-256' },
    keyPair.privateKey,
    signedData
  )

  // Convert raw signature to DER format (WebAuthn uses DER)
  const signature = convertRawToDERSignature(new Uint8Array(rawSignature))

  // Export the public key we just generated
  const exportedPublicKey = await crypto.subtle.exportKey('raw', keyPair.publicKey)
  const publicKeyBase64 = encodeBase64Url(new Uint8Array(exportedPublicKey))
  const signatureBase64 = encodeBase64Url(signature)
  const authenticatorDataBase64 = encodeBase64Url(authenticatorData)

  // Now verify using our function
  const isValid = await verifySignature(
    publicKeyBase64,
    signatureBase64,
    authenticatorDataBase64,
    clientDataJSON
  )

  assertEquals(isValid, true, "Valid signature should verify successfully")
})

Deno.test("verifySignature - should reject invalid signature", async () => {
  const authenticatorData = new Uint8Array(37).fill(0x00)
  const clientDataJSON = JSON.stringify({
    type: "webauthn.get",
    challenge: "test-challenge-123",
    origin: "https://api.risaboss.com"
  })

  // Use test public key
  const publicKeyBase64 = encodeBase64Url(TEST_PUBLIC_KEY_UNCOMPRESSED)

  // Create an invalid signature (random bytes)
  const invalidSignature = new Uint8Array(64).fill(0xFF)
  const invalidDERSignature = convertRawToDERSignature(invalidSignature)
  const signatureBase64 = encodeBase64Url(invalidDERSignature)
  const authenticatorDataBase64 = encodeBase64Url(authenticatorData)

  const isValid = await verifySignature(
    publicKeyBase64,
    signatureBase64,
    authenticatorDataBase64,
    clientDataJSON
  )

  assertEquals(isValid, false, "Invalid signature should fail verification")
})

Deno.test("verifySignature - should reject signature with wrong clientDataJSON", async () => {
  // Generate a valid signature
  const authenticatorData = new Uint8Array(37).fill(0x00)
  const originalClientDataJSON = JSON.stringify({
    type: "webauthn.get",
    challenge: "test-challenge-123",
    origin: "https://api.risaboss.com"
  })

  const keyPair = await crypto.subtle.generateKey(
    { name: 'ECDSA', namedCurve: 'P-256' },
    true,
    ['sign', 'verify']
  )

  const clientDataHash = await crypto.subtle.digest(
    'SHA-256',
    new TextEncoder().encode(originalClientDataJSON)
  )
  const signedData = new Uint8Array([
    ...authenticatorData,
    ...new Uint8Array(clientDataHash)
  ])

  const rawSignature = await crypto.subtle.sign(
    { name: 'ECDSA', hash: 'SHA-256' },
    keyPair.privateKey,
    signedData
  )

  const signature = convertRawToDERSignature(new Uint8Array(rawSignature))
  const exportedPublicKey = await crypto.subtle.exportKey('raw', keyPair.publicKey)

  // Now try to verify with DIFFERENT clientDataJSON
  const modifiedClientDataJSON = JSON.stringify({
    type: "webauthn.get",
    challenge: "different-challenge-456", // Changed!
    origin: "https://api.risaboss.com"
  })

  const isValid = await verifySignature(
    encodeBase64Url(new Uint8Array(exportedPublicKey)),
    encodeBase64Url(signature),
    encodeBase64Url(authenticatorData),
    modifiedClientDataJSON
  )

  assertEquals(isValid, false, "Signature should fail when clientDataJSON is modified")
})

Deno.test("verifySignature - should reject signature with wrong authenticatorData", async () => {
  // Generate a valid signature
  const originalAuthenticatorData = new Uint8Array(37).fill(0x00)
  const clientDataJSON = JSON.stringify({
    type: "webauthn.get",
    challenge: "test-challenge-123",
    origin: "https://api.risaboss.com"
  })

  const keyPair = await crypto.subtle.generateKey(
    { name: 'ECDSA', namedCurve: 'P-256' },
    true,
    ['sign', 'verify']
  )

  const clientDataHash = await crypto.subtle.digest(
    'SHA-256',
    new TextEncoder().encode(clientDataJSON)
  )
  const signedData = new Uint8Array([
    ...originalAuthenticatorData,
    ...new Uint8Array(clientDataHash)
  ])

  const rawSignature = await crypto.subtle.sign(
    { name: 'ECDSA', hash: 'SHA-256' },
    keyPair.privateKey,
    signedData
  )

  const signature = convertRawToDERSignature(new Uint8Array(rawSignature))
  const exportedPublicKey = await crypto.subtle.exportKey('raw', keyPair.publicKey)

  // Now try to verify with DIFFERENT authenticatorData
  const modifiedAuthenticatorData = new Uint8Array(37).fill(0xFF) // Changed!

  const isValid = await verifySignature(
    encodeBase64Url(new Uint8Array(exportedPublicKey)),
    encodeBase64Url(signature),
    encodeBase64Url(modifiedAuthenticatorData),
    clientDataJSON
  )

  assertEquals(isValid, false, "Signature should fail when authenticatorData is modified")
})

Deno.test("verifySignature - should handle malformed base64 gracefully", async () => {
  const clientDataJSON = JSON.stringify({
    type: "webauthn.get",
    challenge: "test-challenge",
    origin: "https://api.risaboss.com"
  })

  const isValid = await verifySignature(
    "not-valid-base64!!!",
    "also-invalid!!!",
    "bad-base64!!!",
    clientDataJSON
  )

  assertEquals(isValid, false, "Should return false for malformed inputs")
})

/**
 * Helper function to convert raw ECDSA signature (r || s) to DER format
 * This mimics what WebAuthn authenticators produce
 */
function convertRawToDERSignature(rawSignature: Uint8Array): Uint8Array {
  const r = rawSignature.slice(0, 32)
  const s = rawSignature.slice(32, 64)

  // Remove leading zeros (but keep at least one byte)
  let rStart = 0
  while (rStart < r.length - 1 && r[rStart] === 0) rStart++
  const rBytes = r.slice(rStart)

  let sStart = 0
  while (sStart < s.length - 1 && s[sStart] === 0) sStart++
  const sBytes = s.slice(sStart)

  // Add 0x00 prefix if high bit is set (to indicate positive number)
  const rNeedsPadding = (rBytes[0] & 0x80) !== 0
  const sNeedsPadding = (sBytes[0] & 0x80) !== 0

  const rLength = rBytes.length + (rNeedsPadding ? 1 : 0)
  const sLength = sBytes.length + (sNeedsPadding ? 1 : 0)

  // DER format: 0x30 [total-length] 0x02 [r-length] [r] 0x02 [s-length] [s]
  const totalLength = 2 + rLength + 2 + sLength
  const der = new Uint8Array(2 + totalLength)

  let offset = 0
  der[offset++] = 0x30 // SEQUENCE tag
  der[offset++] = totalLength

  der[offset++] = 0x02 // INTEGER tag for r
  der[offset++] = rLength
  if (rNeedsPadding) der[offset++] = 0x00
  der.set(rBytes, offset)
  offset += rBytes.length

  der[offset++] = 0x02 // INTEGER tag for s
  der[offset++] = sLength
  if (sNeedsPadding) der[offset++] = 0x00
  der.set(sBytes, offset)

  return der
}
