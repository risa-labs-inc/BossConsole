package ai.rever.boss.components.home

import ai.rever.boss.plugin.StoreHomeCatalogProvider
import ai.rever.boss.plugin.repository.PluginInfo
import ai.rever.boss.plugin.repository.PluginRepository
import ai.rever.boss.plugin.repository.PluginSearchFilter
import ai.rever.boss.plugin.repository.PluginSearchResult
import androidx.compose.foundation.layout.Column
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HomeCatalogRecoveryTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun `failure offers retry in place and an empty success clears the error`() {
        var calls = 0
        val provider =
            provider {
                calls++
                if (calls == 1) error("private transport detail")
                emptyList()
            }
        rule.setContent {
            Column {
                HomeCatalogNotice(rememberHomeCatalog(provider))
                Text("Installed terminal")
            }
        }
        rule.onNodeWithText("Retry").assertIsDisplayed()
        rule.onNodeWithText("Installed terminal").assertIsDisplayed()
        rule.onNodeWithText("private transport detail").assertDoesNotExist()
        rule.onNodeWithText("Retry").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Retry").assertDoesNotExist()
        rule.onNodeWithText("Loading available tools…").assertDoesNotExist()
        assertEquals(2, calls)
    }

    @Test
    fun `late provider starts discovery and replacement ignores stale completion`() {
        val release = CompletableDeferred<Unit>()
        var currentProvider by mutableStateOf<HomeCatalogProvider?>(null)
        val old =
            provider {
                withContext(NonCancellable) { release.await() }
                listOf(row("Old"))
            }
        rule.setContent {
            val catalog = rememberHomeCatalog(currentProvider)
            Column {
                HomeCatalogNotice(catalog)
                catalog.rows.forEach { Text(it.displayName) }
            }
        }
        rule.onNodeWithText("Loading available tools…").assertIsDisplayed()
        rule.runOnIdle { currentProvider = old }
        rule.waitForIdle()
        rule.runOnIdle { currentProvider = provider { listOf(row("New")) } }
        rule.onNodeWithText("New").assertIsDisplayed()
        rule.runOnIdle { release.complete(Unit) }
        rule.waitForIdle()
        rule.onNodeWithText("Old").assertDoesNotExist()
        rule.onNodeWithText("New").assertIsDisplayed()
    }

    @Test
    fun `retry stays single flight while loading`() {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val provider =
            provider {
                calls++
                if (calls == 1) error("offline")
                release.await()
                emptyList()
            }
        lateinit var catalog: HomeCatalogState
        rule.setContent {
            catalog = rememberHomeCatalog(provider)
            HomeCatalogNotice(catalog)
        }
        rule.onNodeWithText("Retry").performClick()
        rule.runOnIdle {
            catalog.retry()
            catalog.retry()
            assertEquals(1, catalog.attempt)
        }
        rule.onNodeWithText("Loading available tools…").assertIsDisplayed()
        rule.runOnIdle { release.complete(Unit) }
        rule.waitForIdle()
        assertEquals(2, calls)
    }

    @Test
    fun `store failure is not cached and successful empty result is cached`() =
        runTest {
            var calls = 0
            val store =
                ListingStore {
                    calls++
                    if (calls == 1) Result.failure(IllegalStateException("offline")) else Result.success(emptyList())
                }
            val provider = StoreHomeCatalogProvider({ store }, { null })
            assertFailsWith<IllegalStateException> { provider.discoverable() }
            assertEquals(emptyList(), provider.discoverable())
            assertEquals(emptyList(), provider.discoverable())
            assertEquals(2, calls)
        }

    @Test
    fun `missing repository can recover without replacing provider`() =
        runTest {
            var store: PluginRepository? = null
            val provider = StoreHomeCatalogProvider({ store }, { null })
            assertFailsWith<IllegalStateException> { provider.discoverable() }
            store = ListingStore { Result.success(emptyList()) }
            assertEquals(emptyList(), provider.discoverable())
        }

    @Test
    fun `thrown and returned cancellation propagate and do not poison cache`() =
        runTest {
            for (throws in listOf(true, false)) {
                var cancelled = true
                val store =
                    ListingStore {
                        if (!cancelled) {
                            Result.success(emptyList())
                        } else if (throws) {
                            throw CancellationException("closed")
                        } else {
                            Result.failure(CancellationException("closed"))
                        }
                    }
                val provider = StoreHomeCatalogProvider({ store }, { null })
                assertFailsWith<CancellationException> { provider.discoverable() }
                cancelled = false
                assertEquals(emptyList(), provider.discoverable())
            }
        }

    @Test
    fun `cancelled store request cannot replace a newer cached success`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val store =
                ListingStore {
                    calls++
                    if (calls == 1) {
                        started.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        Result.success(listOf(PluginInfo(pluginId = "old", displayName = "Old", version = "1")))
                    } else {
                        Result.success(emptyList())
                    }
                }
            val provider = StoreHomeCatalogProvider({ store }, { null })
            val old = launch { provider.discoverable() }
            started.await()
            old.cancel()
            assertEquals(emptyList(), provider.discoverable())
            release.complete(Unit)
            old.join()
            assertEquals(emptyList(), provider.discoverable())
            assertEquals(2, calls)
        }

    private fun provider(load: suspend () -> List<HomeStorePluginInput>) =
        object : HomeCatalogProvider {
            override suspend fun discoverable() = load()

            override suspend fun install(pluginId: String) = error("No install expected")
        }

    private fun row(name: String) = HomeStorePluginInput(name, name, "", false, true, false)

    private class ListingStore(
        val list: suspend () -> Result<List<PluginInfo>>,
    ) : PluginRepository {
        override val id = "test"
        override val name = "Test"
        override val isLocal = false
        override val isAvailable = true

        override suspend fun listPlugins() = list()

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
