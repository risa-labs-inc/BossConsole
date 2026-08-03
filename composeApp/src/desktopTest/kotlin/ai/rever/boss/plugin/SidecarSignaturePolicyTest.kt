package ai.rever.boss.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the rule that keeps the system-plugin install path safe: a store signature
 * may be bound to a JAR only when the store agrees about those exact bytes.
 *
 * The asymmetry is the whole point and is easy to "simplify" away — a *missing*
 * sidecar is warn-and-allow at load, while a *present but invalid* one hard-fails
 * regardless of PluginSignatureEnforcement. System plugins are fetched from GitHub
 * releases while signatures come from the store, and `POST /github` re-hosts JARs
 * through Supabase Storage, so the two artifacts are not guaranteed to be identical
 * bytes. Guessing turns a working plugin into a permanently unloadable one.
 */
class SidecarSignaturePolicyTest {
    private val sig = "c2lnbmF0dXJl"
    private val digest = "abc123def456"

    @Test
    fun `binds the signature when the store agrees about the bytes`() {
        assertEquals(
            sig,
            PluginStoreSetup.resolveSidecarSignature(
                storeSha256 = digest,
                storeSignature = sig,
                localSha256 = digest,
            ),
        )
    }

    @Test
    fun `digest comparison is case-insensitive`() {
        // The store returns lowercase hex and sha256Of is lowercase today, but the
        // anchor is defined on the digest value, not its spelling — an uppercase
        // digest must not silently drop a valid signature.
        assertEquals(
            sig,
            PluginStoreSetup.resolveSidecarSignature(
                storeSha256 = digest.uppercase(),
                storeSignature = sig,
                localSha256 = digest,
            ),
        )
    }

    @Test
    fun `refuses to bind a signature to bytes the store does not vouch for`() {
        assertNull(
            PluginStoreSetup.resolveSidecarSignature(
                storeSha256 = digest,
                storeSignature = sig,
                localSha256 = "0000000000000000",
            ),
        )
    }

    @Test
    fun `an unsigned store row stays unsigned even on a digest match`() {
        // Versions published before store signing have no signature at all.
        assertNull(
            PluginStoreSetup.resolveSidecarSignature(
                storeSha256 = digest,
                storeSignature = null,
                localSha256 = digest,
            ),
        )
    }
}
