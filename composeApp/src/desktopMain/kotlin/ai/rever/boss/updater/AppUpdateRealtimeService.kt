package ai.rever.boss.updater

import ai.rever.boss.config.UpdateSourceConfig
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Pushes desktop-app release notifications via Supabase Realtime so the app learns
 * about new versions instantly instead of polling. Subscribes to `postgres_changes`
 * on the `app_releases` table (filtered client-side by app id) and invokes
 * [onReleaseChanged] on each INSERT/UPDATE and on every (re)connect (catch-up for
 * releases published while the app was closed or disconnected).
 *
 * Modeled on PluginStoreRealtimeService: a dedicated, isolated Realtime client with
 * an exponential-backoff reconnect loop. Disabled when the primary update source is
 * forced to GitHub.
 */
class AppUpdateRealtimeService(
    private val supabaseUrl: String = UpdateSourceConfig.supabaseUrl,
    private val anonKey: String = UpdateSourceConfig.supabaseAnonKey,
    private val appId: String = UpdateSourceConfig.appId,
) {
    private val logger = BossLogger.forComponent("AppUpdateRealtime")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Touched from start()/stop() and the subscribe + triggerCheck coroutines.
    @Volatile private var client: SupabaseClient? = null

    @Volatile private var channel: RealtimeChannel? = null

    companion object {
        val instance: AppUpdateRealtimeService by lazy { AppUpdateRealtimeService() }
    }

    /**
     * Invoked when a release row for [appId] changes, and once on every successful
     * (re)connect. Wire this to UpdateManager.checkForUpdates().
     */
    var onReleaseChanged: (suspend () -> Unit)? = null

    fun start() {
        if (UpdateSourceConfig.primarySource == "github") {
            logger.info(LogCategory.NETWORK, "App update realtime disabled (primary source = github)")
            return
        }
        if (client != null) {
            logger.debug(LogCategory.NETWORK, "App update realtime already started")
            return
        }
        try {
            val fullUrl =
                if (supabaseUrl.startsWith("http://") || supabaseUrl.startsWith("https://")) {
                    supabaseUrl
                } else {
                    "https://$supabaseUrl"
                }
            logger.info(LogCategory.NETWORK, "Starting app update realtime", mapOf("baseUrl" to fullUrl))

            client =
                createSupabaseClient(supabaseUrl = fullUrl, supabaseKey = anonKey) {
                    install(Realtime) {
                        // Match SupabaseConfig heartbeat settings to avoid "Heartbeat
                        // timeout" websocket crashes in Ktor.
                        heartbeatInterval = 30.seconds
                        reconnectDelay = 7.seconds
                    }
                }
            subscribe()
        } catch (e: Exception) {
            logger.error(LogCategory.NETWORK, "Failed to start app update realtime", error = e)
        }
    }

    private fun subscribe() {
        scope.launch {
            var backoffMs = 5_000L
            val maxBackoffMs = 60_000L

            while (isActive) {
                try {
                    val c = client ?: return@launch

                    val ch = c.channel("app-releases-changes")
                    channel = ch
                    val changeFlow =
                        ch.postgresChangeFlow<PostgresAction>(schema = "public") {
                            table = "app_releases"
                        }

                    ch.subscribe()
                    backoffMs = 5_000L // Reset backoff on successful connection
                    logger.info(LogCategory.NETWORK, "Subscribed to app_releases changes")

                    // Catch-up: pick up releases published while the app was closed
                    // or this connection was down.
                    triggerCheck()

                    changeFlow.collect { action ->
                        when (action) {
                            is PostgresAction.Insert -> {
                                handleRecord(action.record["app"], action.record["version"])
                            }

                            is PostgresAction.Update -> {
                                handleRecord(action.record["app"], action.record["version"])
                            }

                            else -> {}
                        }
                    }
                    // collect returned without throwing -> channel closed normally.
                    logger.info(LogCategory.NETWORK, "app_releases subscription closed; will resubscribe")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(LogCategory.NETWORK, "app_releases subscription lost", error = e)
                }
                // Common path for normal close OR error: unsubscribe the old channel
                // (avoid leaking it) and back off before reconnecting so a flow that
                // keeps completing immediately can't busy-spin / hammer checkForUpdates.
                try {
                    channel?.unsubscribe()
                } catch (_: Exception) {
                }
                channel = null
                if (!isActive) break
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
            }
        }
    }

    private fun handleRecord(
        appElement: Any?,
        versionElement: Any?,
    ) {
        // Shared table: ignore rows for other apps.
        val app = appElement?.toString()?.removeSurrounding("\"")
        if (app != null && app != appId) return
        val version = versionElement?.toString()?.removeSurrounding("\"") ?: ""
        logger.info(LogCategory.NETWORK, "Release change received", mapOf("version" to version))
        triggerCheck()
    }

    private fun triggerCheck() {
        scope.launch {
            try {
                onReleaseChanged?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(LogCategory.NETWORK, "Error running update check from realtime event", error = e)
            }
        }
    }

    fun stop() {
        scope.launch {
            try {
                channel?.unsubscribe()
                client?.close()
            } catch (e: Exception) {
                logger.error(LogCategory.NETWORK, "Error stopping app update realtime", error = e)
            } finally {
                channel = null
                client = null
            }
        }
    }

    fun dispose() {
        stop()
        scope.cancel()
    }
}
