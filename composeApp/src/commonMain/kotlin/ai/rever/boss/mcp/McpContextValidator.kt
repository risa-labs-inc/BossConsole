package ai.rever.boss.mcp

import ai.rever.boss.components.window_panel.TabPaths
import ai.rever.boss.plugin.window.WorkspaceContextToken
import ai.rever.boss.window.WindowProjectStateRegistry

/**
 * Result of validating an operation's [WorkspaceContextToken] against the live host state.
 */
sealed interface ContextValidationResult {
    /** The token matches the active window, project path, and generation epoch. */
    data object Valid : ContextValidationResult

    /** The window's generation epoch has advanced beyond the token's epoch. */
    data class StaleGeneration(
        val expected: Long,
        val actual: Long,
        val currentPath: String,
    ) : ContextValidationResult

    /** The active project in the window switched to a different path. */
    data class ProjectSwitched(
        val expectedPath: String,
        val actualPath: String,
    ) : ContextValidationResult

    /** The window referenced by the token has been closed or unregistered. */
    data class WindowClosed(
        val windowId: String,
    ) : ContextValidationResult

    /** The window has no open project (empty project path). */
    data object NoProjectSelected : ContextValidationResult

    /** Context was required (e.g. for mutating tool) but none was provided. */
    data object MissingRequiredContext : ContextValidationResult

    /** Formats a user- and agent-readable failure explanation, or null if valid. */
    fun failureReason(): String? =
        when (this) {
            is Valid -> null
            is StaleGeneration ->
                "Workspace context generation advanced from $expected to $actual (current project: '$currentPath') while operation was in-flight"
            is ProjectSwitched ->
                "Active project switched from '$expectedPath' to '$actualPath' while operation was in-flight"
            is WindowClosed ->
                "Window '$windowId' was closed while operation was in-flight"
            is NoProjectSelected ->
                "No project is open in the target window"
            is MissingRequiredContext ->
                "Mutating tool requires a bound, active workspace context token"
        }
}

/**
 * Contract for validating that an operation's [WorkspaceContextToken] remains
 * authoritative before, during, and after execution.
 */
fun interface McpContextValidator {
    fun validate(token: WorkspaceContextToken?): ContextValidationResult
}

/**
 * Default validator querying [WindowProjectStateRegistry].
 */
object DefaultMcpContextValidator : McpContextValidator {
    override fun validate(token: WorkspaceContextToken?): ContextValidationResult {
        if (token == null) return ContextValidationResult.MissingRequiredContext
        val state = WindowProjectStateRegistry.get(token.windowId)
            ?: return ContextValidationResult.WindowClosed(token.windowId)
        val snapshot = state.snapshot()
        if (snapshot.isClosed) {
            return ContextValidationResult.WindowClosed(token.windowId)
        }
        if (!token.hasProject) {
            return ContextValidationResult.NoProjectSelected
        }
        if (!TabPaths.pathsMatch(snapshot.projectPath, token.projectPath)) {
            return ContextValidationResult.ProjectSwitched(
                expectedPath = token.projectPath,
                actualPath = snapshot.projectPath,
            )
        }
        if (snapshot.generation != token.generation) {
            return ContextValidationResult.StaleGeneration(
                expected = token.generation,
                actual = snapshot.generation,
                currentPath = snapshot.projectPath,
            )
        }
        return ContextValidationResult.Valid
    }
}
