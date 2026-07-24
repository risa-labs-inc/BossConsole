package ai.rever.boss.ipc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IpcVersionTest {
    @Test
    fun `parse splits well-formed semver`() {
        assertEquals(Triple(1, 2, 3), IpcVersion.parse("1.2.3"))
        assertEquals(Triple(10, 0, 0), IpcVersion.parse("10.0.0"))
    }

    @Test
    fun `parse tolerates pre-release and build metadata`() {
        assertEquals(Triple(1, 2, 3), IpcVersion.parse("1.2.3-beta.1"))
        assertEquals(Triple(1, 2, 3), IpcVersion.parse("1.2.3+build.9"))
        assertEquals(Triple(1, 2, 3), IpcVersion.parse("1.2.3-rc.2+sha.abc"))
    }

    @Test
    fun `parse returns null for malformed input`() {
        assertNull(IpcVersion.parse(""))
        assertNull(IpcVersion.parse("1.2"))
        assertNull(IpcVersion.parse("v1.2.3"))
        assertNull(IpcVersion.parse("abc"))
        assertNull(IpcVersion.parse("1.x.3"))
    }

    @Test
    fun `blank minIpcVersion is UnknownRuntime (legacy)`() {
        val r = IpcVersion.isCompatible(runtimeMinIpcVersion = "", hostIpcVersion = "1.0.0")
        assertTrue(r is IpcVersion.CompatResult.UnknownRuntime)
    }

    @Test
    fun `same version compatible`() {
        val r = IpcVersion.isCompatible("1.0.0", "1.0.0")
        assertEquals(IpcVersion.CompatResult.Compatible, r)
    }

    @Test
    fun `runtime patch-older-than-host is compatible`() {
        val r = IpcVersion.isCompatible("1.2.3", "1.2.5")
        assertEquals(IpcVersion.CompatResult.Compatible, r)
    }

    @Test
    fun `runtime minor-older-than-host is compatible`() {
        val r = IpcVersion.isCompatible("1.1.0", "1.5.0")
        assertEquals(IpcVersion.CompatResult.Compatible, r)
    }

    @Test
    fun `runtime minor-newer-than-host is incompatible (host too old)`() {
        val r = IpcVersion.isCompatible("1.5.0", "1.2.0")
        val inc = r as? IpcVersion.CompatResult.Incompatible
        assertNotNull(inc)
        assertTrue(inc.reason.contains("Update BossConsole"), "got: ${inc.reason}")
    }

    @Test
    fun `runtime patch-newer-than-host is incompatible`() {
        val r = IpcVersion.isCompatible("1.0.5", "1.0.3")
        assertTrue(r is IpcVersion.CompatResult.Incompatible)
    }

    @Test
    fun `major mismatch host-newer is incompatible and asks to update runtime`() {
        val r = IpcVersion.isCompatible("1.9.9", "2.0.0")
        val inc = r as? IpcVersion.CompatResult.Incompatible
        assertNotNull(inc)
        assertTrue(inc.reason.contains("Update the runtime JAR"), "got: ${inc.reason}")
    }

    @Test
    fun `major mismatch runtime-newer is incompatible and asks to update BossConsole`() {
        val r = IpcVersion.isCompatible("2.0.0", "1.9.9")
        val inc = r as? IpcVersion.CompatResult.Incompatible
        assertNotNull(inc)
        assertTrue(inc.reason.contains("Update BossConsole"), "got: ${inc.reason}")
    }

    @Test
    fun `unparseable runtime version is incompatible`() {
        val r = IpcVersion.isCompatible("not-a-version", "1.0.0")
        assertTrue(r is IpcVersion.CompatResult.Incompatible)
    }

    @Test
    fun `CURRENT is itself a parseable semver`() {
        assertNotNull(IpcVersion.parse(IpcVersion.CURRENT))
    }
}
