package ai.rever.boss.crash

import ai.rever.boss.plugin.loader.ClassLoaderState
import ai.rever.boss.plugin.loader.PluginUnloadRefusal
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

    // region a plugin classloader refusing a late request after unload

    /**
     * The shape all three filed reports arrive in (boss-plugin-terminal-tab#69, #71, #76): the
     * straggler's `NoClassDefFoundError`, the loader's refusal as its cause, and the plain miss
     * under that. Built from the loader's real refusal type; `PluginTeardownRefusalIntegrationTest`
     * gets the same chain out of the JVM instead of building it.
     */
    private fun refusal(
        pluginId: String,
        className: String,
    ): PluginUnloadRefusal {
        val miss = ClassNotFoundException(className)
        return PluginUnloadRefusal(pluginId, ClassLoaderState.UNLOADED, className, miss)
    }

    @Test
    fun `a refusal after unload is ignorable, from any plugin and any straggler`() {
        // #69 and #76: terminal-tab's own coroutine.
        val ownStraggler =
            NoClassDefFoundError("ai/rever/bossterm/compose/tabs/TabController").initCause(
                refusal(
                    "ai.rever.boss.plugin.dynamic.terminaltab",
                    "ai.rever.bossterm.compose.tabs.TabController\$wireCwdTitle\$3\$2\$repository\$1",
                ),
            )
        // #71: a different plugin, and a third-party straggler (ktor's selector).
        val libraryStraggler =
            NoClassDefFoundError("io/ktor/network/selector/SelectorManagerSupport").initCause(
                refusal(
                    "ai.rever.boss.plugin.dynamic.fluckbrowser",
                    "io.ktor.network.selector.SelectorManagerSupport\$ClosedSelectorCancellationException",
                ),
            )

        assertTrue(CrashHandler.isIgnorable(ownStraggler))
        assertTrue(CrashHandler.isIgnorable(libraryStraggler))
    }

    /**
     * The carve-out is the loader's refusal type, not the error type or the wording: a class
     * genuinely missing from a live plugin's jar is still a crash worth showing, and so is
     * anything that merely carries the loader's sentence.
     */
    @Test
    fun `an ordinary missing class is still a crash`() {
        assertFalse(CrashHandler.isIgnorable(NoClassDefFoundError("com/example/Missing")))
        assertFalse(
            CrashHandler.isIgnorable(
                NoClassDefFoundError("com/example/Missing").initCause(ClassNotFoundException("com.example.Missing")),
            ),
        )
        // The loader's exact sentence on a plain ClassNotFoundException: the text alone decides nothing.
        val sentence = refusal("ai.rever.boss.plugin.dynamic.terminaltab", "com.example.Missing").message
        val lookalike = NoClassDefFoundError("com/example/Missing").initCause(ClassNotFoundException(sentence))
        assertFalse(CrashHandler.isIgnorable(lookalike))
    }

    // endregion
}
