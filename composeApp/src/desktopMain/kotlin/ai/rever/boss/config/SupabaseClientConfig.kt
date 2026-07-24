package ai.rever.boss.config

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory

/**
 * Configuration for Supabase client.
 *
 * The Supabase credentials can be provided through:
 * 1. Environment variable: SUPABASE_URL, SUPABASE_ANON_KEY
 * 2. System property: SUPABASE_URL, SUPABASE_ANON_KEY
 * 3. local.properties file: SUPABASE_URL=..., SUPABASE_ANON_KEY=...
 * 4. Embedded build config (baked in at build time from CI secrets)
 *
 * There are deliberately no credential fallbacks in source: this repo is
 * public. Official builds get their values embedded at build time; forks
 * supply their own backend or run without auth.
 */
object SupabaseClientConfig {
    private val logger = BossLogger.forComponent("SupabaseClientConfig")

    private fun require(key: String): String =
        ConfigLoader.getConfig(key) ?: run {
            logger.error(
                LogCategory.AUTH,
                "$key not configured — set it as an env var or in local.properties. " +
                    "Supabase features will be unavailable.",
            )
            ""
        }

    /**
     * Supabase URL loaded from secure sources.
     */
    val url: String by lazy { require("SUPABASE_URL") }

    /**
     * Supabase anonymous key loaded from secure sources.
     */
    val anonKey: String by lazy { require("SUPABASE_ANON_KEY") }

    /**
     * Supabase Functions base URL loaded from secure sources.
     * This should point to /functions/v1 (not a specific function like /passkey)
     */
    val functionUrl: String by lazy { require("SUPABASE_FUNCTION_URL") }
}
