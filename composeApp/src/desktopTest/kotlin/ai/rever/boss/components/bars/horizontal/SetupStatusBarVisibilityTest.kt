package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.components.plugin.registries.RegistryAccess
import ai.rever.boss.plugin.api.StatusBarItemProvider
import androidx.compose.runtime.Composable
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SetupStatusBarVisibilityTest {
    private fun item(
        id: String = TERMINAL_SETUP_STATUS_ITEM_ID,
        permissions: Set<String> = emptySet(),
    ) = object : StatusBarItemProvider {
        override val itemId = id
        override val requiredPermissions = permissions

        @Composable
        override fun Content() = Unit
    }

    @Test
    fun `setup keeps its home visible until its status item is removed`() {
        val setup = item()
        assertTrue(hasAccessibleSetupStatus(mapOf(setup.itemId to setup), RegistryAccess()))
        assertFalse(hasAccessibleSetupStatus(emptyMap(), RegistryAccess()))
    }

    @Test
    fun `ordinary status widgets do not override hidden bar preferences`() {
        val ordinary = item("another-plugin:status")
        assertFalse(hasAccessibleSetupStatus(mapOf(ordinary.itemId to ordinary), RegistryAccess()))
    }

    @Test
    fun `setup cannot reveal a status widget hidden by access rules`() {
        val setup = item(permissions = setOf("terminal.setup"))
        val items = mapOf(setup.itemId to setup)
        assertFalse(hasAccessibleSetupStatus(items, RegistryAccess()))
        assertTrue(hasAccessibleSetupStatus(items, RegistryAccess(permissions = setOf("terminal.setup"))))
    }
}
