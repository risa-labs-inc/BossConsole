package ai.rever.boss.plugin.repository.remote

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.plugin.logging.LogSanitizer
import ai.rever.boss.plugin.repository.PluginInfo
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.logging.SupabaseLoggingProcessor
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Event types for plugin store changes.
 */
sealed class PluginStoreEvent {
    /**
     * A plugin was inserted (new plugin published).
     */
    data class PluginInserted(
        val pluginId: String,
    ) : PluginStoreEvent()

    /**
     * A plugin was updated (new version, metadata change).
     */
    data class PluginUpdated(
        val pluginId: String,
    ) : PluginStoreEvent()

    /**
     * A plugin was deleted.
     */
    data class PluginDeleted(
        val pluginId: String,
    ) : PluginStoreEvent()

    /**
     * A version was published for a plugin.
     */
    data class VersionPublished(
        val pluginId: String,
        val version: String,
    ) : PluginStoreEvent()

    /**
     * Connection status changed.
     */
    data class ConnectionStateChanged(
        val connected: Boolean,
    ) : PluginStoreEvent()
}

/**
 * Service that provides real-time updates for the plugin store.
 *
 * Uses Supabase Realtime to subscribe to changes in the plugins and
 * plugin_versions tables, emitting events when changes occur.
 */
class PluginStoreRealtimeService {
    private val logger = BossLogger.forComponent("PluginStoreRealtime")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _events = MutableSharedFlow<PluginStoreEvent>()
    val events: SharedFlow<PluginStoreEvent> = _events.asSharedFlow()

    private var supabaseClient: io.github.jan.supabase.SupabaseClient? = null
    private var pluginsChannel: RealtimeChannel? = null
    private var versionsChannel: RealtimeChannel? = null

    private var isConnected = false

    /**
     * Callback invoked when the available plugins list should be refreshed.
     * This provides a way to integrate with existing refresh mechanisms.
     */
    var onRefreshRequested: (suspend () -> Unit)? = null

    /**
     * Start the realtime service and subscribe to plugin changes.
     */
    fun start() {
        if (!PluginStoreConfig.isInitialized) {
            logger.warn(LogCategory.NETWORK, "Cannot start realtime: PluginStoreConfig not initialized")
            return
        }

        if (supabaseClient != null) {
            logger.debug(LogCategory.NETWORK, "Realtime service already started")
            return
        }

        try {
            val baseUrl = PluginStoreConfig.supabaseUrl
            val anonKey = PluginStoreConfig.anonKey

            logger.info(
                LogCategory.NETWORK,
                "Starting plugin store realtime",
                mapOf(
                    "baseUrl" to baseUrl,
                ),
            )

            supabaseClient =
                createSupabaseClient(
                    supabaseUrl = baseUrl,
                    supabaseKey = anonKey,
                ) {
                    defaultLoggingFactory = { level -> StoreSupabaseLogging(level) }
                    install(Realtime) {
                        // Match main SupabaseConfig heartbeat settings to prevent
                        // "Heartbeat timeout" crashes in Ktor websocket
                        heartbeatInterval = kotlin.time.Duration.parse("30s")
                        reconnectDelay = kotlin.time.Duration.parse("7s")
                    }
                }

            subscribeToPlugins()
            subscribeToVersions()
        } catch (e: Exception) {
            logger.error(LogCategory.NETWORK, "Failed to start realtime service", error = e)
        }
    }

    private fun subscribeToPlugins() {
        scope.launch {
            var backoffMs = 5_000L
            val maxBackoffMs = 60_000L

            while (isActive) {
                try {
                    val client = supabaseClient ?: return@launch

                    pluginsChannel = client.channel("plugins-changes")
                    val changeFlow =
                        pluginsChannel!!.postgresChangeFlow<PostgresAction>(schema = "public") {
                            table = "plugins"
                        }

                    // Subscribe to the channel
                    pluginsChannel!!.subscribe()

                    isConnected = true
                    backoffMs = 5_000L // Reset backoff on successful connection
                    _events.emit(PluginStoreEvent.ConnectionStateChanged(true))
                    logger.info(LogCategory.NETWORK, "Subscribed to plugins table changes")

                    // Listen for changes
                    changeFlow.collect { action ->
                        when (action) {
                            is PostgresAction.Insert -> {
                                val pluginId = action.record["plugin_id"]?.toString()?.removeSurrounding("\"") ?: ""
                                logger.debug(LogCategory.NETWORK, "Plugin inserted", mapOf("pluginId" to pluginId))
                                _events.emit(PluginStoreEvent.PluginInserted(pluginId))
                                triggerRefresh()
                            }

                            is PostgresAction.Update -> {
                                val pluginId = action.record["plugin_id"]?.toString()?.removeSurrounding("\"") ?: ""
                                logger.debug(LogCategory.NETWORK, "Plugin updated", mapOf("pluginId" to pluginId))
                                _events.emit(PluginStoreEvent.PluginUpdated(pluginId))
                                triggerRefresh()
                            }

                            is PostgresAction.Delete -> {
                                val pluginId = action.oldRecord["plugin_id"]?.toString()?.removeSurrounding("\"") ?: ""
                                logger.debug(LogCategory.NETWORK, "Plugin deleted", mapOf("pluginId" to pluginId))
                                _events.emit(PluginStoreEvent.PluginDeleted(pluginId))
                                triggerRefresh()
                            }

                            else -> {}
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(LogCategory.NETWORK, "Plugins subscription lost, reconnecting in ${backoffMs}ms", error = e)
                    isConnected = false
                    _events.emit(PluginStoreEvent.ConnectionStateChanged(false))
                    // Clean up before retry
                    try {
                        pluginsChannel?.unsubscribe()
                    } catch (_: Exception) {
                    }
                    pluginsChannel = null
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
                }
            }
        }
    }

    private fun subscribeToVersions() {
        scope.launch {
            var backoffMs = 5_000L
            val maxBackoffMs = 60_000L

            while (isActive) {
                try {
                    val client = supabaseClient ?: return@launch

                    versionsChannel = client.channel("versions-changes")
                    val changeFlow =
                        versionsChannel!!.postgresChangeFlow<PostgresAction>(schema = "public") {
                            table = "plugin_versions"
                        }

                    // Subscribe to the channel
                    versionsChannel!!.subscribe()

                    backoffMs = 5_000L // Reset backoff on successful connection
                    logger.info(LogCategory.NETWORK, "Subscribed to plugin_versions table changes")

                    // Listen for changes
                    changeFlow.collect { action ->
                        when (action) {
                            is PostgresAction.Insert -> {
                                val version = action.record["version"]?.toString()?.removeSurrounding("\"") ?: ""
                                logger.debug(LogCategory.NETWORK, "Version published", mapOf("version" to version))
                                _events.emit(PluginStoreEvent.VersionPublished("", version))
                                triggerRefresh()
                            }

                            else -> {}
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(LogCategory.NETWORK, "Versions subscription lost, reconnecting in ${backoffMs}ms", error = e)
                    // Clean up before retry
                    try {
                        versionsChannel?.unsubscribe()
                    } catch (_: Exception) {
                    }
                    versionsChannel = null
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
                }
            }
        }
    }

    private fun triggerRefresh() {
        scope.launch {
            try {
                onRefreshRequested?.invoke()
            } catch (e: Exception) {
                logger.error(LogCategory.NETWORK, "Error triggering refresh", error = e)
            }
        }
    }

    /**
     * Stop the realtime service and disconnect.
     */
    fun stop() {
        scope.launch {
            try {
                pluginsChannel?.unsubscribe()
                versionsChannel?.unsubscribe()
                supabaseClient?.close()
                supabaseClient = null
                pluginsChannel = null
                versionsChannel = null
                isConnected = false
                _events.emit(PluginStoreEvent.ConnectionStateChanged(false))
                logger.info(LogCategory.NETWORK, "Realtime service stopped")
            } catch (e: Exception) {
                logger.error(LogCategory.NETWORK, "Error stopping realtime service", error = e)
            }
        }
    }

    /**
     * Dispose of resources.
     */
    fun dispose() {
        stop()
        scope.cancel()
    }
}

/**
 * Names this client's supabase-kt log lines so they can be told apart from the app's other two.
 *
 * A near-copy of composeApp's NamedSupabaseLogging, and deliberately not shared: that one is
 * commonMain in a module this one does not depend on (composeApp depends on plugin-repository, not
 * the reverse), and this file is desktopMain, so there is no source set both can reach without
 * inventing a module for forty lines.
 *
 * The reason either exists: supabase-kt tags every line `(Supabase-Realtime)` and nothing more, so
 * with three clients in one process a flapping socket cannot be attributed to a feature - the
 * lines interleave, and pairing a "Connected" with the next "Heartbeat timeout" quietly assumes
 * they came from the same client.
 */
private class StoreSupabaseLogging(
    private val minimum: LogLevel,
) : SupabaseLoggingProcessor {
    private val logger = BossLogger.forComponent("Supabase")

    override fun isEnabled(level: LogLevel): Boolean = level.ordinal >= minimum.ordinal

    override fun processLog(
        level: LogLevel,
        tag: String,
        throwable: Throwable?,
        message: String,
    ) {
        if (!isEnabled(level)) return
        // The throwable is NOT handed to the logger. BossLogger writes `error.message` and a
        // stack trace to the log file, and what arrives here is whatever supabase-kt chose to
        // log: a RestException carries the PostgREST error body, which can echo column values.
        // sanitizeSupabaseFailure would not help, as it rewrites only SerializationException.
        // The type is the diagnostic half worth keeping; the library's own text is in `message`.
        //
        // Library message text is sanitized through LogSanitizer so sensitive payloads
        // (such as Supabase JWT access tokens embedded in Realtime websocket frames) are redacted.
        val fields =
            mapOf<String, Any?>(
                "client" to "plugin-store",
                "tag" to tag,
                "errorType" to throwable?.let { it::class.simpleName },
            )
        val sanitized = LogSanitizer.sanitizeLogMessage(message)
        val text = "[plugin-store] $sanitized"
        when (level) {
            LogLevel.ERROR -> logger.error(LogCategory.NETWORK, text, fields)
            LogLevel.WARNING -> logger.warn(LogCategory.NETWORK, text, fields)
            LogLevel.INFO -> logger.info(LogCategory.NETWORK, text, fields)
            LogLevel.DEBUG -> logger.debug(LogCategory.NETWORK, text, fields)
            LogLevel.NONE -> Unit
        }
    }
}
