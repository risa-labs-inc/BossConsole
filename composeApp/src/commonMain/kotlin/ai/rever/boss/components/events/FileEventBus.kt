@file:Suppress("UNUSED")
package ai.rever.boss.components.events

import ai.rever.boss.dashboard.DashboardStatsManager
import ai.rever.boss.dashboard.RecentFilesManager
import ai.rever.boss.ipc.IpcEventBridge
import kotlinx.coroutines.flow.SharedFlow

/**
 * Re-exports from plugin-events module for backward compatibility.
 * New code should import directly from ai.rever.boss.plugin.events
 */

// Re-export types
typealias FileOpenEvent = ai.rever.boss.plugin.events.FileOpenEvent
typealias ParsedFileReference = ai.rever.boss.plugin.events.ParsedFileReference
typealias FileOpenCallback = ai.rever.boss.plugin.events.FileOpenCallback

// Note: FileValidationResult should be imported directly from ai.rever.boss.plugin.events
// Sealed class typealiases don't work well with pattern matching (is Valid/is Invalid)

// Re-export functions
fun stripFilePrefix(path: String): String = ai.rever.boss.plugin.events.stripFilePrefix(path)
fun parseFileReference(fileUrl: String): ParsedFileReference = ai.rever.boss.plugin.events.parseFileReference(fileUrl)
fun validateFilePath(filePath: String): ai.rever.boss.plugin.events.FileValidationResult = ai.rever.boss.plugin.events.validateFilePath(filePath)

// Note: extractFileName is already available in ai.rever.boss.utils.PathUtils

/**
 * Delegating object for FileEventBus.
 * Registers RecentFilesManager callback on first access.
 */
object FileEventBus {
    /** Optional IPC bridge for forwarding events cross-process in kernel mode. */
    @Volatile var ipcBridge: IpcEventBridge? = null

    private val delegate = ai.rever.boss.plugin.events.FileEventBus

    init {
        // Register callback for recent files tracking and dashboard stats
        delegate.setFileOpenCallback { filePath, projectPath ->
            RecentFilesManager.recordFileOpen(filePath, projectPath)
            DashboardStatsManager.recordFileOpen()
        }
    }

    val fileOpenEvents: SharedFlow<FileOpenEvent>
        get() = delegate.fileOpenEvents

    suspend fun openFile(filePath: String, line: Int = 0, column: Int = 0, sourceWindowId: String, projectPath: String = "") {
        delegate.openFile(filePath, line, column, sourceWindowId, projectPath)
        val cleanPath = stripFilePrefix(filePath)
        val fileName = cleanPath.substringAfterLast('/').substringAfterLast('\\').ifEmpty { "untitled" }
        ipcBridge?.forward("FileOpenEvent", FileOpenEvent(cleanPath, fileName, line, column, sourceWindowId), sourceWindowId)
    }
}
