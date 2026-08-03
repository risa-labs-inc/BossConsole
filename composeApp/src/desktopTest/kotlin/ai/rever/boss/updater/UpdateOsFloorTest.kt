package ai.rever.boss.updater

import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertTrue(UpdateInstaller.compareVersions("12.7.6", "13.0") < 0)
    }

    @Test
    fun `a newer major is greater`() {
        assertTrue(UpdateInstaller.compareVersions("14.2", "13.0") > 0)
    }

    @Test
    fun `equal versions compare equal regardless of trailing components`() {
        assertEquals(0, UpdateInstaller.compareVersions("13.0", "13.0"))
        assertEquals(0, UpdateInstaller.compareVersions("13.0.0", "13.0"))
    }

    @Test
    fun `a trailing patch still satisfies a two-component floor`() {
        // The real shape of the check: macOS reports 13.0.1, the bundle asks 13.0.
        assertTrue(UpdateInstaller.compareVersions("13.0.1", "13.0") >= 0)
    }

    @Test
    fun `comparison is numeric, not lexicographic`() {
        // "9" > "13" as strings; the whole point is that it must not be.
        assertTrue(UpdateInstaller.compareVersions("9.0", "13.0") < 0)
        assertTrue(UpdateInstaller.compareVersions("10.15", "9.0") > 0)
    }

    @Test
    fun `a non-numeric component degrades to zero rather than throwing`() {
        assertTrue(UpdateInstaller.compareVersions("13.0-beta", "13.0") <= 0)
    }
}
