/**
 * The DGX's own half of this function: two machine routes, authenticated by the same Ed25519
 * key pair that signs the links.
 *
 * ## Why these exist at all
 *
 * The README's original polling contract had the DGX call `fluck_vault_claim` directly with the
 * project's service role key. That key opens far more than this queue, and putting it on a box
 * that also runs a model is the one thing the sealed design was built to avoid: a compromise of
 * the DGX would then be a compromise of the whole project rather than of a queue of blobs plus
 * one private key. So the DGX holds no Supabase credential at all. It holds the Ed25519 private
 * key it already had to hold, and proves itself with a detached signature.
 *
 * The asymmetry still holds in the direction that matters: this end can VERIFY a DGX request
 * and cannot forge one, exactly as it can verify a link and cannot mint one.
 *
 * ## The signature
 *
 * Over a canonical string, never over the parsed body, so the bytes that were signed are the
 * bytes that were read:
 *
 * ```
 *   fluck-vault-signed-v1\n<METHOD>\n<route path>\n<unix seconds>\n<sha256 hex of the body>
 * ```
 *
 * carried as `X-Fluck-Timestamp` and `X-Fluck-Signature` (base64url, unpadded). The path is the
 * ROUTED path, so the same signature cannot be replayed against a different route by moving the
 * request between the function's three possible mount points.
 *
 * Freshness is the timestamp window and nothing else. There is no nonce table, and the two
 * routes are built so that it does not need one: `/requests` is keyed by a `jti` the DGX picked,
 * so a replay is a primary key conflict rather than a second link, and `/inbox/claim` deletes
 * what it returns, so a replay collects an empty list.
 */
import { decodeBase64, publicKeyBytes } from "./token.ts"

/** How far the DGX clock may be from this one. Both are NTP disciplined; two minutes is slack. */
export const SIGNATURE_SKEW_SECONDS = 120

export const TIMESTAMP_HEADER = "x-fluck-timestamp"
export const SIGNATURE_HEADER = "x-fluck-signature"

const PREFIX = "fluck-vault-signed-v1"

/** SHA-256 hex of the raw request body, empty string included. */
export async function bodyDigest(body: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(body) as BufferSource,
  )
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("")
}

/** The exact string both ends sign. Exported so the Kotlin minter can be asserted against it. */
export function signingString(
  method: string,
  path: string,
  timestamp: number,
  digest: string,
): string {
  return `${PREFIX}\n${method}\n${path}\n${Math.trunc(timestamp)}\n${digest}`
}

/**
 * Verify a signed DGX request.
 *
 * True or false, with no distinction between a bad signature, a stale timestamp and a missing
 * header. The caller answers all three the same way for the same reason the link pages do: an
 * unauthenticated caller learning WHICH check failed turns this into an oracle.
 */
export async function verifySigned(options: {
  publicKey: string | undefined
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

  let raw: Uint8Array
  try {
    raw = publicKeyBytes(options.publicKey)
  } catch {
    return false
  }
  const signature = decodeBase64(options.signature)
  if (!signature || signature.length !== 64) return false

  let key: CryptoKey
  try {
    key = await crypto.subtle.importKey(
      "raw",
      raw as BufferSource,
      { name: "Ed25519" },
      false,
      ["verify"],
    )
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
