package ai.rever.boss.components.workspaces

import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * Desktop implementation of WorkspaceFileManager
 */
actual class WorkspaceFileManager {
    private val logger = BossLogger.forComponent("WorkspaceFileManager")
    private val workspaceDirectory: String by lazy {
        val userHome = SystemUtils.getUserHome()
        val documentsPath = Paths.get(userHome, "Documents", WorkspaceFileManagerCommon.getDefaultWorkspaceDirectoryName())
        documentsPath.toString()
    }
    
    actual fun getDefaultWorkspaceDirectory(): String = workspaceDirectory
    
    actual suspend fun ensureWorkspaceDirectory(): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = File(workspaceDirectory)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            dir.exists() && dir.isDirectory
        } catch (e: Exception) {
            logger.warn(LogCategory.WORKSPACE, "Failed to create workspace directory", error = e)
            false
        }
    }
    
    actual suspend fun saveWorkspace(
        workspace: LayoutWorkspace, 
        fileName: String?
    ): String? = withContext(Dispatchers.IO) {
        try {
            ensureWorkspaceDirectory()
            
            val actualFileName = fileName ?: WorkspaceFileManagerCommon.generateFileName(workspace.name)
            val filePath = getWorkspaceFilePath(actualFileName)
            val file = File(filePath)
            
            // Serialize workspace
            val json = WorkspaceSerializer.serialize(workspace)
            
            // Write to file
            file.writeText(json)
            
            filePath
        } catch (e: Exception) {
            logger.warn(LogCategory.WORKSPACE, "Failed to save workspace to disk", mapOf("workspace" to workspace.name), error = e)
            null
        }
    }
    
    actual suspend fun loadWorkspace(fileName: String): LayoutWorkspace? = withContext(Dispatchers.IO) {
        try {
            val filePath = getWorkspaceFilePath(fileName)
            val file = File(filePath)
            
            if (!file.exists()) {
                return@withContext null
            }
            
            val json = file.readText()
            WorkspaceSerializer.deserialize(json)
        } catch (e: Exception) {
            logger.warn(LogCategory.WORKSPACE, "Failed to load workspace file", mapOf("fileName" to fileName), error = e)
            null
        }
    }
    
    actual suspend fun listWorkspaces(): List<WorkspaceFileInfo> = withContext(Dispatchers.IO) {
        try {
            val dir = File(workspaceDirectory)
            if (!dir.exists() || !dir.isDirectory) {
                return@withContext emptyList()
            }
            
            dir.listFiles { file -> 
                file.isFile && file.name.endsWith(".json") 
            }?.map { file ->
                WorkspaceFileInfo(
                    fileName = file.name,
                    filePath = file.absolutePath,
                    lastModified = file.lastModified(),
                    size = file.length()
                )
            } ?: emptyList()
        } catch (e: Exception) {
            logger.warn(LogCategory.WORKSPACE, "Failed to list workspace files", error = e)
            emptyList()
        }
    }
    
    actual suspend fun deleteWorkspace(fileName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val filePath = getWorkspaceFilePath(fileName)
            val file = File(filePath)
            
            if (file.exists()) {
                file.delete()
            } else {
                false
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.WORKSPACE, "Failed to delete workspace file", mapOf("fileName" to fileName), error = e)
            false
        }
    }
    
    actual fun getWorkspaceFilePath(fileName: String): String {
        return Paths.get(workspaceDirectory, fileName).toString()
    }
}
