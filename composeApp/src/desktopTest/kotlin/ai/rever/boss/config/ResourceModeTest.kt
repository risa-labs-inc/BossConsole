package ai.rever.boss.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Guards the resource-tier decision in [ResourceModeConfig].
 *
 * Over-reducing is treated as the serious failure throughout, under-reducing merely as a missed
 * optimisation. A machine that lands in a tighter tier by accident gets a capped browser and
 * eager hibernation it never asked for; one that lands too loose only misses an optimisation.
 * The asymmetry is deliberate, and it is why an unreadable memory reading resolves to FULL.
 */
class ResourceModeTest {
    private val windows = "windows 11"
    private val mac = "mac os x"
    private val linux = "linux"

    private val gb = ResourceModeConfig.BYTES_PER_GB

    private fun resolve(
        raw: String? = null,
        os: String = mac,
        totalGb: Double = 64.0,
    ) = ResourceModeConfig.resolveResourceMode(
        raw = raw,
        os = os,
        totalMemoryBytes = (totalGb * gb).toLong(),
    )

    // region platform

    /**
     * LITE, not ULTRA_LITE. The renderer limit is a security control as well as a memory one:
     * ULTRA_LITE's limit of 2 forces nearly every tab into a shared process, collapsing the Site
     * Isolation boundary. Not a default worth shipping to a whole platform.
     */
    @Test
    fun `windows defaults to LITE regardless of how much memory it has`() {
        for (totalGb in listOf(8.0, 16.0, 64.0, 512.0)) {
            val decision = resolve(os = windows, totalGb = totalGb)
            assertEquals(BossResourceMode.LITE, decision.mode, "at ${totalGb}GB")
            assertEquals(ResourceModeReason.PLATFORM_DEFAULT, decision.reason, "at ${totalGb}GB")
        }
    }

    @Test
    fun `the windows default keeps site isolation usable`() {
        val limit = BossResourceMode.LITE.rendererProcessLimit!!
        assertTrue(limit > BossResourceMode.ULTRA_LITE.rendererProcessLimit!!, "limit=$limit")
    }

    @Test
    fun `every windows spelling is caught`() {
        for (os in listOf("windows 10", "windows 11", "windows server 2022", "windows")) {
            assertEquals(BossResourceMode.LITE, resolve(os = os, totalGb = 64.0).mode, os)
        }
    }

    /**
     * The trap this test exists for: `"darwin"` contains the substring `"win"`, so a platform
     * check written as `contains("win")` hands macOS the Windows branch, which would silently
     * put every Mac in the tightest tier.
     *
     * [JxBrowserRenderingModeTest] keeps the same case alive for the rendering-mode resolver.
     */
    @Test
    fun `darwin is not windows`() {
        val decision = resolve(os = "darwin", totalGb = 64.0)
        assertEquals(BossResourceMode.FULL, decision.mode)
        assertNotEquals(ResourceModeReason.PLATFORM_DEFAULT, decision.reason)
    }

    @Test
    fun `roomy mac and linux stay on FULL`() {
        for (os in listOf(mac, linux, "freebsd", "sunos", "")) {
            val decision = resolve(os = os, totalGb = 64.0)
            assertEquals(BossResourceMode.FULL, decision.mode, os)
            assertEquals(ResourceModeReason.DEFAULT, decision.reason, os)
        }
    }

    // endregion

    // region memory thresholds

    @Test
    fun `memory thresholds pick the tier`() {
        assertEquals(BossResourceMode.ULTRA_LITE, resolve(totalGb = 4.0).mode)
        assertEquals(BossResourceMode.ULTRA_LITE, resolve(totalGb = 7.9).mode)
        assertEquals(BossResourceMode.LITE, resolve(totalGb = 8.0).mode)
        assertEquals(BossResourceMode.LITE, resolve(totalGb = 15.9).mode)
        assertEquals(BossResourceMode.FULL, resolve(totalGb = 16.0).mode)
        assertEquals(BossResourceMode.FULL, resolve(totalGb = 128.0).mode)
    }

    @Test
    fun `a memory-driven tier says so`() {
        assertEquals(ResourceModeReason.DETECTED_MEMORY, resolve(totalGb = 4.0).reason)
        assertEquals(ResourceModeReason.DETECTED_MEMORY, resolve(totalGb = 12.0).reason)
    }

    @Test
    fun `an ultra threshold above the lite one is clamped rather than inverting the tiers`() {
        // Ultra is tested first, so an un-clamped 32 would put this 24 GB machine in ULTRA_LITE
        // even though the user asked for Lite below 8.
        val decision =
            ResourceModeConfig.resolveResourceMode(
                raw = null,
                os = mac,
                totalMemoryBytes = 24 * gb,
                thresholds = ResourceModeThresholds(liteGb = 8, ultraLiteGb = 32),
            )
        assertEquals(BossResourceMode.FULL, decision.mode)
        assertEquals(8, ResourceModeThresholds(liteGb = 8, ultraLiteGb = 32).normalized().ultraLiteGb)
    }

    @Test
    fun `an environment override is distinguished from a settings choice`() {
        // Settings still shows the user's own pick, so calling the env var "because you selected
        // it" would contradict the control right above it.
        val fromEnv =
            ResourceModeConfig.resolveResourceMode(
                raw = "LITE",
                os = mac,
                totalMemoryBytes = 64 * gb,
                explicitSource = ResourceModeSource.ENVIRONMENT,
            )
        assertEquals(ResourceModeReason.ENVIRONMENT_OVERRIDE, fromEnv.reason)
        assertEquals(ResourceModeReason.USER_SELECTION, resolve(raw = "LITE").reason)
    }

    /**
     * A pressure restart must not read as a user selection. It is neither permanent nor chosen,
     * and Settings exists to explain how the tier was picked.
     */
    @Test
    fun `a pressure restart is its own reason`() {
        val decision =
            ResourceModeConfig.resolveResourceMode(
                raw = "ULTRALITE",
                os = mac,
                totalMemoryBytes = 64 * gb,
                explicitSource = ResourceModeSource.PRESSURE_RESTART,
            )
        assertEquals(BossResourceMode.ULTRA_LITE, decision.mode)
        assertEquals(ResourceModeReason.PRESSURE_RESTART, decision.reason)
    }

    @Test
    fun `every source maps to a distinct reason`() {
        val reasons = ResourceModeSource.entries.map { it.toReason() }
        assertEquals(reasons.size, reasons.toSet().size, "sources collapsed onto one reason")
    }

    @Test
    fun `thresholds are configurable`() {
        val decision =
            ResourceModeConfig.resolveResourceMode(
                raw = null,
                os = mac,
                totalMemoryBytes = 24 * gb,
                thresholds = ResourceModeThresholds(liteGb = 32, ultraLiteGb = 16),
            )
        assertEquals(BossResourceMode.LITE, decision.mode)
    }

    /**
     * An unreadable MXBean reports 0, which is "unknown", not "no memory". Treating it as a
     * small machine would let one failed reflective call put a 512 GB workstation in the
     * tightest tier.
     */
    @Test
    fun `undetectable memory does not reduce`() {
        val decision =
            ResourceModeConfig.resolveResourceMode(
                raw = null,
                os = mac,
                totalMemoryBytes = 0L,
            )
        assertEquals(BossResourceMode.FULL, decision.mode)
        assertEquals(ResourceModeReason.DEFAULT, decision.reason)
    }

    @Test
    fun `a negative reading is treated the same as unknown`() {
        val decision =
            ResourceModeConfig.resolveResourceMode(
                raw = null,
                os = mac,
                totalMemoryBytes = -1L,
            )
        assertEquals(BossResourceMode.FULL, decision.mode)
    }

    // endregion

    // region explicit override

    @Test
    fun `an explicit tier beats the platform default`() {
        // The escape hatch that matters most: a Windows user who wants the unreduced app.
        val decision = resolve(raw = "FULL", os = windows, totalGb = 8.0)
        assertEquals(BossResourceMode.FULL, decision.mode)
        assertEquals(ResourceModeReason.USER_SELECTION, decision.reason)
    }

    @Test
    fun `an explicit tier beats detected memory in both directions`() {
        assertEquals(BossResourceMode.FULL, resolve(raw = "FULL", totalGb = 2.0).mode)
        assertEquals(BossResourceMode.ULTRA_LITE, resolve(raw = "ULTRALITE", totalGb = 512.0).mode)
    }

    @Test
    fun `every spelling resolves`() {
        for (raw in listOf("FULL", "full", " Full ", "NONE", "OFF")) {
            assertEquals(BossResourceMode.FULL, resolve(raw = raw, os = windows).mode, raw)
        }
        for (raw in listOf("LITE", "lite", " Lite ")) {
            assertEquals(BossResourceMode.LITE, resolve(raw = raw, os = windows).mode, raw)
        }
        for (raw in listOf("ULTRALITE", "ULTRA_LITE", "ultra-lite", " Minimal ")) {
            assertEquals(BossResourceMode.ULTRA_LITE, resolve(raw = raw).mode, raw)
        }
    }

    /**
     * A typo must fall through to automatic selection, never to a guess. `"ULTRA"` is the
     * realistic near-miss and it must NOT be read as ULTRA_LITE.
     */
    @Test
    fun `an unrecognized value falls through to automatic selection`() {
        for (raw in listOf("ULTRA", "LIGHT", "LITEE", "yes", "1", "!!")) {
            val decision = resolve(raw = raw, os = mac, totalGb = 64.0)
            assertEquals(BossResourceMode.FULL, decision.mode, raw)
            assertNotEquals(ResourceModeReason.USER_SELECTION, decision.reason, raw)
        }
    }

    @Test
    fun `AUTO is recognized but selects nothing itself`() {
        assertTrue(ResourceModeConfig.isRecognizedResourceMode("AUTO"))
        assertTrue(ResourceModeConfig.isRecognizedResourceMode("detect"))
        // Recognized means "do not warn", not "override" - AUTO still resolves by memory.
        assertEquals(BossResourceMode.ULTRA_LITE, resolve(raw = "AUTO", totalGb = 4.0).mode)
        assertEquals(BossResourceMode.FULL, resolve(raw = "AUTO", totalGb = 64.0).mode)
    }

    @Test
    fun `recognition covers exactly the honoured spellings`() {
        for (raw in listOf("FULL", "LITE", "ULTRALITE", "ULTRA_LITE", "AUTO")) {
            assertTrue(ResourceModeConfig.isRecognizedResourceMode(raw), raw)
        }
        for (raw in listOf(null, "", "  ", "ULTRA", "SMALL")) {
            assertFalse(ResourceModeConfig.isRecognizedResourceMode(raw), raw ?: "null")
        }
    }

    // endregion

    // region tier table

    @Test
    fun `FULL constrains nothing`() {
        assertFalse(BossResourceMode.FULL.isReduced)
        assertEquals(null, BossResourceMode.FULL.rendererProcessLimit)
        assertTrue(BossResourceMode.FULL.backgroundSamplingEnabled)
    }

    @Test
    fun `LITE constrains the browser only`() {
        assertTrue(BossResourceMode.LITE.isReduced)
        assertTrue(BossResourceMode.LITE.rendererProcessLimit!! > 0)
        assertTrue(BossResourceMode.LITE.hibernationIdleMs > 0)
        assertTrue(BossResourceMode.LITE.backgroundSamplingEnabled)
    }

    @Test
    fun `ULTRA_LITE is strictly tighter than LITE`() {
        assertFalse(BossResourceMode.ULTRA_LITE.backgroundSamplingEnabled)
        assertTrue(
            BossResourceMode.ULTRA_LITE.rendererProcessLimit!! <
                BossResourceMode.LITE.rendererProcessLimit!!,
        )
        assertTrue(
            BossResourceMode.ULTRA_LITE.hibernationIdleMs < BossResourceMode.LITE.hibernationIdleMs,
        )
    }

    /**
     * Every tier hibernates, increasingly eagerly. Deliberately no "never hibernate" option, even
     * for FULL: hibernation is now the *only* browser bound. The concurrent-browser ceiling was
     * retired because it refused the tab the user had just asked for while idle ones sat
     * untouched, and because it could not coexist with hibernation at all - waking a hibernated
     * tab needs a slot, so switching tabs got refused. Plugin gating was retired for the same
     * class of reason: it took features away to save memory that hibernation reclaims silently.
     */
    @Test
    fun `every tier hibernates, and more eagerly as it tightens`() {
        for (mode in BossResourceMode.entries) {
            assertTrue(mode.hibernationIdleMs > 0, "${mode.name} must hibernate")
            mode.rendererProcessLimit?.let { assertTrue(it > 0, "${mode.name} renderer limit") }
        }
        assertTrue(BossResourceMode.FULL.hibernationIdleMs > BossResourceMode.LITE.hibernationIdleMs)
    }

    // endregion
}
