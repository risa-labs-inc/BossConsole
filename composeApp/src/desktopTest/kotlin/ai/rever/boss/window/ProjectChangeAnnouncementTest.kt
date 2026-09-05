package ai.rever.boss.window

import ai.rever.boss.components.plugin.panels.left_top.ProjectState
import ai.rever.boss.components.plugin.providers.publishSystemEvent
import ai.rever.boss.plugin.api.ApplicationEvent
import ai.rever.boss.plugin.api.ApplicationEventBus
import ai.rever.boss.plugin.api.ApplicationEventBusRegistry
import ai.rever.boss.plugin.api.ProjectChangeEvent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * drop it from one of the registry's two construction paths, and one of these fails.
 */
class ProjectChangeAnnouncementTest {
    private val captured = mutableListOf<ApplicationEvent>()
    private var previousPublisher: ((ApplicationEvent) -> Unit)? = null

    @BeforeTest
    fun install() {
        previousPublisher = ApplicationEventBusRegistry.systemPublisher
        ApplicationEventBusRegistry.systemPublisher = { captured += it }
    }

    @AfterTest
    fun restore() {
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
    fun `a state from the registry announces, whichever entry point built it`() {
        val registered = WindowProjectStateRegistry.register("w-registered")
        val created = WindowProjectStateRegistry.getOrCreate("w-created")
        try {
            registered.selectProject(project(REGISTERED_PATH))
            created.selectProject(project(CREATED_PATH))

            assertEquals(
                listOf("w-registered" to REGISTERED_PATH, "w-created" to CREATED_PATH),
                changes().map { it.windowId to it.projectPath },
            )
        } finally {
            WindowProjectStateRegistry.unregister("w-registered")
            WindowProjectStateRegistry.unregister("w-created")
            // The registry's other half is ProjectState.updateRecentProjects, which persists.
            // These paths do not exist; leaving them in the list would put them in the picker.
            ProjectState.removeRecentProject(REGISTERED_PATH)
            ProjectState.removeRecentProject(CREATED_PATH)
        }
    }

    // ============================================================
    // The bus itself: it is created lazily, and used to drop everything until it was.
    // ============================================================

    /**
     * `publishSystemEvent` routed through `ApplicationEventBusRegistry.systemPublisher` and did
     * nothing when it was absent - and the only thing that ever created the bus was a plugin
     * touching `PluginContext.applicationEventBus`. So on a build where no installed plugin had
     * asked yet, no host event existed at all: not this one, not `AuthEvent`, not `TabEvent`.
     */
    @Test
    fun `a system event published before any subscriber creates the bus rather than being dropped`() {
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
        const val REGISTERED_PATH = "/tmp/boss-pca-registered"
        const val CREATED_PATH = "/tmp/boss-pca-created"
    }
}
