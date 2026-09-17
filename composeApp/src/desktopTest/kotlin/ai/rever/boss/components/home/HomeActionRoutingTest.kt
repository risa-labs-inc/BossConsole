package ai.rever.boss.components.home

import ai.rever.boss.components.events.DashboardEventBus
import ai.rever.boss.components.events.DashboardNewTabEvent
import ai.rever.boss.components.events.DashboardOpenTabTypeEvent
import ai.rever.boss.plugin.api.TabTypeId
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.reflect.KFunction
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The guard for the defect this rewrite exists to remove.
 *
 * The home screen had two mount points and took twelve action callbacks, so each mount had to
 * supply all twelve. One of them - `DashboardContentProviderImpl`, the browser's about:blank
 * surface - passed eleven of them as `{ /* No-op for browser plugin */ }` and `onShowSettings` as
 * null. Every project card, file card, split template, "Open File", "New Project", "New Tab",
 * "New Terminal", "New Window", "Open Project" and "Settings" rendered normally there and did
 * nothing at all. Two more, `onActivatePlugin` and `onNewTerminal`, were dead on both ends: the
 * screen never invoked them and one call site had wired `onActivatePlugin` to a bare `logger.debug`.
 *
 * Nothing failed. There was no test, no log line and no visible difference between a card that
 * worked and a card that did not.
 *
 * `HomeScreen` therefore takes **no action callbacks at all**: it emits on [DashboardEventBus],
 * whose handlers live in `BossAppEventBusEffects` and are window-filtered. These tests hold that
 * shape in place structurally, because the failure mode is silence and a behavioural test of "did
 * clicking this do something" is exactly what nobody wrote for four years.
 */
class HomeActionRoutingTest {
    @Test
    fun `HomeScreen takes no action callbacks`() {
        val homeScreen =
            Class
                .forName("ai.rever.boss.components.home.HomeScreenKt")
                .declaredMethods
                .single { it.name == "HomeScreen" }

        // A composable's synthetic parameters are Modifier, Composer and two ints. Any
        // Function0/Function1 beyond that is an action callback, which is the shape that let a
        // caller pass an empty lambda.
        val functionParams =
            homeScreen.parameterTypes.filter { param ->
                param.name.startsWith("kotlin.jvm.functions.Function")
            }

        assertTrue(
            functionParams.isEmpty(),
            "HomeScreen must not take action callbacks - that is what allowed a mount point to " +
                "supply eleven empty lambdas. Route new actions through DashboardEventBus " +
                "instead. Found: $functionParams",
        )
    }

    @Test
    fun `every host action is routed, none silently unhandled`() {
        // HomeHostAction is a closed enum and `hostAction` is an exhaustive `when` over it, so
        // this passing means the compiler has already refused any member without a branch. The
        // assertion is that the enum is the whole surface: if someone adds an action they must
        // add a route, and they cannot do it by passing a lambda from a call site.
        assertTrue(HomeHostAction.entries.isNotEmpty())
        assertEquals(
            HomeHostAction.entries.size,
            HomeHostAction.entries
                .map { it.name }
                .distinct()
                .size,
        )
    }

    @Test
    fun `every emit on the bus carries a source window id`() {
        // Window filtering is what stops one window's home screen acting on every window. An
        // emit function that forgot the parameter would compile and would broadcast.
        val emitters =
            DashboardEventBus::class
                .declaredMemberFunctions
                .filter { it.name !in NON_EMITTER_FUNCTIONS }

        assertTrue(emitters.isNotEmpty(), "Expected DashboardEventBus to expose emit functions")
        emitters.forEach { emitter ->
            assertTrue(
                emitter.hasSourceWindowId(),
                "${emitter.name} must take sourceWindowId, or it acts on every window at once",
            )
        }
    }

    @Test
    fun `every event flow has a matching emit function`() {
        // A flow with no emitter is precisely the state this whole bus was in before: nine
        // SharedFlows, nine handlers in BossAppEventBusEffects, and not one caller anywhere in
        // the repo. The handlers were unreachable and the screen used callbacks instead.
        val flowNames =
            DashboardEventBus::class
                .declaredMemberProperties
                // Public only: each flow has a private `_name` backing field beside it, and
                // those are not the surface anything emits through.
                .filter { it.visibility == KVisibility.PUBLIC }
                .map { it.name }
                .filter { it.endsWith("Events") }
        val emitterNames =
            DashboardEventBus::class
                .declaredMemberFunctions
                .map { it.name }
                .toSet()

        assertTrue(flowNames.isNotEmpty())
        flowNames.forEach { flow ->
            // openFileEvents -> openFile, showSettingsEvents -> showSettings
            val expected = flow.removeSuffix("Events")
            assertTrue(
                expected in emitterNames,
                "$flow has no emit function named '$expected'. A flow with no emitter is dead, " +
                    "which is how nine of these came to have handlers nothing could reach.",
            )
        }
    }

    @Test
    fun `every event flow is collected by a handler`() {
        // The other half of the pairing, and the half that was actually missing: an emitter with
        // no handler is as dead as a handler with no emitter, and neither fails anything at
        // runtime. Read as source because the handlers are `LaunchedEffect` bodies inside a
        // composable - there is no registry of them to reflect over.
        val handlers =
            File("src/commonMain/kotlin/ai/rever/boss/app/BossAppEventBusEffects.kt")
                .readText()

        val flowNames =
            DashboardEventBus::class
                .declaredMemberProperties
                .filter { it.visibility == KVisibility.PUBLIC }
                .map { it.name }
                .filter { it.endsWith("Events") }

        assertTrue(flowNames.isNotEmpty())
        flowNames.forEach { flow ->
            assertTrue(
                handlers.contains("DashboardEventBus.$flow"),
                "$flow is emitted but never collected in BossAppEventBusEffects, so the action " +
                    "it represents silently does nothing.",
            )
        }
    }

    @Test
    fun `a tab type is addressed by both halves of its registry key`() {
        // The Arcade bug. `TabTypeId` is a data class over (typeId, pluginId), so a handler that
        // rebuilt the key from typeId alone produced `TabTypeId("arcade", "")`, which does not
        // equal the `TabTypeId("arcade", "ai.rever.boss.plugin.dynamic.arcade")` the plugin
        // registered. The SnapshotStateMap lookup missed, the handler waited out its
        // registration timeout and logged "tab type never registered", and clicking Arcade did
        // nothing - for a plugin that was installed, loaded, and visible in the grid.
        //
        // Both halves must therefore survive from the registry through the tile to the event.
        val openTab = HomeToolLaunch.OpenTab("arcade", "ai.rever.boss.plugin.dynamic.arcade", needsInput = false)
        assertEquals("arcade", openTab.typeId)
        assertEquals("ai.rever.boss.plugin.dynamic.arcade", openTab.typePluginId)

        val emitter =
            DashboardEventBus::class
                .declaredMemberFunctions
                .single { it.name == "openTabType" }
        assertTrue(
            emitter.parameters.any { it.name == "typePluginId" },
            "openTabType must carry typePluginId, or the handler is back to guessing the " +
                "registry key and Arcade silently stops opening",
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Home input tools emit full identity and instant tools bypass the dialog`() =
        runTest {
            val requests = mutableListOf<DashboardNewTabEvent>()
            val instant = mutableListOf<DashboardOpenTabTypeEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                DashboardEventBus.newTabEvents.collect { requests += it }
            }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                DashboardEventBus.openTabTypeEvents.collect { instant += it }
            }
            val installing = mutableStateMapOf<String, Unit>()

            fun tool(
                plugin: String,
                needsInput: Boolean,
            ) = HomeTool(
                id = plugin,
                label = plugin,
                icon = HomeToolIcon.FromStore("", "T"),
                launch = HomeToolLaunch.OpenTab("query", plugin, needsInput),
            )
            HomeActions("window-a", this).launch(tool("first", true), installing)
            HomeActions("window-b", this).launch(tool("second", true), installing)
            HomeActions("window-b", this).launch(tool("instant", false), installing)
            HomeActions("window-a", this).newTab()
            runCurrent()
            assertEquals(
                listOf(
                    DashboardNewTabEvent("window-a", TabTypeId("query", "first")),
                    DashboardNewTabEvent("window-b", TabTypeId("query", "second")),
                    DashboardNewTabEvent("window-a"),
                ),
                requests,
            )
            assertEquals("instant", instant.single().typePluginId)
            assertEquals("query", instant.single().typeId)
            assertEquals("window-b", instant.single().sourceWindowId)
        }

    private fun KFunction<*>.hasSourceWindowId(): Boolean = parameters.any { it.name == "sourceWindowId" }

    private companion object {
        /** Not emitters: the IPC bridge accessors the bus exposes for kernel mode. */
        val NON_EMITTER_FUNCTIONS = setOf("getIpcBridge", "setIpcBridge")
    }
}
