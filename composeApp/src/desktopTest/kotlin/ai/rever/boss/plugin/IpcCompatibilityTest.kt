package ai.rever.boss.plugin

import ai.rever.boss.ipc.IpcVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies [IpcCompatibility]'s status rules and that it reflects
 * `IpcVersion.CURRENT` correctly. Inputs are derived from the live host
 * version so the assertions survive a future bump of the IPC contract.
 */
class IpcCompatibilityTest {
    private val host =
        IpcVersion.parse(IpcVersion.CURRENT)
            ?: error("IpcVersion.CURRENT not parseable: ${IpcVersion.CURRENT}")

    @Test
    fun `hostVersion reflects IpcVersion CURRENT`() {
        assertEquals(IpcVersion.CURRENT, IpcCompatibility.hostVersion)
    }

    @Test
    fun `blank version is unknown and installable`() {
        assertEquals(IpcCompatibility.Status.UNKNOWN, IpcCompatibility.status(""))
        assertTrue(IpcCompatibility.isInstallable(""))
    }

    @Test
    fun `equal version is compatible and installable`() {
        assertEquals(IpcCompatibility.Status.COMPATIBLE, IpcCompatibility.status(IpcVersion.CURRENT))
        assertTrue(IpcCompatibility.isInstallable(IpcVersion.CURRENT))
    }

    @Test
    fun `newer minor within same major requires host update`() {
        val tooNew = "${host.first}.${host.second + 1}.0"
        assertEquals(IpcCompatibility.Status.REQUIRES_HOST_UPDATE, IpcCompatibility.status(tooNew))
        assertTrue(!IpcCompatibility.isInstallable(tooNew))
    }

    @Test
    fun `newer major is a major mismatch`() {
        val nextMajor = "${host.first + 1}.0.0"
        assertEquals(IpcCompatibility.Status.MAJOR_MISMATCH, IpcCompatibility.status(nextMajor))
        assertTrue(!IpcCompatibility.isInstallable(nextMajor))
    }
}
