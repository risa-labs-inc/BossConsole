package ai.rever.boss.components.workspaces

import kotlinx.browser.localStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * WebAssembly implementation of WorkspaceFileManager
 * Uses localStorage for persistence
 */
actual class WorkspaceFileManager {
    private val workspacePrefix = "boss_workspace_"
    private val workspaceListKey = "boss_workspace_list"
    
    actual fun getDefaultWorkspaceDirectory(): String = "localStorage://boss/workspaces"
    
    actual suspend fun ensureWorkspaceDirectory(): Boolean = withContext(Dispatchers.Main) {
        // localStorage is always available in browser
        true
    }
    
    actual suspend fun saveWorkspace(
        workspace: LayoutWorkspace, 
        fileName: String?
    ): String? = withContext(Dispatchers.Main) {
        try {
            val actualFileName = fileName ?: WorkspaceFileManagerCommon.generateFileName(workspace.name)
            val storageKey = "$workspacePrefix$actualFileName"
            
            // Serialize workspace
            val json = WorkspaceSerializer.serialize(workspace)
            
            // Save to localStorage
            localStorage.setItem(storageKey, json)
            
            // Update workspace list
            updateWorkspaceList(actualFileName, true)
            
            storageKey
        } catch (e: Exception) {
            null
        }
    }
    
    actual suspend fun loadWorkspace(fileName: String): LayoutWorkspace? = withContext(Dispatchers.Main) {
        try {
            val storageKey = "$workspacePrefix$fileName"
            val json = localStorage.getItem(storageKey) ?: return@withContext null
            WorkspaceSerializer.deserialize(json)
        } catch (e: Exception) {
            null
        }
    }
    
    actual suspend fun listWorkspaces(): List<WorkspaceFileInfo> = withContext(Dispatchers.Main) {
        try {
            val listJson = localStorage.getItem(workspaceListKey) ?: return@withContext emptyList()
            val fileList = Json.decodeFromString<List<WorkspaceFileMetadata>>(listJson)
            
            fileList.mapNotNull { metadata ->
                val storageKey = "$workspacePrefix${metadata.fileName}"
                val json = localStorage.getItem(storageKey)
                
                if (json != null) {
                    WorkspaceFileInfo(
                        fileName = metadata.fileName,
                        filePath = storageKey,
                        lastModified = metadata.lastModified,
                        size = json.length.toLong()
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    actual suspend fun deleteWorkspace(fileName: String): Boolean = withContext(Dispatchers.Main) {
        try {
            val storageKey = "$workspacePrefix$fileName"
            localStorage.removeItem(storageKey)
            updateWorkspaceList(fileName, false)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    actual fun getWorkspaceFilePath(fileName: String): String {
        return "$workspacePrefix$fileName"
    }
    
    private fun updateWorkspaceList(fileName: String, add: Boolean) {
        try {
            val listJson = localStorage.getItem(workspaceListKey)
            val fileList = if (listJson != null) {
                Json.decodeFromString<MutableList<WorkspaceFileMetadata>>(listJson)
            } else {
                mutableListOf()
            }
            
            if (add) {
                // Remove existing entry if present
                fileList.removeAll { it.fileName == fileName }
                // Add new entry
                fileList.add(
                    WorkspaceFileMetadata(
                        fileName = fileName,
                        lastModified = kotlin.time.Clock.System.now().toEpochMilliseconds()
                    )
                )
            } else {
                // Remove entry
                fileList.removeAll { it.fileName == fileName }
            }
            
            localStorage.setItem(workspaceListKey, Json.encodeToString(fileList))
        } catch (e: Exception) {
        }
    }
}

/**
 * Metadata for workspace files stored in localStorage
 */
@kotlinx.serialization.Serializable
private data class WorkspaceFileMetadata(
    val fileName: String,
    val lastModified: Long
)
