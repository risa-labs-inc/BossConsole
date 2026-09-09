package ai.rever.boss.components.home

import ai.rever.boss.components.plugin.PluginDependencyResolution
import ai.rever.boss.components.plugin.RetiredPluginIds
import ai.rever.boss.components.plugin.registries.RegistryAccess
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [HomeToolCatalog], the derivation that replaced the home screen's hardcoded list of
 * twelve cards.
 *
 * Every case here is one where being wrong is **silent**: a tool that quietly does not appear, or
 * an Install tile that quietly cannot work. That is what the old screen did for 33 installed
 * plugins, so the rules that stop it are worth pinning.
 */
class HomeToolCatalogTest {
    private val icon: ImageVector = Icons.Default.Extension
    private val admin = RegistryAccess(isAdmin = true, permissions = emptySet())
    private val nobody = RegistryAccess(isAdmin = false, permissions = emptySet())

    private fun tabType(
        typeId: String,
        pluginId: String? = null,
        offered: Boolean = true,
        needsInput: Boolean = false,
    ) = HomeTabTypeInput(
        typeId = typeId,
        typePluginId = pluginId.orEmpty(),
        displayName = typeId.replaceFirstChar { it.uppercase() },
        icon = icon,
        offeredInNewTab = offered,
        needsInput = needsInput,
        ownerPluginId = pluginId,
    )

    private fun storeRow(
        pluginId: String,
        requiresAdmin: Boolean = false,
        compatible: Boolean = true,
        service: Boolean = false,
        displayName: String = pluginId.substringAfterLast('.'),
        iconUrl: String = "",
    ) = HomeStorePluginInput(
        pluginId = pluginId,
        displayName = displayName,
        iconUrl = iconUrl,
        requiresAdmin = requiresAdmin,
        isCompatible = compatible,
        isService = service,
    )

    private fun build(
        tabTypes: List<HomeTabTypeInput> = emptyList(),
        panels: List<HomePanelInput> = emptyList(),
        store: List<HomeStorePluginInput> = emptyList(),
        installed: Set<String> = emptySet(),
        access: RegistryAccess = admin,
        versions: Map<String, String> = emptyMap(),
    ) = HomeToolCatalog
        .build(
            tabTypes = tabTypes,
            panels = panels,
            storeCatalogue = store,
            installedPluginIds = installed,
            access = access,
            installedVersionOf = { versions[it] },
        )
        // The host actions are always present and tested separately below; every case here is
        // about what plugins and the store contribute.
        .filterNot { it.launch is HomeToolLaunch.HostAction }

    @Test
    fun `a registered tab type becomes a ready tool`() {
        val tools = build(tabTypes = listOf(tabType("arcade", "ai.rever.boss.plugin.dynamic.arcade")))

        val arcade = tools.single()
        assertTrue(arcade.isReady)
        assertEquals(
            HomeToolLaunch.OpenTab("arcade", "ai.rever.boss.plugin.dynamic.arcade", needsInput = false),
            arcade.launch,
        )
        // The tool's own registered icon, not one looked up by plugin id.
        assertEquals(HomeToolIcon.Vector(icon), arcade.icon)
    }

    @Test
    fun `a tab type that did not opt into the new tab dialog is not offered`() {
        // newTabSpec == null is a plugin declining to be offered. Honouring the same signal the
        // dialog honours keeps one contract rather than two that can disagree.
        val tools = build(tabTypes = listOf(tabType("internal", offered = false)))

        assertTrue(tools.isEmpty())
    }

    @Test
    fun `a tab type needing input carries that through so the tile can hand off to the dialog`() {
        val tools = build(tabTypes = listOf(tabType("url", needsInput = true)))

        assertEquals(HomeToolLaunch.OpenTab("url", "", needsInput = true), tools.single().launch)
    }

    @Test
    fun `an uninstalled store plugin becomes an install tool`() {
        val tools = build(store = listOf(storeRow("ai.rever.boss.plugin.dynamic.kubernetes")))

        val k8s = tools.single()
        assertFalse(k8s.isReady)
        assertEquals(HomeToolLaunch.Install("ai.rever.boss.plugin.dynamic.kubernetes"), k8s.launch)
    }

    @Test
    fun `a store tile takes its icon from the store row, not from a table`() {
        val tools =
            build(
                store =
                    listOf(
                        storeRow(
                            "ai.rever.boss.plugin.dynamic.kubernetes",
                            displayName = "Kubernetes",
                            iconUrl = "https://cdn.example/k8s.png",
                        ),
                    ),
            )

        assertEquals(
            HomeToolIcon.FromStore("https://cdn.example/k8s.png", "K"),
            tools.single().icon,
        )
    }

    @Test
    fun `a store row with no icon falls back to initials from its own name`() {
        // icon_url is blank for every row in the store today, so this is the common path, not an
        // error path. Initials come from the display name - there is no id-to-icon table to
        // consult, which is the point: a plugin published after this build presents itself.
        val tools =
            build(
                store = listOf(storeRow("ai.rever.boss.plugin.dynamic.toolevolver", displayName = "Tool Evolver")),
            )

        assertEquals(HomeToolIcon.FromStore("", "TE"), tools.single().icon)
    }

    @Test
    fun `initials never come back empty`() {
        // A blank tile icon reads as a rendering hole, so every input must yield something.
        assertEquals("A", initialsFor("Arcade"))
        assertEquals("TE", initialsFor("Tool Evolver"))
        assertEquals("GL", initialsFor("git-log"))
        assertEquals("RC", initialsFor("  Run   Configurations  "))
        assertEquals("?", initialsFor(""))
        assertEquals("?", initialsFor("   "))
    }

    @Test
    fun `an installed plugin is not also offered for install`() {
        val tools =
            build(
                store = listOf(storeRow("ai.rever.boss.plugin.dynamic.docker")),
                installed = setOf("ai.rever.boss.plugin.dynamic.docker"),
            )

        assertTrue(tools.isEmpty())
    }

    @Test
    fun `a plugin already contributing a tool is not also offered for install`() {
        // Deduped by plugin, not by tool id. The tool's id is "tab:arcade" and the store row's is
        // the plugin id, so comparing ids would let the same plugin appear twice - once to open
        // and once to install.
        val tools =
            build(
                tabTypes = listOf(tabType("arcade", "ai.rever.boss.plugin.dynamic.arcade")),
                store = listOf(storeRow("ai.rever.boss.plugin.dynamic.arcade")),
            )

        assertEquals(1, tools.size)
        assertTrue(tools.single().isReady)
    }

    @Test
    fun `the api plugin and the microkernel runtime are never offered`() {
        val tools =
            build(
                store = PluginDependencyResolution.NOT_USER_INSTALLABLE.map { storeRow(it) },
            )

        assertTrue(
            tools.isEmpty(),
            "NOT_USER_INSTALLABLE ids must not reach a tile: the api plugin's install is an " +
                "unload-all / swap / reload-all hot swap.",
        )
    }

    @Test
    fun `a retired plugin is not offered once its replacement is new enough`() {
        // Unlisting the store row is a manual action outside CI, so until it happens the store
        // keeps returning the plugin - and a user could install it, have the startup sweep remove
        // it, and install it again indefinitely. The filter is what makes the retirement hold
        // regardless of what the store says - and only once the sweep itself would be able to
        // act (the replacement at its absorbing release or newer).
        val versions =
            RetiredPluginIds
                .ALL
                .associate { it.replacementId to it.minReplacementVersion }
        val tools = build(store = RetiredPluginIds.ALL_IDS.map { storeRow(it) }, versions = versions)

        assertTrue(
            tools.isEmpty(),
            "a retired plugin reached a tile: installing it only gets it swept away at the " +
                "next launch",
        )
    }

    @Test
    fun `a retired plugin is still offered while its replacement predates the floor`() {
        // The floor is the sweep's own: between this host release and the replacement's
        // absorbing release a fresh install has only the retired plugin, so hiding it
        // unconditionally would leave the machine with no panel at all. An OLD replacement
        // (installed but below the floor) must therefore NOT hide the retired one.
        val oldVersion = "0.0.1"
        val tools =
            build(
                store = RetiredPluginIds.ALL_IDS.map { storeRow(it) },
                versions = RetiredPluginIds.ALL.associate { it.replacementId to oldVersion },
            )
        assertEquals(
            RetiredPluginIds.ALL_IDS.size,
            tools.size,
            "the retired plugin was hidden while the sweep is floored out: a fresh install would have no panel",
        )
        // ... and a missing replacement is the same answer - offered, because the sweep cannot
        // act either.
        val noneInstalled = build(store = RetiredPluginIds.ALL_IDS.map { storeRow(it) })
        assertEquals(RetiredPluginIds.ALL_IDS.size, noneInstalled.size)
    }

    @Test
    fun `a store row this host cannot load is not offered`() {
        val tools = build(store = listOf(storeRow("ai.rever.boss.plugin.dynamic.future", compatible = false)))

        assertTrue(tools.isEmpty(), "Offering an install that is certain to fail is worse than silence.")
    }

    @Test
    fun `a service plugin is not offered`() {
        val tools = build(store = listOf(storeRow("ai.rever.boss.plugin.dynamic.someservice", service = true)))

        assertTrue(tools.isEmpty())
    }

    @Test
    fun `an admin-only store plugin is hidden from a non-admin and shown to an admin`() {
        val row = storeRow("ai.rever.boss.plugin.dynamic.adminrolemanagement", requiresAdmin = true)

        assertTrue(build(store = listOf(row), access = nobody).isEmpty())
        assertEquals(1, build(store = listOf(row), access = admin).size)
    }

    @Test
    fun `a blank plugin id is dropped`() {
        val tools = build(store = listOf(storeRow("")))

        assertTrue(tools.isEmpty())
    }

    @Test
    fun `duplicate store rows for one plugin yield one tile`() {
        val tools =
            build(
                store =
                    listOf(
                        storeRow("ai.rever.boss.plugin.dynamic.flowtab"),
                        storeRow("ai.rever.boss.plugin.dynamic.flowtab"),
                    ),
            )

        assertEquals(1, tools.size)
    }

    @Test
    fun `ready tools sort before installable ones`() {
        val tools =
            build(
                tabTypes = listOf(tabType("zzz", "ai.rever.boss.plugin.dynamic.arcade")),
                store = listOf(storeRow("ai.rever.boss.plugin.dynamic.aaa", displayName = "Aaa")),
            )

        assertTrue(tools.first().isReady)
        assertFalse(tools.last().isReady)
    }

    @Test
    fun `host actions lead the list and every one is present`() {
        val tools =
            HomeToolCatalog.build(
                tabTypes = emptyList(),
                panels = emptyList(),
                storeCatalogue = emptyList(),
                installedPluginIds = emptySet(),
                access = admin,
            )

        // The state a first-run user is in: no plugins, and the screen still does something.
        assertTrue(tools.isNotEmpty())
        assertTrue(tools.all { it.launch is HomeToolLaunch.HostAction })
    }

    @Test
    fun `every host action has a label and no two tools share an id`() {
        val tools =
            HomeToolCatalog.build(
                tabTypes =
                    listOf(
                        tabType("arcade", "ai.rever.boss.plugin.dynamic.arcade"),
                        tabType("flow", "ai.rever.boss.plugin.dynamic.flowtab"),
                    ),
                panels = emptyList(),
                storeCatalogue = listOf(storeRow("ai.rever.boss.plugin.dynamic.kubernetes")),
                installedPluginIds = emptySet(),
                access = admin,
            )

        assertTrue(tools.none { it.label.isBlank() })
        assertEquals(tools.size, tools.map { it.id }.distinct().size)
    }
}
