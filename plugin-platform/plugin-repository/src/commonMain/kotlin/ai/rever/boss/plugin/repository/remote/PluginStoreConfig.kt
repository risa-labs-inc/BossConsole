package ai.rever.boss.plugin.repository.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.CharacterCodingException
import java.util.Base64

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
            // Decode before publishing: a decoder failure must never leave a new
            // token paired with the previous admin flag.
            val admin = decodeIsAdmin(value)
            _accessToken = value
            _isAdmin = admin
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
        // Same ordering as the setter: never publish a token with a stale flag.
        val admin = decodeIsAdmin(accessToken)
        _accessToken = accessToken
        _isAdmin = admin
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
     *
     * The contract is deliberately narrow: this answers true only for a top-level
     * `is_admin` JSON boolean `true`. A missing claim, a JSON string, a nested claim
     * (including user-writable `user_metadata`), and legacy `admin` / `store_admin`
     * role entries answer false. The payload is never substring-matched:
     * user-controlled metadata can contain the word "admin".
     *
     * Fail-closed in layers: a malformed envelope (not three nonempty segments),
     * malformed base64url, malformed UTF-8, a root value that is not a JSON object,
     * a non-boolean `is_admin` claim, and payloads breaching the size or nesting
     * depth bounds answer false. Beyond those bounds, this is an
     * authenticated-token payload *decoder*, not a full JSON or full RFC validator:
     * a malformed unrelated sibling value the underlying parser happens to tolerate
     * does not affect the claim read, and no such tolerance can produce `true`
     * unless the top-level claim itself is the JSON boolean `true`.
     *
     * As with RoleService in the host app, this does NOT verify the JWT signature:
     * the flag is client-side UI convenience only, and every real authorization
     * decision is made server-side. The payload is never logged.
     */
    private fun decodeIsAdmin(token: String?): Boolean {
        if (token == null) return false
        return try {
            val segment = envelopePayloadSegment(token) ?: return false
            val payloadBytes = decodePayloadBytes(segment) ?: return false
            val payloadText = decodePayloadText(payloadBytes) ?: return false
            if (!payloadWithinBounds(payloadText)) return false
            val claim = Json.parseToJsonElement(payloadText).jsonObject["is_admin"]?.jsonPrimitive
            claim != null && !claim.isString && claim.content == "true"
        } catch (_: Exception) {
            false
        }
    }

    /** The middle segment of a three-nonempty-segment token, or null. */
    private fun envelopePayloadSegment(token: String): String? {
        val parts = token.split(".")
        val valid = parts.size == 3 && parts.all { it.isNotEmpty() } && parts[1].length <= MAX_SEGMENT_CHARS
        return if (valid) parts[1] else null
    }

    /**
     * Strict base64url decode with padding tolerance (JWT producers commonly omit
     * it); lengths that can never be valid base64 answer null.
     */
    private fun decodePayloadBytes(segment: String): ByteArray? {
        val padded =
            when (segment.length % 4) {
                0 -> segment
                2 -> "$segment=="
                3 -> "$segment="
                else -> return null
            }
        return try {
            Base64.getUrlDecoder().decode(padded)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Decode bytes as UTF-8, refusing malformed input instead of substituting. */
    private fun decodePayloadText(bytes: ByteArray): String? =
        try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            null
        }

    /**
     * Hard caps so adversarial input cannot reach the parser unbounded. Real
     * Supabase access-token payloads are on the order of a kilobyte; anything near
     * these limits is not a token this decoder will read claims out of.
     */
    private const val MAX_SEGMENT_CHARS = 32_768
    private const val MAX_PAYLOAD_CHARS = 16_384
    private const val MAX_NESTING_DEPTH = 32

    /**
     * Lexical pre-scan: rejects oversized payloads and nesting deeper than
     * [MAX_NESTING_DEPTH] before kotlinx parsing, so hostile input cannot throw
     * StackOverflowError. Strings and escapes are understood, so braces inside
     * string values do not count toward depth. Fails closed without logging.
     */
    private fun payloadWithinBounds(text: String): Boolean {
        if (text.length > MAX_PAYLOAD_CHARS) return false
        val scanner = NestingDepthScanner()
        var withinBounds = true
        for (c in text) {
            withinBounds = scanner.feed(c)
            if (!withinBounds) break
        }
        return withinBounds
    }

    /** Tracks JSON nesting depth across one character at a time. */
    private class NestingDepthScanner {
        private var depth = 0
        private var inString = false
        private var escaped = false

        /** Feeds one char; false once nesting exceeds [MAX_NESTING_DEPTH]. */
        fun feed(c: Char): Boolean {
            if (escaped) {
                escaped = false
            } else if (inString) {
                if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> depth++
                    '}', ']' -> depth--
                }
            }
            return depth <= MAX_NESTING_DEPTH
        }
    }
}
