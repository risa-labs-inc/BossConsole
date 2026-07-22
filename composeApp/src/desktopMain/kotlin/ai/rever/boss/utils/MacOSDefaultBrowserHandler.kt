package ai.rever.boss.utils

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempFile

private val logger = BossLogger.forComponent("MacOSDefaultBrowserHandler")

/**
 * macOS-specific handler for default browser functionality
 *
 * Uses macOS APIs and system commands to:
 * - Check if BOSS is the default browser
 * - Set BOSS as the default browser
 */
object MacOSDefaultBrowserHandler {
    private const val PROCESS_TIMEOUT_SECONDS = 30L

    /**
     * Check if BOSS is currently the default browser on macOS
     *
     * Uses a single Swift script with LSCopyDefaultHandlerForURLScheme to query
     * both http and https handlers, avoiding double Swift JIT compilation overhead.
     */
    suspend fun isDefaultBrowser(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val handlers = getDefaultHandlers()
            val httpDefault = handlers["http"]
            val httpsDefault = handlers["https"]

            logger.debug(LogCategory.BROWSER, "macOS default browser check", mapOf("httpHandler" to (httpDefault ?: "none"), "httpsHandler" to (httpsDefault ?: "none")))

            // BOSS is default if both schemes point to our bundle ID
            val isDefault = BOSS_MACOS_BUNDLE_ID.equals(httpDefault, ignoreCase = true) &&
                BOSS_MACOS_BUNDLE_ID.equals(httpsDefault, ignoreCase = true)

            Result.success(isDefault)
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Error checking default browser on macOS", error = e)
            Result.failure(e)
        }
    }

    /**
     * Set BOSS as the default browser on macOS
     *
     * Uses LSSetDefaultHandlerForURLScheme via Swift script or system commands
     */
    suspend fun setAsDefaultBrowser(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // First check if already default
            val checkResult = isDefaultBrowser()
            if (checkResult.isSuccess && checkResult.getOrNull() == true) {
                logger.info(LogCategory.BROWSER, "BOSS is already the default browser")
                return@withContext Result.success(true)
            }

            // Try to set as default using Swift script
            val setHttpResult = setDefaultHandlerForScheme("http")
            val setHttpsResult = setDefaultHandlerForScheme("https")

            if (setHttpResult && setHttpsResult) {
                logger.info(LogCategory.BROWSER, "Successfully set BOSS as default browser on macOS")
                Result.success(true)
            } else {
                // If Swift approach fails, open System Preferences
                logger.warn(LogCategory.BROWSER, "Could not set default programmatically, opening System Preferences")
                openSystemPreferences()
                Result.success(false)
            }
        } catch (e: Exception) {
            logger.error(LogCategory.BROWSER, "Error setting default browser on macOS", error = e)
            Result.failure(e)
        }
    }

    /**
     * Get default handlers for both http and https schemes in a single Swift invocation.
     *
     * Returns a map of scheme -> bundle ID. Uses one Swift process to avoid
     * double JIT compilation overhead.
     */
    private fun getDefaultHandlers(): Map<String, String> {
        val scriptFile = createTempFile("get_default_browser", ".swift").toFile()
        try {
            val swiftScript = """
                import Foundation
                import ApplicationServices

                for scheme in ["http", "https"] {
                    if let handler = LSCopyDefaultHandlerForURLScheme(scheme as CFString) {
                        print("\(scheme)=\(handler.takeRetainedValue() as String)")
                    }
                }
            """.trimIndent()

            scriptFile.writeText(swiftScript)

            val process = ProcessBuilder("swift", scriptFile.absolutePath)
                .redirectErrorStream(true)
                .start()

            // Read output on background thread so waitFor timeout can fire if Swift hangs
            val outputFuture = CompletableFuture.supplyAsync {
                BufferedReader(InputStreamReader(process.inputStream)).use { it.readText().trim() }
            }

            val finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                logger.warn(LogCategory.BROWSER, "Swift process timed out getting default handlers")
                return emptyMap()
            }

            if (process.exitValue() != 0) return emptyMap()

            val output = outputFuture.get(1, TimeUnit.SECONDS)

            // Parse "http=com.apple.Safari\nhttps=com.apple.Safari" format
            return output.lines()
                .mapNotNull { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) parts[0] to parts[1] else null
                }
                .toMap()
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Error getting default handlers", error = e)
            return emptyMap()
        } finally {
            scriptFile.delete()
        }
    }

    /**
     * Set default handler for a URL scheme using Swift script
     */
    private fun setDefaultHandlerForScheme(scheme: String): Boolean {
        val scriptFile = createTempFile("set_default_browser", ".swift").toFile()
        try {
            val swiftScript = """
                import AppKit
                import ApplicationServices

                let bundleId = "$BOSS_MACOS_BUNDLE_ID"
                let scheme = "$scheme"

                let status = LSSetDefaultHandlerForURLScheme(scheme as CFString, bundleId as CFString)

                if status == noErr {
                    print("✅ Set default handler for \(scheme)")
                    exit(0)
                } else {
                    print("❌ Failed to set default handler for \(scheme): \(status)")
                    exit(1)
                }
            """.trimIndent()

            scriptFile.writeText(swiftScript)

            val process = ProcessBuilder("swift", scriptFile.absolutePath)
                .redirectErrorStream(true)
                .start()

            // Read output on background thread so waitFor timeout can fire if Swift hangs
            val outputFuture = CompletableFuture.supplyAsync {
                BufferedReader(InputStreamReader(process.inputStream)).use { it.readText().trim() }
            }

            val finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                logger.warn(LogCategory.BROWSER, "Swift process timed out setting default handler", mapOf("scheme" to scheme))
                return false
            }

            val output = outputFuture.get(1, TimeUnit.SECONDS)
            logger.debug(LogCategory.BROWSER, "Swift script output", mapOf("scheme" to scheme, "output" to output))

            return process.exitValue() == 0
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Error setting default handler", mapOf("scheme" to scheme, "error" to (e.message ?: "unknown")))
            return false
        } finally {
            scriptFile.delete()
        }
    }

    /**
     * Open System Settings (Ventura+) or System Preferences (Monterey and earlier)
     * to the Default Browser settings pane
     */
    private fun openSystemPreferences() {
        try {
            val url = if (getMacOSMajorVersion() >= 13) {
                "x-apple.systempreferences:com.apple.Desktop-Settings.extension"
            } else {
                "x-apple.systempreferences:com.apple.preference.general"
            }

            val process = ProcessBuilder("open", url).start()
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                logger.warn(LogCategory.BROWSER, "Timed out opening System Settings")
                return
            }

            logger.info(LogCategory.BROWSER, "Opened System Settings for user to set default browser")
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Error opening System Settings", error = e)
        }
    }

    /**
     * Get the macOS major version number (e.g. 13 for Ventura, 14 for Sonoma)
     */
    private fun getMacOSMajorVersion(): Int {
        return try {
            val process = ProcessBuilder("sw_vers", "-productVersion").start()
            val version = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText().trim() }
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                logger.warn(LogCategory.BROWSER, "Timed out getting macOS version")
                return 0
            }
            version.substringBefore(".").toIntOrNull() ?: 0
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Error getting macOS version", error = e)
            0
        }
    }
}
