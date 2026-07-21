package ai.rever.boss.components.workspaces

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android implementation of WorkspaceFileManager
 */
actual class WorkspaceFileManager {
    companion object {
        @Volatile
        private var appContext: Context? = null
        
        fun init(context: Context) {
            if (appContext == null) {
                appContext = context.applicationContext
            }
        }
    }
    
    private val context: Context
        get() = appContext ?: throw IllegalStateException("WorkspaceFileManager not initialized. Call WorkspaceFileManager.init() in your Application or Activity.")
    
    private val workspaceDirectory: String by lazy {
        // Use app-specific directory in external storage if available, otherwise internal
        val externalDir = context.getExternalFilesDir(null)
        val baseDir = externalDir ?: context.filesDir
        File(baseDir, WorkspaceFileManagerCommon.getDefaultWorkspaceDirectoryName()).absolutePath
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
            false
        }
    }
    
    actual fun getWorkspaceFilePath(fileName: String): String {
        return File(workspaceDirectory, fileName).absolutePath
    }
}
