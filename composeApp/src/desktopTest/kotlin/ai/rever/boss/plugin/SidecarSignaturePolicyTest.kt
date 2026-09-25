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

    /**
     * A complete 64-hex SHA-256, the shape `sha256Of` and the store's column
     * actually carry. The settled-mismatch rung keys on the digest being real,
     * so it needs a genuine one on both sides of the comparison.
     */
    private val wellFormedDigest = "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0"

    /** A second complete digest, for the rungs that need two real answers. */
    private val otherWellFormedDigest = "f0e1d2c3b4a5968778695a4b3c2d1e0ff0e1d2c3b4a5968778695a4b3c2d1e0f"

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

    // ------------------------------------------------------------------------
    // signatureOutcome: which of the three store answers a lookup resolves to.
    // Pins the rungs the backfill path (#108) now walks, on the signature-only
    // route: bind only on agreement, settle a mismatch only when the store's
    // answer is a real verdict (a well-formed digest over a non-blank
    // signature), keep every other answer retryable — and never fabricate a
    // binding from blank or corrupt data.
    // ------------------------------------------------------------------------

    @Test
    fun `matched digest and signature resolve to a signed outcome`() {
        assertEquals(
            PluginStoreSetup.StoreSignatureOutcome.Signed(sig),
            PluginStoreSetup.signatureOutcome(
                storeSha256 = digest,
                storeSignature = sig,
                localSha256 = digest,
            ),
        )
    }

    @Test
    fun `a signature over different bytes is a settled mismatch, never a binding`() {
        // Settled needs a real verdict on the store's side: a well-formed
        // 64-hex digest against a well-formed local one. The skip that keeps
        // a working plugin loading: the JAR keeps no sidecar rather than
        // getting one the store never vouched for.
        assertEquals(
            PluginStoreSetup.StoreSignatureOutcome.Mismatch,
            PluginStoreSetup.signatureOutcome(
                storeSha256 = wellFormedDigest,
                storeSignature = sig,
                localSha256 = otherWellFormedDigest,
            ),
        )
    }

    @Test
    fun `an unsigned row stays retryable unavailable`() {
        // A row published before store signing: signing it later is a
        // store-side change, so the answer must not be cached as final.
        assertEquals(
            PluginStoreSetup.StoreSignatureOutcome.Unavailable,
            PluginStoreSetup.signatureOutcome(
                storeSha256 = digest,
                storeSignature = null,
                localSha256 = digest,
            ),
        )
    }

    @Test
    fun `a corrupt store digest never fabricates a binding and never settles`() {
        // sha256 is NOT NULL in the store, but a pre-finalize row carries the
        // 'pending' placeholder and no transport bug is impossible. A digest
        // that is not 64 hex chars is not a verdict — the row may still
        // finalize into one that vouches for these bytes — so it must resolve
        // to retryable Unavailable, never to Mismatch: Mismatch is what makes
        // resolveSignatureToBind write the persistent markUnsignable marker,
        // and a dropped field would otherwise pin the JAR unsignable until
        // someone deletes the marker by hand.
        assertEquals(
            PluginStoreSetup.StoreSignatureOutcome.Unavailable,
            PluginStoreSetup.signatureOutcome(
                storeSha256 = "pending",
                storeSignature = sig,
                localSha256 = digest,
            ),
        )
        assertEquals(
            PluginStoreSetup.StoreSignatureOutcome.Unavailable,
            PluginStoreSetup.signatureOutcome(
                storeSha256 = null,
                storeSignature = sig,
                localSha256 = digest,
            ),
        )
        assertEquals(
            PluginStoreSetup.StoreSignatureOutcome.Unavailable,
            PluginStoreSetup.signatureOutcome(
                storeSha256 = null,
                storeSignature = null,
                localSha256 = digest,
            ),
        )
    }

    @Test
    fun `a blank store digest stays retryable unavailable`() {
        // A blank digest cannot vouch and cannot settle: agreement needs two
        // real answers. This is the rung `storeSha256 ?: ""` used to miss.
        assertEquals(
            PluginStoreSetup.StoreSignatureOutcome.Unavailable,
            PluginStoreSetup.signatureOutcome(
                storeSha256 = "",
                storeSignature = sig,
                localSha256 = digest,
            ),
        )
        // The fabricated binding itself: two blank digests used to agree and
        // bound a signature over nothing.
        assertEquals(
            PluginStoreSetup.StoreSignatureOutcome.Unavailable,
            PluginStoreSetup.signatureOutcome(
                storeSha256 = "",
                storeSignature = sig,
                localSha256 = "",
            ),
        )
    }

    @Test
    fun `a blank signature binds nothing and settles nothing`() {
        // A blank signature is not a verdict even beside a well-formed digest:
        // binding it would write the empty, present-but-invalid sidecar that
        // hard-fails at load, and settling it would mark the JAR unsignable
        // just before the store's real signature lands.
        assertEquals(
            PluginStoreSetup.StoreSignatureOutcome.Unavailable,
            PluginStoreSetup.signatureOutcome(
                storeSha256 = wellFormedDigest,
                storeSignature = "",
                localSha256 = otherWellFormedDigest,
            ),
        )
        assertEquals(
            PluginStoreSetup.StoreSignatureOutcome.Unavailable,
            PluginStoreSetup.signatureOutcome(
                storeSha256 = wellFormedDigest,
                storeSignature = "   ",
                localSha256 = wellFormedDigest,
            ),
        )
        // Even on full digest agreement, a blank signature must not bind.
        assertNull(
            PluginStoreSetup.resolveSidecarSignature(
                storeSha256 = digest,
                storeSignature = "",
                localSha256 = digest,
            ),
        )
    }
}
