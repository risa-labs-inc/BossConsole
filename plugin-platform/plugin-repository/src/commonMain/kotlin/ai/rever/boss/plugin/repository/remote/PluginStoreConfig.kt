package ai.rever.boss.plugin.repository.remote

/**
 * Configuration for the remote plugin store.
 *
 * This should be initialized by the main application with Supabase credentials
 * before using RemotePluginRepository.
 */
object PluginStoreConfig {
    private var _functionUrl: String? = null
    private var _anonKey: String? = null
    private var _accessToken: String? = null
    private var _isAdmin: Boolean = false

    /**
     * Supabase Functions base URL (e.g., "https://api.risaboss.com/functions/v1")
     */
    val functionUrl: String
        get() =
            _functionUrl
                ?: throw IllegalStateException("PluginStoreConfig not initialized. Call initialize() first.")

    /**
     * Supabase anonymous key for API access
     */
    val anonKey: String
        get() =
            _anonKey
                ?: throw IllegalStateException("PluginStoreConfig not initialized. Call initialize() first.")

    /**
     * Optional JWT access token for authenticated requests (ratings, publishing)
     */
    var accessToken: String?
        get() = _accessToken
        set(value) {
            _accessToken = value
            _isAdmin = decodeIsAdmin(value)
        }

    /**
     * Whether the current user has admin privileges (decoded from JWT)
     */
    val isAdmin: Boolean
        get() = _isAdmin

    /**
     * Plugin store endpoint URL (functionUrl + /plugin-store)
     */
    val pluginStoreUrl: String
        get() = "$functionUrl/plugin-store"

    /**
     * Supabase base URL for Realtime (derived from functionUrl)
     * e.g., "https://api.risaboss.com/functions/v1" -> "https://api.risaboss.com"
     */
    val supabaseUrl: String
        get() {
            val url = functionUrl
            // Remove /functions/v1 suffix to get base URL
            return url
                .replace("/functions/v1", "")
                .replace("/functions", "")
        }

    /**
     * Whether the configuration has been initialized
     */
    val isInitialized: Boolean
        get() = _functionUrl != null && _anonKey != null

    /**
     * Initialize the plugin store configuration.
     *
     * @param functionUrl Supabase Functions base URL
     * @param anonKey Supabase anonymous key
     * @param accessToken Optional JWT access token for authenticated requests
     */
    fun initialize(
        functionUrl: String,
        anonKey: String,
        accessToken: String? = null,
    ) {
        _functionUrl = functionUrl.removeSuffix("/")
        _anonKey = anonKey
        _accessToken = accessToken
        _isAdmin = decodeIsAdmin(accessToken)
    }

    /**
     * Clear the configuration (for testing or logout)
     */
    fun clear() {
        _functionUrl = null
        _anonKey = null
        _accessToken = null
        _isAdmin = false
    }

    /**
     * Decode admin status from JWT token.
     * Returns true if user has is_admin:true or has admin/store_admin role.
     */
    private fun decodeIsAdmin(token: String?): Boolean {
        if (token == null) return false
        return try {
            val parts = token.split(".")
            if (parts.size != 3) return false
            // Base64 decode the payload (middle part)
            val payload =
                parts[1]
                    .replace("-", "+")
                    .replace("_", "/")
            // Add padding if needed
            val paddedPayload =
                when (payload.length % 4) {
                    2 -> payload + "=="
                    3 -> payload + "="
                    else -> payload
                }
            val decodedBytes =
                java.util.Base64
                    .getDecoder()
                    .decode(paddedPayload)
            val payloadJson = String(decodedBytes, Charsets.UTF_8)
            // Check for is_admin:true
            if (payloadJson.contains("\"is_admin\":true") || payloadJson.contains("\"is_admin\": true")) {
                return true
            }
            // Check for admin or store_admin role in app_metadata or roles
            payloadJson.contains("\"admin\"") || payloadJson.contains("\"store_admin\"")
        } catch (_: Exception) {
            false
        }
    }
}
