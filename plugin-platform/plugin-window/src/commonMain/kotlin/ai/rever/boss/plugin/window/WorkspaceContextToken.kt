package ai.rever.boss.plugin.window

import kotlinx.serialization.Serializable

/**
 * Immutable token identifying the causal workspace context of an authorized operation.
 *
 * @property windowId Identifier of the window where the operation was authorized.
 * @property projectPath Filesystem path of the project that was active when authorized.
 * @property generation Monotonic generation counter of the window's project state.
 */
@Serializable
data class WorkspaceContextToken(
    val windowId: String,
    val projectPath: String,
    val generation: Long,
) {
    /** Whether this token is bound to a non-empty project directory. */
    val hasProject: Boolean get() = projectPath.isNotBlank()
}
