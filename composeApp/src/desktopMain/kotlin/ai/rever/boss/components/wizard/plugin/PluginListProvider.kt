package ai.rever.boss.components.wizard.plugin

import ai.rever.boss.components.plugin.RetiredPluginIds
import ai.rever.boss.plugin.PluginPersistence
import ai.rever.boss.plugin.PluginStoreSetup
import ai.rever.boss.plugin.repository.PluginInfo
import ai.rever.boss.plugin.repository.PluginWithSource
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Engineering
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Web
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Provides the list of available plugins for the installation wizard.
 *
 * This class fetches plugins from the repository manager and converts them
 * to WizardPluginInfo with appropriate categories and default selections.
 */
object PluginListProvider {
    private val logger = BossLogger.forComponent("PluginListProvider")

    /**
     * Plugin IDs that should be selected by default in the wizard.
     * Plugin IDs use the format: ai.rever.boss.plugin.dynamic.<name>
     */
    val DEFAULT_PLUGIN_IDS =
        setOf(
            "ai.rever.boss.plugin.dynamic.terminal",
            "ai.rever.boss.plugin.dynamic.console",
            "ai.rever.boss.plugin.dynamic.fluckagent",
            "ai.rever.boss.plugin.dynamic.aigateway",
            // Codebase now contains the Git Status and Git Log features. Keep the replacement in
            // every fresh setup because startup retires those two standalone plugins.
            "ai.rever.boss.plugin.dynamic.codebase",
            // Was `usersecretlist` ("My Secrets") until that plugin was retired into
            // secret-manager's "Shared with me" section. Installing the read-only half by
            // default and never the half that can add a key was backwards anyway: AI provider
            // settings live in secret-manager, so a default install could not configure AI.
            "ai.rever.boss.plugin.dynamic.secretmanager",
        )

    /**
     * Plugin IDs that are mandatory and cannot be deselected.
     * These are core tab plugins that provide essential functionality.
     */
    val MANDATORY_PLUGIN_IDS =
        setOf(
            "ai.rever.boss.plugin.dynamic.fluckbrowser",
            "ai.rever.boss.plugin.dynamic.editortab",
            "ai.rever.boss.plugin.dynamic.terminaltab",
        )

    /**
     * Map of plugin IDs to their GitHub URLs (for plugins not in the repository).
     */
    private val GITHUB_PLUGIN_URLS =
        mapOf(
            "ai.rever.boss.plugin.dynamic.fluckbrowser" to "https://github.com/risa-labs-inc/boss-plugin-fluck-browser",
            "ai.rever.boss.plugin.dynamic.editortab" to "https://github.com/risa-labs-inc/boss-plugin-editor-tab",
            "ai.rever.boss.plugin.dynamic.terminaltab" to "https://github.com/risa-labs-inc/boss-plugin-terminal-tab",
            "ai.rever.boss.plugin.dynamic.terminal" to "https://github.com/risa-labs-inc/boss-plugin-terminal",
        )

    /**
     * Map of plugin IDs to their categories.
     * Plugin IDs use the format: ai.rever.boss.plugin.dynamic.<name>
     */
    private val PLUGIN_CATEGORIES =
        mapOf(
            // Essential (includes mandatory tab plugins)
            "ai.rever.boss.plugin.dynamic.terminal" to PluginCategory.ESSENTIAL,
            "ai.rever.boss.plugin.dynamic.console" to PluginCategory.ESSENTIAL,
            "ai.rever.boss.plugin.dynamic.fluckagent" to PluginCategory.ESSENTIAL,
            "ai.rever.boss.plugin.dynamic.aigateway" to PluginCategory.ESSENTIAL,
            // Not an admin tool, despite living under ADMIN until 9.4.7 and PRODUCTIVITY
            // after it. It is a per-user credential vault (every RPC behind it is
            // auth.uid()-scoped), it is the only place anyone adds an AI provider key, and
            // since the My Secrets panel was retired into it, it is the only secrets panel
            // there is - so it takes that panel's place among the essentials.
            "ai.rever.boss.plugin.dynamic.secretmanager" to PluginCategory.ESSENTIAL,
            "ai.rever.boss.plugin.dynamic.downloads" to PluginCategory.PRODUCTIVITY,
            "ai.rever.boss.plugin.dynamic.fluckbrowser" to PluginCategory.ESSENTIAL,
            "ai.rever.boss.plugin.dynamic.editortab" to PluginCategory.ESSENTIAL,
            "ai.rever.boss.plugin.dynamic.terminaltab" to PluginCategory.ESSENTIAL,
            // Developer
            "ai.rever.boss.plugin.dynamic.codebase" to PluginCategory.DEVELOPER,
            "ai.rever.boss.plugin.dynamic.gitstatus" to PluginCategory.DEVELOPER,
            "ai.rever.boss.plugin.dynamic.gitlog" to PluginCategory.DEVELOPER,
            "ai.rever.boss.plugin.dynamic.runconfigurations" to PluginCategory.DEVELOPER,
            // Productivity
            "ai.rever.boss.plugin.dynamic.bookmarks" to PluginCategory.PRODUCTIVITY,
            "ai.rever.boss.plugin.dynamic.topofmind" to PluginCategory.PRODUCTIVITY,
            // Automation
            "ai.rever.boss.plugin.dynamic.llmrpa" to PluginCategory.AUTOMATION,
            "ai.rever.boss.plugin.dynamic.rparecorder" to PluginCategory.AUTOMATION,
            "ai.rever.boss.plugin.dynamic.rpaengine" to PluginCategory.AUTOMATION,
            // Admin
            "ai.rever.boss.plugin.dynamic.adminrolemanagement" to PluginCategory.ADMIN,
            "ai.rever.boss.plugin.dynamic.rolecreation" to PluginCategory.ADMIN,
        )

    /**
     * Map of plugin IDs to their icons.
     * Plugin IDs use the format: ai.rever.boss.plugin.dynamic.<name>
     */
    private val PLUGIN_ICONS: Map<String, ImageVector> =
        mapOf(
            "ai.rever.boss.plugin.dynamic.terminal" to Icons.Default.Terminal,
            "ai.rever.boss.plugin.dynamic.console" to Icons.Default.Code,
            "ai.rever.boss.plugin.dynamic.codebase" to Icons.Default.Folder,
            "ai.rever.boss.plugin.dynamic.gitstatus" to Icons.Default.Engineering,
            "ai.rever.boss.plugin.dynamic.gitlog" to Icons.Default.History,
            "ai.rever.boss.plugin.dynamic.bookmarks" to Icons.Default.Bookmark,
            "ai.rever.boss.plugin.dynamic.topofmind" to Icons.Default.Lightbulb,
            "ai.rever.boss.plugin.dynamic.downloads" to Icons.Default.Download,
            "ai.rever.boss.plugin.dynamic.llmrpa" to Icons.Default.Psychology,
            "ai.rever.boss.plugin.dynamic.rparecorder" to Icons.Default.Videocam,
            "ai.rever.boss.plugin.dynamic.rpaengine" to Icons.Default.PlayArrow,
            "ai.rever.boss.plugin.dynamic.adminrolemanagement" to Icons.Default.ManageAccounts,
            "ai.rever.boss.plugin.dynamic.rolecreation" to Icons.Default.AdminPanelSettings,
            "ai.rever.boss.plugin.dynamic.secretmanager" to Icons.Default.Key,
            "ai.rever.boss.plugin.dynamic.performance" to Icons.Default.AutoAwesome,
            "ai.rever.boss.plugin.dynamic.fluck" to Icons.Default.Psychology,
            "ai.rever.boss.plugin.dynamic.fluckagent" to Icons.Default.Psychology,
            "ai.rever.boss.plugin.dynamic.aigateway" to Icons.Default.AutoAwesome,
            "ai.rever.boss.plugin.dynamic.runconfigurations" to Icons.Default.PlayArrow,
            // Mandatory tab plugins
            "ai.rever.boss.plugin.dynamic.fluckbrowser" to Icons.Default.Web,
            "ai.rever.boss.plugin.dynamic.editortab" to Icons.Default.Code,
            "ai.rever.boss.plugin.dynamic.terminaltab" to Icons.Default.Terminal,
        )

    /**
     * Get available plugins for the wizard from the repository.
     * Always includes mandatory GitHub plugins even if not in the repository.
     *
     * @return List of plugins formatted for the wizard
     */
    suspend fun getAvailablePlugins(): List<WizardPluginInfo> {
        return try {
            val repositoryManager = PluginStoreSetup.repositoryManager
            if (repositoryManager == null) {
                logger.warn(LogCategory.SYSTEM, "Repository manager not initialized, using fallback list")
                return getFallbackPluginList()
            }

            val result = repositoryManager.listAllPlugins()
            result.fold(
                onSuccess = { pluginsWithSource: List<PluginWithSource> ->
                    logger.info(
                        LogCategory.SYSTEM,
                        "Fetched plugins for wizard",
                        mapOf(
                            "count" to pluginsWithSource.size,
                        ),
                    )

                    val repoPlugins =
                        pluginsWithSource
                            // Drop service-type plugins (e.g. the microkernel
                            // runtime). They ship through the plugin store but
                            // aren't user-installable — the host's auto-installer
                            // handles them when needed. Letting them into the
                            // wizard tries to load them as regular plugins and
                            // BinaryCompatibilityValidator rejects with a scary
                            // cross-classloader IllegalAccessError.
                            .filter { it.plugin.type != ai.rever.boss.plugin.api.PluginType.SERVICE }
                            .filter { it.plugin.pluginId != ai.rever.boss.components.plugin.MicrokernelRuntime.PLUGIN_ID }
                            // Nor a plugin another plugin has taken over: the store keeps
                            // returning it until its row is unlisted by hand, and installing it
                            // here only gets it swept away at the next launch. The floor is the
                            // sweep's own (see HomeToolCatalog) - an unconditional hide would
                            // leave a fresh install with no panel until the replacement ships.
                            .filter {
                                !RetiredPluginIds.hiddenFromOffers(
                                    it.plugin.pluginId,
                                    installedVersionOf = { id ->
                                        PluginPersistence.getInstalledPlugin(id)?.installedVersion
                                    },
                                )
                            }.map { pws: PluginWithSource ->
                                convertToWizardPluginInfo(pws.plugin)
                            }

                    // Add mandatory GitHub plugins that aren't in the repository
                    val existingIds = repoPlugins.map { it.id }.toSet()
                    val mandatoryPlugins =
                        getMandatoryGitHubPlugins()
                            .filter { it.id !in existingIds }

                    (repoPlugins + mandatoryPlugins)
                        .sortedWith(compareBy({ !it.isMandatory }, { it.category.ordinal }, { !it.isDefault }, { it.name }))
                },
                onFailure = { error ->
                    logger.error(LogCategory.SYSTEM, "Failed to fetch plugins", error = error)
                    getFallbackPluginList()
                },
            )
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e // Don't swallow scope cancellation
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Error getting available plugins", error = e)
            getFallbackPluginList()
        }
    }

    /**
     * Convert a PluginInfo to WizardPluginInfo.
     */
    private fun convertToWizardPluginInfo(plugin: PluginInfo): WizardPluginInfo {
        val category = PLUGIN_CATEGORIES[plugin.pluginId] ?: PluginCategory.OTHER
        val isDefault = plugin.pluginId in DEFAULT_PLUGIN_IDS
        val isMandatory = plugin.pluginId in MANDATORY_PLUGIN_IDS
        val icon = PLUGIN_ICONS[plugin.pluginId] ?: Icons.Default.Extension
        val githubUrl = GITHUB_PLUGIN_URLS[plugin.pluginId] ?: ""

        return WizardPluginInfo(
            id = plugin.pluginId,
            name = plugin.displayName,
            description = plugin.description,
            version = plugin.version,
            icon = icon,
            isDefault = isDefault || isMandatory, // Mandatory plugins are always selected by default
            isMandatory = isMandatory,
            category = category,
            downloadUrl = plugin.downloadUrl,
            githubUrl = githubUrl,
        )
    }

    /**
     * Get the mandatory GitHub plugins that must always be included.
     * These are core tab plugins sourced from GitHub.
     */
    private fun getMandatoryGitHubPlugins(): List<WizardPluginInfo> =
        listOf(
            WizardPluginInfo(
                id = "ai.rever.boss.plugin.dynamic.fluckbrowser",
                name = "Browser Tab",
                description = "Browse the web inside BOSS - your agent can see and interact with pages you open",
                version = "1.0.7",
                icon = Icons.Default.Web,
                isDefault = true,
                isMandatory = true,
                category = PluginCategory.ESSENTIAL,
                githubUrl = "https://github.com/risa-labs-inc/boss-plugin-fluck-browser",
            ),
            WizardPluginInfo(
                id = "ai.rever.boss.plugin.dynamic.editortab",
                name = "Code Editor Tab",
                description = "Write and edit code with your agent - it can read, suggest, and modify files directly",
                version = "1.0.2",
                icon = Icons.Default.Code,
                isDefault = true,
                isMandatory = true,
                category = PluginCategory.ESSENTIAL,
                githubUrl = "https://github.com/risa-labs-inc/boss-plugin-editor-tab",
            ),
            WizardPluginInfo(
                id = "ai.rever.boss.plugin.dynamic.terminaltab",
                name = "Terminal Tab",
                description =
                    "Run shell commands inside BOSS - your agent can execute scripts and read terminal " +
                        "output",
                version = "1.0.4",
                icon = Icons.Default.Terminal,
                isDefault = true,
                isMandatory = true,
                category = PluginCategory.ESSENTIAL,
                githubUrl = "https://github.com/risa-labs-inc/boss-plugin-terminal-tab",
            ),
        )

    /**
     * Fallback list of plugins when the repository is not available.
     * This ensures the wizard can still function offline or during initialization.
     * Plugin IDs use the format: ai.rever.boss.plugin.dynamic.<name>
     */
    private fun getFallbackPluginList(): List<WizardPluginInfo> =
        getMandatoryGitHubPlugins() +
            listOf(
                // Essential
                WizardPluginInfo(
                    id = "ai.rever.boss.plugin.dynamic.terminal",
                    name = "Terminal",
                    description = "A persistent terminal panel your agent uses to run commands on your behalf",
                    version = "1.0.0",
                    icon = Icons.Default.Terminal,
                    isDefault = true,
                    category = PluginCategory.ESSENTIAL,
                ),
                WizardPluginInfo(
                    id = "ai.rever.boss.plugin.dynamic.console",
                    name = "Console",
                    description =
                        "See everything BOSS and your agent are doing behind the scenes - useful when things go " +
                            "wrong",
                    version = "1.0.0",
                    icon = Icons.Default.Code,
                    isDefault = true,
                    category = PluginCategory.ESSENTIAL,
                ),
                WizardPluginInfo(
                    id = "ai.rever.boss.plugin.dynamic.fluckagent",
                    name = "Fluck Agent",
                    description =
                        "BOSS's built-in AI assistant - gives your agent workspace tools and " +
                            "AI capabilities directly inside BOSS",
                    version = "1.0.0",
                    icon = Icons.Default.Psychology,
                    isDefault = true,
                    category = PluginCategory.ESSENTIAL,
                ),
                WizardPluginInfo(
                    id = "ai.rever.boss.plugin.dynamic.aigateway",
                    name = "AI Gateway",
                    description =
                        "Connects the Fluck Agent to AI providers - needed before the assistant can reach " +
                            "AI providers",
                    version = "1.0.0",
                    icon = Icons.Default.AutoAwesome,
                    isDefault = true,
                    category = PluginCategory.ESSENTIAL,
                ),
                WizardPluginInfo(
                    id = "ai.rever.boss.plugin.dynamic.secretmanager",
                    name = "Secret Manager",
                    description =
                        "Store API keys and credentials securely - your agent uses these to " +
                            "act on your behalf without exposing them",
                    version = "1.0.0",
                    icon = Icons.Default.Key,
                    isDefault = true,
                    category = PluginCategory.ESSENTIAL,
                ),
                WizardPluginInfo(
                    id = "ai.rever.boss.plugin.dynamic.downloads",
                    name = "Downloads",
                    description = "Track and access files your agent or browser has downloaded",
                    version = "1.0.0",
                    icon = Icons.Default.Download,
                    isDefault = false,
                    category = PluginCategory.PRODUCTIVITY,
                ),
                // Developer
                WizardPluginInfo(
                    id = "ai.rever.boss.plugin.dynamic.codebase",
                    name = "Codebase",
                    description =
                        "Browse your project files and let your agent navigate your codebase to understand " +
                            "context",
                    version = "1.0.0",
                    icon = Icons.Default.Folder,
                    isDefault = false,
                    category = PluginCategory.DEVELOPER,
                ),
                WizardPluginInfo(
                    id = "ai.rever.boss.plugin.dynamic.gitstatus",
                    name = "Git Status",
                    description =
                        "See uncommitted changes at a glance - your agent can read this to understand what's in " +
                            "progress",
                    version = "1.0.0",
                    icon = Icons.Default.Engineering,
                    isDefault = false,
                    category = PluginCategory.DEVELOPER,
                ),
                WizardPluginInfo(
                    id = "ai.rever.boss.plugin.dynamic.gitlog",
                    name = "Git Log",
                    description =
                        "Explore your project's commit history - useful for your agent to understand recent " +
                            "changes",
                    version = "1.0.0",
                    icon = Icons.Default.History,
                    isDefault = false,
                    category = PluginCategory.DEVELOPER,
                ),
                // Productivity
                WizardPluginInfo(
                    id = "ai.rever.boss.plugin.dynamic.bookmarks",
                    name = "Bookmarks",
                    description = "Save important pages so you and your agent can return to them quickly",
                    version = "1.0.0",
                    icon = Icons.Default.Bookmark,
                    isDefault = false,
                    category = PluginCategory.PRODUCTIVITY,
                ),
                WizardPluginInfo(
                    id = "ai.rever.boss.plugin.dynamic.topofmind",
                    name = "Top of Mind",
                    description =
                        "Keeps your most-used tabs one click away - helps your agent know what you're focused " +
                            "on",
                    version = "1.0.0",
                    icon = Icons.Default.Lightbulb,
                    isDefault = false,
                    category = PluginCategory.PRODUCTIVITY,
                ),
                // Automation
                WizardPluginInfo(
                    id = "ai.rever.boss.plugin.dynamic.llmrpa",
                    name = "LLM RPA",
                    description = "Let your agent automate repetitive UI tasks across websites and desktop apps",
                    version = "1.0.0",
                    icon = Icons.Default.Psychology,
                    isDefault = false,
                    category = PluginCategory.AUTOMATION,
                ),
                WizardPluginInfo(
                    id = "ai.rever.boss.plugin.dynamic.rparecorder",
                    name = "RPA Recorder",
                    description =
                        "Record your actions once and turn them into automation scripts your agent can " +
                            "replay",
                    version = "1.0.0",
                    icon = Icons.Default.Videocam,
                    isDefault = false,
                    category = PluginCategory.AUTOMATION,
                ),
                WizardPluginInfo(
                    id = "ai.rever.boss.plugin.dynamic.rpaengine",
                    name = "RPA Engine",
                    description =
                        "Runs recorded automation scripts - required if you want your agent to execute " +
                            "workflows",
                    version = "1.0.0",
                    icon = Icons.Default.PlayArrow,
                    isDefault = false,
                    category = PluginCategory.AUTOMATION,
                ),
                // Admin
                WizardPluginInfo(
                    id = "ai.rever.boss.plugin.dynamic.adminrolemanagement",
                    name = "Role Management",
                    description = "Control what each team member and agent is allowed to do inside BOSS",
                    version = "1.0.0",
                    icon = Icons.Default.ManageAccounts,
                    isDefault = false,
                    category = PluginCategory.ADMIN,
                ),
                WizardPluginInfo(
                    id = "ai.rever.boss.plugin.dynamic.rolecreation",
                    name = "Role Creation",
                    description = "Define custom permission sets for your team members and agents",
                    version = "1.0.0",
                    icon = Icons.Default.AdminPanelSettings,
                    isDefault = false,
                    category = PluginCategory.ADMIN,
                ),
            )
}
