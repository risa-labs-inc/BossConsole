package ai.rever.boss.updater

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the version comparison behind the macOS floor guard.
 *
 * The guard sits immediately before an irreversible `rm -rf` of the installed
 * app: getting the comparison wrong in one direction blocks every update, and in
 * the other it deletes a working install and replaces it with a build Launch
 * Services refuses to open, with no way back. The string compare that looks
 * obviously fine is the one that breaks on "10.15" vs "9.0" and on "13.0" vs
 * "13.0.1".
 */
class UpdateOsFloorTest {
    @Test
    fun `an older major is less than a newer one`() {
        assertTrue(compareVersions("12.7.6", "13.0") < 0)
    }

    @Test
    fun `a newer major is greater`() {
        assertTrue(compareVersions("14.2", "13.0") > 0)
    }

    @Test
    fun `equal versions compare equal regardless of trailing components`() {
        assertEquals(0, compareVersions("13.0", "13.0"))
        assertEquals(0, compareVersions("13.0.0", "13.0"))
    }

    @Test
    fun `a trailing patch still satisfies a two-component floor`() {
        // The real shape of the check: macOS reports 13.0.1, the bundle asks 13.0.
        assertTrue(compareVersions("13.0.1", "13.0") >= 0)
    }

    @Test
    fun `comparison is numeric, not lexicographic`() {
        // "9" > "13" as strings; the whole point is that it must not be.
        assertTrue(compareVersions("9.0", "13.0") < 0)
        assertTrue(compareVersions("10.15", "9.0") > 0)
    }

    @Test
    fun `a non-numeric component degrades to zero rather than throwing`() {
        // Exactly 0, not merely "<= 0": "13.0-beta" parses its last component as 0
        // and 13.0 has no third component, so they compare equal. Asserting the
        // looser bound would still pass if this started returning -1.
        assertEquals(0, compareVersions("13.0-beta", "13.0"))
    }

    @Test
    fun `an unsupported Mac is refused`() {
        // The load-bearing assertion: this is what stands between a macOS 12 user
        // and an irreversible rm -rf of their working install.
        val message = osFloorMessage(required = "13.0", current = "12.7.6")
        assertNotNull(message)
        assertTrue(message.contains("13.0") && message.contains("12.7.6"))
    }

    @Test
    fun `a supported Mac is allowed`() {
        // Pinned separately from the case above so an inverted argument order is
        // caught. Swapping `required` and `current` at the call site keeps every
        // comparator test green while blocking all supported Macs — this pair is
        // the only thing that fails.
        assertNull(osFloorMessage(required = "13.0", current = "13.0.1"))
        assertNull(osFloorMessage(required = "13.0", current = "14.2"))
    }

    @Test
    fun `unknown versions fail open rather than blocking the update`() {
        // Blank must mean "unknown", not "version zero" — treating it as zero would
        // sort below any floor and refuse every update.
        assertNull(osFloorMessage(required = null, current = "12.0"))
        assertNull(osFloorMessage(required = "13.0", current = null))
        assertNull(osFloorMessage(required = "13.0", current = ""))
        assertNull(osFloorMessage(required = "", current = "12.0"))
    }

    @Test
    fun `a bundle with no Info-plist reads as unknown rather than blocking`() {
        // Asserted against readMinimumSystemVersion directly, not unsupportedOsError:
        // that one short-circuits on os.name before touching the plist, so on the
        // Linux and Windows legs — two of the three — it asserted nothing and would
        // sleep through a regression in this branch. The plist.exists() guard
        // returns before ProcessBuilder, so this stays pure and runs everywhere.
        val bundle = createTempDirectory("no-plist").toFile()
        try {
            assertNull(readMinimumSystemVersion(bundle))
        } finally {
            bundle.deleteRecursively()
        }
    }
}
