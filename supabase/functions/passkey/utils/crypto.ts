import { decodeBase64Url } from "@std/encoding/base64url"

/**
 * Extracts the COSE public key from the attestation object
 */
export function extractPublicKeyFromAttestation(attestationObject: string): string {
  console.log('Extracting public key from attestation')

  try {
    const attestationBuffer = decodeBase64Url(attestationObject)
    const publicKey = parseCOSEPublicKey(attestationBuffer)

    console.log('Successfully extracted public key')
    return publicKey
  } catch (error) {
    console.error('Failed to extract public key:', error)
    throw new Error(`Failed to extract public key: ${error instanceof Error ? error.message : 'Unknown error'}`)
  }
}

/**
 * Parses a COSE public key from an attestation buffer
 */
function parseCOSEPublicKey(attestationBuffer: Uint8Array): string {
  try {
    const attestation = decodeCBOR(attestationBuffer) as Record<string, unknown>

    if (!attestation || typeof attestation !== 'object') {
      throw new Error('Invalid attestation object')
    }

    const authData = attestation.authData as Uint8Array
    if (!authData) {
      throw new Error('Missing authData in attestation')
    }

    // Skip rpIdHash (32 bytes) + flags (1 byte) + signCount (4 bytes)
    let offset = 37

    // Skip AAGUID (16 bytes)
    offset += 16

    // Read credential ID length (2 bytes, big-endian)
    const credIdLength = (authData[offset] << 8) | authData[offset + 1]
    offset += 2

    // Skip credential ID
    offset += credIdLength

    // Extract COSE key (remaining bytes)
    const coseKey = authData.slice(offset)
    const publicKey = decodeCBOR(coseKey)

    if (!publicKey || typeof publicKey !== 'object') {
      throw new Error('Invalid COSE public key')
    }

    // Extract x and y coordinates for ES256 key
    const x = (publicKey as Record<number, unknown>)[-2] as Uint8Array
    const y = (publicKey as Record<number, unknown>)[-3] as Uint8Array

    if (!x || !y) {
      throw new Error('Missing x or y coordinate in public key')
    }

    // Combine x and y into uncompressed EC public key format
    const uncompressedKey = new Uint8Array(65)
    uncompressedKey[0] = 0x04 // Uncompressed point indicator
    uncompressedKey.set(x, 1)
    uncompressedKey.set(y, 33)

    return bufferToBase64(uncompressedKey)
  } catch (error) {
    console.error('COSE parsing error:', error)
    throw new Error(`Failed to parse COSE key: ${error instanceof Error ? error.message : 'Unknown error'}`)
  }
}

/**
 * Verifies the signature of a WebAuthn authentication assertion
 */
export async function verifySignature(
  publicKeyBase64: string,
  signatureBase64: string,
  authenticatorDataBase64: string,
  clientDataJSON: string
): Promise<boolean> {
  console.log('🔐 Verifying signature')
  console.log('🔐 Public key (base64):', publicKeyBase64.substring(0, 50) + '...')
  console.log('🔐 Signature (base64):', signatureBase64.substring(0, 50) + '...')
  console.log('🔐 Authenticator data (base64):', authenticatorDataBase64.substring(0, 50) + '...')
  console.log('🔐 Client data JSON:', clientDataJSON)

  try {
    // Decode inputs
    const publicKeyBytes = decodeBase64Url(publicKeyBase64)
    console.log('🔐 Public key bytes length:', publicKeyBytes.length)
    const signatureBytes = decodeBase64Url(signatureBase64)
    console.log('🔐 Signature bytes length:', signatureBytes.length)
    const authenticatorData = decodeBase64Url(authenticatorDataBase64)
    console.log('🔐 Authenticator data bytes length:', authenticatorData.length)
    const clientDataHashBuffer = await sha256(new TextEncoder().encode(clientDataJSON))
    const clientDataHash = new Uint8Array(clientDataHashBuffer)
    console.log('🔐 Client data hash length:', clientDataHash.length)

    // Create signed data (authenticatorData + clientDataHash)
    const signedData = new Uint8Array(authenticatorData.length + clientDataHash.length)
    signedData.set(authenticatorData, 0)
    signedData.set(clientDataHash, authenticatorData.length)

    // Import public key
    const publicKey = await crypto.subtle.importKey(
      'raw',
      publicKeyBytes,
      {
        name: 'ECDSA',
        namedCurve: 'P-256',
      },
      false,
      ['verify']
    )

    // Parse DER signature to raw format
    const rawSignature = parseDERSignature(signatureBytes)

    // Verify signature
    const isValid = await crypto.subtle.verify(
      {
        name: 'ECDSA',
        hash: 'SHA-256',
      },
      publicKey,
      rawSignature,
      signedData
    )

    console.log('Signature verification result:', isValid)
    return isValid
  } catch (error) {
    console.error('Signature verification error:', error)
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

  // Skip length
  offset++

  // Parse r
  if (derSignature[offset++] !== 0x02) {
    throw new Error('Invalid DER signature: missing INTEGER tag for r')
  }
  const rLength = derSignature[offset++]
  const r = derSignature.slice(offset, offset + rLength)
  offset += rLength

  // Parse s
  if (derSignature[offset++] !== 0x02) {
    throw new Error('Invalid DER signature: missing INTEGER tag for s')
  }
  const sLength = derSignature[offset++]
  const s = derSignature.slice(offset, offset + sLength)

  // Remove leading zeros if present
  const rValue = r[0] === 0 ? r.slice(1) : r
  const sValue = s[0] === 0 ? s.slice(1) : s

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

/**
 * Simple CBOR decoder for WebAuthn attestation objects
 */
function decodeCBOR(buffer: Uint8Array): unknown {
  let offset = 0

  function readByte(): number {
    return buffer[offset++]
  }

  function readBytes(length: number): Uint8Array {
    const result = buffer.slice(offset, offset + length)
    offset += length
    return result
  }

  function readUint16(): number {
    const value = (buffer[offset] << 8) | buffer[offset + 1]
    offset += 2
    return value
  }

  function readUint32(): number {
    const value = (buffer[offset] << 24) | (buffer[offset + 1] << 16) |
                  (buffer[offset + 2] << 8) | buffer[offset + 3]
    offset += 4
    return value
  }

  function decode(): unknown {
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
        const byteLength = additionalInfo < 24 ? additionalInfo :
                          additionalInfo === 24 ? readByte() :
                          additionalInfo === 25 ? readUint16() :
                          readUint32()
        return readBytes(byteLength)
      }

      case 3: { // Text string
        const textLength = additionalInfo < 24 ? additionalInfo :
                          additionalInfo === 24 ? readByte() :
                          additionalInfo === 25 ? readUint16() :
                          readUint32()
        return new TextDecoder().decode(readBytes(textLength))
      }

      case 4: { // Array
        const arrayLength = additionalInfo < 24 ? additionalInfo :
                           additionalInfo === 24 ? readByte() :
                           additionalInfo === 25 ? readUint16() :
                           readUint32()
        const array: unknown[] = []
        for (let i = 0; i < arrayLength; i++) {
          array.push(decode())
        }
        return array
      }

      case 5: { // Map
        const mapLength = additionalInfo < 24 ? additionalInfo :
                         additionalInfo === 24 ? readByte() :
                         additionalInfo === 25 ? readUint16() :
                         readUint32()
        const map: Record<string | number, unknown> = {}
        for (let i = 0; i < mapLength; i++) {
          const key = decode()
          const value = decode()
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

/**
 * Computes SHA-256 hash of input data
 */
async function sha256(data: Uint8Array): Promise<ArrayBuffer> {
  return await crypto.subtle.digest('SHA-256', data)
}

/**
 * Converts a buffer to base64url encoding
 */
function bufferToBase64(buffer: Uint8Array): string {
  const binary = String.fromCharCode(...Array.from(buffer))
  return btoa(binary)
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=/g, '')
}
