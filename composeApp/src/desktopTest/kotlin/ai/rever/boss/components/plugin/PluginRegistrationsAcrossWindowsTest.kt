package ai.rever.boss.components.plugin

import ai.rever.boss.components.plugin.registries.DeepLinkActionRegistryImpl
import ai.rever.boss.components.plugin.registries.PanelMenuRegistryImpl
import ai.rever.boss.components.plugin.registries.PluginShortcutRegistryImpl
import ai.rever.boss.components.plugin.registries.SettingsPageRegistryImpl
import ai.rever.boss.components.plugin.registries.StatusBarRegistryImpl
import ai.rever.boss.mcp.McpToolRegistryImpl
import ai.rever.boss.plugin.api.DeepLinkActionHandler
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelMenuContribution
import ai.rever.boss.plugin.api.PanelMenuItem
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginSearchResult
import ai.rever.boss.plugin.api.PluginShortcutSpec
import ai.rever.boss.plugin.api.SearchProvider
import ai.rever.boss.plugin.api.SettingsPageProvider
import ai.rever.boss.plugin.api.ShortcutActionProvider
import ai.rever.boss.plugin.api.StatusBarItemProvider
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.sandbox.InProcessPluginSandbox
import ai.rever.boss.plugin.sandbox.context.SandboxedPanelRegistry
import ai.rever.boss.plugin.sandbox.context.SandboxedPluginContext
import ai.rever.boss.plugin.sandbox.context.SandboxedTabRegistry
import ai.rever.boss.search.SearchRegistryImpl
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A plugin's app-wide registrations must survive another window closing.
 *
 * Every BOSS window owns a [DefaultPlugin] and a `DynamicPluginManager`, and loads its own copy of
 * every plugin. Seven kinds of registration go to process-wide registries keyed only by id, so the
 * second window's copy replaced the first window's entry, and closing either window unregistered
 * the id for the whole app while the other window still had the plugin loaded. Measured in the
 * released app: an attached agent saw 29 MCP tools, 29 with a second window open, and 16 once that
 * window was closed - every plugin tool gone until restart.
 *
 * Two real [DefaultPlugin] instances stand in for two windows, against the real registries.
 */
class PluginRegistrationsAcrossWindowsTest {
    private val nonce = "w${counter.incrementAndGet()}x${System.nanoTime() % 100_000}"

    /** One registration kind: how a window registers it, how it is removed, what the registry holds. */
    private class Kind(
        val name: String,
        /** Registers a fresh contribution for [id] from [context]; returns a label identifying it. */
        val register: (context: PluginContext, id: String, label: String) -> Unit,
        val unregister: (context: PluginContext, id: String) -> Unit,
        /** The label of the contribution the registry currently serves for [id], or null. */
        val current: (id: String) -> String?,
    )

    private val kinds =
        listOf(
            Kind(
                "MCP tool provider",
                { c, id, label -> c.registerMcpToolProvider(mcpProvider(id, label)) },
                { c, id -> c.unregisterMcpToolProvider(id) },
                { id ->
                    McpToolRegistryImpl.allTools.value
                        .firstOrNull { it.definition.name == "tool_$id" }
                        ?.definition
                        ?.description
                },
            ),
            Kind(
                "search provider",
                { c, id, label -> c.registerSearchProvider(searchProvider(id, label)) },
                { c, id -> c.unregisterSearchProvider(id) },
                { id ->
                    SearchRegistryImpl.providers.value
                        .firstOrNull { it.providerId == id }
                        ?.displayName
                },
            ),
            Kind(
                "panel menu contribution",
                { c, id, label -> c.registerPanelMenuContribution(panelMenu(id, label)) },
                { c, id -> c.unregisterPanelMenuContribution(id) },
                { id ->
                    PanelMenuRegistryImpl.contributions.value[id]
                        ?.items(PanelId("p", 0))
                        ?.single()
                        ?.label
                },
            ),
            Kind(
                "settings page",
                { c, id, label -> c.registerSettingsPage(settingsPage(id, label)) },
                { c, id -> c.unregisterSettingsPage(id) },
                { id -> SettingsPageRegistryImpl.pages.value[id]?.displayName },
            ),
            Kind(
                "deep-link action handler",
                { c, id, label -> c.registerDeepLinkActionHandler(deepLinkHandler(id, label)) },
                { c, id -> c.unregisterDeepLinkActionHandler(id) },
                { id -> (DeepLinkActionRegistryImpl.handlers.value[id] as? Labelled)?.label },
            ),
            Kind(
                "shortcut action provider",
                { c, id, label -> c.registerShortcutActionProvider(shortcutProvider(id, label)) },
                { c, id -> c.unregisterShortcutActionProvider(id) },
                { id ->
                    PluginShortcutRegistryImpl.shortcuts.value
                        .firstOrNull { it.providerId == id }
                        ?.spec
                        ?.displayName
                },
            ),
            Kind(
                "status bar item",
                { c, id, label -> c.registerStatusBarItem(statusBarItem(id, label)) },
                { c, id -> c.unregisterStatusBarItem(id) },
                { id -> (StatusBarRegistryImpl.items.value[id] as? Labelled)?.label },
            ),
        )

    private fun idFor(kind: Kind) = "reg_${nonce}_${kinds.indexOf(kind)}"

    /** A window's [DefaultPlugin], without a project. */
    private fun window() = DefaultPlugin(PanelRegistry(), TabRegistry(), windowProjectState = null)

    /** Runs [block] with two windows, and leaves the registries and windows as it found them. */
    private fun withTwoWindows(block: (DefaultPlugin, DefaultPlugin) -> Unit) {
        val first = window()
        val second = window()
        try {
            block(first, second)
        } finally {
            for (kind in kinds) {
                runCatching { kind.unregister(second, idFor(kind)) }
                runCatching { kind.unregister(first, idFor(kind)) }
            }
            // dispose() returns a Job now - teardown is off-thread, so join it before the
            // next test's window can meet registrations this one was still releasing.
            runCatching { runBlocking { second.dispose().join() } }
            runCatching { runBlocking { first.dispose().join() } }
        }
    }

    /** Every kind is checked before failing, so a failure names all the kinds it applies to. */
    private fun assertEveryKind(
        kinds: List<Kind>,
        expectation: (Kind) -> String?,
    ) {
        val wrong = kinds.mapNotNull { kind -> expectation(kind)?.let { "${kind.name}: $it" } }
        assertEquals(emptyList(), wrong)
    }

    private fun expect(
        expected: String?,
        kind: Kind,
    ): String? {
        val actual = kind.current(idFor(kind))
        return if (actual == expected) null else "expected $expected, registry serves $actual"
    }

    @Test
    fun `closing the newer window keeps the older window's registration`() =
        withTwoWindows { first, second ->
            assertEveryKind(kinds) { kind ->
                kind.register(first, idFor(kind), "first")
                kind.register(second, idFor(kind), "second")
                expect("second", kind) ?: run {
                    kind.unregister(second, idFor(kind))
                    expect("first", kind)
                }
            }
        }

    @Test
    fun `closing the older window keeps the newer window's registration`() =
        withTwoWindows { first, second ->
            assertEveryKind(kinds) { kind ->
                kind.register(first, idFor(kind), "first")
                kind.register(second, idFor(kind), "second")
                kind.unregister(first, idFor(kind))
                expect("second", kind)
            }
        }

    @Test
    fun `the last window to unregister removes the registration`() =
        withTwoWindows { first, second ->
            assertEveryKind(kinds) { kind ->
                kind.register(first, idFor(kind), "first")
                kind.register(second, idFor(kind), "second")
                kind.unregister(second, idFor(kind))
                kind.unregister(first, idFor(kind))
                expect(null, kind)
            }
        }

    @Test
    fun `a window that never registered an id cannot remove another window's`() =
        withTwoWindows { first, second ->
            assertEveryKind(kinds) { kind ->
                kind.register(first, idFor(kind), "first")
                kind.unregister(second, idFor(kind))
                expect("first", kind)
            }
        }

    @Test
    fun `a closed window's plugin teardown keeps the other window's registrations`() {
        // What closing a window runs: DefaultPlugin.dispose -> disposeWindow -> uninstallPlugin(force)
        // -> TrackingPluginContext.unregisterAll, through the sandbox wrapper, per loaded plugin.
        withTwoWindows { first, second ->
            val pluginId = "plugin.$nonce"
            val firstContext = pluginContext(pluginId, first)
            val secondContext = pluginContext(pluginId, second)
            for (kind in kinds) {
                kind.register(firstContext, idFor(kind), "first")
                kind.register(secondContext, idFor(kind), "second")
            }

            secondContext.unregisterAll()

            assertEveryKind(kinds) { kind -> expect("first", kind) }
        }
    }

    @Test
    fun `disposing a window releases registrations its plugins' teardown left behind`() {
        // A registration no tracking context recorded, or whose teardown threw, must not keep a closed
        // window's copy served - nor come back later when the surviving window lets go.
        val first = window()
        val second = window()
        try {
            for (kind in kinds) {
                kind.register(first, idFor(kind), "first")
                kind.register(second, idFor(kind), "second")
            }

            // Joined, not just launched: the release runs on dispose's background scope, and
            // the "late" registration below is only refused once it has run.
            runBlocking { second.dispose().join() }
            // A startup registration finishing after disposal cannot resurrect this window.
            for (kind in kinds) kind.register(second, idFor(kind), "late-second")

            assertEveryKind(kinds) { kind -> expect("first", kind) }
        } finally {
            for (kind in kinds) runCatching { kind.unregister(first, idFor(kind)) }
            runCatching { runBlocking { first.dispose().join() } }
        }
    }

    private fun pluginContext(
        pluginId: String,
        window: DefaultPlugin,
    ): TrackingPluginContext {
        val sandbox = InProcessPluginSandbox(pluginId)
        val sandboxed =
            SandboxedPluginContext(
                _sandbox = sandbox,
                delegate = window,
                sandboxedPanelRegistry = SandboxedPanelRegistry(sandbox, window.panelRegistry),
                sandboxedTabRegistry = SandboxedTabRegistry(sandbox, window.tabRegistry),
            )
        return TrackingPluginContext(pluginId = pluginId, delegate = sandboxed, tracker = PluginRegistrationTracker())
    }

    private interface Labelled {
        val label: String
    }

    private companion object {
        val counter = AtomicInteger()

        fun mcpProvider(
            id: String,
            label: String,
        ): McpToolProvider =
            object : McpToolProvider {
                override val providerId = id

                override fun tools() =
                    listOf(
                        McpToolDefinition(
                            name = "tool_$id",
                            description = label,
                            handler = McpToolHandler { McpToolResult(label) },
                        ),
                    )
            }

        fun searchProvider(
            id: String,
            label: String,
        ): SearchProvider =
            object : SearchProvider {
                override val providerId = id
                override val displayName = label

                override suspend fun search(
                    query: String,
                    limit: Int,
                ): List<PluginSearchResult> = emptyList()
            }

        fun panelMenu(
            id: String,
            label: String,
        ): PanelMenuContribution =
            object : PanelMenuContribution {
                override val contributionId = id

                override fun items(panelId: PanelId) = listOf(PanelMenuItem(id = "item", label = label))

                override fun onItemClick(
                    panelId: PanelId,
                    itemId: String,
                    windowId: String?,
                ) = Unit
            }

        fun settingsPage(
            id: String,
            label: String,
        ): SettingsPageProvider =
            object : SettingsPageProvider {
                override val pageId = id
                override val displayName = label
                override val description = label
                override val icon = Icons.Default.Settings

                @Composable
                override fun Content() = Unit
            }

        fun deepLinkHandler(
            id: String,
            label: String,
        ): DeepLinkActionHandler =
            object : DeepLinkActionHandler, Labelled {
                override val handlerId = id
                override val label = label

                override fun handle(
                    action: String,
                    params: Map<String, String>,
                ) = false
            }

        fun shortcutProvider(
            id: String,
            label: String,
        ): ShortcutActionProvider =
            object : ShortcutActionProvider {
                override val providerId = id

                override fun shortcuts() = listOf(PluginShortcutSpec("plugin.$id.act", label, label))

                override fun onAction(
                    actionId: String,
                    windowId: String?,
                ) = Unit
            }

        fun statusBarItem(
            id: String,
            label: String,
        ): StatusBarItemProvider =
            object : StatusBarItemProvider, Labelled {
                override val itemId = id
                override val label = label

                @Composable
                override fun Content() = Unit
            }
    }
}
