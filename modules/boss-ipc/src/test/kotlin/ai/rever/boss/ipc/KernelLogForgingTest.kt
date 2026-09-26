package ai.rever.boss.ipc

import ai.rever.boss.ipc.auth.ProcessAuthority
import ai.rever.boss.ipc.proto.InvokeCapabilityRequest
import ai.rever.boss.ipc.proto.KernelServiceGrpcKt
import ai.rever.boss.ipc.proto.PluginCapability
import ai.rever.boss.ipc.proto.ProcessManifest
import ai.rever.boss.ipc.proto.ProcessState
import ai.rever.boss.ipc.proto.ProcessStatusRequest
import ai.rever.boss.ipc.proto.ProcessType
import ai.rever.boss.ipc.proto.RegisterProcessRequest
import ai.rever.boss.ipc.proto.ShutdownRequest
import ai.rever.boss.ipc.proto.StateKey
import ai.rever.boss.ipc.proto.StateServiceGrpcKt
import ai.rever.boss.ipc.proto.StateUpdate
import ai.rever.boss.ipc.services.KernelServiceImpl
import ai.rever.boss.ipc.services.StateServiceImpl
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.google.protobuf.ByteString
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A child cannot use an IPC message to forge records in the kernel's own log (CWE-117).
 *
 * Everything that reads the kernel log - the in-app log panel, the console capture, `boss
 * log-parse`, grep, tail - treats a newline as the end of a record. IPC messages carry text the
 * kernel did not author: a registration manifest's display name, a state key, a supervisor's
 * shutdown target, a capability invocation's requested action. The identity layer proves who
 * a message came from, not what it says, so a hostile child could make the kernel print a
 * second line reading exactly like a record the kernel produced - a forged ERROR about a
 * sibling, a fake audit line - with no credential involved.
 *
 * [IpcLogText.neutralize] (the kernel-side twin of the plugin logger's LogLineText) is applied at
 * the log sites only: registration still succeeds, the stored value stays original, and the
 * control characters that would have split the record are escaped onto one line. The end-to-end
 * tests run through the authenticated transport so the logged text is exactly what a real hostile
 * child sends, and assert on the captured rendered record, not the raw field.
 *
 * Control characters are built from code points, not string escapes, because the repo's ktlint
 * rewrites escapes into raw invisible characters.
 */
class KernelLogForgingTest {
    @Test
    fun `neutralize keeps clean text and tab layout exactly as it was`() {
        val tab = 0x09.toChar()
        val clean = "billing worker 2 (tab" + tab + "kept)"

        assertEquals(clean, IpcLogText.neutralize(clean))
    }

    @Test
    fun `literal backslashes remain distinct from escaped controls`() {
        assertEquals("alpha\\\\n", IpcLogText.neutralize("alpha\\n"))
        assertEquals("alpha\\n", IpcLogText.neutralize("alpha" + 0x0a.toChar()))
        assertEquals("\\\\u0000", IpcLogText.neutralize("\\u0000"))
    }

    @Test
    fun `hidden BMP characters and record separators are escaped`() {
        val cases =
            mapOf(
                0x00 to "\\u0000",
                0x7f to "\\u007f",
                0x00ad to "\\u00ad",
                0xfeff to "\\ufeff",
                0x2029 to "\\u2029",
            )
        cases.forEach { (codePoint, expected) ->
            assertEquals(expected, IpcLogText.neutralize(codePoint.toChar().toString()))
        }
    }

    @Test
    fun `neutralize escapes every way a child can end a log record`() {
        val lf = 0x0a.toChar()
        val cr = 0x0d.toChar()
        val nel = 0x85.toChar()
        val escape = 0x1b.toChar()
        val lineSeparator = 0x2028.toChar()
        val rtlOverride = 0x202e.toChar()
        val zeroWidthSpace = 0x200b.toChar()
        val hostile =
            "child" + lf + cr + nel + lineSeparator + escape + "[2J" + rtlOverride + "admin" + zeroWidthSpace

        assertEquals("child\\n\\r\\u0085\\u2028\\u001b[2J\\u202eadmin\\u200b", IpcLogText.neutralize(hostile))
    }

    @Test
    fun `supplementary format characters are escaped without damaging visible emoji`() {
        val languageTag = String(Character.toChars(0xE0001))
        val tagSpace = String(Character.toChars(0xE0020))
        val musicalFormat = String(Character.toChars(0x1D173))
        assertEquals("\\udb40\\udc20", IpcLogText.neutralize(tagSpace))
        assertEquals("\\ud834\\udd73", IpcLogText.neutralize(musicalFormat))
        val emoji = String(Character.toChars(0x1F600))
        val input = "visible$emoji$languageTag\u00a0end"
        assertEquals("visible${emoji}\\udb40\\udc01\\u00a0end", IpcLogText.neutralize(input))
    }

    @Test
    fun `a registering child cannot forge records in the kernel log`() =
        runBlocking {
            val lf = 0x0a.toChar()
            val cr = 0x0d.toChar()
            val registeredManifest = AtomicReference<ProcessManifest?>()
            val kernel = KernelServiceImpl(onProcessRegistered = { _, manifest, _ -> registeredManifest.set(manifest) })
            IpcTestServer(kernel).use { host ->
                LogCapture(KernelServiceImpl::class.java).use { capture ->
                    val alpha =
                        KernelServiceGrpcKt.KernelServiceCoroutineStub(host.channelFor("alpha", ADDRESS))
                    val forgedMarker = "2026-09-20 12:00:00.000 [ERROR] Session for admin revoked"
                    val hostileName = "alpha" + lf + forgedMarker + cr + "second forged line"
                    val registration =
                        RegisterProcessRequest
                            .newBuilder()
                            .setManifest(
                                ProcessManifest
                                    .newBuilder()
                                    .setProcessId("alpha")
                                    .setDisplayName(hostileName)
                                    .build(),
                            ).setIpcAddress(ADDRESS)
                            .build()

                    val response = alpha.registerProcess(registration)

                    // The hostile name is stored as-is: neutralizing is for the log, not the message.
                    assertTrue(response.success)
                    assertEquals("alpha", response.assignedProcessId)
                    assertEquals(hostileName, registeredManifest.get()?.displayName)
                    assertEquals(
                        ProcessState.PROCESS_STATE_RUNNING,
                        alpha
                            .getProcessStatus(
                                ProcessStatusRequest.newBuilder().setProcessId("alpha").build(),
                            ).state,
                    )
                    val records = capture.lines()
                    assertTrue(records.isNotEmpty())
                    records.forEach { record ->
                        assertTrue(
                            lf !in record && cr !in record,
                            "a kernel log record was split onto several lines: $record",
                        )
                    }
                    val registering = records.first { it.contains("Process registering") }
                    assertTrue(registering.contains("alpha\\n$forgedMarker\\rsecond forged line"))
                    assertEquals(1, records.count { it.contains(forgedMarker) })
                }
            }
        }

    @Test
    fun `a supervisor cannot forge records through a shutdown target id`() =
        runBlocking {
            val lf = 0x0a.toChar()
            val kernel = KernelServiceImpl(onShutdownRequested = { _, _ -> false })
            IpcTestServer(kernel).use { host ->
                LogCapture(KernelServiceImpl::class.java).use { capture ->
                    val supervisor =
                        KernelServiceGrpcKt.KernelServiceCoroutineStub(
                            host.channelFor("orchestrator", authority = ProcessAuthority.SUPERVISOR),
                        )
                    val forgedMarker = "2026-09-20 [WARN] orchestrator drained the token store"
                    val target = "beta" + lf + forgedMarker

                    val response =
                        supervisor.requestShutdown(
                            ShutdownRequest
                                .newBuilder()
                                .setProcessId(target)
                                .setForce(true)
                                .build(),
                        )

                    assertFalse(response.success)
                    val records = capture.lines()
                    records.forEach { record ->
                        assertTrue(
                            lf !in record,
                            "a kernel log record was split onto several lines: $record",
                        )
                    }
                    val requested = records.first { it.contains("Shutdown requested") }
                    assertTrue(requested.contains("beta\\n$forgedMarker"))
                }
            }
        }

    @Test
    fun `a caller cannot forge records through a refused capability invocation`() =
        runBlocking<Unit> {
            val lf = 0x0a.toChar()
            val kernel = KernelServiceImpl()
            IpcTestServer(kernel).use { host ->
                LogCapture(KernelServiceImpl::class.java).use { capture ->
                    val plugin =
                        KernelServiceGrpcKt
                            .KernelServiceCoroutineStub(host.channelFor("capability-plugin", ADDRESS))
                    val registration =
                        RegisterProcessRequest
                            .newBuilder()
                            .setManifest(
                                ProcessManifest
                                    .newBuilder()
                                    .setProcessId("capability-plugin")
                                    .setProcessType(ProcessType.PROCESS_TYPE_SERVICE)
                                    .addCapabilities(
                                        PluginCapability
                                            .newBuilder()
                                            .setAction("echo")
                                            .setDescription("Echoes the say input")
                                            .build(),
                                    ).build(),
                            ).setIpcAddress(ADDRESS)
                            .build()
                    assertTrue(plugin.registerProcess(registration).success)

                    val supervisor =
                        KernelServiceGrpcKt.KernelServiceCoroutineStub(
                            host.channelFor("orchestrator", authority = ProcessAuthority.SUPERVISOR),
                        )
                    val forgedMarker = "2026-09-25 [ERROR] orchestrator drained the token store"
                    val hostileAction = "echo" + lf + forgedMarker

                    val response =
                        supervisor.invokeCapability(
                            InvokeCapabilityRequest
                                .newBuilder()
                                .setPluginId("capability-plugin")
                                .setAction(hostileAction)
                                .build(),
                        )

                    // The refusal names the hostile action verbatim to the authenticated
                    // caller; neutralizing is for the log, not the message.
                    assertFalse(response.success)
                    assertEquals(
                        "Plugin capability-plugin does not advertise capability: $hostileAction",
                        response.errorMessage,
                    )
                    val records = capture.lines()
                    records.forEach { record ->
                        assertTrue(
                            lf !in record,
                            "a kernel log record was split onto several lines: $record",
                        )
                    }
                    val refusal = records.first { it.contains("Capability invocation refused") }
                    assertTrue(refusal.contains("echo\\n$forgedMarker"))
                    assertEquals(1, records.count { it.contains(forgedMarker) })
                }
            }
        }

    @Test
    fun `a hostile state key cannot forge records in the kernel log`() =
        runBlocking {
            val lf = 0x0a.toChar()
            val state = StateServiceImpl()
            IpcTestServer(state).use { host ->
                LogCapture(StateServiceImpl::class.java).use { capture ->
                    val alpha = StateServiceGrpcKt.StateServiceCoroutineStub(host.channelFor("alpha"))
                    val forgedMarker = "2026-09-20 [ERROR] billing credentials rotated"
                    val hostileKey = "billing.state" + lf + forgedMarker

                    val created = alpha.setState(update(hostileKey, expectedVersion = 0))
                    val conflicted = alpha.setState(update(hostileKey, expectedVersion = WRONG_VERSION))

                    assertEquals(1, created.version)
                    assertEquals(1, conflicted.version)
                    val stored = alpha.getState(StateKey.newBuilder().setKey(hostileKey).build())
                    assertEquals(hostileKey, stored.key)
                    assertEquals(1, stored.version)
                    val records = capture.lines()
                    records.forEach { record ->
                        assertTrue(
                            lf !in record,
                            "a kernel log record was split onto several lines: $record",
                        )
                    }
                    val conflict = records.first { it.contains("State update conflict") }
                    assertTrue(conflict.contains("billing.state\\n$forgedMarker"))
                    val updated = records.first { it.contains("State updated") }
                    assertTrue(updated.contains("billing.state\\n$forgedMarker"))
                }
            }
        }

    private fun update(
        key: String,
        expectedVersion: Long,
    ): StateUpdate =
        StateUpdate
            .newBuilder()
            .setKey(key)
            .setValue(ByteString.copyFromUtf8("value"))
            .setExpectedVersion(expectedVersion)
            .build()

    /**
     * Captures every record [owner]'s logger emits, at DEBUG, so assertions run against the
     * rendered record rather than the raw field. Logback is on the test classpath precisely for
     * this; production modules never see it.
     */
    private class LogCapture(
        owner: Class<*>,
    ) : AutoCloseable {
        private val logger = LoggerFactory.getLogger(owner) as Logger
        private val originalLevel: Level? = logger.level
        private val appender = ListAppender<ILoggingEvent>()

        init {
            appender.start()
            logger.level = Level.DEBUG
            logger.addAppender(appender)
        }

        fun lines(): List<String> = appender.list.map { it.formattedMessage }

        override fun close() {
            logger.level = originalLevel
            logger.detachAppender(appender)
        }
    }

    private companion object {
        const val ADDRESS = "tcp://127.0.0.1:59000"
        const val WRONG_VERSION = 999L
    }
}
