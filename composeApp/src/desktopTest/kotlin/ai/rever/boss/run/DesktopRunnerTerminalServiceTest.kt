package ai.rever.boss.run

import ai.rever.boss.components.events.RunnerTerminalEventBus
import ai.rever.boss.ipc.IpcEventBridge
import ai.rever.boss.plugin.run.Language
import ai.rever.boss.plugin.run.RunConfiguration
import ai.rever.boss.plugin.run.RunConfigurationType
import ai.rever.boss.plugin.run.RunnerTerminalCloseEvent
import ai.rever.boss.plugin.run.RunnerTerminalOpenEvent
import ai.rever.boss.plugin.run.RunnerTerminalStopEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BossConsole#486 review, round 3: `rerunRunner`'s `withContext(NonCancellable)` guarantees the
 * interrupt/close teardown of the terminal being replaced always finishes, but guarantees nothing
 * about what runs after it returns to a caller that was cancelled *during* that teardown - neither
 * `stateLock.withLock` nor the `RunnerTerminalEventBus` emit are suspension points that check
 * cancellation on their own. The maintainer's own diagnostic (an [IpcEventBridge] that cancels the
 * caller on the close event's forward) reproduced exactly this: the event sequence came out
 * `[Open, Close, Open]`, i.e. a cancelled caller still requested a replacement terminal.
 *
 * This reuses that same diagnostic, but asserts on [RunnerTerminalService]'s own state rather than
 * the event sequence's timing - `configToTerminal`/`isConfigRunning` are what Stop and a later
 * re-run actually read, and are unaffected by how quickly a `MutableSharedFlow` collector drains.
 *
 * `TerminalAPIAccess.sendInterrupt` needs no test double: with nothing registered it returns
 * `false`, so `rerunRunner` skips its `delay()` and goes straight to `closeRunnerTerminal` - which
 * is where this test's [IpcEventBridge] cancels the caller. That proves cancellation landing at
 * teardown/return; cancellation during the delay itself (a real, successful interrupt) needs a
 * `TerminalAPIAccess` provider double this repo does not have and is not covered here, matching
 * the review's own note that the two need separate coverage.
 */
class DesktopRunnerTerminalServiceTest {
    private val windowId = "cancel-test-window"
    private val windowA = "cancel-test-window-a"
    private val windowB = "cancel-test-window-b"
    private val config =
        RunConfiguration(
            id = "cancel-test-config",
            name = "cancel test",
            type = RunConfigurationType.CUSTOM,
            filePath = "",
            lineNumber = 0,
            language = Language.UNKNOWN,
            command = "echo hi",
            workingDirectory = "",
        )
    private val originalSettings = RunnerSettingsManager.currentSettings.value

    @AfterTest
    fun tearDown() =
        runBlocking {
            RunnerTerminalEventBus.ipcBridge = null
            RunnerTerminalService.cleanupWindow(windowId)
            RunnerTerminalService.cleanupWindow(windowA)
            RunnerTerminalService.cleanupWindow(windowB)
            RunnerSettingsManager.updateSettings(originalSettings)
        }

    @Test
    fun `a caller cancelled during rerun teardown does not leave a stale running config`() =
        runBlocking {
            // MAIN_PANEL is required for the interrupt/close teardown block to run at all -
            // rerunRunner skips it entirely under SIDEBAR_PANEL (usesSidebar).
            RunnerSettingsManager.setTerminalTarget(RunnerTerminalTarget.MAIN_PANEL)

            val events = mutableListOf<String>()
            lateinit var rerunJob: Job
            RunnerTerminalEventBus.ipcBridge =
                object : IpcEventBridge {
                    override suspend fun forward(
                        eventType: String,
                        payload: Any,
                        sourceWindowId: String,
                    ) {
                        events += eventType
                        if (eventType == "RunnerTerminalCloseEvent") rerunJob.cancel()
                    }
                }

            rerunJob =
                launch(start = CoroutineStart.LAZY) {
                    RunnerTerminalService.openRunnerTerminal(config, windowId) {}
                    RunnerTerminalService.rerunRunner(config, windowId) {}
                }
            rerunJob.start()
            rerunJob.join()

            assertTrue(rerunJob.isCancelled)
            assertEquals(listOf("RunnerTerminalOpenEvent", "RunnerTerminalCloseEvent"), events)

            assertFalse(
                RunnerTerminalService.isConfigRunning(config.id),
                "a rerun cancelled during teardown must not leave the config marked as running",
            )
            assertNull(
                RunnerTerminalService.configToTerminal.value[config.id],
                "a rerun cancelled during teardown must not leave the config pointing at a never-opened terminal",
            )
        }

    @Test
    fun `cancelled rerun preserves the other window terminal and routes Stop to it`() =
        runBlocking {
            RunnerSettingsManager.setTerminalTarget(RunnerTerminalTarget.MAIN_PANEL)
            // Model the host's window-scoped open/close routing without creating a live PTY.
            val liveTabs = mutableSetOf<Pair<String, String>>()
            val events = mutableListOf<String>()
            var rerunJob: Job? = null
            RunnerTerminalEventBus.ipcBridge =
                object : IpcEventBridge {
                    override suspend fun forward(
                        eventType: String,
                        payload: Any,
                        sourceWindowId: String,
                    ) {
                        events += eventType
                        when (payload) {
                            is RunnerTerminalOpenEvent -> {
                                liveTabs += sourceWindowId to payload.terminalId
                            }

                            is RunnerTerminalCloseEvent -> {
                                liveTabs -= sourceWindowId to payload.terminalId
                                rerunJob?.cancel()
                            }

                            is RunnerTerminalStopEvent -> {
                                assertTrue(liveTabs.remove(sourceWindowId to payload.terminalId))
                            }
                        }
                    }
                }
            val originalId = RunnerTerminalService.openRunnerTerminal(config, windowA) {}
            RunnerTerminalService.openRunnerTerminal(config, windowB) {}
            val job = launch(start = CoroutineStart.LAZY) { RunnerTerminalService.rerunRunner(config, windowB) {} }
            rerunJob = job
            job.start()
            job.join()

            assertTrue(job.isCancelled)
            assertEquals(
                listOf("RunnerTerminalOpenEvent", "RunnerTerminalOpenEvent", "RunnerTerminalCloseEvent"),
                events,
            )
            // Existing rerun routing tears down the first registered window (A), not B.
            assertEquals(setOf(windowB to originalId), liveTabs)
            RunnerTerminalService.removeTerminal(windowA, originalId)
            assertEquals(originalId, RunnerTerminalService.configToTerminal.value[config.id])
            assertFalse(RunnerTerminalService.isConfigRunningInWindow(windowA, config.id))
            assertTrue(RunnerTerminalService.isConfigRunningInWindow(windowB, config.id))
            assertTrue(RunnerTerminalService.isConfigRunning(config.id))
            assertEquals(config.id, RunnerTerminalService.getConfigForTerminal(originalId))

            // No provider is registered, so closeActiveTab returns false; verify Stop's actual
            // window/terminal event and tracking cleanup rather than claiming a live PTY closed.
            RunnerTerminalService.stopRunner(windowB, config.id)
            assertEquals("RunnerTerminalStopEvent", events.last())
            assertTrue(liveTabs.isEmpty())
            assertFalse(RunnerTerminalService.isConfigRunning(config.id))
            assertNull(RunnerTerminalService.configToTerminal.value[config.id])
        }

    /**
     * BossConsole#486 review round 4, finding 1. `rerunStillValid`'s rollback removed
     * `existingWindowId` unconditionally, even under SIDEBAR_PANEL where the interrupt/close
     * teardown above it never runs (`usesSidebar` skips it entirely) - so a cancelled sidebar
     * rerun dropped a window's running state for a tab nobody touched.
     *
     * SIDEBAR_PANEL emits no close event, so this cannot hook cancellation the way the
     * MAIN_PANEL tests above do (on `RunnerTerminalCloseEvent`). Cancellation is injected before
     * `rerunRunner` ever starts instead: [CoroutineStart.ATOMIC] guarantees the coroutine still
     * runs its body - the state swap included - even though the job is already cancelled by the
     * time it's scheduled, so `rerunStillValid`'s `!callerContext.isActive` read observes it.
     *
     * That only holds because nothing suspends between the swap and `rerunStillValid`: the state
     * lock is a plain `java.util.concurrent.locks.ReentrantLock` (not a suspend `Mutex`) and the
     * sidebar path skips the `NonCancellable` block, so an already-cancelled job cannot throw
     * before the swap. If the lock ever becomes a `Mutex`, add a probe that the swap actually ran.
     */
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `cancelled sidebar rerun preserves both windows and their terminal mapping`() =
        runBlocking {
            RunnerSettingsManager.setTerminalTarget(RunnerTerminalTarget.SIDEBAR_PANEL)
            val events = mutableListOf<String>()
            RunnerTerminalEventBus.ipcBridge =
                object : IpcEventBridge {
                    override suspend fun forward(
                        eventType: String,
                        payload: Any,
                        sourceWindowId: String,
                    ) {
                        events += eventType
                    }
                }

            val originalId = RunnerTerminalService.openRunnerTerminal(config, windowA) {}
            RunnerTerminalService.openRunnerTerminal(config, windowB) {}

            val job = launch(start = CoroutineStart.ATOMIC) { RunnerTerminalService.rerunRunner(config, windowB) {} }
            job.cancel()
            job.join()

            assertTrue(job.isCancelled)
            // Only the two initial opens: SIDEBAR_PANEL has no interrupt/close teardown to emit
            // anything for, and rerunStillValid's rollback fires before a rerun ever opens.
            assertEquals(listOf("RunnerTerminalOpenEvent", "RunnerTerminalOpenEvent"), events)

            assertEquals(
                originalId,
                RunnerTerminalService.configToTerminal.value[config.id],
                "a cancelled sidebar rerun must restore the original terminal mapping",
            )
            assertTrue(
                RunnerTerminalService.isConfigRunningInWindow(windowA, config.id),
                "window A's sidebar tab was never touched and must still read as running",
            )
            assertTrue(
                RunnerTerminalService.isConfigRunningInWindow(windowB, config.id),
                "the caller's own window must still read as running after its rerun is cancelled",
            )
            assertEquals(
                config.id,
                RunnerTerminalService.getConfigForTerminal(originalId),
                "the rollback re-adds the config to the restored terminal's reverse map",
            )
            assertTrue(RunnerTerminalService.isConfigRunning(config.id))
        }

    /**
     * BossConsole#486 review round 4, finding 2. `removeTerminal` only cleared `_runningConfigs`
     * inside the guard that also protects `_configToTerminal` from a newer mapping - so when the
     * last window for a config disappeared while `_configToTerminal` had already moved to a
     * replacement that has not opened a window of its own yet, the config stayed in
     * `_runningConfigs` with no owning window anywhere. `isConfigRunning` then disagreed with
     * `isConfigRunningInWindow` for every window, with no way to make them agree again short of
     * a fresh run.
     *
     * SIDEBAR_PANEL reaches this without any cancellation: `rerunRunner`'s swap only calls
     * `removeConfigFromTerminal` for the terminal being replaced when `!usesSidebar`, so a fully
     * successful sidebar rerun leaves the superseded terminal's own tracking entry in place even
     * though `_configToTerminal` already points at the replacement - exactly the stale-mapping
     * shape this guards against.
     */
    @Test
    fun `removeTerminal clears the running flag when the window set empties behind a replacement mapping`() =
        runBlocking {
            RunnerSettingsManager.setTerminalTarget(RunnerTerminalTarget.SIDEBAR_PANEL)
            // No ipcBridge needed: this test asserts on service state, not on the emitted events.

            val originalId = RunnerTerminalService.openRunnerTerminal(config, windowA) {}
            // Both IDs are minted from System.currentTimeMillis() for the same config.id, so back
            // to back mints can collide and produce the identical string - which would make the
            // "stale" removeTerminal call below legitimately current. On Windows the clock tick
            // is commonly ~15 ms, so a burst of fast mints can all share one tick; this file
            // runs on the windows-latest leg (only ARM64 is excluded). Re-mint until they
            // differ, pausing a beat after each collision so a slow clock can advance; the loop
            // is bounded, so a truly stalled clock fails loudly. Each mint is a real sidebar
            // rerun, which preserves originalId's reverse-map entry.
            var replacementId = ""
            var mints = 0
            do {
                replacementId = RunnerTerminalService.rerunRunner(config, windowA) {}
                mints++
                if (replacementId == originalId) delay(20)
            } while (replacementId == originalId && mints < 100)
            assertNotEquals(
                originalId,
                replacementId,
                "replacement must mint a distinct terminal id",
            )
            assertEquals(replacementId, RunnerTerminalService.configToTerminal.value[config.id])
            assertTrue(RunnerTerminalService.isConfigRunning(config.id))

            // A delayed close event for the superseded terminal arrives after the replacement is
            // already in place. windowA is the only window, so this empties configToWindows for
            // the config - but configToTerminal no longer names originalId.
            RunnerTerminalService.removeTerminal(windowA, originalId)

            assertFalse(RunnerTerminalService.isConfigRunningInWindow(windowA, config.id))
            assertFalse(
                RunnerTerminalService.isConfigRunning(config.id),
                "no window owns this config any more, so it must not still read as running",
            )
            assertEquals(
                replacementId,
                RunnerTerminalService.configToTerminal.value[config.id],
                "the replacement mapping must survive a stale close for the terminal it replaced",
            )
        }
}
