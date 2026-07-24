package ai.rever.boss.plugin.workspace

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * Represents a tab configuration within a workspace.
 *
 * @property type The tab type: "browser", "terminal", or "editor"
 * @property title Display title for the tab
 * @property url URL for browser tabs
 * @property filePath File path for editor tabs
 * @property faviconCacheKey Cache key for browser tab favicon
 * @property initialCommand Command to run on start for terminal tabs
 * @property workingDirectory Working directory for terminal tabs
 */
@Immutable
@Serializable
data class TabConfig(
    val type: String,
    val title: String,
    val url: String? = null,
    val filePath: String? = null,
    val faviconCacheKey: String? = null,
    val initialCommand: String? = null,
    val workingDirectory: String? = null,
)

/**
 * Represents a panel configuration containing multiple tabs.
 *
 * @property id Unique identifier for the panel
 * @property tabs List of tab configurations in this panel
 */
@Immutable
@Serializable
data class PanelConfig(
    val id: String,
    val tabs: List<TabConfig>,
)

/**
 * Represents a split layout configuration.
 * Can be a single panel or a vertical/horizontal split of two layouts.
 */
@Immutable
@Serializable
sealed class SplitConfig {
    /**
     * A layout with a single panel.
     */
    @Immutable
    @Serializable
    data class SinglePanel(
        val panel: PanelConfig,
    ) : SplitConfig()

    /**
     * A vertical split with left and right layouts.
     */
    @Immutable
    @Serializable
    data class VerticalSplit(
        val left: SplitConfig,
        val right: SplitConfig,
    ) : SplitConfig()

    /**
     * A horizontal split with top and bottom layouts.
     */
    @Immutable
    @Serializable
    data class HorizontalSplit(
        val top: SplitConfig,
        val bottom: SplitConfig,
    ) : SplitConfig()
}

/**
 * Extract all panels from a SplitConfig with human-readable labels.
 *
 * @param prefix Prefix for the label (used for nested splits)
 * @return List of (panelId, label) pairs
 */
fun SplitConfig.extractPanels(prefix: String = ""): List<Pair<String, String>> =
    when (this) {
        is SplitConfig.SinglePanel -> {
            val label = if (prefix.isEmpty()) "Main Panel" else prefix.trim() + " Panel"
            listOf(panel.id to label)
        }

        is SplitConfig.VerticalSplit -> {
            left.extractPanels("${prefix}Left ") + right.extractPanels("${prefix}Right ")
        }

        is SplitConfig.HorizontalSplit -> {
            top.extractPanels("${prefix}Top ") + bottom.extractPanels("${prefix}Bottom ")
        }
    }

/**
 * Breadcrumb display configuration for workspaces.
 *
 * @property enabled Whether breadcrumbs are enabled
 * @property showWorkspacePath Show workspace path in breadcrumb
 * @property showTabPath Show tab path in breadcrumb
 * @property maxLength Maximum length of breadcrumb text
 * @property separator Separator character between breadcrumb segments
 */
@Immutable
@Serializable
data class BreadcrumbConfig(
    val enabled: Boolean = true,
    val showWorkspacePath: Boolean = true,
    val showTabPath: Boolean = true,
    val maxLength: Int = 50,
    val separator: String = " › ",
)

/**
 * Represents a complete layout workspace configuration.
 *
 * @property id Unique identifier for the workspace
 * @property name Display name of the workspace
 * @property description Description of the workspace
 * @property layout The split layout configuration
 * @property breadcrumbConfig Breadcrumb display settings
 * @property timestamp Creation/modification timestamp
 * @property projectPath Project path associated with this workspace
 */
@Immutable
@Serializable
data class LayoutWorkspace(
    val id: String = "",
    val name: String,
    val description: String,
    val layout: SplitConfig,
    val breadcrumbConfig: BreadcrumbConfig = BreadcrumbConfig(),
    val timestamp: Long = 0L,
    val projectPath: String? = null,
) {
    companion object {
        /**
         * Generate a unique workspace ID based on current timestamp.
         */
        fun generateId(): String = "workspace-${Clock.System.now().toEpochMilliseconds()}"
    }
}

/**
 * JSON serializer for workspace configurations.
 * Provides pretty-printed JSON output and ignores unknown keys for forward compatibility.
 */
object WorkspaceSerializer {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    /**
     * Serialize a workspace to JSON string.
     */
    fun serialize(config: LayoutWorkspace): String = json.encodeToString(config)

    /**
     * Deserialize a JSON string to a workspace.
     */
    fun deserialize(jsonString: String): LayoutWorkspace = json.decodeFromString(jsonString)
}
