package ai.rever.boss.plugin.loader

/**
 * Trust anchor for BOSS Plugin Store artifact signatures.
 *
 * The store's `publish` edge function signs each published version's
 * canonical anchor — see [versionAnchor] — with the store's private signing
 * key (RSASSA-PKCS1-v1_5 / SHA-256); the matching public key is pinned here.
 * See [PluginSignatureVerifier.verifySignedMessage].
 */
object PluginStoreTrust {
    const val PUBLISHER = "boss-plugin-store"

    val PUBLIC_KEY_PEM =
        """
        -----BEGIN PUBLIC KEY-----
        MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAleoguYaY21h3LEKsxC3q
        EfAVXCWZ7IkxBy6BohJwpfaYDJwyID8l5K+P+jHPZeS1Spxxol5CgJhij6HGFmcr
        TIGFNBInTj338w/1yh/IFjb/WjJ+0xykz7pU4M/U+U5XsrTcaf1KWfLnHcMoIHCT
        4ha5HqrcVv2Qyp21CgPFbzPIOUV3h/uAeilfK1PbOMYnbggU1k1XD08B10sEqwS+
        iKlSFii4enJIAcEt3oafgHhDLE+GQ+EoNFn/0J0OK1HO3D6TgAaucLsnACSVFWQX
        9UZd9jCm6YvkH8wHHxd0mjDeRPuxZWkvbcvmYRUGB8TGjC4Z4ead/SFgXfjXT6cY
        SY8GoX+FREYAvLTgI9zVqMT5pQyx7UBGByejuLjJvGzhudvgNUiVK/EE9jwaYm11
        oo2DWapcAkqFkmSIur8t00R/4fafP+0Cp2Z+KqRJWxI348gDw+ogSZjrQ+f2FekG
        YX6etSgNtjrfVBB9ymnBYwGxyBcu4i4WBWJA/t5hKVHtBJqTOjzho54C1HkN1z74
        2tNeEMnzksVUII5MzUG/U43UlGrWtNaSZN0F+FDoAThI1tGmc4WHwucO8DwsaaDf
        2IYqdAXle8zJzXps0RVjhtcny8a7nwH6A6zrg8/aFUfjuKgAcI1eUDiil9IwltnJ
        4dNvQ+d7SRuA57gdpdyCgfsCAwEAAQ==
        -----END PUBLIC KEY-----
        """.trimIndent()

    /**
     * All currently trusted store keys by publisher label. (Declared after
     * the PEM it references — keep it that way.)
     *
     * Key rotation without a flag-day break: FIRST ship a host release that
     * pins the incoming key here alongside the current one (e.g.
     * "boss-plugin-store-2027"), THEN switch the store's signing key and
     * re-run the backfill. Reversing the order makes freshly re-signed
     * versions a hard "invalid signature" failure on older hosts rather
     * than a benign "unsigned" warn.
     */
    val TRUSTED_KEYS: Map<String, String> = mapOf(PUBLISHER to PUBLIC_KEY_PEM)

    /**
     * Canonical signed message for a plugin version. Must stay in lockstep
     * with `versionAnchor` in the plugin-store function's utils/signing.ts
     * (wire format pinned by tests on both sides).
     *
     * Binding pluginId and version — not just the digest — prevents an
     * attacker with DB write access from substituting a different (older,
     * vulnerable, or cross-plugin) legitimately signed store artifact into a
     * version row: the signature only verifies for the exact identity the
     * host is installing.
     *
     * [pluginId] and [version] enter the anchor VERBATIM (only the digest is
     * lowercased) — plugin ids are case-sensitive identifiers and normalizing
     * them could alias distinct ids. The host passes the same id string it
     * requested from the store, so it round-trips; if a casing divergence is
     * ever introduced upstream it surfaces as "signature verification failed"
     * — check id casing before suspecting the crypto.
     *
     * LOAD-BEARING COUPLING: download-time verification uses the STORE ROW's
     * version, while load-time re-derives this anchor from the JAR's MANIFEST
     * version. A present-but-invalid sidecar hard-fails regardless of the
     * enforcement flag, so if a plugin's row version and manifest version ever
     * differ (trailing space, 1.0 vs 1.0.0, casing) the sidecar would verify
     * at download yet be rejected at load. The publish function enforces
     * manifest.version == row version at publish time so this can't happen;
     * keep that guard if this format changes.
     */
    fun versionAnchor(
        pluginId: String,
        version: String,
        sha256Hex: String,
    ): String = "$pluginId|$version|${sha256Hex.lowercase()}"
}
