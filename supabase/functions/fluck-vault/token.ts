/**
 * The link token: an EdDSA (Ed25519) JWT the DGX mints and this function can only verify.
 *
 * ## Why asymmetric, when the sibling `fluck-oauth` uses HS256
 *
 * `fluck-oauth` verifies a state that only decides WHOSE Google grant is being stored, and a
 * forged one buys an attacker nothing they did not already have. A vault link is different: it
 * is a bearer credential that opens a page into which a card number or a CVV is typed. If this
 * function held the minting key, a compromise of the Supabase environment would let an attacker
 * mint vault and CVV links and text them from a trusted path. With Ed25519 the DGX holds the
 * private key and this function holds thirty two bytes of public key, so a compromise here can
 * verify and nothing more. (Red team D5.)
 *
 * ## The claims are opaque on purpose
 *
 * The URL, token included, travels through the Photon relay, which sees plaintext. So the token
 * carries no merchant, no last four and no total: those live in a `fluck_vault_requests` row the
 * DGX writes when it mints, and the page reads them server side by `jti`. (Red team C3.)
 *
 * Wire format, so the Kotlin minter can be written against it byte for byte:
 * - header: `{"alg":"EdDSA","typ":"JWT"}`
 * - payload: compact JSON, claims in this order:
 *   `{"jti":"…","ws":"…","purpose":"vault"|"cvv","kind":"password"|"card",` (kind on vault only)
 *   `"alias":"…"` (vault only, optional) `,"purchase_id":"…"` (cvv only) `,"aud":"…","iat":<s>,"exp":<s>}`
 * - signature: base64url(Ed25519(privateKey, `b64url(header).b64url(payload)`)), unpadded
 *
 * `aud` is the HOST of `FLUCK_VAULT_BASE_URL`, not the whole URL. It is checked, so a token minted
 * for the functions URL cannot be replayed against a custom domain and, more to the point, a
 * dangling `api.risaboss.com` that someone else takes over cannot accept our tokens.
 * (Red team I3.)
 */

/** The longest a vault link may live, from its own `iat`. */
export const MAX_VAULT_AGE_SECONDS = 600

/** The longest a CVV link may live. Shorter because it sits open during a purchase. */
export const MAX_CVV_AGE_SECONDS = 300

export type Purpose = "vault" | "cvv"
export type Kind = "password" | "card"

export interface LinkClaims {
  /** Request id. Primary key of the `fluck_vault_requests` row the DGX wrote when it minted. */
  jti: string
  /** The Fluck workspace. Never read off the request; only ever out of the signature. */
  ws: string
  purpose: Purpose
  /** Which form to render. Present for `vault`, absent for `cvv`. */
  kind?: Kind
  /** The vault alias this item will be stored under. Display only on this side. */
  alias?: string
  /** The purchase this CVV belongs to. Display only on this side. */
  purchase_id?: string
  /** Host of the public base URL this token was minted for. */
  aud: string
  iat: number
  exp: number
}

/**
 * Decode the configured public key.
 *
 * Accepts the raw 32 bytes base64 or base64url encoded, which is what the DGX will print, and
 * also a PEM SubjectPublicKeyInfo block, because that is what most key generation tooling hands
 * you and an operator pasting one should get a working deployment rather than a function that
 * refuses every link.
 */
export function publicKeyBytes(configured: string | undefined): Uint8Array {
  if (!configured || configured.trim().length === 0) {
    throw new Error("public key is not set")
  }
  const value = configured.trim()
  if (value.includes("-----BEGIN")) {
    const body = value
      .replace(/-----BEGIN [^-]+-----/g, "")
      .replace(/-----END [^-]+-----/g, "")
      .replace(/\s+/g, "")
    const der = decodeBase64(body)
    if (!der) throw new Error("public key is not decodable")
    // SubjectPublicKeyInfo for Ed25519 is a fixed 44 bytes whose last 32 are the key.
    if (der.length === 44) return der.slice(12)
    throw new Error("public key is not an ed25519 key")
  }
  const raw = decodeBase64(value)
  if (!raw || raw.length !== 32) throw new Error("public key is not 32 bytes")
  return raw
}

/** Base64 or base64url, padded or not. Null rather than a throw, so callers decide the message. */
export function decodeBase64(value: string): Uint8Array | null {
  if (!/^[A-Za-z0-9_+/=-]*$/.test(value)) return null
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

export function encodeBase64Url(bytes: Uint8Array): string {
  let binary = ""
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "")
}

async function importVerifyKey(raw: Uint8Array): Promise<CryptoKey> {
  return await crypto.subtle.importKey(
    "raw",
    raw as BufferSource,
    { name: "Ed25519" },
    false,
    ["verify"],
  )
}

/**
 * Mint one link token.
 *
 * Exported for the tests and for anyone reproducing a token by hand while debugging. This
 * function NEVER mints in production: `index.ts` has no private key to mint with, which is the
 * whole point of the scheme. The payload is built by string concatenation so its byte order is
 * guaranteed by construction rather than by a runtime's property ordering, and the Kotlin side
 * can be asserted against the same bytes.
 */
export async function mintLink(
  privateKey: CryptoKey,
  claims: LinkClaims,
): Promise<string> {
  const header = '{"alg":"EdDSA","typ":"JWT"}'
  const parts: string[] = [
    '"jti":' + JSON.stringify(claims.jti),
    '"ws":' + JSON.stringify(claims.ws),
    '"purpose":' + JSON.stringify(claims.purpose),
  ]
  if (claims.kind !== undefined) parts.push('"kind":' + JSON.stringify(claims.kind))
  if (claims.alias !== undefined) parts.push('"alias":' + JSON.stringify(claims.alias))
  if (claims.purchase_id !== undefined) {
    parts.push('"purchase_id":' + JSON.stringify(claims.purchase_id))
  }
  parts.push('"aud":' + JSON.stringify(claims.aud))
  parts.push('"iat":' + String(Math.trunc(claims.iat)))
  parts.push('"exp":' + String(Math.trunc(claims.exp)))
  const payload = "{" + parts.join(",") + "}"

  const encoder = new TextEncoder()
  const signingInput = encodeBase64Url(encoder.encode(header)) + "." +
    encodeBase64Url(encoder.encode(payload))
  const signature = await crypto.subtle.sign(
    { name: "Ed25519" },
    privateKey,
    encoder.encode(signingInput) as BufferSource,
  )
  return signingInput + "." + encodeBase64Url(new Uint8Array(signature))
}

/**
 * Verify a link token and return its claims, or null.
 *
 * Null for every failure with no distinction between them. The caller renders ONE fixed page
 * either way (red team C4): telling an unauthenticated browser which check failed turns this
 * into an oracle, and a debug page that echoed `ws`, `alias` or a merchant would leak exactly
 * the things the opaque claim design exists to keep off the wire.
 */
export async function verifyLink(
  publicKey: Uint8Array,
  token: string,
  audience: string,
  nowSeconds: number,
): Promise<LinkClaims | null> {
  const parts = token.split(".")
  if (parts.length !== 3) return null
  const [headerPart, payloadPart, signaturePart] = parts
  const headerBytes = decodeBase64(headerPart)
  const payloadBytes = decodeBase64(payloadPart)
  const signature = decodeBase64(signaturePart)
  if (!headerBytes || !payloadBytes || !signature) return null

  let header: { alg?: unknown; typ?: unknown }
  try {
    header = JSON.parse(new TextDecoder().decode(headerBytes))
  } catch {
    return null
  }
  // Pinned, not read. Accepting the token's own `alg` is the classic JWT confusion bug, and
  // `none` would make every check below decorative.
  if (header.alg !== "EdDSA") return null

  let key: CryptoKey
  try {
    key = await importVerifyKey(publicKey)
  } catch {
    return null
  }
  const ok = await crypto.subtle.verify(
    { name: "Ed25519" },
    key,
    signature as BufferSource,
    new TextEncoder().encode(headerPart + "." + payloadPart) as BufferSource,
  )
  if (!ok) return null

  let claims: Partial<LinkClaims>
  try {
    claims = JSON.parse(new TextDecoder().decode(payloadBytes))
  } catch {
    return null
  }
  if (typeof claims.jti !== "string" || claims.jti.length === 0) return null
  if (typeof claims.ws !== "string" || claims.ws.length === 0) return null
  if (claims.purpose !== "vault" && claims.purpose !== "cvv") return null
  if (typeof claims.aud !== "string" || claims.aud !== audience) return null
  if (typeof claims.iat !== "number" || typeof claims.exp !== "number") return null
  if (!Number.isFinite(claims.iat) || !Number.isFinite(claims.exp)) return null
  if (claims.exp <= nowSeconds) return null

  if (claims.purpose === "vault") {
    if (claims.kind !== "password" && claims.kind !== "card") return null
    if (claims.alias !== undefined && typeof claims.alias !== "string") return null
    // A lifetime longer than the policy is refused even though the signature is good, so a
    // minting bug on the DGX cannot hand out a link that is valid for a week.
    if (claims.exp - claims.iat > MAX_VAULT_AGE_SECONDS) return null
  } else {
    if (claims.kind !== undefined) return null
    if (typeof claims.purchase_id !== "string" || claims.purchase_id.length === 0) return null
    if (claims.exp - claims.iat > MAX_CVV_AGE_SECONDS) return null
  }
  return claims as LinkClaims
}
