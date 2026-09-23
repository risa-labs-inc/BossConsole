package ai.rever.boss.mastery.orchestrator

import ai.rever.boss.ipc.proto.InvokeCapabilityRequest
import ai.rever.boss.ipc.proto.InvokeCapabilityResponse
import ai.rever.boss.ipc.proto.PluginCapability
import ai.rever.boss.ipc.proto.ProcessManifest
import ai.rever.boss.ipc.proto.ProcessState
import ai.rever.boss.process.ManagedProcess
import ai.rever.boss.process.ProcessConfig
import ai.rever.boss.process.ProcessRegistry
import ai.rever.boss.process.ProcessType
import kotlinx.coroutines.test.runTest
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The capability resolver refuses out-of-scope dispatches before any dial.
 *
 * A mastery node names (pluginId, action) and the resolver used to forward that pair to
 * whatever process matched the id, without asking whether the plugin advertised the action
 * or was still alive. These tests drive the real resolver against a recording fake
 * transport: an unadvertised action, an action belonging to a sibling plugin's namespace,
 * an unknown plugin and a plugin that crashed since registration all fail with a scope
 * refusal and never reach the wire, while the granted call dials exactly the admitted pair
 * and returns the child's output.
 */
class ProcessRegistryCapabilityResolverDispatchTest {
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

    /** Records every dial the boundary lets through and answers with a canned success. */
    private class RecordingDial {
        val requests = mutableListOf<InvokeCapabilityRequest>()

        val dial: suspend (ManagedProcess, InvokeCapabilityRequest) -> InvokeCapabilityResponse =
            { _, request ->
                requests += request
                InvokeCapabilityResponse
                    .newBuilder()
                    .setSuccess(true)
                    .putOutput("echo", request.action)
                    .build()
            }
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
                ipcAddress = "unix:///tmp/boss-resolver-test-$id.sock",
            )
        registry.register(id, process, manifestBuilder.build())
        process.updateState(ProcessState.PROCESS_STATE_RUNNING)
        return process
    }

    @Test
    fun `an action the plugin never advertised is refused before any dial`() =
        runTest {
            registerRunning("boss-app-terminal", "run_command")
            val transport = RecordingDial()
            val resolver = ProcessRegistryCapabilityResolver(registry, transport.dial)

            val failure =
                assertFailsWith<IllegalStateException> {
                    resolver.invoke("boss-app-terminal", "read_file", emptyMap())
                }
            assertEquals("Plugin boss-app-terminal does not advertise capability: read_file", failure.message)
            assertTrue(transport.requests.isEmpty(), "a refused dispatch must not reach any transport")
        }

    @Test
    fun `an action of another plugin is refused under a different owner id`() =
        runTest {
            registerRunning("boss-app-terminal", "run_command")
            registerRunning("boss-service-filesystem", "read_file")
            val transport = RecordingDial()
            val resolver = ProcessRegistryCapabilityResolver(registry, transport.dial)

            val failure =
                assertFailsWith<IllegalStateException> {
                    resolver.invoke("boss-app-terminal", "read_file", emptyMap())
                }
            assertEquals("Plugin boss-app-terminal does not advertise capability: read_file", failure.message)
            assertTrue(transport.requests.isEmpty(), "a shadowed dispatch must not reach any transport")
        }

    @Test
    fun `an unknown plugin is refused before any dial`() =
        runTest {
            registerRunning("boss-app-terminal", "run_command")
            val transport = RecordingDial()
            val resolver = ProcessRegistryCapabilityResolver(registry, transport.dial)

            val failure =
                assertFailsWith<IllegalStateException> {
                    resolver.invoke("boss-unknown", "run_command", emptyMap())
                }
            assertEquals("Process not found: boss-unknown", failure.message)
            assertTrue(transport.requests.isEmpty(), "an unknown target must not reach any transport")
        }

    @Test
    fun `a plugin that crashed after registration is refused before any dial`() =
        runTest {
            val process = registerRunning("boss-app-editor", "open_file")
            val transport = RecordingDial()
            val resolver = ProcessRegistryCapabilityResolver(registry, transport.dial)
            process.updateState(ProcessState.PROCESS_STATE_CRASHED)

            val failure =
                assertFailsWith<IllegalStateException> {
                    resolver.invoke("boss-app-editor", "open_file", emptyMap())
                }
            assertEquals("Process boss-app-editor is not dispatchable (state: PROCESS_STATE_CRASHED)", failure.message)
            assertTrue(transport.requests.isEmpty(), "a crashed target must not reach any transport")
        }

    @Test
    fun `a granted call dials the admitted process and returns its output`() =
        runTest {
            registerRunning("boss-app-terminal", "run_command")
            val transport = RecordingDial()
            val resolver = ProcessRegistryCapabilityResolver(registry, transport.dial)

            val output = resolver.invoke("boss-app-terminal", "run_command", mapOf("command" to "ls"))

            assertEquals(mapOf("echo" to "run_command"), output)
            assertEquals(1, transport.requests.size)
            assertEquals("boss-app-terminal", transport.requests.single().pluginId)
            assertEquals("run_command", transport.requests.single().action)
        }
}
