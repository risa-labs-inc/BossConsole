import { authFailureDetails } from "./logging.ts"
/**
 * WebAuthn cryptography: attestation parsing and assertion signature verification.
 *
 * Two algorithms are supported, matching the `pubKeyCredParams` offered during
 * registration (see utils/webauthn.ts `SUPPORTED_COSE_ALGS`):
 * - ES256 (-7)  : stored as the raw 65-byte uncompressed EC point
 * - RS256 (-257): stored as SPKI DER
 *
 * Every transport-encoded input is decoded with `decodeBase64Any`, which accepts
 * base64 and base64url, padded or unpadded — see utils/base64.ts for why that
 * matters.
 */

import { asBufferSource, decodeBase64Any, encodeBase64UrlBytes } from "./base64.ts"
import {
  COSE_ALG_ES256,
  COSE_ALG_RS256,
  parseAuthenticatorData,
  sha256Bytes,
  type ParsedAuthenticatorData
} from "./webauthn.ts"

export interface AttestedCredential {
  /** Base64url public key: raw EC point for ES256, SPKI DER for RS256 */
  publicKey: string
  /** COSE algorithm identifier the credential will sign with */
  alg: number
  /** Initial signature counter reported by the authenticator */
  signCount: number
  /** SHA-256(rpId) as signed by the authenticator */
  rpIdHash: Uint8Array
  /** Credential id from the attested credential data, base64url */
  credentialId: string | null
  /** User Present flag from the attestation's authenticator data */
  userPresent: boolean
  /** User Verified flag from the attestation's authenticator data */
  userVerified: boolean
}

/**
 * Extracts the credential public key, algorithm, initial signature counter and
 * rpIdHash from an attestation object.
 */
export async function extractCredentialFromAttestation(
  attestationObject: string
): Promise<AttestedCredential> {
  try {
    const attestationBuffer = decodeBase64Any(attestationObject)
    const attestation = decodeCBOR(attestationBuffer) as Record<string, unknown>

    if (!attestation || typeof attestation !== 'object') {
      throw new Error('Invalid attestation object')
    }

    const authDataBytes = attestation.authData as Uint8Array
    if (!authDataBytes || !(authDataBytes instanceof Uint8Array)) {
      throw new Error('Missing authData in attestation')
    }

    // NOTE: the attestation *statement* is deliberately not verified. Ceremonies
    // request attestation: "none", so there is no trustworthy statement to check
    // and no attestation trust anchors are configured. `fmt` is logged when it is
    // anything else so that this stays a visible choice rather than an
    // assumption — do not read the presence of an attStmt as proof of anything.
    const fmt = attestation.fmt
    if (typeof fmt === 'string' && fmt !== 'none') {
      console.log(`ℹ️ Attestation statement present (fmt: ${fmt}) but not verified (attestation: "none")`)
    }

    const authData: ParsedAuthenticatorData = parseAuthenticatorData(authDataBytes)

    if (!authData.attestedCredentialData || !authData.credentialPublicKey) {
      throw new Error('Attestation is missing attested credential data')
    }

    const cose = decodeCBOR(authData.credentialPublicKey)
    const { publicKey, alg } = await coseKeyToStoredKey(cose)

    return {
      publicKey,
      alg,
      signCount: authData.signCount,
      rpIdHash: authData.rpIdHash,
      credentialId: authData.credentialId ? encodeBase64UrlBytes(authData.credentialId) : null,
      userPresent: authData.userPresent,
      userVerified: authData.userVerified
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Unknown error'
    console.error('Failed to extract credential from attestation:', message)
    throw new Error(`Failed to extract public key: ${message}`)
  }
}

/**
 * Extracts only the public key from an attestation object.
 *
 * Kept as a thin wrapper because callers/tests that predate algorithm support
 * use it; new code should prefer `extractCredentialFromAttestation`.
 */
export async function extractPublicKeyFromAttestation(attestationObject: string): Promise<string> {
  const credential = await extractCredentialFromAttestation(attestationObject)
  return credential.publicKey
}

/**
 * Converts a COSE_Key into the form we persist, per algorithm.
 */
async function coseKeyToStoredKey(coseKey: unknown): Promise<{ publicKey: string; alg: number }> {
  if (!coseKey || typeof coseKey !== 'object') {
    throw new Error('Invalid COSE public key')
  }

  const cose = coseKey as Record<number, unknown>
  const kty = cose[1]
  const alg = cose[3]

  if (typeof alg !== 'number') {
    throw new Error('COSE public key is missing an algorithm')
  }

  // EC2 key type (2) — ES256
  if (kty === 2) {
    if (alg !== COSE_ALG_ES256) {
      throw new Error(`Unsupported EC algorithm: ${alg}`)
    }

    const x = cose[-2] as Uint8Array
    const y = cose[-3] as Uint8Array

    if (!x || !y || x.length !== 32 || y.length !== 32) {
      throw new Error('Missing or malformed x/y coordinate in EC public key')
    }

    const uncompressedKey = new Uint8Array(65)
    uncompressedKey[0] = 0x04 // Uncompressed point indicator
    uncompressedKey.set(x, 1)
    uncompressedKey.set(y, 33)

    return { publicKey: encodeBase64UrlBytes(uncompressedKey), alg: COSE_ALG_ES256 }
  }

  // RSA key type (3) — RS256
  if (kty === 3) {
    if (alg !== COSE_ALG_RS256) {
      throw new Error(`Unsupported RSA algorithm: ${alg}`)
    }

    const rawN = cose[-1] as Uint8Array
    const e = cose[-2] as Uint8Array

    if (!rawN || !e || rawN.length === 0 || e.length === 0) {
      throw new Error('Missing modulus or exponent in RSA public key')
    }

    // Some authenticators emit the modulus with a leading 0x00 sign byte. JWK
    // wants the unsigned big-endian integer, and the size check has to measure
    // the same bytes the key is built from — otherwise a 2048-bit key with a
    // sign byte measures 2056 and a 2040-bit one could pass as 2048.
    const n = rawN[0] === 0x00 ? rawN.slice(1) : rawN

    // Floor the modulus at 2048 bits. Nothing stops an authenticator (or a
    // native client speaking for one) from offering a short RSA key, and a
    // credential is long-lived once enrolled.
    if (n.length < 256) {
      throw new Error(`RSA modulus too small: ${n.length * 8} bits, minimum 2048`)
    }

    // Import the COSE parameters as a JWK, then export SPKI so verification only
    // ever has to deal with one stored representation.
    const jwkKey = await crypto.subtle.importKey(
      'jwk',
      {
        kty: 'RSA',
        n: encodeBase64UrlBytes(n),
        e: encodeBase64UrlBytes(e),
        alg: 'RS256',
        ext: true
      },
      { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
      true,
      ['verify']
    )

    const spki = new Uint8Array(await crypto.subtle.exportKey('spki', jwkKey))
    return { publicKey: encodeBase64UrlBytes(spki), alg: COSE_ALG_RS256 }
  }

  throw new Error(`Unsupported COSE key type: ${String(kty)}`)
}

/**
 * Verifies the signature of a WebAuthn authentication assertion.
 *
 * The signed payload is `authenticatorData || SHA-256(clientDataJSON)`.
 *
 * @param publicKeyBase64 stored public key (raw EC point for ES256, SPKI for RS256)
 * @param signatureBase64 assertion signature (DER for ES256, raw for RS256)
 * @param authenticatorDataBase64 authenticator data as sent by the client
 * @param clientData raw `clientDataJSON` bytes — a string is accepted and encoded
 *                   as UTF-8, but passing the original bytes is preferred so the
 *                   hash cannot drift from what was signed
 * @param alg COSE algorithm of the stored credential (defaults to ES256)
 */
export async function verifySignature(
  publicKeyBase64: string,
  signatureBase64: string,
  authenticatorDataBase64: string,
  clientData: string | Uint8Array,
  alg: number = COSE_ALG_ES256
): Promise<boolean> {
  console.log('🔐 Verifying assertion signature', { alg })

  try {
    const publicKeyBytes = decodeBase64Any(publicKeyBase64)
    const signatureBytes = decodeBase64Any(signatureBase64)
    const authenticatorData = decodeBase64Any(authenticatorDataBase64)
    const clientDataBytes = typeof clientData === 'string'
      ? new TextEncoder().encode(clientData)
      : clientData

    const clientDataHash = await sha256Bytes(clientDataBytes)

    // Create signed data (authenticatorData || clientDataHash)
    const signedData = new Uint8Array(authenticatorData.length + clientDataHash.length)
    signedData.set(authenticatorData, 0)
    signedData.set(clientDataHash, authenticatorData.length)

    if (alg === COSE_ALG_RS256) {
      const publicKey = await crypto.subtle.importKey(
        'spki',
        asBufferSource(publicKeyBytes),
        { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
        false,
        ['verify']
      )

      const isValid = await crypto.subtle.verify(
        { name: 'RSASSA-PKCS1-v1_5' },
        publicKey,
        asBufferSource(signatureBytes),
        asBufferSource(signedData)
      )

      console.log('Signature verification result:', isValid)
      return isValid
    }

    if (alg !== COSE_ALG_ES256) {
      console.error('Unsupported credential algorithm for verification:', alg)
      return false
    }

    const publicKey = await crypto.subtle.importKey(
      'raw',
      asBufferSource(publicKeyBytes),
      {
        name: 'ECDSA',
        namedCurve: 'P-256',
      },
      false,
      ['verify']
    )

    // WebAuthn ES256 signatures are DER-encoded; WebCrypto wants raw r || s
    const rawSignature = parseDERSignature(signatureBytes)

    const isValid = await crypto.subtle.verify(
      {
        name: 'ECDSA',
        hash: 'SHA-256',
      },
      publicKey,
      asBufferSource(rawSignature),
      asBufferSource(signedData)
    )

    console.log('Signature verification result:', isValid)
    return isValid
  } catch (error) {
    console.error('Signature verification error:', authFailureDetails(error))
    return false
  }
}

/**
 * Converts DER-encoded ECDSA signature to raw format (r || s)
 */
function parseDERSignature(derSignature: Uint8Array): Uint8Array {
  let offset = 0

  // Check SEQUENCE tag
  if (derSignature[offset++] !== 0x30) {
    throw new Error('Invalid DER signature: missing SEQUENCE tag')
  }

  // Validate the declared SEQUENCE length against what was actually sent. P-256
  // signatures are always short-form (< 128 bytes of content), so a long-form
  // length byte here is malformed rather than merely unexpected.
  const declaredLength = derSignature[offset++]
  if (declaredLength === undefined || declaredLength > 0x7f) {
    throw new Error('Invalid DER signature: unsupported SEQUENCE length encoding')
  }
  if (declaredLength !== derSignature.length - 2) {
    throw new Error('Invalid DER signature: SEQUENCE length does not match payload')
  }

  // Parse r
  if (derSignature[offset++] !== 0x02) {
    throw new Error('Invalid DER signature: missing INTEGER tag for r')
  }
  const rLength = derSignature[offset++]
  if (rLength === undefined || offset + rLength > derSignature.length) {
    throw new Error('Invalid DER signature: r overruns the payload')
  }
  const r = derSignature.slice(offset, offset + rLength)
  offset += rLength

  // Parse s
  if (derSignature[offset++] !== 0x02) {
    throw new Error('Invalid DER signature: missing INTEGER tag for s')
  }
  const sLength = derSignature[offset++]
  if (sLength === undefined || offset + sLength > derSignature.length) {
    throw new Error('Invalid DER signature: s overruns the payload')
  }
  const s = derSignature.slice(offset, offset + sLength)

  if (r.length === 0 || s.length === 0) {
    throw new Error('Invalid DER signature: empty r or s')
  }

  // Remove leading zeros (DER pads a positive INTEGER whose high bit is set)
  const rValue = stripLeadingZeros(r)
  const sValue = stripLeadingZeros(s)

  if (rValue.length > 32 || sValue.length > 32) {
    throw new Error('Invalid DER signature: r or s longer than 32 bytes')
  }

  // Pad to 32 bytes each
  const rPadded = new Uint8Array(32)
  const sPadded = new Uint8Array(32)
  rPadded.set(rValue, 32 - rValue.length)
  sPadded.set(sValue, 32 - sValue.length)

  // Combine r and s
  const rawSignature = new Uint8Array(64)
  rawSignature.set(rPadded, 0)
  rawSignature.set(sPadded, 32)

  return rawSignature
}

function stripLeadingZeros(value: Uint8Array): Uint8Array {
  let start = 0
  while (start < value.length - 1 && value[start] === 0) start++
  return value.slice(start)
}

/** Guards against a hostile attestation nesting maps/arrays without bound */
const CBOR_MAX_DEPTH = 16

/**
 * Simple CBOR decoder for WebAuthn attestation objects
 *
 * Trailing bytes after the top-level item are accepted **deliberately** — this is
 * required, not an oversight. The COSE key is read from
 * `authData.slice(offset)`, which carries whatever follows the credential public
 * key: with the ED flag set that is the CBOR extension-output map. Rejecting
 * trailing data would break every ceremony that requests an extension.
 */
function decodeCBOR(buffer: Uint8Array): unknown {
  let offset = 0

  function readByte(): number {
    if (offset >= buffer.length) {
      throw new Error('Unexpected end of CBOR input')
    }
    return buffer[offset++]
  }

  function readBytes(length: number): Uint8Array {
    if (!Number.isSafeInteger(length) || length < 0 || offset + length > buffer.length) {
      throw new Error('Unexpected end of CBOR input')
    }
    const result = buffer.slice(offset, offset + length)
    offset += length
    return result
  }

  function readUint16(): number {
    if (offset + 2 > buffer.length) {
      throw new Error('Unexpected end of CBOR input')
    }
    const value = (buffer[offset] << 8) | buffer[offset + 1]
    offset += 2
    return value
  }

  function readUint32(): number {
    if (offset + 4 > buffer.length) {
      throw new Error('Unexpected end of CBOR input')
    }
    // >>> 0 keeps values ≥ 2^31 unsigned: with `|` alone a huge declared length
    // goes negative, and a negative length silently yields an empty slice
    // instead of the error it should be.
    const value = ((buffer[offset] << 24) | (buffer[offset + 1] << 16) |
                  (buffer[offset + 2] << 8) | buffer[offset + 3]) >>> 0
    offset += 4
    return value
  }

  /** Reads a definite length, rejecting indefinite and 64-bit forms */
  function readLength(additionalInfo: number): number {
    if (additionalInfo < 24) return additionalInfo
    if (additionalInfo === 24) return readByte()
    if (additionalInfo === 25) return readUint16()
    if (additionalInfo === 26) return readUint32()
    throw new Error(`Unsupported CBOR length encoding: ${additionalInfo}`)
  }

  function decode(depth = 0): unknown {
    if (depth > CBOR_MAX_DEPTH) {
      throw new Error('CBOR nesting too deep')
    }
    const byte = readByte()
    const majorType = byte >> 5
    const additionalInfo = byte & 0x1f

    switch (majorType) {
      case 0: // Unsigned integer
        if (additionalInfo < 24) return additionalInfo
        if (additionalInfo === 24) return readByte()
        if (additionalInfo === 25) return readUint16()
        if (additionalInfo === 26) return readUint32()
        throw new Error('Unsupported integer size')

      case 1: // Negative integer
        if (additionalInfo < 24) return -1 - additionalInfo
        if (additionalInfo === 24) return -1 - readByte()
        if (additionalInfo === 25) return -1 - readUint16()
        if (additionalInfo === 26) return -1 - readUint32()
        throw new Error('Unsupported negative integer size')

      case 2: { // Byte string
        return readBytes(readLength(additionalInfo))
      }

      case 3: { // Text string
        return new TextDecoder().decode(readBytes(readLength(additionalInfo)))
      }

      case 4: { // Array
        const arrayLength = readLength(additionalInfo)
        // A declared length cannot exceed the bytes left to read, one per item
        if (arrayLength > buffer.length - offset) {
          throw new Error('Unexpected end of CBOR input')
        }
        const array: unknown[] = []
        for (let i = 0; i < arrayLength; i++) {
          array.push(decode(depth + 1))
        }
        return array
      }

      case 5: { // Map
        const mapLength = readLength(additionalInfo)
        // Each entry needs at least two bytes (key + value)
        if (mapLength * 2 > buffer.length - offset) {
          throw new Error('Unexpected end of CBOR input')
        }
        // Object.create(null): an attestation carrying a "__proto__" key would
        // otherwise set the prototype instead of a property
        const map: Record<string | number, unknown> = Object.create(null)
        for (let i = 0; i < mapLength; i++) {
          const key = decode(depth + 1)
          const value = decode(depth + 1)
          map[key as string | number] = value
        }
        return map
      }

      case 7: // Simple/Float
        if (additionalInfo === 20) return false
        if (additionalInfo === 21) return true
        if (additionalInfo === 22) return null
        if (additionalInfo === 23) return undefined
        throw new Error(`Unsupported simple value: ${additionalInfo}`)

      default:
        throw new Error(`Unsupported CBOR major type: ${majorType}`)
    }
  }

  return decode()
}

