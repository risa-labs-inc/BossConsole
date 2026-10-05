package ai.rever.boss.components.workspaces

import ai.rever.boss.app.subscribeWorkspaceLoadEvents
import ai.rever.boss.components.events.WorkspaceEventBus
import ai.rever.boss.components.events.WorkspaceLoadEvent
import ai.rever.boss.mcp.secrets.captureHostLogs
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.WorkspaceSerializer
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.ComponentLogger
import ai.rever.boss.utils.logging.LogEntry
import ai.rever.boss.utils.logging.LogListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Saved Spaces carry the most privacy-sensitive local-state mix: project paths, browser URLs and
 * terminal commands. kotlinx serialization includes the source document in decoder messages, so
 * every workspace decode boundary must publish structured diagnostics without attaching the raw
 * exception. These tests exercise the four distinct boundaries (including CLI/deep-link workspace load,
 * Issue #1711) rather than only [decodeFailure].
 */
class WorkspaceDecodePrivacyTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `a corrupt Space file is logged without its layout data`() {
        runBlocking {
            val secret = "private-space-url-${System.nanoTime()}"
            val fileName = "corrupt-space.json"
            File(dir, fileName).writeText("{\"name\":\"Space\",\"url\":\"https://$secret.example/")
            val fileManager = WorkspaceFileManager(dir.absolutePath)

            val (loaded, logged) = captureHostLogs { runBlocking { fileManager.loadWorkspace(fileName) } }

            assertNull(loaded)
            assertDecodeFailureRedacted(logged, "Failed to load workspace file", secret)
        }
    }

    @Test
    fun `a corrupt Last Session set is logged without its saved tabs`() {
        runBlocking {
            val secret = "private-session-tab-${System.nanoTime()}"
            val fileManager = WorkspaceFileManager(dir.absolutePath)
            fileManager.writeDocumentBlocking(
                LAST_SESSION_SET_FILE,
                "{\"spaces\":[{\"workspaceId\":\"one\",\"tabUrl\":\"https://$secret.example/",
            )
            val manager = WorkspaceManager(fileManager)

            val (loaded, logged) = captureHostLogs { runBlocking { manager.loadLastSessionSet() } }

            assertNull(loaded)
            assertDecodeFailureRedacted(logged, "Last Session set could not be read", secret)
        }
    }

    @Test
    fun `a rejected Space import is logged without caller JSON`() {
        val secret = "private-import-command-${System.nanoTime()}"
        val manager = WorkspaceManager(WorkspaceFileManager(dir.absolutePath))
        val document = "{\"name\":\"Imported\",\"command\":\"echo $secret"

        val (loaded, logged) = captureHostLogs { manager.importWorkspace(document) }

        assertNull(loaded)
        assertDecodeFailureRedacted(logged, "Failed to import workspace from JSON", secret)
    }

    @Test
    fun `a corrupt Space file loaded via CLI workspace event is logged without its layout data`() {
        runBlocking {
            val secret = "private-cli-space-secret-${System.nanoTime()}"
            val fileName = "corrupt-cli-space.json"
            val corruptFile = File(dir, fileName)
            corruptFile.writeText("{\"name\":\"CLISpace\",\"url\":\"https://$secret.example/")
            var loadedWorkspace: LayoutWorkspace? = null

            val loggedSignal = CompletableDeferred<Unit>()
            val logger = BossLogger.forComponent("BossAppEventBusEffects")

            val (_, logged) =
                captureHostLogs {
                    runBlocking {
                        val logListener =
                            LogListener { entry ->
                                if (entry.message == "Workspace load from CLI failed") {
                                    loggedSignal.complete(Unit)
                                }
                            }
                        BossLogger.addListener(logListener)
                        try {
                            withSubscribedWorkspaceLoadEvents(
                                windowId = "window-cli-test",
                                logger = logger,
                                onLoadSpace = { _, workspace -> loadedWorkspace = workspace },
                            ) {
                                WorkspaceEventBus.loadWorkspace(
                                    workspacePath = corruptFile.absolutePath,
                                    sourceWindowId = "window-cli-test",
                                )
                                loggedSignal.await()
                            }
                        } finally {
                            BossLogger.removeListener(logListener)
                        }
                    }
                }

            assertNull(loadedWorkspace, "corrupt Space must not be loaded")
            assertDecodeFailureRedacted(logged, "Workspace load from CLI failed", secret)
            val failure = logged.single { it.message == "Workspace load from CLI failed" }
            assertEquals(corruptFile.absolutePath, failure.data?.get("spacePath"))
        }
    }

    @Test
    fun `a corrupt Space file targeted at another window does not log or dispatch in this window`() {
        runBlocking {
            val secretOther = "private-other-window-secret-${System.nanoTime()}"
            val corruptFileOther = File(dir, "other-window.json")
            corruptFileOther.writeText("{\"name\":\"OtherSpace\",\"command\":\"echo $secretOther")

            val validWorkspace =
                LayoutWorkspace(
                    id = "control-space-id",
                    name = "ControlSpace",
                    description = "Positive control",
                    layout = SplitConfig.SinglePanel(PanelConfig("test-panel", emptyList())),
                )
            val validFileActive = File(dir, "active-control-space.json")
            validFileActive.writeText(WorkspaceSerializer.serialize(validWorkspace))

            var otherDispatched = false
            var activeDispatched = false
            val activeSignal = CompletableDeferred<Unit>()
            val logger = BossLogger.forComponent("BossAppEventBusEffects")

            val (_, logged) =
                captureHostLogs {
                    runBlocking {
                        withSubscribedWorkspaceLoadEvents(
                            windowId = "window-active",
                            logger = logger,
                            onLoadSpace = { event, _ ->
                                if (event.sourceWindowId == "window-other") {
                                    otherDispatched = true
                                }
                                if (event.sourceWindowId == "window-active") {
                                    activeDispatched = true
                                    activeSignal.complete(Unit)
                                }
                            },
                        ) {
                            WorkspaceEventBus.loadWorkspace(
                                workspacePath = corruptFileOther.absolutePath,
                                sourceWindowId = "window-other",
                            )
                            WorkspaceEventBus.loadWorkspace(
                                workspacePath = validFileActive.absolutePath,
                                sourceWindowId = "window-active",
                            )
                            activeSignal.await()
                        }
                    }
                }

            assertTrue(activeDispatched, "positive control for window-active must be dispatched")
            assertFalse(otherDispatched, "events for window-other must not be dispatched in window-active")
            assertTrue(
                logged.none { it.message == "Workspace load from CLI failed" },
                "events for window-other must not log failures in window-active",
            )
        }
    }

    @Test
    fun `valid Space file loaded via CLI workspace event dispatches to onLoadSpace without errors`() {
        runBlocking {
            val validWorkspace =
                LayoutWorkspace(
                    id = "valid-space-id",
                    name = "ValidCLIWorkspace",
                    description = "Valid Space Description",
                    layout = SplitConfig.SinglePanel(PanelConfig("test-panel", emptyList())),
                )
            val validFile = File(dir, "valid-space.json")
            validFile.writeText(WorkspaceSerializer.serialize(validWorkspace))
            var loadedEvent: WorkspaceLoadEvent? = null
            var loadedSpace: LayoutWorkspace? = null
            val loadedSignal = CompletableDeferred<Unit>()
            val logger = BossLogger.forComponent("BossAppEventBusEffects")

            val (_, logged) =
                captureHostLogs {
                    runBlocking {
                        withSubscribedWorkspaceLoadEvents(
                            windowId = "window-valid",
                            logger = logger,
                            onLoadSpace = { event, workspace ->
                                loadedEvent = event
                                loadedSpace = workspace
                                loadedSignal.complete(Unit)
                            },
                        ) {
                            WorkspaceEventBus.loadWorkspace(
                                workspacePath = validFile.absolutePath,
                                sourceWindowId = "window-valid",
                            )
                            loadedSignal.await()
                        }
                    }
                }

            assertNotNull(loadedSpace)
            assertEquals("ValidCLIWorkspace", loadedSpace?.name)
            assertEquals(validFile.absolutePath, loadedEvent?.workspacePath)
            assertTrue(
                logged.none { it.message == "Workspace load from CLI failed" },
                "valid workspace load must not log failure",
            )
        }
    }

    @Test
    fun `an unreadable Space path on CLI workspace event retains error throwable`() {
        runBlocking {
            val subDir = File(dir, "unreadable-dir-space")
            subDir.mkdir()
            val loggedSignal = CompletableDeferred<Unit>()
            val logger = BossLogger.forComponent("BossAppEventBusEffects")

            val (_, logged) =
                captureHostLogs {
                    runBlocking {
                        val logListener =
                            LogListener { entry ->
                                if (entry.message == "Workspace load from CLI failed") {
                                    loggedSignal.complete(Unit)
                                }
                            }
                        BossLogger.addListener(logListener)
                        try {
                            withSubscribedWorkspaceLoadEvents(
                                windowId = "window-io-test",
                                logger = logger,
                            ) {
                                WorkspaceEventBus.loadWorkspace(
                                    workspacePath = subDir.absolutePath,
                                    sourceWindowId = "window-io-test",
                                )
                                loggedSignal.await()
                            }
                        } finally {
                            BossLogger.removeListener(logListener)
                        }
                    }
                }

            val failure = logged.single { it.message == "Workspace load from CLI failed" }
            assertNotNull(failure.error, "ordinary I/O exceptions must retain their throwable")
            assertEquals(subDir.absolutePath, failure.data?.get("spacePath"))
            assertNull(failure.data?.get("decodeFailure"), "ordinary I/O must not be classified as decode failure")
        }
    }

    @Test
    fun `decode failure carrying at path segment preserves spacePath and logs JSON path`() {
        runBlocking {
            val secret = "nested-private-url-${System.nanoTime()}"
            val corruptFile = File(dir, "corrupt-at-path.json")
            corruptFile.writeText("{\"name\":\"SpaceWithPath\",\"url\":\"https://$secret.example/\",\"layout\":")
            val loggedSignal = CompletableDeferred<Unit>()
            val logger = BossLogger.forComponent("BossAppEventBusEffects")

            val (_, logged) =
                captureHostLogs {
                    runBlocking {
                        val logListener =
                            LogListener { entry ->
                                if (entry.message == "Workspace load from CLI failed") {
                                    loggedSignal.complete(Unit)
                                }
                            }
                        BossLogger.addListener(logListener)
                        try {
                            withSubscribedWorkspaceLoadEvents(
                                windowId = "window-path-test",
                                logger = logger,
                            ) {
                                WorkspaceEventBus.loadWorkspace(
                                    workspacePath = corruptFile.absolutePath,
                                    sourceWindowId = "window-path-test",
                                )
                                loggedSignal.await()
                            }
                        } finally {
                            BossLogger.removeListener(logListener)
                        }
                    }
                }

            assertDecodeFailureRedacted(logged, "Workspace load from CLI failed", secret)
            val failure = logged.single { it.message == "Workspace load from CLI failed" }
            assertEquals(corruptFile.absolutePath, failure.data?.get("spacePath"))
            assertNotNull(failure.data?.get("path"), "JSON path from decodeFailure must be preserved")
        }
    }

    private suspend fun withSubscribedWorkspaceLoadEvents(
        windowId: String,
        logger: ComponentLogger,
        onLoadSpace: (WorkspaceLoadEvent, LayoutWorkspace) -> Unit = { _, _ -> },
        block: suspend () -> Unit,
    ) = coroutineScope {
        val before = WorkspaceEventBus.subscriptionCount.value
        val subscription =
            subscribeWorkspaceLoadEvents(
                windowId = windowId,
                logger = logger,
                onLoadSpace = onLoadSpace,
            )
        try {
            withTimeout(5_000) {
                WorkspaceEventBus.subscriptionCount.first { it > before }
                block()
            }
        } finally {
            subscription.cancelAndJoin()
        }
    }

    private fun assertDecodeFailureRedacted(
        logged: List<LogEntry>,
        message: String,
        secret: String,
    ) {
        val failure = logged.single { it.message == message }
        assertNull(failure.error, "the decoder exception includes workspace JSON and must not be attached")
        assertEquals("JsonDecodingException", failure.data?.get("decodeFailure"))
        for (entry in logged) {
            assertFalse(secret in "${entry.message} ${entry.data} ${entry.error}", "leaked in: $entry")
        }
    }
}
