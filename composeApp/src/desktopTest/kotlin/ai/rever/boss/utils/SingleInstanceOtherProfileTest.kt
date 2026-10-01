package ai.rever.boss.utils

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The calls one BOSS profile uses to reach another profile's instance, by that profile's runtime
 * directory: they must see a live peer there, and nothing where none answers.
 */
class SingleInstanceOtherProfileTest {
    @TempDir
    lateinit var tempDir: Path

    @AfterEach
    fun tearDown() {
        SingleInstanceManager.release()
        SingleInstanceManager.runtimeDirOverride = null
    }

    @Test
    fun `a runtime directory with no descriptor has no instance and accepts nothing`() {
        val empty = File(tempDir.toFile(), "nobody/run")
        assertFalse(SingleInstanceManager.isInstanceRunningAt(empty))
        assertFalse(SingleInstanceManager.sendToInstanceAt(empty, "boss://workspace?path=%2Fx.json"))
    }

    @Test
    fun `a live instance is seen through its runtime directory, and only there`() {
        val peerRun = File(tempDir.toFile(), "peer/run")
        SingleInstanceManager.runtimeDirOverride = peerRun
        assertTrue(SingleInstanceManager.acquireLock())

        assertTrue(SingleInstanceManager.isInstanceRunningAt(peerRun))
        assertFalse(SingleInstanceManager.isInstanceRunningAt(File(tempDir.toFile(), "other/run")))
    }

    @Test
    fun `a blank link is refused before any connection`() {
        assertFalse(SingleInstanceManager.sendToInstanceAt(File(tempDir.toFile(), "x/run"), "  "))
    }

    @Test
    fun `an instance that has gone is no longer seen`() {
        val peerRun = File(tempDir.toFile(), "peer/run")
        SingleInstanceManager.runtimeDirOverride = peerRun
        assertTrue(SingleInstanceManager.acquireLock())
        SingleInstanceManager.release()
        assertFalse(SingleInstanceManager.isInstanceRunningAt(peerRun))
    }
}
