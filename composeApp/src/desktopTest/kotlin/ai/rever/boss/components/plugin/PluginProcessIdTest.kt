package ai.rever.boss.components.plugin

import ai.rever.boss.process.ManagedProcess
import ai.rever.boss.process.ProcessConfig
import ai.rever.boss.process.ProcessRegistry
import ai.rever.boss.process.ProcessType
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PluginProcessIdTest {
    private class LiveProcess(
        private val pidValue: Long,
    ) : Process() {
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = InputStream.nullInputStream()

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int = 0

        override fun exitValue(): Int = throw IllegalThreadStateException()

        override fun destroy() = Unit

        override fun isAlive(): Boolean = true

        override fun pid(): Long = pidValue
    }

    private fun managedProcess(
        processId: String,
        pid: Long,
    ) = ManagedProcess(
        config =
            ProcessConfig(
                processId = processId,
                processType = ProcessType.PLUGIN,
                displayName = processId,
                mainClass = "Main",
            ),
        process = LiveProcess(pid),
        ipcAddress = "test://$processId",
    )

    @Test
    fun `same plugin receives distinct process ids in different windows`() {
        val firstWindow = pluginProcessId("window-a", "example-plugin")
        val secondWindow = pluginProcessId("window-b", "example-plugin")

        assertTrue(firstWindow.matches(Regex("plugin-[a-f0-9]{32}")))
        assertTrue(secondWindow.matches(Regex("plugin-[a-f0-9]{32}")))
        assertNotEquals(firstWindow, secondWindow)
    }

    @Test
    fun `same plugin in two windows keeps both live registry entries`() {
        val registry = ProcessRegistry()
        val firstId = pluginProcessId("window-a", "example-plugin")
        val secondId = pluginProcessId("window-b", "example-plugin")
        val firstProcess = managedProcess(firstId, pid = 1)
        val secondProcess = managedProcess(secondId, pid = 2)

        registry.register(firstId, firstProcess)
        registry.register(secondId, secondProcess)

        assertEquals(2, registry.size)
        assertSame(firstProcess, registry.getProcess(firstId))
        assertSame(secondProcess, registry.getProcess(secondId))
    }

    @Test
    fun `long manifest and window ids fit a typical Unix socket path`() {
        val id =
            pluginProcessId(
                "123e4567-e89b-12d3-a456-426614174000",
                "ai.rever.boss.plugin.dynamic.averylongpluginname",
            )
        val socketPath = "/Users/developer/.boss_debug/ipc/boss-plugin-$id.sock"
        assertTrue(socketPath.toByteArray(Charsets.UTF_8).size < 104)
        assertNotEquals(pluginProcessId("a-b", "c"), pluginProcessId("a", "b-c"))
    }

    @Test
    fun `process id remains stable within the same window`() {
        assertEquals(
            pluginProcessId("window-a", "example-plugin"),
            pluginProcessId("window-a", "example-plugin"),
        )
    }

    @Test
    fun `empty window id preserves legacy identity for non-window contexts`() {
        assertEquals(
            "plugin-example-plugin",
            pluginProcessId("", "example-plugin"),
        )
    }
}
