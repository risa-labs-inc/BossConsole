package ai.rever.boss.components.workspaces

/**
 * Manages file-based workspace storage
 */
expect class WorkspaceFileManager() {
    /**
     * Get the default workspace directory path
     */
    fun getDefaultWorkspaceDirectory(): String
    
    /**
     * Ensure the workspace directory exists
     */
    suspend fun ensureWorkspaceDirectory(): Boolean
    
    /**
     * Save a workspace to a file
     */
    suspend fun saveWorkspace(workspace: LayoutWorkspace, fileName: String? = null): String?
    
    /**
     * Load a workspace from a file
     */
    suspend fun loadWorkspace(fileName: String): LayoutWorkspace?
    
    /**
     * List all saved workspace files
     */
    suspend fun listWorkspaces(): List<WorkspaceFileInfo>
    
    /**
     * Delete a workspace file
     */
    suspend fun deleteWorkspace(fileName: String): Boolean
    
    /**
     * Get full path for a workspace file
     */
    fun getWorkspaceFilePath(fileName: String): String
}

/**
 * Information about a workspace file
 */
data class WorkspaceFileInfo(
    val fileName: String,
    val filePath: String,
    val lastModified: Long,
    val size: Long
)

/**
 * Common workspace file manager functionality
 */
object WorkspaceFileManagerCommon {
    /**
     * Get the default workspace directory name
     */
    fun getDefaultWorkspaceDirectoryName(): String = "BOSS/workspaces"
    
    /**
     * Generate a filename from workspace name
     */
    fun generateFileName(workspaceName: String): String {
        // Replace spaces and special characters with underscores
        val sanitized = workspaceName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        return "$sanitized.json"
    }
    
    /**
     * Extract workspace name from filename
     */
    fun extractWorkspaceName(fileName: String): String {
        return fileName.removeSuffix(".json").replace("_", " ")
    }
}