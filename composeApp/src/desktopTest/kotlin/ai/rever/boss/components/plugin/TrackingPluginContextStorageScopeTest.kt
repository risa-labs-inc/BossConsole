package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginStorageFactory
import ai.rever.boss.plugin.api.PluginStorageProvider
import ai.rever.boss.plugin.api.TabRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A raw [PluginStorageFactory] takes an arbitrary `pluginId` per call and the desktop
 * implementation persists purely off that string
 * (`~/.boss/plugin-data/{pluginId}/storage.properties`) with no check that it names the
 * calling plugin. [TrackingPluginContext] is the only [PluginContext] a plugin's own code
 * ever holds (see DynamicPluginManager: every registration path wraps
 * `createSandboxedContext` in a `TrackingPluginContext` before calling `register()`), so
 * before this fix a plugin could reach another plugin's storage by simply passing its id:
 * `context.pluginStorageFactory?.createStorage("victim-plugin")`.
 *
 * These tests exercise [TrackingPluginContext.pluginStorageFactory] directly against a
 * fake delegate factory, proving the id it actually asks the delegate for is always the
 * context's own `pluginId`, never the caller-supplied one.
 */
class TrackingPluginContextStorageScopeTest {
    /** Records which id it was actually asked to create storage for. */
    private class FakeStorageFactory : PluginStorageFactory {
        val requestedIds = mutableListOf<String>()

        override fun createStorage(pluginId: String): PluginStorageProvider {
            requestedIds += pluginId
            return FakeStorageProvider(pluginId)
        }
    }

    /** Minimal in-memory provider - only identity matters for these tests. */
    private class FakeStorageProvider(
        private val id: String,
    ) : PluginStorageProvider {
        private val changes = MutableSharedFlow<String>(extraBufferCapacity = 1)
        private val values = mutableMapOf<String, String>()

        override fun getPluginId(): String = id

        override suspend fun putString(
            key: String,
            value: String,
        ) {
            values[key] = value
        }

        override suspend fun getString(
            key: String,
            defaultValue: String?,
        ): String? = values[key] ?: defaultValue

        override suspend fun putInt(
            key: String,
            value: Int,
        ) = putString(key, value.toString())

        override suspend fun getInt(
            key: String,
            defaultValue: Int,
        ): Int = getString(key)?.toIntOrNull() ?: defaultValue

        override suspend fun putLong(
            key: String,
            value: Long,
        ) = putString(key, value.toString())

        override suspend fun getLong(
            key: String,
            defaultValue: Long,
        ): Long = getString(key)?.toLongOrNull() ?: defaultValue

        override suspend fun putBoolean(
            key: String,
            value: Boolean,
        ) = putString(key, value.toString())

        override suspend fun getBoolean(
            key: String,
            defaultValue: Boolean,
        ): Boolean = getString(key)?.toBooleanStrictOrNull() ?: defaultValue

        override suspend fun putFloat(
            key: String,
            value: Float,
        ) = putString(key, value.toString())

        override suspend fun getFloat(
            key: String,
            defaultValue: Float,
        ): Float = getString(key)?.toFloatOrNull() ?: defaultValue

        override suspend fun putJson(
            key: String,
            jsonValue: String,
        ) = putString("json:$key", jsonValue)

        override suspend fun getJson(key: String): String? = getString("json:$key")

        override suspend fun contains(key: String): Boolean = values.containsKey(key)

        override suspend fun remove(key: String) {
            values.remove(key)
        }

        override suspend fun getAllKeys(): Set<String> = values.keys.toSet()

        override suspend fun clear() = values.clear()

        override fun observeString(key: String): Flow<String?> = throw NotImplementedError("unused in these tests")

        override fun observeChanges(): Flow<String> = changes
    }

    /** Fake delegate exposing a shared [FakeStorageFactory], like DefaultPlugin does. */
    private class RecordingContext(
        override val pluginStorageFactory: PluginStorageFactory?,
    ) : PluginContext {
        override val panelRegistry = PanelRegistry()
        override val tabRegistry = TabRegistry()
        override val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @Test
    fun `createStorage always uses the context's own plugin id, ignoring the caller's argument`() {
        val factory = FakeStorageFactory()
        val delegate = RecordingContext(factory)
        val tracker = PluginRegistrationTracker()
        val pluginA = TrackingPluginContext("plugin.a", delegate, tracker)

        pluginA.pluginStorageFactory?.createStorage("plugin.b")

        assertEquals(
            listOf("plugin.a"),
            factory.requestedIds,
            "the delegate must only ever be asked for this context's own plugin id",
        )
    }

    @Test
    fun `a write through one plugin's storage never lands in another plugin's storage`() {
        val factory = FakeStorageFactory()
        val delegate = RecordingContext(factory)
        val tracker = PluginRegistrationTracker()
        val pluginA = TrackingPluginContext("plugin.a", delegate, tracker)
        val pluginB = TrackingPluginContext("plugin.b", delegate, tracker)

        // Plugin A attempts to reach plugin B's storage by naming it directly.
        val storage = pluginA.pluginStorageFactory?.createStorage("plugin.b")
        kotlinx.coroutines.runBlocking { storage?.putString("stolen", "value") }

        // The underlying factory was still only ever asked for plugin.a - never plugin.b.
        assertTrue("plugin.b" !in factory.requestedIds, "plugin A must not be able to name plugin B's storage")
        assertEquals(listOf("plugin.a"), factory.requestedIds)

        // Plugin B's own storage (via its own context) is untouched.
        val pluginBStorage = pluginB.pluginStorageFactory?.createStorage("plugin.b")
        val stolenValue = kotlinx.coroutines.runBlocking { pluginBStorage?.getString("stolen") }
        assertEquals(null, stolenValue, "plugin B's real storage must not contain plugin A's write")
        assertFalse(kotlinx.coroutines.runBlocking { pluginBStorage?.contains("stolen") } ?: false)
    }

    @Test
    fun `null delegate factory stays null, not a scoped wrapper around nothing`() {
        val delegate = RecordingContext(pluginStorageFactory = null)
        val tracker = PluginRegistrationTracker()
        val pluginA = TrackingPluginContext("plugin.a", delegate, tracker)

        assertEquals(null, pluginA.pluginStorageFactory)
    }
}
