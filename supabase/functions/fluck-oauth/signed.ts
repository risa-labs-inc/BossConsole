/**
 * Signed install requests, the same scheme as `fluck-vault/signed.ts` (copied, not imported:
 * each function deploys with its own import map, and the two must be verifiable in isolation).
 *
 * Canonical string, signed with the install's Ed25519 link key:
 *
 * ```
 *   fluck-vault-signed-v1\n<METHOD>\n<routed path>\n<unix seconds>\n<sha256 hex of the body>
 * ```
 *
 * carried as `X-Fluck-Instance`, `X-Fluck-Timestamp` and `X-Fluck-Signature` (base64url,
 * unpadded). The prefix is shared with fluck-vault on purpose so the plugin has one signer; the
 * routed path keeps a vault signature from being replayed here (no vault route is `/refresh`).
 * Freshness is the timestamp window; `/refresh` is idempotent, so a replay inside it only
 * returns another access token to whoever already holds the refresh token.
 */

export const SIGNATURE_SKEW_SECONDS = 120

export const INSTANCE_HEADER = "x-fluck-instance"
export const TIMESTAMP_HEADER = "x-fluck-timestamp"
export const SIGNATURE_HEADER = "x-fluck-signature"

const PREFIX = "fluck-vault-signed-v1"

/** Base64 or base64url, padded or not. Null rather than a throw. */
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

/**
 * A registered `link_public_key`: raw 32 bytes base64/base64url, or a PEM SubjectPublicKeyInfo,
 * the same forms fluck-vault accepts. Null for anything else.
 */
export function publicKeyBytes(configured: string | undefined | null): Uint8Array | null {
  if (!configured || configured.trim().length === 0) return null
  const value = configured.trim()
  if (value.includes("-----BEGIN")) {
    const der = decodeBase64(
      value.replace(/-----BEGIN [^-]+-----/g, "").replace(/-----END [^-]+-----/g, "")
        .replace(/\s+/g, ""),
    )
    // SubjectPublicKeyInfo for Ed25519 is a fixed 44 bytes whose last 32 are the key.
    return der && der.length === 44 ? der.slice(12) : null
  }
  const raw = decodeBase64(value)
  return raw && raw.length === 32 ? raw : null
}

/** SHA-256 hex of the raw request body, empty string included. */
export async function bodyDigest(body: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(body) as BufferSource,
  )
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("")
}

export function signingString(
  method: string,
  path: string,
  timestamp: number,
  digest: string,
): string {
  return `${PREFIX}\n${method}\n${path}\n${Math.trunc(timestamp)}\n${digest}`
}

/** True or false, with no distinction between failures: an oracle is worse than a shrug. */
export async function verifySigned(options: {
  publicKey: string | null
  method: string
  path: string
  body: string
  timestamp: string | null
  signature: string | null
  nowSeconds: number
}): Promise<boolean> {
  if (!options.timestamp || !options.signature) return false
  const ts = Number(options.timestamp)
  if (!Number.isFinite(ts) || !Number.isInteger(ts)) return false
  if (Math.abs(options.nowSeconds - ts) > SIGNATURE_SKEW_SECONDS) return false
  const raw = publicKeyBytes(options.publicKey)
  if (!raw) return false
  const signature = decodeBase64(options.signature)
  if (!signature || signature.length !== 64) return false
  let key: CryptoKey
  try {
    key = await crypto.subtle.importKey("raw", raw as BufferSource, { name: "Ed25519" }, false, [
      "verify",
    ])
  } catch {
    return false
  }
  const message = signingString(options.method, options.path, ts, await bodyDigest(options.body))
  return await crypto.subtle.verify(
    { name: "Ed25519" },
    key,
    signature as BufferSource,
    new TextEncoder().encode(message) as BufferSource,
  )
}
