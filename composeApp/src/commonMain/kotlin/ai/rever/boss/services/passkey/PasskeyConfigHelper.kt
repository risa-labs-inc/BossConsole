package ai.rever.boss.services.passkey

import ai.rever.boss.services.supabase.getSupabaseFunctionUrl
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory

/**
 * Helper object to extract RP ID from Supabase Function URL
 *
 * The RP ID (Relying Party ID) must match the domain where the browser accesses the WebAuthn page.
 * For production: api.risaboss.com
 * For local development: 127.0.0.1
 *
 * IMPORTANT: We extract from the Function base URL (e.g., /functions/v1)
 * - Function URL is where the browser will access the WebAuthn page
 * - Base Supabase URL might point to internal services (like kong gateway)
 */
object PasskeyConfigHelper {
    private val logger = BossLogger.forComponent("PasskeyConfigHelper")

    /**
     * Extract RP ID from Supabase Function base URL
     * Examples:
     * - https://api.risaboss.com/functions/v1 -> api.risaboss.com
     * - http://127.0.0.1:54321/functions/v1 -> localhost (WebAuthn requires localhost for local dev)
     * - http://localhost:54321/functions/v1 -> localhost
     */
    fun getRpId(): String {
        val functionBaseUrl = getSupabaseFunctionUrl()

        return try {
            // Remove protocol
            val withoutProtocol =
                functionBaseUrl
                    .removePrefix("https://")
                    .removePrefix("http://")

            // Remove port if present
            val host = withoutProtocol.split(":").first()

            // Remove path if present
            val rpId = host.split("/").first()

            // WebAuthn requires "localhost" instead of "127.0.0.1" for local development
            if (rpId == "127.0.0.1") "localhost" else rpId
        } catch (e: Exception) {
            logger.warn(
                LogCategory.PASSKEY,
                "Failed to derive RP ID from function URL - falling back to production",
                error = e,
            )
            "api.risaboss.com" // Fallback to production
        }
    }

    /**
     * Get display name for the relying party
     */
    fun getRpName(): String = "BOSS"
}
