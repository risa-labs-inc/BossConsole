package ai.rever.boss.services.supabase

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import io.github.jan.supabase.functions.Functions
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Supabase configuration and client management
 */
object SupabaseConfig {
    private val logger = BossLogger.forComponent("SupabaseConfig")

    private var _client: SupabaseClient? = null
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()
    
    /**
     * Initialize the Supabase client with the provided credentials
     * @param url The Supabase project URL
     * @param anonKey The Supabase anonymous key
     */
    fun initialize(url: String, anonKey: String) {
        if (_client != null) {
            logger.debug(LogCategory.NETWORK, "Supabase client already initialized")
            return
        }
        
        try {
            // Ensure URL has https:// prefix
            val fullUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
                url
            } else {
                "https://$url"
            }
            
            _client = createSupabaseClient(
                supabaseUrl = fullUrl,
                supabaseKey = anonKey
            ) {
                // Install modules
                install(Auth) {
                    // Configure redirect URL for email verification
                    scheme = "boss"
                    host = "auth"
                    // Enable persistent session management and auto-refresh for proper session persistence
                    alwaysAutoRefresh = true
                    autoLoadFromStorage = true
                }
                install(Postgrest)
                install(Realtime) {
                    // Increase heartbeat interval to prevent premature timeout disconnects.
                    // Default is 10s with 30s timeout — on slower networks or under load
                    // this can trigger "Heartbeat timeout" followed by websocket channel
                    // close/cancel crashes in Ktor's RawWebSocket.
                    heartbeatInterval = kotlin.time.Duration.parse("30s")
                    reconnectDelay = kotlin.time.Duration.parse("7s")
                }
                install(Storage)
                install(Functions)
                
                // Configure HTTP client with explicit timeouts to prevent
                // "Connect timeout has expired" hangs when connection pool is stale.
                httpEngine = CIO.create {
                    maxConnectionsCount = 64
                    requestTimeout = 30_000 // 30s for full request
                    endpoint.connectTimeout = 15_000        // 15s to establish TCP
                    endpoint.connectAttempts = 2             // Retry once on failure
                    endpoint.keepAliveTime = 30_000          // 30s keep-alive
                    endpoint.socketTimeout = 30_000          // 30s socket idle
                    endpoint.maxConnectionsPerRoute = 20     // Per-host limit
                }
            }
            
            _isInitialized.value = true
            logger.info(LogCategory.NETWORK, "Supabase client initialized successfully")
        } catch (e: Exception) {
            logger.error(LogCategory.NETWORK, "Failed to initialize Supabase client", error = e)
            throw e
        }
    }
    
    /**
     * Initialize from secure configuration sources
     * Priority: Environment variables → System properties → local.properties → fallback
     */
    fun initializeFromEnvironment() {
        val url = getSupabaseUrl()
        val anonKey = getSupabaseAnonKey()

        initialize(url, anonKey)
    }
    
    /**
     * Get the Supabase client instance
     * @throws IllegalStateException if the client is not initialized
     */
    val client: SupabaseClient
        get() = _client ?: throw IllegalStateException("Supabase client not initialized. Call initialize() first.")
    
    /**
     * Get the Auth module
     */
    val auth: Auth
        get() = client.auth

    /**
     * Get the Storage module
     */
    val storage: Storage
        get() = client.storage

    /**
     * Clear the client instance (useful for testing or logout)
     */
    fun clear() {
        _client = null
        _isInitialized.value = false
    }
}

/**
 * Platform-specific Supabase URL configuration
 */
expect fun getSupabaseUrl(): String

/**
 * Platform-specific Supabase anonymous key configuration
 */
expect fun getSupabaseAnonKey(): String

/**
 * Platform-specific Supabase Functions URL configuration
 */
expect fun getSupabaseFunctionUrl(): String
