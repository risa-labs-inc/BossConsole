package ai.rever.boss.cli

import ai.rever.boss.plugin.workspace.BreadcrumbConfig
import ai.rever.boss.plugin.workspace.LayoutWorkspace
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.TabConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BossWorkspaceDiffTest {
    private fun tab(
        type: String,
        title: String = "untitled",
        url: String? = null,
        filePath: String? = null,
        initialCommand: String? = null,
        workingDirectory: String? = null,
    ) = TabConfig(
        type = type,
        title = title,
        url = url,
        filePath = filePath,
        initialCommand = initialCommand,
        workingDirectory = workingDirectory,
    )

    private fun panel(
        id: String,
        tabs: List<TabConfig>,
        pinned: Int = 0,
    ) = PanelConfig(id = id, tabs = tabs, pinnedCount = pinned)

    private fun singlePanel(panel: PanelConfig) = SplitConfig.SinglePanel(panel = panel)

    private fun workspace(
        id: String = "ws-1",
        name: String = "Default",
        description: String = "",
        projectPath: String? = null,
        layout: SplitConfig,
        breadcrumb: BreadcrumbConfig = BreadcrumbConfig(),
    ) = LayoutWorkspace(
        id = id,
        name = name,
        description = description,
        layout = layout,
        breadcrumbConfig = breadcrumb,
        projectPath = projectPath,
    )

    @Test
    fun `identical workspaces produce no changes`() {
        val ws = workspace(layout = singlePanel(panel("p1", listOf(tab("browser", url = "https://example.com")))))
        val diff = WorkspaceDiffer.diff(ws, ws)
        assertFalse(diff.hasChanges())
        assertEquals(emptyList(), diff.panelsAdded)
        assertEquals(emptyList(), diff.tabsAdded)
    }

    @Test
    fun `a tab added to an existing panel appears in tabsAdded`() {
        val left = workspace(layout = singlePanel(panel("p1", listOf(tab("browser", url = "https://a")))))
        val right =
            workspace(
                layout =
                    singlePanel(
                        panel(
                            "p1",
                            listOf(tab("browser", url = "https://a"), tab("browser", url = "https://b")),
                        ),
                    ),
            )
        val diff = WorkspaceDiffer.diff(left, right)
        assertTrue(diff.tabsAdded.any { it.contains("https://b") })
        assertFalse(diff.hasChanges().not())
    }

    @Test
    fun `a tab removed appears in tabsRemoved`() {
        val left =
            workspace(
                layout =
                    singlePanel(
                        panel("p1", listOf(tab("browser", url = "https://a"), tab("browser", url = "https://b"))),
                    ),
            )
        val right = workspace(layout = singlePanel(panel("p1", listOf(tab("browser", url = "https://a")))))
        val diff = WorkspaceDiffer.diff(left, right)
        assertTrue(diff.tabsRemoved.any { it.contains("https://b") })
    }

    @Test
    fun `a tab title change with the same URL appears as modified not removed`() {
        val left =
            workspace(
                layout =
                    singlePanel(
                        panel("p1", listOf(tab("browser", title = "old", url = "https://a"))),
                    ),
            )
        val right =
            workspace(
                layout =
                    singlePanel(
                        panel("p1", listOf(tab("browser", title = "new", url = "https://a"))),
                    ),
            )
        val diff = WorkspaceDiffer.diff(left, right)
        assertEquals(emptyList(), diff.tabsRemoved)
        assertEquals(emptyList(), diff.tabsAdded)
        assertTrue(diff.tabsModified.any { it.contains("title") }, "title-only change must be modified")
    }

    @Test
    fun `a panel added to the layout appears in panelsAdded`() {
        val left = workspace(layout = singlePanel(panel("p1", emptyList())))
        val right =
            workspace(
                layout =
                    SplitConfig.VerticalSplit(
                        left = singlePanel(panel("p1", emptyList())),
                        right = singlePanel(panel("p2", emptyList())),
                    ),
            )
        val diff = WorkspaceDiffer.diff(left, right)
        assertEquals(listOf("p2"), diff.panelsAdded)
        assertEquals(emptyList(), diff.panelsRemoved)
        assertEquals(listOf("p1"), diff.panelsKept)
        assertNotNull(diff.layoutShapeDelta)
    }

    @Test
    fun `a panel removed appears in panelsRemoved`() {
        val left =
            workspace(
                layout =
                    SplitConfig.VerticalSplit(
                        left = singlePanel(panel("p1", emptyList())),
                        right = singlePanel(panel("p2", emptyList())),
                    ),
            )
        val right = workspace(layout = singlePanel(panel("p1", emptyList())))
        val diff = WorkspaceDiffer.diff(left, right)
        assertEquals(listOf("p2"), diff.panelsRemoved)
    }

    @Test
    fun `a pin-count change surfaces in pinningChanges`() {
        val left = workspace(layout = singlePanel(panel("p1", listOf(tab("browser", url = "https://a")), pinned = 0)))
        val right = workspace(layout = singlePanel(panel("p1", listOf(tab("browser", url = "https://a")), pinned = 1)))
        val diff = WorkspaceDiffer.diff(left, right)
        assertTrue(diff.pinningChanges.any { it.contains("pinnedCount 0 → 1") })
    }

    @Test
    fun `name change surfaces as nameDelta`() {
        val left = workspace(name = "Old", layout = singlePanel(panel("p1", emptyList())))
        val right = workspace(name = "New", layout = singlePanel(panel("p1", emptyList())))
        val diff = WorkspaceDiffer.diff(left, right)
        assertEquals("Old → New", diff.nameDelta)
    }

    @Test
    fun `project path change surfaces as projectPathDelta`() {
        val left = workspace(projectPath = "/a", layout = singlePanel(panel("p1", emptyList())))
        val right = workspace(projectPath = "/b", layout = singlePanel(panel("p1", emptyList())))
        val diff = WorkspaceDiffer.diff(left, right)
        assertEquals("/a → /b", diff.projectPathDelta)
    }

    @Test
    fun `matching name and projectPath leave their deltas null`() {
        val ws = workspace(name = "X", projectPath = "/p", layout = singlePanel(panel("p1", emptyList())))
        val diff = WorkspaceDiffer.diff(ws, ws)
        assertNull(diff.nameDelta)
        assertNull(diff.projectPathDelta)
    }

    @Test
    fun `breadcrumb config change surfaces as breadcrumbDelta`() {
        val left =
            workspace(
                layout = singlePanel(panel("p1", emptyList())),
                breadcrumb = BreadcrumbConfig(enabled = true, maxLength = 50),
            )
        val right =
            workspace(
                layout = singlePanel(panel("p1", emptyList())),
                breadcrumb = BreadcrumbConfig(enabled = false, maxLength = 80),
            )
        val diff = WorkspaceDiffer.diff(left, right)
        assertNotNull(diff.breadcrumbDelta)
        assertTrue(diff.breadcrumbDelta!!.contains("maxLength"))
        assertTrue(diff.breadcrumbDelta!!.contains("enabled"))
    }

    @Test
    fun `matching breadcrumb leaves breadcrumbDelta null`() {
        val bc = BreadcrumbConfig()
        val left = workspace(layout = singlePanel(panel("p1", emptyList())), breadcrumb = bc)
        val right = workspace(layout = singlePanel(panel("p1", emptyList())), breadcrumb = bc)
        val diff = WorkspaceDiffer.diff(left, right)
        assertNull(diff.breadcrumbDelta)
    }

    @Test
    fun `panelsKept lists the panels present in both spaces`() {
        val left =
            workspace(
                layout =
                    SplitConfig.VerticalSplit(
                        left = singlePanel(panel("p1", emptyList())),
                        right = singlePanel(panel("p2", emptyList())),
                    ),
            )
        val right =
            workspace(
                layout =
                    SplitConfig.HorizontalSplit(
                        top = singlePanel(panel("p1", emptyList())),
                        bottom = singlePanel(panel("p3", emptyList())),
                    ),
            )
        val diff = WorkspaceDiffer.diff(left, right)
        assertEquals(listOf("p1"), diff.panelsKept)
        assertEquals(listOf("p3"), diff.panelsAdded)
        assertEquals(listOf("p2"), diff.panelsRemoved)
        assertNotNull(diff.layoutShapeDelta)
    }
}
