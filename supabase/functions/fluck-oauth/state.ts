/**
 * The signed `state` that travels through Google's consent screen.
 *
 * ## Why a JWT and not an opaque id
 *
 * The party that STARTS the sign in (the Fluck plugin, on someone's BOSS) and the party that
 * FINISHES it (this function) share no database and no session. A random id would therefore
 * have to be written somewhere both can read before the link is even texted, which is a round
 * trip on the one path that has to be fast and cannot fail. A signed token carries the whole
 * binding with it: the workspace, the BOSS user the grant belongs to, the connector, and a
 * one-time nonce, all covered by an HMAC that only the two ends can produce.
 *
 * ## The wire format is a contract, not an implementation detail
 *
 * The Kotlin side mints these with `bridge/connect/StateJwt.kt` in the fluck-agent-imessage
 * repo. Both sides assert the SAME fixture (`tests/fluck-oauth-state.fixture.json`, copied
 * into that repo's test resources), so a change to the header bytes, the claim order or the
 * key derivation fails in both suites rather than silently refusing every callback in
 * production. If you change anything here, regenerate the fixture and change it there too.
 *
 * Format:
 * - header, byte for byte: `{"alg":"HS256","typ":"JWT"}`
 * - payload, byte for byte, keys in this order, no whitespace:
 *   `{"ws":"…","uid":"…","cid":"…","n":"…","iat":<s>,"exp":<s>}`
 * - signature: base64url(HMAC-SHA256(key, `b64url(header).b64url(payload)`)), unpadded
 */

/** How long a minted state may live. Anything longer is refused even if it verifies. */
export const MAX_STATE_AGE_SECONDS = 900

/** The one connector id this function serves. A state for anything else is refused. */
export const GOOGLE_CONNECTOR_ID = "google"

export interface StateClaims {
  /** The Fluck workspace, which is the privacy anchor for the stored secret. */
  ws: string
  /** The BOSS user id the secret is written for. Never taken from the request. */
  uid: string
  /** Connector id. Must be [GOOGLE_CONNECTOR_ID]. */
  cid: string
  /** Single use nonce, claimed server side so a replayed link is dead. */
  n: string
  iat: number
  exp: number
}

/**
 * The raw key bytes for a configured `FLUCK_STATE_KEY`.
 *
 * The plugin mints 32 random bytes and stores them base64url encoded, so the ordinary case is
 * the first branch. The UTF-8 fallback exists so an operator who pasted a long passphrase gets
 * a working deployment rather than a function that verifies nothing; both ends implement the
 * same two branches, so whichever one the value takes, they agree.
 */
export function keyBytes(secret: string | undefined): Uint8Array {
  if (!secret || secret.length === 0) throw new Error("FLUCK_STATE_KEY is not set")
  const decoded = tryBase64Url(secret)
  if (decoded && decoded.length === 32) return decoded
  const utf8 = new TextEncoder().encode(secret)
  if (utf8.length < 32) throw new Error("FLUCK_STATE_KEY is too short")
  return utf8
}

function tryBase64Url(value: string): Uint8Array | null {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) return null
  const padded = value.replaceAll("-", "+").replaceAll("_", "/") +
    "=".repeat((4 - (value.length % 4)) % 4)
  try {
    const binary = atob(padded)
    const out = new Uint8Array(binary.length)
    for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i)
    return out
  } catch {
    return null
  }
}

function b64urlEncode(bytes: Uint8Array): string {
  let binary = ""
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "")
}

function b64urlDecode(value: string): Uint8Array | null {
  return tryBase64Url(value)
}

async function hmacKey(secret: Uint8Array): Promise<CryptoKey> {
  return await crypto.subtle.importKey(
    "raw",
    secret as BufferSource,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  )
}

/**
 * Mint one state token.
 *
 * Exported for the fixture test and for anyone reproducing a token by hand while debugging.
 * The function itself never mints in production: the plugin does, and this end only verifies.
 * The payload is assembled by STRING CONCATENATION rather than JSON.stringify of an object,
 * so the byte order of the claims is guaranteed by construction and not by a runtime's
 * property ordering.
 */
export async function mintState(
  secret: Uint8Array,
  claims: StateClaims,
): Promise<string> {
  const header = '{"alg":"HS256","typ":"JWT"}'
  const payload = '{"ws":' + jsonString(claims.ws) +
    ',"uid":' + jsonString(claims.uid) +
    ',"cid":' + jsonString(claims.cid) +
    ',"n":' + jsonString(claims.n) +
    ',"iat":' + String(Math.trunc(claims.iat)) +
    ',"exp":' + String(Math.trunc(claims.exp)) + "}"
  const encoder = new TextEncoder()
  const signingInput = b64urlEncode(encoder.encode(header)) + "." +
    b64urlEncode(encoder.encode(payload))
  const signature = await crypto.subtle.sign(
    "HMAC",
    await hmacKey(secret),
    encoder.encode(signingInput) as BufferSource,
  )
  return signingInput + "." + b64urlEncode(new Uint8Array(signature))
}

/** JSON string literal, escaped. The claims are url-safe in practice; this is belt and braces. */
function jsonString(value: string): string {
  return JSON.stringify(value)
}

/**
 * Verify a state token and return its claims, or null.
 *
 * Null for every failure, with no distinction between them: the caller renders one sentence
 * either way, and telling an unauthenticated browser WHICH check failed turns this into an
 * oracle for guessing at signatures.
 *
 * The comparison is over the signature bytes and is length checked first, then constant time.
 */
export async function verifyState(
  secret: Uint8Array,
  token: string,
  nowSeconds: number,
): Promise<StateClaims | null> {
  const parts = token.split(".")
  if (parts.length !== 3) return null
  const [headerPart, payloadPart, signaturePart] = parts
  const headerBytes = b64urlDecode(headerPart)
  const payloadBytes = b64urlDecode(payloadPart)
  const signature = b64urlDecode(signaturePart)
  if (!headerBytes || !payloadBytes || !signature) return null

  let header: { alg?: unknown; typ?: unknown }
  try {
    header = JSON.parse(new TextDecoder().decode(headerBytes))
  } catch {
    return null
  }
  // Pinned, not read: accepting the token's own `alg` is the classic JWT confusion bug, and
  // `none` would make every one of these checks decorative.
  if (!header || typeof header !== "object" || header.alg !== "HS256") return null

  const expected = new Uint8Array(
    await crypto.subtle.sign(
      "HMAC",
      await hmacKey(secret),
      new TextEncoder().encode(headerPart + "." + payloadPart) as BufferSource,
    ),
  )
  if (!timingSafeEqual(expected, signature)) return null

  let claims: Partial<StateClaims>
  try {
    claims = JSON.parse(new TextDecoder().decode(payloadBytes))
  } catch {
    return null
  }
  if (!claims || typeof claims !== "object") return null
  if (typeof claims.ws !== "string" || claims.ws.length === 0) return null
  if (typeof claims.uid !== "string" || claims.uid.length === 0) return null
  if (typeof claims.cid !== "string" || claims.cid.length === 0) return null
  if (typeof claims.n !== "string" || claims.n.length === 0) return null
  if (typeof claims.iat !== "number" || typeof claims.exp !== "number") return null
  if (!Number.isSafeInteger(claims.iat) || !Number.isSafeInteger(claims.exp)) return null
  if (claims.iat > nowSeconds || claims.exp <= claims.iat) return null
  if (claims.exp <= nowSeconds) return null
  // A token whose own lifetime is longer than the policy is refused even though it verifies,
  // so a minting bug cannot hand out a state that is good for a week.
  if (claims.exp - claims.iat > MAX_STATE_AGE_SECONDS) return null
  return claims as StateClaims
}

function timingSafeEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false
  let diff = 0
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i]
  return diff === 0
}
