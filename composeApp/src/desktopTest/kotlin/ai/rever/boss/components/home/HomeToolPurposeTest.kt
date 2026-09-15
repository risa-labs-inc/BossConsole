package ai.rever.boss.components.home

import ai.rever.boss.components.plugin.registries.RegistryAccess
import ai.rever.boss.plugin.StoreHomeCatalogProvider
import ai.rever.boss.plugin.repository.PluginInfo
import ai.rever.boss.plugin.repository.PluginRepository
import ai.rever.boss.plugin.repository.PluginSearchFilter
import ai.rever.boss.plugin.repository.PluginSearchResult
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class HomeToolPurposeTest {
    @get:Rule val rule = createComposeRule()
    private val purpose = "Write and organize notes for your project."

    @Test fun storePurposeReachesEligibleTileVerbatimWithoutChangingInstallIdentity() =
        runTest {
            var listings = 0
            val row = PluginInfo(pluginId = "notes", displayName = "Notes", version = "1", description = purpose)
            val provider =
                StoreHomeCatalogProvider({
                    ListingStore {
                        listings++
                        listOf(row)
                    }
                }, { null })
            val input = provider.discoverable().single()
            val tile = catalog(input).single()
            assertEquals(purpose, tile.description)
            assertEquals(HomeToolLaunch.Install("notes"), tile.launch)
            assertEquals(1, listings)
            assertEquals(emptyList(), catalog(input.copy(requiresAdmin = true)))
            assertEquals(emptyList(), catalog(input.copy(isCompatible = false)))
        }

    @Test fun keyboardFocusExplainsPurposeAndEnterStillActivatesExactlyOnce() {
        var clicks = 0
        rule.setContent {
            CompositionLocalProvider(
                LocalWindowInfo provides
                    object : WindowInfo {
                        override val isWindowFocused = true
                    },
            ) {
                Row(Modifier.width(300.dp)) {
                    HomeToolCard(
                        tool(),
                        HomeToolState.INSTALLABLE,
                        null,
                        { clicks++ },
                        Modifier.weight(1f).testTag("tool"),
                    )
                    HomeToolCard(
                        tool().copy(id = "other", label = "Other", description = ""),
                        HomeToolState.READY,
                        null,
                        {},
                        Modifier.weight(1f).testTag("other"),
                    )
                }
            }
        }
        rule.onNodeWithText(purpose).assertDoesNotExist()
        rule.onNodeWithText("Notes").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithText(purpose).assertIsDisplayed()
        rule.onNodeWithContentDescription("Notes. $purpose").assertExists()
        assertEquals(0, clicks)
        rule.onNodeWithText("Notes").performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, clicks)
        rule.onNodeWithText("Notes").performKeyInput { pressKey(Key.Tab) }
        rule.onNodeWithText("Other").assertIsFocused()
        rule.onNodeWithText(purpose).assertDoesNotExist()
    }

    @Test fun hoverExplainsWithoutInstallingAndBlankReplacementRemovesPurpose() {
        val description = mutableStateOf(purpose)
        var clicks = 0
        rule.setContent {
            HomeToolCard(
                tool().copy(description = description.value),
                HomeToolState.INSTALLABLE,
                null,
                { clicks++ },
                Modifier.width(150.dp).testTag("tool"),
            )
        }
        rule.onNodeWithTag("tool").performMouseInput { enter(center) }
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithText(purpose).assertIsDisplayed()
        assertEquals(0, clicks)
        rule.runOnIdle { description.value = "  " }
        rule.onNodeWithText(purpose).assertDoesNotExist()
        rule.onNodeWithContentDescription("Notes. $purpose").assertDoesNotExist()
    }

    private fun tool() =
        HomeTool(
            "notes",
            "Notes",
            HomeToolIcon.FromStore("", "N"),
            HomeToolLaunch.Install("notes"),
            "notes",
            purpose,
        )

    private fun catalog(row: HomeStorePluginInput) =
        HomeToolCatalog
            .build(
                emptyList(),
                emptyList(),
                listOf(row),
                emptySet(),
                RegistryAccess(),
            ).filterNot { it.launch is HomeToolLaunch.HostAction }

    private class ListingStore(
        val list: () -> List<PluginInfo>,
    ) : PluginRepository {
        override val id = "test"
        override val name = "Test"
        override val isLocal = false
        override val isAvailable = true

        override suspend fun listPlugins() = Result.success(list())

        override suspend fun searchPlugins(filter: PluginSearchFilter): Result<PluginSearchResult> = error("unused")

        override suspend fun getPlugin(pluginId: String): Result<PluginInfo?> = error("unused")

        override suspend fun getPluginVersions(pluginId: String): Result<List<PluginInfo>> = error("unused")

        override suspend fun downloadPlugin(
            pluginId: String,
            version: String?,
            targetPath: String,
            onProgress: ((Float) -> Unit)?,
        ): Result<String> = error("unused")

        override fun getDownloadProgress(pluginId: String): Flow<Float>? = null

        override suspend fun refresh(): Result<Unit> = error("unused")
    }
}
