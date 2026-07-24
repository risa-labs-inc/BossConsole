package ai.rever.boss.plugin.window

import kotlinx.serialization.Serializable

/**
 * Represents a project directory.
 *
 * @property name Display name for the project
 * @property path Absolute path to the project directory
 * @property lastOpened Timestamp when the project was last opened (epoch millis)
 */
@Serializable
data class Project(
    val name: String,
    val path: String,
    val lastOpened: Long = 0L,
)
