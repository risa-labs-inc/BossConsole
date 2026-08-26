package ai.rever.boss.components.plugin.providers

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File

private val fileIoLogger = BossLogger.forComponent("EditorFileIo")

actual fun readFileContentSafe(
    filePath: String,
    maxSize: Long,
): FileReadOutcome =
    try {
        val file = File(filePath)
        when {
            !file.exists() || !file.isFile -> {
                FileReadOutcome.FileNotFound
            }

            file.length() > maxSize -> {
                FileReadOutcome.FileTooLarge(file.length(), maxSize)
            }

            else -> {
                try {
                    FileReadOutcome.Success(file.readText())
                } catch (e: OutOfMemoryError) {
                    FileReadOutcome.Error("File too large to load into memory: ${e.message}")
                }
            }
        }
    } catch (e: Exception) {
        FileReadOutcome.Error(e.message ?: "Unknown error reading file")
    }

actual fun writeFileContentSafe(
    filePath: String,
    content: String,
): Boolean =
    try {
        val file = File(filePath)
        // Create parent directories if they don't exist
        file.parentFile?.mkdirs()
        file.writeText(content)
        true
    } catch (e: Exception) {
        fileIoLogger.warn(LogCategory.EDITOR, "Error writing file", error = e)
        false
    }
