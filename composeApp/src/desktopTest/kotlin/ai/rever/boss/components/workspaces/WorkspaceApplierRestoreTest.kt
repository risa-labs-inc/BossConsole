package ai.rever.boss.components.workspaces

import ai.rever.boss.components.plugin.TabUpdateRegistry
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.tab.composer.ComposerTabInfo
import ai.rever.boss.plugin.tab.composer.ComposerTabType
import ai.rever.boss.plugin.tab.diff.DiffTabInfo
import ai.rever.boss.plugin.tab.diff.DiffTabType
import ai.rever.boss.plugin.workspace.TabConfig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the RESTORE side of the layout round trip: [createTabFromWorkspaceConfig]
 * over a saved [TabConfig] tree.
 *
 * [WorkspaceExtractorTest] covers the extract side; this test exists because
 * the two sides are separate decisions and used to disagree. A blank diff
 * path used to restore as a diff tab that can never show anything, while the
 * composer branch refused a blank session id and the extractor refused to
 * persist a blank path - three answers to "no scope". All three must agree
 * on "no scope, no tab".
 */
class WorkspaceApplierRestoreTest {
    private val tabRegistry =
        TabRegistry().apply {
            listOf(CodeEditorTabType, DiffTabType, ComposerTabType).forEach { type ->
                registerTabType(type) { _, _ -> throw UnsupportedOperationException("not used by restore") }
            }
        }

    @AfterTest
    fun tearDown() {
        TabUpdateRegistry.clear()
    }

    private fun restore(tabConfig: TabConfig): TabInfo? =
        createTabFromWorkspaceConfig(
            tabConfig = tabConfig,
            resolvedProjectPath = "/tmp/proj",
            splitViewState = SplitViewState(tabRegistry, windowId = "restore-test"),
        )

    // ==================== diff tabs ====================

    @Test
    fun `a saved file diff restores as a working tree diff of that file`() {
        val tab =
            restore(
                TabConfig(
                    type = "diff",
                    title = "main.kt",
                    filePath = "src/main.kt",
                ),
            )

        val diff = assertIs<DiffTabInfo>(tab)
        assertEquals("src/main.kt", diff.filePath)
        assertTrue(
            !diff.staged,
            "the extractor only persists working-tree diffs, so restore must not invent staged ones",
        )
        assertNull(diff.fromRef)
        assertNull(diff.toRef)
    }

    @Test
    fun `a blank diff path restores no tab`() {
        // A corrupt or hand-edited layout: no scope, no tab. Agreeing with the
        // composer branch (blank session id) and the extractor (refuses to
        // persist a blank path) is the contract this test exists for.
        val tab =
            restore(
                TabConfig(
                    type = "diff",
                    title = "Diff",
                    filePath = "",
                ),
            )
        assertNull(tab, "a diff tab with no scope can never show anything; it must not be restored")
    }

    @Test
    fun `a null diff path restores no tab`() {
        val tab = restore(TabConfig(type = "diff", title = "Diff"))
        assertNull(tab)
    }

    // ==================== composer tabs ====================

    @Test
    fun `a saved composer tab restores with its session id`() {
        val tab =
            restore(
                TabConfig(
                    type = "composer",
                    title = "Composer",
                    filePath = "session-abc123",
                ),
            )

        val composer = assertIs<ComposerTabInfo>(tab)
        assertEquals("session-abc123", composer.sessionId)
    }

    @Test
    fun `a blank composer session id restores no tab`() {
        // The branch the diff one used to disagree with; the round trip only
        // holds if both sides drop the scopeless tab.
        val tab =
            restore(
                TabConfig(
                    type = "composer",
                    title = "Composer",
                    filePath = "",
                ),
            )
        assertNull(tab, "a composer tab with no session id cannot reload a session")
    }

    @Test
    fun `an unknown tab type restores nothing rather than crashing the layout`() {
        val tab = restore(TabConfig(type = "mystery", title = "?"))
        assertNull(tab)
    }
}
