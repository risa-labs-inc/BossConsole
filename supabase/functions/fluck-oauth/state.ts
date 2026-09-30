/**
 * The signed `state` that travels through Google's consent screen.
 *
 * ## Signed by the install, verified against its registered key
 *
 * The plugin signs with the same Ed25519 private key it registered in
 * `fluck_vault_instances.link_public_key`, so any BOSS user's Fluck can mint a state and this
 * function needs no shared secret: it looks the install up by `iid` and verifies with that row's
 * public key. A compromise of this function can verify states and cannot mint one.
 *
 * ## The wire format is a contract, not an implementation detail
 *
 * The Kotlin side mints these in the fluck-agent-imessage repo. Both sides assert the SAME
 * fixture (`tests/fluck-oauth-state.fixture.json`, a fixed Ed25519 key pair, copied into that
 * repo's test resources), so a change to the signing prefix or the claim order fails in both
 * suites rather than silently refusing every callback in production.
 *
 * Format: `<b64url(payload)>.<b64url(signature)>`, both unpadded.
 * - payload, compact JSON:
 *   `{"v":1,"iid":"…","uid":"…","ws":"…","cid":"…","nonce":"…","iat":<s>,"exp":<s>}`
 *   (`cid`, the conversation, and `iat` are optional; the verifier parses, so key order is only
 *   fixed for the fixture)
 * - signature: Ed25519 over the ASCII bytes `fluck-oauth-state-v1.` + the payload segment
 */

/** How long a minted state may live, from now and from its own `iat`. */
export const MAX_STATE_AGE_SECONDS = 900

/** Domain separation: a state signature can never double as a signed vault request. */
export const STATE_SIGNING_PREFIX = "fluck-oauth-state-v1."

/** Same shape `fluck-vault` accepts for an install id. */
export const INSTANCE_ID_PATTERN = /^[A-Za-z0-9_-]{16,64}$/

/** The nonce is claimed as `<iid>.<nonce>`; the claim RPC caps the whole at 256 characters. */
const MAX_NONCE_LENGTH = 128

export interface StateClaims {
  v: 1
  /** The install that signed, a `fluck_vault_instances` primary key. */
  iid: string
  /** The BOSS user the secret is written for. Must own `iid`. */
  uid: string
  /** The Fluck workspace, which is the privacy anchor for the stored secret. */
  ws: string
  /** The conversation that asked, if any. Opaque here. */
  cid?: string
  /** Single use, claimed server side so a replayed link is dead. */
  nonce: string
  iat?: number
  exp: number
}

export interface ParsedState {
  claims: StateClaims
  payloadSegment: string
  signature: Uint8Array
}

function b64urlDecode(value: string): Uint8Array | null {
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

export function b64urlEncode(bytes: Uint8Array): string {
  let binary = ""
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "")
}

function nonEmpty(value: unknown, max = 256): value is string {
  return typeof value === "string" && value.length > 0 && value.length <= max
}

/**
 * Structure and claims only; the signature is NOT checked here.
 *
 * Split from verification because the key to verify with is found by the `iid` inside the
 * payload. Nothing parsed here may be acted on until `verifyState` has passed.
 */
export function parseState(token: string): ParsedState | null {
  const parts = token.split(".")
  if (parts.length !== 2) return null
  const [payloadSegment, signaturePart] = parts
  const payloadBytes = b64urlDecode(payloadSegment)
  const signature = b64urlDecode(signaturePart)
  if (!payloadBytes || !signature || signature.length !== 64) return null

  let raw: Record<string, unknown>
  try {
    raw = JSON.parse(new TextDecoder().decode(payloadBytes))
  } catch {
    return null
  }
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null
  if (raw.v !== 1) return null
  if (typeof raw.iid !== "string" || !INSTANCE_ID_PATTERN.test(raw.iid)) return null
  if (!nonEmpty(raw.uid) || !nonEmpty(raw.ws) || !nonEmpty(raw.nonce, MAX_NONCE_LENGTH)) {
    return null
  }
  if (raw.cid !== undefined && !nonEmpty(raw.cid)) return null
  if (typeof raw.exp !== "number" || !Number.isSafeInteger(raw.exp)) return null
  if (raw.iat !== undefined && (typeof raw.iat !== "number" || !Number.isSafeInteger(raw.iat))) {
    return null
  }
  const claims: StateClaims = {
    v: 1,
    iid: raw.iid,
    uid: raw.uid,
    ws: raw.ws,
    nonce: raw.nonce,
    exp: raw.exp,
  }
  if (raw.cid !== undefined) claims.cid = raw.cid as string
  if (raw.iat !== undefined) claims.iat = raw.iat as number
  return { claims, payloadSegment, signature }
}

/**
 * Verify a parsed state against the install's raw 32 byte Ed25519 public key.
 *
 * True or false with no reason: the caller renders one sentence either way, and telling an
 * unauthenticated browser WHICH check failed is an oracle.
 */
export async function verifyState(
  publicKey: Uint8Array,
  parsed: ParsedState,
  nowSeconds: number,
): Promise<boolean> {
  const { claims } = parsed
  if (claims.exp <= nowSeconds) return false
  // Refused even though it verifies, so a minting bug cannot hand out a week-long state.
  if (claims.exp - nowSeconds > MAX_STATE_AGE_SECONDS) return false
  if (claims.iat !== undefined) {
    if (claims.iat > nowSeconds || claims.exp <= claims.iat) return false
    if (claims.exp - claims.iat > MAX_STATE_AGE_SECONDS) return false
  }
  if (publicKey.length !== 32) return false
  let key: CryptoKey
  try {
    key = await crypto.subtle.importKey(
      "raw",
      publicKey as BufferSource,
      { name: "Ed25519" },
      false,
      ["verify"],
    )
  } catch {
    return false
  }
  return await crypto.subtle.verify(
    { name: "Ed25519" },
    key,
    parsed.signature as BufferSource,
    new TextEncoder().encode(STATE_SIGNING_PREFIX + parsed.payloadSegment) as BufferSource,
  )
}

/**
 * The payload bytes for a set of claims, in the fixture's key order.
 *
 * String concatenation rather than JSON.stringify of an object, so the order is guaranteed by
 * construction and the Kotlin minter can be asserted against the same bytes.
 */
export function statePayload(claims: StateClaims): string {
  return '{"v":1,"iid":' + JSON.stringify(claims.iid) +
    ',"uid":' + JSON.stringify(claims.uid) +
    ',"ws":' + JSON.stringify(claims.ws) +
    (claims.cid !== undefined ? ',"cid":' + JSON.stringify(claims.cid) : "") +
    ',"nonce":' + JSON.stringify(claims.nonce) +
    (claims.iat !== undefined ? ',"iat":' + String(Math.trunc(claims.iat)) : "") +
    ',"exp":' + String(Math.trunc(claims.exp)) + "}"
}

/**
 * Mint one state. For the fixture and the tests only: this function holds no private key in
 * production, the plugin mints.
 */
export async function mintState(privateKey: CryptoKey, claims: StateClaims): Promise<string> {
  const segment = b64urlEncode(new TextEncoder().encode(statePayload(claims)))
  const signature = await crypto.subtle.sign(
    { name: "Ed25519" },
    privateKey,
    new TextEncoder().encode(STATE_SIGNING_PREFIX + segment) as BufferSource,
  )
  return segment + "." + b64urlEncode(new Uint8Array(signature))
}
