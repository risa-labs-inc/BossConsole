package ai.rever.boss.run

/**
 * Interface for detecting main functions and entry points in source code.
 * Platform-specific implementations handle file system access.
 */
interface MainFunctionDetector {
    /**
     * Scans a project directory for all runnable entry points.
     * @param projectPath The root path of the project to scan
     * @return List of detected run configurations
     */
    suspend fun scanProject(projectPath: String): List<RunConfiguration>

    /**
     * Detects main functions in a single file's content.
     * @param filePath The path to the file (for context and naming)
     * @param content The source code content
     * @param language The programming language (if known)
     * @return List of detected main functions with line numbers
     */
    fun detectInFile(
        filePath: String,
        content: String,
        language: Language? = null
    ): List<DetectedMainFunction>

    /**
     * Generates the appropriate run command for a detected main function.
     * @param detected The detected main function
     * @param projectPath The project root path
     * @return The command to execute
     */
    fun generateCommand(
        detected: DetectedMainFunction,
        projectPath: String
    ): String

    /**
     * Finds the project root directory from a file path by looking for project markers.
     * @param filePath The path to a file in the project
     * @return The project root path, or the file's parent directory if not found
     */
    fun findProjectRoot(filePath: String): String
}

/**
 * Platform-specific MainFunctionDetector instance.
 */
expect fun createMainFunctionDetector(): MainFunctionDetector

/**
 * Singleton accessor for the main function detector.
 */
object MainFunctionDetectorProvider {
    private var instance: MainFunctionDetector? = null

    fun get(): MainFunctionDetector {
        if (instance == null) {
            instance = createMainFunctionDetector()
        }
        return instance!!
    }
}
