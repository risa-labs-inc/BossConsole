package ai.rever.boss.components.workspaces

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.*
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * iOS implementation of WorkspaceFileManager
 */
@OptIn(ExperimentalForeignApi::class)
actual class WorkspaceFileManager {
    private val workspaceDirectory: String by lazy {
        val documentsDir = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory,
            NSUserDomainMask,
            true
        ).firstOrNull() as? String ?: ""
        
        val workspacePath = "$documentsDir/${WorkspaceFileManagerCommon.getDefaultWorkspaceDirectoryName()}"
        workspacePath
    }
    
    actual fun getDefaultWorkspaceDirectory(): String = workspaceDirectory
    
    actual suspend fun ensureWorkspaceDirectory(): Boolean = withContext(Dispatchers.Main) {
        try {
            val fileManager = NSFileManager.defaultManager
            
            if (!fileManager.fileExistsAtPath(workspaceDirectory)) {
                fileManager.createDirectoryAtPath(
                    workspaceDirectory,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = null
                )
            }
            
            fileManager.fileExistsAtPath(workspaceDirectory)
        } catch (e: Exception) {
            false
        }
    }
    
    actual suspend fun saveWorkspace(
        workspace: LayoutWorkspace, 
        fileName: String?
    ): String? = withContext(Dispatchers.Main) {
        try {
            ensureWorkspaceDirectory()
            
            val actualFileName = fileName ?: WorkspaceFileManagerCommon.generateFileName(workspace.name)
            val filePath = getWorkspaceFilePath(actualFileName)
            
            // Serialize workspace
            val json = WorkspaceSerializer.serialize(workspace)
            val nsString = NSString.create(string = json)
            
            // Write to file
            nsString.writeToFile(filePath, atomically = true, encoding = NSUTF8StringEncoding, error = null)
            
            filePath
        } catch (e: Exception) {
            null
        }
    }
    
    actual suspend fun loadWorkspace(fileName: String): LayoutWorkspace? = withContext(Dispatchers.Main) {
        try {
            val filePath = getWorkspaceFilePath(fileName)
            val fileManager = NSFileManager.defaultManager
            
            if (!fileManager.fileExistsAtPath(filePath)) {
                return@withContext null
            }
            
            val nsString = NSString.stringWithContentsOfFile(filePath, encoding = NSUTF8StringEncoding, error = null) ?: return@withContext null
            WorkspaceSerializer.deserialize(nsString.toString())
        } catch (e: Exception) {
            null
        }
    }
    
    actual suspend fun listWorkspaces(): List<WorkspaceFileInfo> = withContext(Dispatchers.Main) {
        try {
            val fileManager = NSFileManager.defaultManager
            val contents = fileManager.contentsOfDirectoryAtPath(workspaceDirectory, error = null) as? List<String>
            
            contents?.filter { fileName ->
                fileName.endsWith(".json")
            }?.mapNotNull { fileName ->
                val filePath = getWorkspaceFilePath(fileName)
                val attributes = fileManager.attributesOfItemAtPath(filePath, error = null) as? Map<Any?, Any?>
                
                WorkspaceFileInfo(
                    fileName = fileName,
                    filePath = filePath,
                    lastModified = (attributes?.get(NSFileModificationDate) as? NSDate)?.timeIntervalSince1970?.toLong() ?: 0L,
                    size = (attributes?.get(NSFileSize) as? NSNumber)?.longValue ?: 0L
                )
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    actual suspend fun deleteWorkspace(fileName: String): Boolean = withContext(Dispatchers.Main) {
        try {
            val filePath = getWorkspaceFilePath(fileName)
            val fileManager = NSFileManager.defaultManager
            
            if (fileManager.fileExistsAtPath(filePath)) {
                fileManager.removeItemAtPath(filePath, error = null)
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    actual fun getWorkspaceFilePath(fileName: String): String {
        return "$workspaceDirectory/$fileName"
    }
}

