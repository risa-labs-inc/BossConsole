package ai.rever.boss.updater

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingMacOSUpdateTest {
    @TempDir
    lateinit var directory: File

    private class HelperProcess(
        private val alive: () -> Boolean,
    ) : Process() {
        override fun isAlive(): Boolean = alive()

        override fun getOutputStream() = ByteArrayOutputStream()

        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())

        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())

        override fun waitFor(): Int = 0

        override fun exitValue(): Int = 1

        override fun destroy() = Unit
    }

    @Test
    fun `live helper is armed for its exact download`() {
        val marker = File(directory, "windowless.request")
        val pending = PendingMacOSUpdate("BOSS.dmg", marker, HelperProcess { true })
        assertTrue(pending.armWindowlessRelaunch("BOSS.dmg"))
        assertEquals("--no-window\n", marker.readText())
    }

    @Test
    fun `different download cannot arm the helper`() {
        val marker = File(directory, "windowless.request")
        val pending = PendingMacOSUpdate("BOSS.dmg", marker, HelperProcess { true })
        assertFalse(pending.armWindowlessRelaunch("other.dmg"))
        assertFalse(marker.exists())
    }

    @Test
    fun `exited helper does not create a marker`() {
        val marker = File(directory, "windowless.request")
        val pending = PendingMacOSUpdate("BOSS.dmg", marker, HelperProcess { false })
        assertFalse(pending.armWindowlessRelaunch("BOSS.dmg"))
        assertFalse(marker.exists())
    }

    @Test
    fun `helper exiting while arming does not quit or leave an orphan marker`() {
        val marker = File(directory, "windowless.request")
        var aliveChecks = 0
        val helper =
            HelperProcess {
                aliveChecks++
                if (aliveChecks == 2) assertTrue(marker.exists(), "The second liveness check must follow the write")
                aliveChecks == 1
            }
        val pending = PendingMacOSUpdate("BOSS.dmg", marker, helper)
        assertFalse(pending.armWindowlessRelaunch("BOSS.dmg"))
        assertEquals(2, aliveChecks)
        assertFalse(marker.exists())
    }

    @Test
    fun `marker write failure leaves the app running`() {
        val marker = File(directory, "windowless.request").apply { mkdir() }
        val pending = PendingMacOSUpdate("BOSS.dmg", marker, HelperProcess { true })
        assertFalse(pending.armWindowlessRelaunch("BOSS.dmg"))
    }

    @Test
    fun `partial marker write cannot relaunch after a later manual quit`() {
        val marker = File(directory, "windowless.request")
        val pending =
            PendingMacOSUpdate("BOSS.dmg", marker, HelperProcess { true }) {
                it.writeText("--no")
                throw IOException("filesystem full")
            }
        assertFalse(pending.armWindowlessRelaunch("BOSS.dmg"))
        assertFalse(marker.exists(), "The helper must not see a relaunch request after failed preparation")
    }
}
