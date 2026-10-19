package ai.rever.boss.window

import java.awt.desktop.QuitResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ApplicationQuitLifecycleTest {
    @Test
    fun failedComposeDisposalStillAcknowledgesNativeQuitBeforeProcessExit() {
        val events = mutableListOf<String>()
        val lifecycle = ApplicationQuitLifecycle()
        val failure = NoClassDefFoundError("plugin disposal classloader closed")
        val thrown =
            assertFailsWith<NoClassDefFoundError> {
                lifecycle.runApplication(terminate = { events.add("exit $it") }) {
                    lifecycle.requestQuit(RecordingQuitResponse("native", events)) {
                        events.add("exit composition requested")
                    }
                    events.add("composition disposal attempted")
                    throw failure
                }
            }
        assertSame(failure, thrown)
        assertEquals(
            listOf("exit composition requested", "composition disposal attempted", "native quit", "exit 1"),
            events,
        )
        lifecycle.completeQuit()
        assertEquals(4, events.size, "Native completion must remain once-only after failed disposal")
    }

    @Test
    fun manualQuitWithoutNativeResponseStillExitsAfterFailedComposeDisposal() {
        val events = mutableListOf<String>()
        val lifecycle = ApplicationQuitLifecycle()
        assertFailsWith<IllegalStateException> {
            lifecycle.runApplication(terminate = { events.add("exit $it") }) {
                lifecycle.closeApplication(
                    windowIds = emptyList(),
                    prepareWindow = { error("There are no windows") },
                    exitApplication = { events.add("exit composition requested") },
                )
                error("onDispose failed")
            }
        }
        assertEquals(listOf("exit composition requested", "exit 1"), events)
    }

    @Test
    fun failedDisposalCompletesNativeRequestRetainedBeforeUiDispatch() {
        val events = mutableListOf<String>()
        val lifecycle = ApplicationQuitLifecycle()
        assertFailsWith<IllegalStateException> {
            lifecycle.runApplication(terminate = { events.add("exit $it") }) {
                lifecycle.retainResponse(RecordingQuitResponse("queued", events))
                events.add("composition disposal attempted")
                error("onDispose failed before the queued UI task ran")
            }
        }
        assertEquals(listOf("composition disposal attempted", "queued quit", "exit 1"), events)
    }

    @Test
    fun applicationFailureBeforeQuitStillPropagatesWithoutProcessTermination() {
        val lifecycle = ApplicationQuitLifecycle()
        val failure = IllegalStateException("application failed before Quit")
        val thrown =
            assertFailsWith<IllegalStateException> {
                lifecycle.runApplication(terminate = { error("The crash handler must receive this failure") }) {
                    throw failure
                }
            }
        assertSame(failure, thrown)
    }

    @Test
    fun normalApplicationReturnCompletesQuitBeforeSuccessfulProcessExit() {
        val events = mutableListOf<String>()
        val lifecycle = ApplicationQuitLifecycle()
        lifecycle.runApplication(terminate = { events.add("exit $it") }) {
            lifecycle.retainResponse(RecordingQuitResponse("native", events))
            events.add("composition disposed")
        }
        assertEquals(listOf("composition disposed", "native quit", "exit 0"), events)
    }

    @Test
    fun repeatedNativeRequestsRemainPendingAndCloseCompositionsOnce() {
        val events = mutableListOf<String>()
        val lifecycle = ApplicationQuitLifecycle()
        val first = RecordingQuitResponse("first", events)
        val second = RecordingQuitResponse("second", events)

        lifecycle.retainResponse(first)
        lifecycle.requestQuit(first) { events.add("close application") }
        lifecycle.requestQuit(second) { events.add("unexpected close") }
        lifecycle.requestQuit(first) { events.add("unexpected close") }
        assertEquals(listOf("close application"), events)

        events.add("composition disposed")
        lifecycle.completeQuit()
        lifecycle.completeQuit()
        lifecycle.requestQuit(first) { events.add("unexpected close") }

        assertEquals(listOf("close application", "composition disposed", "first quit", "second quit"), events)
    }

    @Test
    fun nativeRequestAfterCleanupCompletesImmediatelyWithoutRestartingCleanup() {
        val events = mutableListOf<String>()
        val lifecycle = ApplicationQuitLifecycle()
        lifecycle.completeQuit()
        val response = RecordingQuitResponse("late", events)

        lifecycle.requestQuit(response) { events.add("unexpected close") }
        lifecycle.requestQuit(response) { events.add("unexpected close") }

        assertEquals(listOf("late quit"), events)
    }

    @Test
    fun nativeQuitRequestsComposeClosureOnlyThroughTheUiDispatcher() {
        val events = mutableListOf<String>()
        val uiTasks = mutableListOf<() -> Unit>()
        val lifecycle = ApplicationQuitLifecycle()
        val dispatcher =
            MacOSQuitDispatcher(
                retainResponse = lifecycle::retainResponse,
                onQuit = { response -> lifecycle.requestQuit(response) { events.add("close application") } },
                dispatchToUi = { uiTasks.add(it) },
            )

        dispatcher.requestQuit(RecordingQuitResponse("queued", events))
        assertEquals(emptyList(), events)
        uiTasks.single().invoke()
        assertEquals(listOf("close application"), events)
        dispatcher.dispose()
        events.add("composition disposed")
        lifecycle.completeQuit()

        assertEquals(listOf("close application", "composition disposed", "queued quit"), events)
    }

    @Test
    fun queuedNativeRequestSurvivesEffectDisposalAndWaitsForCompositionCleanup() {
        val events = mutableListOf<String>()
        val uiTasks = mutableListOf<() -> Unit>()
        val lifecycle = ApplicationQuitLifecycle()
        val dispatcher =
            MacOSQuitDispatcher(
                retainResponse = lifecycle::retainResponse,
                onQuit = { response -> lifecycle.requestQuit(response) { events.add("close application") } },
                dispatchToUi = { uiTasks.add(it) },
            )

        dispatcher.requestQuit(RecordingQuitResponse("queued", events))
        assertEquals(emptyList(), events)
        dispatcher.dispose()
        uiTasks.forEach { it() }
        assertEquals(emptyList(), events, "A disposed effect must not call the Compose closure callback")

        events.add("composition disposed")
        lifecycle.completeQuit()

        assertEquals(listOf("composition disposed", "queued quit"), events)
    }

    @Test
    fun nativeRequestRacingDisposedHandlerRemainsPendingUntilFinalCleanup() {
        val events = mutableListOf<String>()
        val lifecycle = ApplicationQuitLifecycle()
        val dispatcher =
            MacOSQuitDispatcher(
                retainResponse = lifecycle::retainResponse,
                onQuit = { response -> lifecycle.requestQuit(response) { events.add("close application") } },
                dispatchToUi = { error("A disposed handler must not queue a Quit response") },
            )
        dispatcher.dispose()

        dispatcher.requestQuit(RecordingQuitResponse("racing", events))
        assertEquals(emptyList(), events, "A disposed native handler must not call Compose on its native event thread")

        events.add("composition disposed")
        lifecycle.completeQuit()
        dispatcher.requestQuit(RecordingQuitResponse("late", events))

        assertEquals(listOf("composition disposed", "racing quit", "late quit"), events)
    }

    private class RecordingQuitResponse(
        private val name: String,
        private val events: MutableList<String>,
    ) : QuitResponse {
        override fun performQuit() {
            events.add("$name quit")
        }

        override fun cancelQuit() {
            events.add("unexpected cancellation")
        }
    }

    @Test
    fun nativeQuitStaysPendingUntilCompositionCleanupCompletes() {
        val events = mutableListOf<String>()
        val response =
            object : QuitResponse {
                override fun performQuit() {
                    events.add("native quit")
                }

                override fun cancelQuit() {
                    events.add("cancel")
                }
            }
        val lifecycle = ApplicationQuitLifecycle()

        lifecycle.requestQuit(response) { events.add("close application") }
        assertEquals(listOf("close application"), events)

        events.add("composition disposed")
        lifecycle.completeQuit()
        assertEquals(listOf("close application", "composition disposed", "native quit"), events)

        lifecycle.completeQuit()
        assertEquals(3, events.size)
    }

    @Test
    fun automaticUpdateQuitNeedsNoNativeResponse() {
        ApplicationQuitLifecycle().completeQuit()
    }

    @Test
    fun failedWindowCleanupStillPreparesOtherWindowsAndCompletesQuitAfterDisposal() {
        val events = mutableListOf<String>()
        val lifecycle = ApplicationQuitLifecycle()
        val response =
            object : QuitResponse {
                override fun performQuit() {
                    events.add("native quit")
                }

                override fun cancelQuit() {
                    events.add("cancel")
                }
            }

        lifecycle.requestQuit(response) {
            lifecycle.closeApplication(
                windowIds = listOf("failing", "healthy"),
                prepareWindow = { id ->
                    events.add(id)
                    if (id == "failing") throw NoClassDefFoundError("plugin classloader closed")
                },
                exitApplication = { events.add("exit application") },
            )
        }
        assertEquals(listOf("failing", "healthy", "exit application"), events)

        events.add("composition disposed")
        lifecycle.completeQuit()
        lifecycle.completeQuit()
        assertEquals(listOf("failing", "healthy", "exit application", "composition disposed", "native quit"), events)
    }

    @Test
    fun quitWithNoWindowsStillExits() {
        var exits = 0
        ApplicationQuitLifecycle().closeApplication(
            windowIds = emptyList(),
            prepareWindow = { error("No window should need preparation") },
            exitApplication = { exits++ },
        )
        assertEquals(1, exits)
    }
}
