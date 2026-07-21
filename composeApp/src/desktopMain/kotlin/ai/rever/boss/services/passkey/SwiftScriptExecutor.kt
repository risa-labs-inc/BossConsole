package ai.rever.boss.services.passkey

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File

/**
 * Utility for executing Swift scripts on macOS
 * Handles temporary file creation and cleanup for Swift code execution
 */
object SwiftScriptExecutor {
    private val logger = BossLogger.forComponent("SwiftScriptExecutor")

    /**
     * Execute a Swift file with arguments and return the output
     */
    fun executeSwiftFile(fileName: String, vararg args: String): String {
        val swiftFilesDir = getSwiftFilesDirectory()
        val swiftFile = File(swiftFilesDir, fileName)
        
        if (!swiftFile.exists()) {
            throw IllegalArgumentException("Swift file not found: ${swiftFile.absolutePath}")
        }
        
        val command = mutableListOf("swift", swiftFile.absolutePath)
        command.addAll(args)
        
        val process = ProcessBuilder(command).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()
        
        logger.debug(LogCategory.PASSKEY, "Executed Swift file", mapOf("fileName" to fileName, "exitCode" to exitCode))
        
        return output
    }

    /**
     * Get the directory containing Swift files
     */
    private fun getSwiftFilesDirectory(): File {
        val projectDir = System.getProperty("user.dir")
        logger.debug(LogCategory.PASSKEY, "Project dir", mapOf("path" to projectDir))
        
        // Try multiple possible paths
        val possiblePaths = listOf(
            "$projectDir/composeApp/src/desktopMain/kotlin/ai/rever/boss/services/passkey/swift",
            "$projectDir/src/desktopMain/kotlin/ai/rever/boss/services/passkey/swift"
        )
        
        for (path in possiblePaths) {
            val dir = File(path)
            logger.debug(LogCategory.PASSKEY, "Checking path", mapOf("path" to dir.absolutePath))
            if (dir.exists() && dir.isDirectory) {
                logger.debug(LogCategory.PASSKEY, "Found Swift directory", mapOf("path" to dir.absolutePath))
                return dir
            }
        }
        
        throw IllegalStateException("Swift files directory not found. Checked paths: $possiblePaths")
    }

    /**
     * Check if macOS Swift compiler is available
     */
    fun isSwiftAvailable(): Boolean {
        return try {
            val process = ProcessBuilder("swift", "--version").start()
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            logger.debug(LogCategory.PASSKEY, "Swift not available")
            false
        }
    }
}
