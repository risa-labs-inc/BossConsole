/**
 * Store-side artifact signing.
 *
 * Signs the canonical version anchor `pluginId|version|sha256` (UTF-8,
 * lowercase hex digest) with the store's RSA private key
 * (RSASSA-PKCS1-v1_5 / SHA-256). The BOSS host pins the matching public key
 * and verifies the same anchor at install time.
 *
 * Binding the plugin identity and version — not just the digest — matters:
 * a signature over the bare sha256 would let an attacker with DB write
 * substitute any OTHER legitimately signed store artifact (e.g. an older,
 * vulnerable version of the same plugin) into a version row, and the host
 * could not tell. With the anchor, a signature only verifies for the exact
 * (pluginId, version) the host asked about.
 *
 * The key is provided via the PLUGIN_SIGNING_PRIVATE_KEY secret as a PKCS#8
 * PEM ("-----BEGIN PRIVATE KEY-----"). When the secret is absent, signing is
 * skipped (returns null) so publishing keeps working — the host treats
 * missing signatures as warn-only until enforcement is enabled.
 */

/**
 * Canonical signed message for a plugin version. Must stay in lockstep with
 * PluginStoreTrust.versionAnchor in BossConsole (wire format pinned by
 * tests/signing.test.ts and the Kotlin PluginSignatureVerifierTest).
 *
 * pluginId and version enter the anchor VERBATIM (only the digest is
 * lowercased) — ids are case-sensitive identifiers; do not normalize on one
 * side only.
 *
 * The `version` signed here MUST equal the JAR's manifest version: the host
 * re-derives this anchor from the manifest at load time, so a divergence
 * between the stored/signed version and the manifest version would reject a
 * legitimate plugin as tampered. The publish route asserts
 * manifest.version === row version before calling signVersionAnchor.
 */
export function versionAnchor(pluginId: string, version: string, sha256Hex: string): string {
  return `${pluginId}|${version}|${sha256Hex.toLowerCase()}`
}

// Memoize the import promise (not the resolved key) so concurrent first
// callers share one import instead of racing duplicate ones.
//
// The memo lives for the isolate's lifetime: after rotating
// PLUGIN_SIGNING_PRIVATE_KEY, running isolates keep signing with the old key
// until they recycle — keep the old public key pinned in hosts until well
// after rotation (see PluginStoreTrust.TRUSTED_KEYS). A missing secret stays
// memoized (isolate env is fixed at boot), but a transient import failure
// clears the memo so the next publish retries instead of pinning unsigned
// publishing until recycle.
let keyPromise: Promise<CryptoKey | null> | undefined

function getSigningKey(): Promise<CryptoKey | null> {
  if (!keyPromise) {
    // Relies on importSigningKey NEVER rejecting (see its contract): a
    // rejected promise here has no rejection handler and would be memoized
    // for the isolate's lifetime, pinning every later publish to a failure.
    keyPromise = importSigningKey().then((key) => {
      if (key === null && Deno.env.get('PLUGIN_SIGNING_PRIVATE_KEY')) {
        keyPromise = undefined // secret present but import failed — retry later
      }
      return key
    })
  }
  return keyPromise
}

// CONTRACT: must never throw/reject — always resolves to a key or null (the
// internal try/catch is load-bearing for getSigningKey's memoization above).
async function importSigningKey(): Promise<CryptoKey | null> {
  const pem = Deno.env.get('PLUGIN_SIGNING_PRIVATE_KEY')
  if (!pem) {
    console.warn('PLUGIN_SIGNING_PRIVATE_KEY not set — publishing unsigned versions')
    return null
  }

  try {
    const body = pem
      .replace('-----BEGIN PRIVATE KEY-----', '')
      .replace('-----END PRIVATE KEY-----', '')
      .replace(/\s/g, '')
    const der = Uint8Array.from(atob(body), (c) => c.charCodeAt(0))

    return await crypto.subtle.importKey(
      'pkcs8',
      der,
      { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
      false,
      ['sign'],
    )
  } catch (e) {
    console.error('Failed to import PLUGIN_SIGNING_PRIVATE_KEY:', e)
    return null
  }
}

/**
 * Sign a version's canonical anchor. Returns base64 signature, or null when
 * no signing key is configured (or signing fails) — never throws, so a
 * signing problem can't block a publish.
 */
export async function signVersionAnchor(
  pluginId: string,
  version: string,
  sha256Hex: string,
): Promise<string | null> {
  const key = await getSigningKey()
  if (!key) return null

  try {
    const data = new TextEncoder().encode(versionAnchor(pluginId, version, sha256Hex))
    const sig = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, data)
    return btoa(String.fromCharCode(...new Uint8Array(sig)))
  } catch (e) {
    console.error('Failed to sign version anchor:', e)
    return null
  }
}
