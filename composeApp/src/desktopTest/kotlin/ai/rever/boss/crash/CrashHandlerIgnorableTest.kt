package ai.rever.boss.crash

import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.exception.TokenExpiredException
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.channel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Unit tests for [CrashHandler.isIgnorable] — recoverable background failures
 * must not pop the crash dialog (dismissing it exits the app).
 */
class CrashHandlerIgnorableTest {
    // First four frames copied from BossConsole-Releases#28, not inferred from SDK source.
    private fun staleRealtimeRejoin(): IllegalStateException =
        IllegalStateException("Websocket not yet initialized").apply {
            stackTrace =
                arrayOf(
                    StackTraceElement(
                        "io.github.jan.supabase.realtime.RealtimeImpl",
                        "getWebsocket",
                        "RealtimeImpl.kt",
                        58,
                    ),
                    StackTraceElement(
                        "io.github.jan.supabase.realtime.RealtimeChannelImpl",
                        "unsubscribe",
                        "RealtimeChannelImpl.kt",
                        196,
                    ),
                    StackTraceElement(
                        "io.github.jan.supabase.realtime.RealtimeChannelImpl",
                        "resubscribe",
                        "RealtimeChannelImpl.kt",
                        356,
                    ),
                    StackTraceElement(
                        "io.github.jan.supabase.realtime.RealtimeChannelImpl",
                        "scheduleRejoin",
                        "RealtimeChannelImpl.kt",
                        190,
                    ),
                )
        }

    // Frames copied from the BossConsole#1739 release-testing crash trace, not inferred from SDK source.
    private fun realtimeHeartbeatRace(): IllegalStateException =
        IllegalStateException("Websocket not yet initialized").apply {
            stackTrace =
                arrayOf(
                    StackTraceElement(
                        "io.github.jan.supabase.realtime.RealtimeImpl",
                        "getWebsocket",
                        "RealtimeImpl.kt",
                        58,
                    ),
                    StackTraceElement(
                        "io.github.jan.supabase.realtime.RealtimeImpl",
                        "sendHeartbeat",
                        "RealtimeImpl.kt",
                        263,
                    ),
                    StackTraceElement(
                        "io.github.jan.supabase.realtime.RealtimeImpl",
                        "startHeartbeating",
                        "RealtimeImpl.kt",
                        198,
                    ),
                )
        }

    @Test
    @OptIn(SupabaseInternal::class)
    fun `installed SDK delayed rejoin produces the recognized failure`() =
        runBlocking {
            val client =
                createSupabaseClient("https://realtime-test.invalid", "test-key") {
                    install(Realtime) { rejoinDelay = 1.milliseconds }
                }
            try {
                // Reproduce the disconnected state at retry wake-up without making a network request.
                val channel = client.channel("retry-test")
                val failure = assertFailsWith<IllegalStateException> { channel.scheduleRejoin() }
                assertTrue(CrashHandler.isIgnorable(failure), failure.stackTraceToString())
                val directFailure = assertFailsWith<IllegalStateException> { channel.unsubscribe() }
                assertFalse(CrashHandler.isIgnorable(directFailure))
            } finally {
                client.close()
            }
        }

    @Test
    fun `missing or changed stack evidence remains reportable`() {
        val empty = staleRealtimeRejoin().apply { stackTrace = emptyArray() }
        assertFalse(CrashHandler.isIgnorable(empty))
        val changed =
            staleRealtimeRejoin().apply {
                stackTrace = arrayOf(StackTraceElement("other.library.Wrapper", "invoke", "Wrapper.kt", 1)) + stackTrace
            }
        assertFalse(CrashHandler.isIgnorable(changed))
    }

    @Test
    fun `issue 28 stale realtime rejoin is recoverable`() {
        assertTrue(CrashHandler.isIgnorable(staleRealtimeRejoin()))
        assertTrue(CrashHandler.isIgnorable(RuntimeException("background retry failed", staleRealtimeRejoin())))
    }

    @Test
    fun `issue 1739 realtime heartbeat disconnect race is recoverable`() {
        assertTrue(CrashHandler.isIgnorable(realtimeHeartbeatRace()))
        assertTrue(CrashHandler.isIgnorable(RuntimeException("heartbeat tick failed", realtimeHeartbeatRace())))
    }

    @Test
    fun `heartbeat race lookalikes remain reportable`() {
        val frames = realtimeHeartbeatRace().stackTrace
        assertFalse(CrashHandler.isIgnorable(IllegalStateException("another failure").apply { stackTrace = frames }))
        val wrongType = RuntimeException("Websocket not yet initialized").apply { stackTrace = frames }
        assertFalse(CrashHandler.isIgnorable(wrongType))
        assertFalse(
            CrashHandler.isIgnorable(
                IllegalStateException("Websocket not yet initialized").apply {
                    stackTrace =
                        frames
                            .map {
                                StackTraceElement("other.library.Realtime", it.methodName, it.fileName, it.lineNumber)
                            }.toTypedArray()
                },
            ),
        )
        val empty = realtimeHeartbeatRace().apply { stackTrace = emptyArray() }
        assertFalse(CrashHandler.isIgnorable(empty))
        // Pins the deliberate two-frame boundary: an app frame past the prefix stays
        // ignorable, because only the library's own getter throws this message and the
        // third frame varies with coroutine stack recovery.
        val appFramePastPrefix =
            realtimeHeartbeatRace().apply {
                stackTrace = stackTrace.take(2).toTypedArray() +
                    StackTraceElement("ai.rever.boss.Application", "onCrashed", "Application.kt", 1)
            }
        assertTrue(CrashHandler.isIgnorable(appFramePastPrefix))
    }

    @Test
    fun `matching message from application code is still a crash`() {
        assertFalse(CrashHandler.isIgnorable(IllegalStateException("Websocket not yet initialized")))
    }

    @Test
    fun `direct unsubscribe without a connection is still a crash`() {
        val failure =
            staleRealtimeRejoin().apply {
                stackTrace = stackTrace.take(2).toTypedArray() +
                    StackTraceElement("ai.rever.boss.Application", "unsubscribe", "Application.kt", 1)
            }
        assertFalse(CrashHandler.isIgnorable(failure))
    }

    @Test
    fun `other errors on the retry path remain visible`() {
        val frames = staleRealtimeRejoin().stackTrace
        assertFalse(CrashHandler.isIgnorable(IllegalStateException("another failure").apply { stackTrace = frames }))
        val wrongType = RuntimeException("Websocket not yet initialized").apply { stackTrace = frames }
        assertFalse(CrashHandler.isIgnorable(wrongType))
        assertFalse(
            CrashHandler.isIgnorable(
                IllegalStateException("Websocket not yet initialized").apply {
                    stackTrace =
                        frames
                            .map {
                                StackTraceElement("other.library.Channel", it.methodName, it.fileName, it.lineNumber)
                            }.toTypedArray()
                },
            ),
        )
    }

    @Test
    fun `supabase token expiry is ignorable`() {
        assertTrue(CrashHandler.isIgnorable(TokenExpiredException()))
    }

    @Test
    fun `token expiry nested in cause chain is ignorable`() {
        assertTrue(CrashHandler.isIgnorable(RuntimeException("request failed", TokenExpiredException())))
    }

    @Test
    fun `coroutine cancellation is ignorable`() {
        assertTrue(CrashHandler.isIgnorable(kotlinx.coroutines.CancellationException("cancelled")))
    }

    @Test
    fun `broken pipe io exception is ignorable`() {
        assertTrue(CrashHandler.isIgnorable(java.io.IOException("Broken pipe")))
    }

    @Test
    fun `generic runtime exception is not ignorable`() {
        assertFalse(CrashHandler.isIgnorable(RuntimeException("actual crash")))
    }
}
