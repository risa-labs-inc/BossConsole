/**
 * The sealing scheme, stated once so both ends can be written against it.
 *
 * ## What this function is allowed to know
 *
 * Nothing. The page encrypts the card, the password or the CVV in the browser, to a public key
 * whose private half never leaves the DGX, and POSTs only the ciphertext. This function stores
 * that opaque blob and hands it to the DGX when it asks. It never holds a plaintext PAN, it
 * never holds a decryption key, and it has no grant on `public.secrets` at all. That is the
 * sealed design the red team asked for in D1, and it is why a compromise of this function, or
 * of the Supabase project's service key, does not yield a card number.
 *
 * ## Why P-256 and not X25519
 *
 * X25519 would be the tidier choice and `crypto_box_seal` compatibility would come almost free.
 * It is not universally available in browser WebCrypto: Safari only gained it recently, and the
 * device that opens these links is whatever phone the owner happens to be holding. A page that
 * cannot encrypt is a page on which the card is typed and then lost. P-256 ECDH has been in
 * every shipping browser for a decade, and it mirrors exactly onto JCA on the DGX
 * (`KeyAgreement.getInstance("ECDH")` over `secp256r1`, `HKDF` or a hand rolled RFC 5869, and
 * `AES/GCM/NoPadding`). Reach beat elegance.
 *
 * ## The byte layout
 *
 * The POSTed `c` field is the standard base64 of:
 *
 * ```
 *   byte    0        version, always 0x01
 *   bytes   1..65    ephemeral P-256 public key, uncompressed X9.62 (0x04 || X32 || Y32)
 *   bytes  66..77    AES-GCM IV, 12 random bytes
 *   bytes  78..      AES-GCM ciphertext with its 16 byte tag appended
 * ```
 *
 * Derivation, given the recipient's public key `R` and a fresh ephemeral pair `(e, E)`:
 *
 * ```
 *   shared  = ECDH(e, R)                            32 bytes, the X coordinate only
 *   key     = HKDF-SHA256(ikm = shared,
 *                         salt = 32 zero bytes,
 *                         info = utf8("fluck-vault-v1|" || jti),
 *                         length = 32)
 *   iv      = 12 random bytes
 *   ct      = AES-256-GCM(key, iv, plaintext, aad = utf8(jti))
 * ```
 *
 * The `jti` is bound twice, in the HKDF info and in the AEAD associated data, so a blob sealed
 * for one request cannot be replayed into another even by someone holding both rows.
 *
 * The plaintext is compact JSON. For a password: `{"kind":"password","username":…,"password":…}`.
 * For a card: `{"kind":"card","name":…,"pan":…,"exp":"MM/YY","billing":{…}}`. For a CVV:
 * `{"kind":"cvv","cvv":"…"}`. The DGX is the only reader and the only validator of it.
 */

/** Version byte at the head of every sealed blob. */
export const SEAL_VERSION = 0x01

/** Uncompressed X9.62 P-256 point: `0x04` plus two 32 byte coordinates. */
export const SEAL_PUBLIC_KEY_BYTES = 65

/** HKDF info prefix. The `jti` is appended after a `|`. Mirror this exactly on the DGX. */
export const SEAL_INFO_PREFIX = "fluck-vault-v1|"

/** Version byte, ephemeral key, IV. A blob shorter than this plus a tag cannot be genuine. */
export const SEAL_HEADER_BYTES = 1 + SEAL_PUBLIC_KEY_BYTES + 12

/** The smallest a well formed blob can be: header, a 16 byte tag, and at least one byte. */
export const SEAL_MIN_BYTES = SEAL_HEADER_BYTES + 16 + 1

/**
 * The largest blob accepted. A card with a full billing address seals to a few hundred bytes;
 * eight kilobytes is room for every legitimate case and a ceiling on using this table as free
 * anonymous storage.
 */
export const SEAL_MAX_BYTES = 8192

/**
 * Check that `FLUCK_SEAL_PUBLIC_KEY` is a usable recipient key before embedding it in a page.
 *
 * Serving a malformed key would mean a page that renders, takes a card number, fails to encrypt
 * on submit, and loses it. Better to refuse the link and say so.
 */
export function sealPublicKey(configured: string | undefined): Uint8Array {
  if (!configured || configured.trim().length === 0) {
    throw new Error("seal public key is not set")
  }
  const raw = decode(configured.trim())
  if (!raw || raw.length !== SEAL_PUBLIC_KEY_BYTES || raw[0] !== 0x04) {
    throw new Error("seal public key is not an uncompressed p-256 point")
  }
  return raw
}

/**
 * Structural check on a POSTed blob.
 *
 * Deliberately shallow: this end cannot verify the ciphertext, and pretending otherwise would
 * be theatre. What it does prove is that the body is an opaque blob of the right shape and not
 * a card number somebody sent in the clear because the page's script did not run.
 */
export function looksSealed(value: unknown): value is string {
  if (typeof value !== "string") return false
  if (value.length > Math.ceil(SEAL_MAX_BYTES / 3) * 4 + 4) return false
  const bytes = decode(value)
  if (!bytes) return false
  if (bytes.length < SEAL_MIN_BYTES || bytes.length > SEAL_MAX_BYTES) return false
  if (bytes[0] !== SEAL_VERSION) return false
  if (bytes[1] !== 0x04) return false
  return true
}

function decode(value: string): Uint8Array | null {
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
