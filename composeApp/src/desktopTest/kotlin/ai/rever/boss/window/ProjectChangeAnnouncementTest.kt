package ai.rever.boss.window

import ai.rever.boss.components.plugin.panels.left_top.ProjectState
import ai.rever.boss.components.plugin.providers.ProjectDataProviderImpl
import ai.rever.boss.components.plugin.providers.publishSystemEvent
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.PanelConfig
import ai.rever.boss.components.workspaces.applyWorkspace
import ai.rever.boss.plugin.api.ApplicationEvent
import ai.rever.boss.plugin.api.ApplicationEventBus
import ai.rever.boss.plugin.api.ApplicationEventBusRegistry
import ai.rever.boss.plugin.api.ProjectChangeEvent
import ai.rever.boss.plugin.api.ProjectData
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `ProjectChangeEvent` used to be published from `ProjectDataProviderImpl.selectProject` - the
 * path a *plugin* takes, and the only one of about ten. The startup restore
 * (`WorkspaceApplier.applyWorkspace`) calls `WindowProjectState.selectProject` directly, so it
 * announced nothing, and a panel built before the workspace JSON came off disk read the seeded
 * "" and stayed empty for the session. The bus is `replay = 0`, so nothing recovers it later.
 *
 * What these pin is the placement, not the plumbing: the announcement hangs off the state's own
 * [ProjectSelectionCallback], which `WindowProjectState.selectProject` - the sole mutator of the
 * selection - invokes synchronously for every caller. Move it back onto any single caller, or
 * drop it from `WindowProjectStateRegistry.newState`, and one of these fails.
 *
 * Isolation note: `captured` is fed by the process-global
 * `ApplicationEventBusRegistry.systemPublisher`, so while this class runs it intercepts every
 * host event in the JVM and `changes().single()` assumes nothing else is publishing. That holds
 * because `composeApp/build.gradle.kts` sets no `maxParallelForks`; turning parallel forks on
 * would break this class, `BrowserAnalyticsEmissionTest` and `BossTabsComponentMoveTest` together.
 */
class ProjectChangeAnnouncementTest {
    private val captured = mutableListOf<ApplicationEvent>()
    private var previousPublisher: ((ApplicationEvent) -> Unit)? = null

    @BeforeTest
    fun install() {
        previousPublisher = ApplicationEventBusRegistry.systemPublisher
        ApplicationEventBusRegistry.systemPublisher = { captured += it }
        // ProjectDataProviderImpl collects on Dispatchers.Main, which has no implementation in a
        // plain test JVM - its launch fails and the collector silently never runs. The announcer
        // itself uses no dispatcher; this is only so the provider in
        // `a plugin-initiated selection is announced exactly once` is a working object.
        //
        // Dispatchers.Unconfined, NOT UnconfinedTestDispatcher. The provider owns its scope and
        // nothing cancels it (see ProjectDataProviderImpl: DisposableProvider is deliberately not
        // implemented), so its `ProjectState.recentProjects` collector outlives this class for
        // the rest of the JVM. On a TestCoroutineScheduler that live coroutine is something every
        // later `runTest` in the suite waits on, and the git tests - the next `runTest`-heavy
        // classes to run - time out after 60s. Unconfined gives the same run-on-the-calling-
        // thread behaviour without enrolling the leak in a scheduler anyone else observes.
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @AfterTest
    fun restore() {
        Dispatchers.resetMain()
        ApplicationEventBusRegistry.systemPublisher = previousPublisher
    }

    private fun changes() = captured.filterIsInstance<ProjectChangeEvent>()

    /** A state wired the way [WindowProjectStateRegistry] wires one, minus the recent-projects half. */
    private fun announcingState(windowId: String): WindowProjectState =
        WindowProjectState(windowId).also { state ->
            state.setProjectSelectionCallback(ProjectChangeAnnouncer(windowId, state.selectedProject.value.path))
        }

    private fun project(path: String) = Project(name = path.substringAfterLast('/'), path = path, lastOpened = 0L)

    // ============================================================
    // The regression: a selection nobody routed through the plugin provider.
    // ============================================================

    @Test
    fun `a selection made directly on the state is announced`() {
        val state = announcingState("w-direct")

        // Exactly what WorkspaceApplier.applyWorkspace does on startup restore.
        state.selectProject(project("/tmp/boss-pca-restored"))

        val event = changes().single()
        assertEquals("/tmp/boss-pca-restored", event.projectPath)
        assertEquals("w-direct", event.windowId)
        assertEquals("", event.previousProjectPath, "no project was open before the restore")
    }

    @Test
    fun `the second selection carries the first as its previous path`() {
        val state = announcingState("w-two")

        state.selectProject(project("/tmp/boss-pca-first"))
        state.selectProject(project("/tmp/boss-pca-second"))

        assertEquals(
            listOf("" to "/tmp/boss-pca-first", "/tmp/boss-pca-first" to "/tmp/boss-pca-second"),
            changes().map { it.previousProjectPath to it.projectPath },
        )
    }

    /**
     * Deliberate behaviour change, and the only one this carries. `selectProject` rewrites
     * `lastOpened` on every call, so the old publish site fired for a repeat selection with
     * `previousProjectPath == projectPath`.
     */
    @Test
    fun `re-selecting the same project announces nothing`() {
        val state = announcingState("w-repeat")

        state.selectProject(project("/tmp/boss-pca-same"))
        state.selectProject(project("/tmp/boss-pca-same"))

        assertEquals(1, changes().size)
    }

    /**
     * The seed. Announcing the selection that was already in place - `""` in every production
     * path - would tell every plugin the project just became "no project", moments before the
     * real restore lands: precisely the clear-yourself signal this exists to avoid.
     */
    @Test
    fun `a project already selected when the announcer is installed is not announced`() {
        val state = WindowProjectState("w-seeded")
        state.selectProject(project("/tmp/boss-pca-preexisting"))
        state.setProjectSelectionCallback(ProjectChangeAnnouncer("w-seeded", state.selectedProject.value.path))

        assertTrue(changes().isEmpty(), "the standing selection is not a change")

        state.selectProject(project("/tmp/boss-pca-next"))

        assertEquals("/tmp/boss-pca-preexisting", changes().single().previousProjectPath)
    }

    // ============================================================
    // The wiring. Without these, the announcer above is a class nothing installs.
    // ============================================================

    @Test
    fun `a state from the registry announces`() {
        val created = WindowProjectStateRegistry.getOrCreate("w-created")
        try {
            created.selectProject(project(CREATED_PATH))

            assertEquals(
                listOf("w-created" to CREATED_PATH),
                changes().map { it.windowId to it.projectPath },
            )
            // The callback's OTHER half. Without this, deleting ProjectState.updateRecentProjects
            // from newState leaves every test in this file green while the project picker
            // silently stops recording anything.
            val recent = ProjectState.recentProjects.value.map { it.path }
            assertTrue(CREATED_PATH in recent, "the recent-projects half of the callback did not run")
        } finally {
            WindowProjectStateRegistry.unregister("w-created")
            ProjectState.removeRecentProject(CREATED_PATH)
        }
    }

    /**
     * The regression path itself, rather than a comment claiming to imitate it. `applyWorkspace`
     * is what runs on startup restore, and its `windowProjectState.selectProject` call is the one
     * that used to announce nothing at all.
     *
     * `WorkspaceApplierMigrationTest` already stands this fixture up; this is the same shape with
     * an empty panel, since no tab is needed to observe the selection.
     */
    @Test
    fun `applyWorkspace announces the project it restores`() {
        val state = WindowProjectStateRegistry.getOrCreate("w-restore")
        try {
            val workspace =
                LayoutWorkspace(
                    id = "restore-test",
                    name = "Restore test",
                    description = "No tabs; only the project matters",
                    layout = SinglePanel(PanelConfig(id = "main", tabs = emptyList())),
                    projectPath = RESTORED_PATH,
                )

            runBlocking { applyWorkspace(workspace, SplitViewState(TabRegistry(), windowId = "w-restore"), state) }

            val event = changes().single()
            assertEquals(RESTORED_PATH, event.projectPath)
            assertEquals("w-restore", event.windowId)
            assertEquals("", event.previousProjectPath)
        } finally {
            WindowProjectStateRegistry.unregister("w-restore")
            ProjectState.removeRecentProject(RESTORED_PATH)
        }
    }

    /**
     * The path that always worked, which this refactor is most likely to break in either
     * direction: silently to zero (the provider stopped publishing and nothing replaced it) or to
     * two (someone re-adds the publish to `selectProject` alongside the callback).
     */
    @Test
    fun `a plugin-initiated selection is announced exactly once`() {
        val state = WindowProjectStateRegistry.getOrCreate("w-plugin")
        val provider = ProjectDataProviderImpl(state)
        try {
            provider.selectProject(ProjectData(name = "plugin", path = PLUGIN_PATH, lastOpened = 0L))

            val event = changes().single()
            assertEquals(PLUGIN_PATH, event.projectPath)
            assertEquals("w-plugin", event.windowId)
        } finally {
            WindowProjectStateRegistry.unregister("w-plugin")
            ProjectState.removeRecentProject(PLUGIN_PATH)
        }
    }

    /**
     * Windows are independent. This is the property most likely to break if the announcer is
     * ever hoisted to an object or the seed is shared: B's selection must not move A's chain.
     */
    @Test
    fun `one window's selection does not touch another window's previous path`() {
        val a = announcingState("w-a")
        val b = announcingState("w-b")

        a.selectProject(project("/tmp/boss-pca-a1"))
        b.selectProject(project("/tmp/boss-pca-b1"))
        a.selectProject(project("/tmp/boss-pca-a2"))

        assertEquals(
            listOf(
                Triple("w-a", "", "/tmp/boss-pca-a1"),
                Triple("w-b", "", "/tmp/boss-pca-b1"),
                // "" would mean B's selection reset A, /tmp/boss-pca-b1 that they share a chain.
                Triple("w-a", "/tmp/boss-pca-a1", "/tmp/boss-pca-a2"),
            ),
            changes().map { Triple(it.windowId, it.previousProjectPath, it.projectPath) },
        )
    }

    /**
     * The one design claim in `newState` that is not about placement: the announcement must not
     * be the half an unrelated failure can skip. `ProjectState` is an object, so this is only
     * reachable because `hostProjectCallback` takes the recents update as a parameter.
     */
    @Test
    fun `a failing recent-projects update does not swallow the announcement`() {
        val state = WindowProjectState("w-throwing")
        state.setProjectSelectionCallback(
            WindowProjectStateRegistry.hostProjectCallback(
                updateRecents = { error("recent-projects persistence is broken") },
                announcer = ProjectChangeAnnouncer("w-throwing", ""),
            ),
        )

        assertFailsWith<IllegalStateException> {
            state.selectProject(project("/tmp/boss-pca-throwing"))
        }

        // Announced anyway - and, the half that actually bites, previousPath advanced with it.
        assertEquals("/tmp/boss-pca-throwing", changes().single().projectPath)

        // Throws again - updateRecents is broken for good in this test - but the announcement
        // is what is being read, and it happens in the finally either way.
        assertFailsWith<IllegalStateException> {
            state.selectProject(project("/tmp/boss-pca-throwing-next"))
        }

        assertEquals(
            "/tmp/boss-pca-throwing",
            changes().last().previousProjectPath,
            "previousPath was left behind by the throw, so every later event is one step stale",
        )
    }

    // ============================================================
    // The bus itself: it is created lazily, and used to drop everything until it was.
    // ============================================================

    /**
     * `publishSystemEvent` routed through `ApplicationEventBusRegistry.systemPublisher` and did
     * nothing when it was absent - and the only thing that ever created the bus was a plugin
     * touching `PluginContext.applicationEventBus`. So on a build where no installed plugin had
     * asked yet, no host event existed at all: not this one, not `AuthEvent`, not `TabEvent`.
     *
     * To be precise about what is fixed: no registered publisher means nobody holds the bus,
     * which means it has no subscribers, so the event that triggers this branch still reaches
     * no one. What changes is that it is the last one to - the host stops being permanently
     * silent while it waits for a plugin to be curious.
     */
    @Test
    fun `a system event published with no bus leaves one behind, so the next one has somewhere to go`() {
        val previousBus: ApplicationEventBus? = ApplicationEventBusRegistry.bus
        val outerPublisher = ApplicationEventBusRegistry.systemPublisher
        ApplicationEventBusRegistry.bus = null
        ApplicationEventBusRegistry.systemPublisher = null
        try {
            publishSystemEvent(
                ProjectChangeEvent(projectPath = "/tmp/boss-pca-nobus", previousProjectPath = "", windowId = "w-nobus"),
            )

            assertNotNull(ApplicationEventBusRegistry.bus, "the publish should have created the bus")
            assertNotNull(ApplicationEventBusRegistry.systemPublisher, "and registered a publisher for the next one")
        } finally {
            ApplicationEventBusRegistry.bus = previousBus
            ApplicationEventBusRegistry.systemPublisher = outerPublisher
        }
    }

    private companion object {
        // Deliberately non-existent directories. ProjectState's saves are fire-and-forget on
        // Dispatchers.IO, so an add and its cleanup remove are unordered and a test path can
        // survive in the real recent-projects.json. loadRecentProjects drops entries whose
        // directory is gone, so a non-existent path is reclaimed on the next launch; a real temp
        // directory would not be.
        const val CREATED_PATH = "/tmp/boss-pca-created"
        const val RESTORED_PATH = "/tmp/boss-pca-restored-by-applier"
        const val PLUGIN_PATH = "/tmp/boss-pca-plugin"
    }
}
