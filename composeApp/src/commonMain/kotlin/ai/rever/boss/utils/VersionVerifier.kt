package ai.rever.boss.utils

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlin.jvm.JvmStatic

/**
 * Runtime version verification to detect mismatches between version.properties
 * and VersionConstants.kt (which is auto-generated at build time).
 *
 * This addresses Issue #111 where stale VersionConstants caused wrong versions
 * to be embedded in release artifacts.
 *
 * Usage: Call verifyVersionConsistency() early in application startup
 */
object VersionVerifier {
    private val logger = BossLogger.forComponent("VersionVerifier")

    /**
     * Verify that the runtime version matches expected version from properties.
     *
     * This is a safety check to detect if VersionConstants.kt was not regenerated
     * before the build, which was the root cause of Issue #111.
     *
     * Logs a warning if mismatch is detected. Does not throw exception to avoid
     * breaking app startup, but logs clearly for debugging.
     */
    @JvmStatic
    fun verifyVersionConsistency() {
        try {
            // Get runtime version from VersionConstants
            val runtimeVersion = AppVersion.CURRENT

            // Try to load version from properties file if available
            // Note: In production builds, version.properties may not be embedded
            val propsVersion = loadVersionFromProperties()

            if (propsVersion != null) {
                if (runtimeVersion != propsVersion) {
                    // VERSION MISMATCH DETECTED - This is the Issue #111 scenario!
                    logger.error(LogCategory.SYSTEM, "VERSION MISMATCH DETECTED - VersionConstants.kt not regenerated", mapOf(
                        "expectedVersion" to propsVersion.toString(),
                        "actualVersion" to runtimeVersion.toString(),
                        "fix" to "Run ./gradlew generateVersionConstants or ./gradlew clean build"
                    ))

                    // TODO: Consider adding analytics/crash reporting here
                    // to track how often this occurs in production
                } else {
                    logger.debug(LogCategory.SYSTEM, "Version verification passed", mapOf("version" to runtimeVersion.toString()))
                }
            } else {
                // Production build without embedded version.properties - this is normal
                logger.debug(LogCategory.SYSTEM, "Version verification skipped (production build)", mapOf("version" to runtimeVersion.toString()))
            }
        } catch (e: Exception) {
            // Don't crash the app if verification fails
            logger.warn(LogCategory.SYSTEM, "Version verification failed", error = e)
        }
    }

    /**
     * Attempt to load version from embedded version.properties file.
     * Returns null if file is not available (normal for production builds).
     */
    private fun loadVersionFromProperties(): Version? {
        return try {
            // Try to load version.properties from resources
            // This may not be available in production builds
            val propsContent = object {}.javaClass.getResourceAsStream("/version.properties")
                ?.bufferedReader()
                ?.use { it.readText() }

            if (propsContent != null) {
                val lines = propsContent.lines()
                val major = lines.find { it.startsWith("app.version.major=") }
                    ?.substringAfter("=")?.toIntOrNull()
                val minor = lines.find { it.startsWith("app.version.minor=") }
                    ?.substringAfter("=")?.toIntOrNull()
                val patch = lines.find { it.startsWith("app.version.patch=") }
                    ?.substringAfter("=")?.toIntOrNull()

                if (major != null && minor != null && patch != null) {
                    Version(major, minor, patch)
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            // version.properties not available - normal for production
            logger.debug(
                LogCategory.SYSTEM,
                "version.properties not readable - expected in packaged builds",
                mapOf("error" to e.toString()),
            )
            null
        }
    }

    /**
     * Get current runtime version for display purposes.
     */
    @JvmStatic
    fun getCurrentVersion(): Version = AppVersion.CURRENT

    /**
     * Get current version as string (e.g., "8.12.19").
     */
    @JvmStatic
    fun getCurrentVersionString(): String = AppVersion.CURRENT.toString()
}
