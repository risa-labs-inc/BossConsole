package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.plugin.workspace.TabConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkspaceTemplatePreparationTest {
    private fun space(
        id: String,
        file: String = "/tmp/example.kt",
    ) = LayoutWorkspace(
        id = id,
        name = id,
        description = "Disposable template test",
        layout =
            SinglePanel(
                PanelConfig(
                    id = "main",
                    tabs =
                        listOf(
                            TabConfig(type = "editor", title = "Editor", filePath = file),
                        ),
                ),
            ),
    )

    @Test fun templatePreparationSavesWithoutSelectingOverAnotherWindow() =
        runTest {
            val directory = Files.createTempDirectory("template-preparation")
            try {
                val files = WorkspaceFileManager(directory.toString())
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val manager =
                    WorkspaceManager(files, backgroundScope, writeWorkspace = { workspace, name ->
                        entered.complete(Unit)
                        release.await()
                        files.saveWorkspace(workspace, name)
                    })
                manager.awaitLoaded()
                val a = space("a")
                val b = space("b")
                manager.loadWorkspace(a)
                val prepared =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        spaceToOpen(
                            space("template", "{projectPath}/file.kt"),
                            directory.toString(),
                            manager,
                            publishSelection = false,
                        )
                    }
                entered.await()
                assertEquals(a, manager.currentWorkspace.value)
                manager.loadWorkspace(b)
                release.complete(Unit)
                val saved = prepared.await()
                assertEquals(saved, manager.savedCopyOf(saved.id))
                assertEquals(b, manager.currentWorkspace.value)
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test fun failedTemplateSaveLeavesCurrentSelectionAndDirtyMarkIntact() =
        runTest {
            val directory = Files.createTempDirectory("template-save-failure")
            try {
                val manager =
                    WorkspaceManager(
                        WorkspaceFileManager(directory.toString()),
                        backgroundScope,
                        writeWorkspace = { _, _ -> null },
                    )
                manager.awaitLoaded()
                val a = space("a")
                manager.loadWorkspace(a)
                manager.setWorkspaceUnsaved("window-a", a.id, true)
                assertFailsWith<IllegalStateException> {
                    spaceToOpen(
                        space("template", "{projectPath}/file.kt"),
                        directory.toString(),
                        manager,
                        publishSelection = false,
                    )
                }
                assertEquals(a, manager.currentWorkspace.value)
                assertTrue(manager.isWorkspaceUnsavedIn("window-a", a.id))
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
}
