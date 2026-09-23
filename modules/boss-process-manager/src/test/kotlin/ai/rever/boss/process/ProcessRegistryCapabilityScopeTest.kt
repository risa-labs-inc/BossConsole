package ai.rever.boss.process

import ai.rever.boss.ipc.proto.PluginCapability
import ai.rever.boss.ipc.proto.ProcessManifest
import ai.rever.boss.ipc.proto.ProcessState
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Capability dispatch admission is re-checked at the boundary, never trusted from registration.
 *
 * A mastery node names any (pluginId, action) pair it wants executed. Until the registry
 * gated dispatch, that pair was forwarded to whatever process matched the id: an action the
 * plugin never advertised reached the child, an action borrowed from a sibling plugin's
 * namespace was routed to the wrong owner, and a process that had crashed or been stopped
 * since registration stayed dispatchable for the rest of the session. These tests pin the
 * fail-closed contract of [ProcessRegistry.admitCapabilityDispatch]: only a RUNNING process
 * advertising the exact action in its registered manifest is dispatchable, and every refusal
 * says why.
 */
class ProcessRegistryCapabilityScopeTest {
    /** A [Process] stub; admission reads registry state, not OS liveness. */
    private class FakeProcess : Process() {
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = InputStream.nullInputStream()

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int = 0

        override fun waitFor(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = true

        override fun exitValue(): Int = 0

        override fun destroy() = Unit

        override fun isAlive(): Boolean = true

        override fun pid(): Long = 4242
    }

    private val registry = ProcessRegistry()

    private fun registerRunning(
        id: String,
        vararg actions: String,
    ): ManagedProcess {
        val manifestBuilder =
            ProcessManifest
                .newBuilder()
                .setProcessId(id)
        actions.forEach { action ->
            manifestBuilder.addCapabilities(PluginCapability.newBuilder().setAction(action).build())
        }
        val process =
            ManagedProcess(
                config =
                    ProcessConfig(
                        processId = id,
                        processType = ProcessType.SERVICE,
                        displayName = id,
                        mainClass = "Main",
                    ),
                process = FakeProcess(),
                ipcAddress = "unix:///tmp/boss-scope-test-$id.sock",
            )
        registry.register(id, process, manifestBuilder.build())
        process.updateState(ProcessState.PROCESS_STATE_RUNNING)
        return process
    }

    @Test
    fun `a running process advertising the action is dispatchable`() {
        val process = registerRunning("boss-app-terminal", "run_command")

        assertSame(process, registry.admitCapabilityDispatch("boss-app-terminal", "run_command"))
    }

    @Test
    fun `an action the plugin never advertised is refused at dispatch`() {
        registerRunning("boss-app-terminal", "run_command")

        val failure =
            assertFailsWith<IllegalStateException> {
                registry.admitCapabilityDispatch("boss-app-terminal", "read_file")
            }
        assertEquals("Plugin boss-app-terminal does not advertise capability: read_file", failure.message)
    }

    @Test
    fun `another plugin's action cannot be dispatched under a different owner id`() {
        registerRunning("boss-app-terminal", "run_command")
        registerRunning("boss-service-filesystem", "read_file")

        // read_file belongs to the filesystem service's namespace; naming the terminal as
        // the owner must not route it there.
        val failure =
            assertFailsWith<IllegalStateException> {
                registry.admitCapabilityDispatch("boss-app-terminal", "read_file")
            }
        assertEquals("Plugin boss-app-terminal does not advertise capability: read_file", failure.message)
    }

    @Test
    fun `a crashed process stays undispatchable while it remains registered`() {
        val process = registerRunning("boss-app-editor", "open_file")
        process.updateState(ProcessState.PROCESS_STATE_CRASHED)

        val failure =
            assertFailsWith<IllegalStateException> {
                registry.admitCapabilityDispatch("boss-app-editor", "open_file")
            }
        assertEquals("Process boss-app-editor is not dispatchable (state: PROCESS_STATE_CRASHED)", failure.message)
    }

    @Test
    fun `a stopped or disabled process is undispatchable`() {
        val process = registerRunning("boss-app-editor", "open_file")

        listOf(ProcessState.PROCESS_STATE_STOPPED, ProcessState.PROCESS_STATE_DISABLED).forEach { state ->
            process.updateState(state)
            val failure =
                assertFailsWith<IllegalStateException> {
                    registry.admitCapabilityDispatch("boss-app-editor", "open_file")
                }
            assertEquals("Process boss-app-editor is not dispatchable (state: ${state.name})", failure.message)
        }
    }

    @Test
    fun `a process that has not finished starting is not yet dispatchable`() {
        val process = registerRunning("boss-app-editor", "open_file")
        process.updateState(ProcessState.PROCESS_STATE_STARTING)

        val failure =
            assertFailsWith<IllegalStateException> {
                registry.admitCapabilityDispatch("boss-app-editor", "open_file")
            }
        assertEquals("Process boss-app-editor is not dispatchable (state: PROCESS_STATE_STARTING)", failure.message)
    }

    @Test
    fun `an unregistered plugin is refused`() {
        registerRunning("boss-app-editor", "open_file")
        registry.unregister("boss-app-editor")

        val failure =
            assertFailsWith<IllegalStateException> {
                registry.admitCapabilityDispatch("boss-app-editor", "open_file")
            }
        assertEquals("Process not found: boss-app-editor", failure.message)
    }
}
