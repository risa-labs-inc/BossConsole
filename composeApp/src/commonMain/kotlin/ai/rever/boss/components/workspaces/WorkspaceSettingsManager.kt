package ai.rever.boss.components.workspaces

import ai.rever.boss.utils.SystemUtils
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * Settings for workspace behavior.
 */
@Serializable
data class WorkspaceSettings(
    /**
     * What happens to the layout when a project is selected: a predefined workspace id,
     * [ASK_WORKSPACE_ID] to be asked which one, or [NO_WORKSPACE_ID] to be left alone.
     */
    val defaultWorkspaceId: String = ASK_WORKSPACE_ID,
    /**
     * What happens to the workspace you are leaving when you switch to another one:
     * [SWITCH_ASK], [SWITCH_KEEP] or [SWITCH_CLOSE].
     */
    val onWorkspaceSwitch: String = SWITCH_ASK,
    /**
     * Schema version of this file, used to apply one-time migrations to installs
     * that already have a settings file written by an older build.
     *
     * The default is deliberately 0, not [CURRENT_SETTINGS_VERSION]: a missing key
     * decodes to the default, and every file written before this field existed is
     * missing it. The manager stamps the current version when it writes.
     */
    val settingsVersion: Int = 0,
) {
    companion object {
        /**
         * Never apply a workspace on its own. The window keeps whatever is open.
         *
         * Distinct from [ASK_WORKSPACE_ID], and kept working exactly as it always has:
         * someone who set "None" asked not to be interrupted, and turning that into a
         * prompt would be a different answer to the question they already answered.
         */
        const val NO_WORKSPACE_ID = "none"

        /**
         * Start with no workspace, and ask which one to open when a project is selected.
         *
         * The default on every platform. BOSS used to come up on a layout nobody chose -
         * Claude Code everywhere, browser-only on Windows - so a terminal running an agent,
         * or a browser tab, appeared before the user had said what they were doing. Now
         * nothing is applied until someone picks.
         */
        const val ASK_WORKSPACE_ID = "ask"

        /**
         * Ask, on each switch, whether to keep the workspace being left running.
         *
         * The default, because the choice has a cost that is invisible either way. Keeping a
         * workspace running keeps its whole split tree alive - live tab components, and for
         * browser tabs live Chromium - and nothing on screen said so; closing it throws away
         * state that took work to arrange. Neither is safe to pick on someone's behalf, and
         * "Don't ask again" turns this into whichever they chose.
         */
        const val SWITCH_ASK = "ask"

        /** Keep it running, so switching back is instant. What every build before this one did. */
        const val SWITCH_KEEP = "keep"

        /** Close it, freeing its tabs. Switching back rebuilds the workspace from its layout. */
        const val SWITCH_CLOSE = "close"

        /**
         * Bump when a migration is added to [WorkspaceSettingsMigrations.migrate].
         * 1: Windows moves from the Claude Code default to browser-only.
         * 2: every platform moves off its built-in default to [ASK_WORKSPACE_ID].
         */
        const val CURRENT_SETTINGS_VERSION = 2

        /**
         * The workspace a build older than version 2 applied on its own, per platform.
         *
         * Only referenced by the 1 -> 2 migration, which needs to tell "this install is
         * sitting on a default nobody chose" apart from "this user picked Claude Code".
         * It cannot do that perfectly - see [WorkspaceSettingsMigrations.migrate] - but it
         * can leave every other pick alone.
         */
        fun previousPlatformDefault(isWindows: Boolean): String =
            if (isWindows) PredefinedWorkspaces.BROWSER_ONLY_ID else PredefinedWorkspaces.CLAUDE_CODE_ID
    }
}

/**
 * One-time migrations for [WorkspaceSettings] files written by older builds.
 * Kept in commonMain (and pure) so it is directly testable.
 */
object WorkspaceSettingsMigrations {
    /** The version whose only step was the Windows browser-only default. */
    private const val VERSION_WINDOWS_BROWSER_ONLY = 1

    /**
     * Returns the settings to use, or null when the file is already current.
     *
     * A non-null result does not mean the default moved: every out-of-date file is
     * returned with the version stamped, whether or not anything else changed. The
     * caller distinguishes the two (see `DesktopWorkspaceSettingsManager`).
     *
     * Both steps share one limitation. A file records *what* the default is, never *who*
     * set it, so an install sitting on the value its build shipped cannot be told apart
     * from a user who picked that same value deliberately. Both steps therefore move such
     * an install once, deliberately, and preserve every other value - including
     * [WorkspaceSettings.NO_WORKSPACE_ID]. Each step runs at most once, because the
     * migrated file records the new version.
     *
     * - **0 -> 1** rewrites a Windows install on the old universal Claude Code default.
     * - **1 -> 2** rewrites an install on whichever default its platform shipped with
     *   (browser-only on Windows, Claude Code elsewhere) to
     *   [WorkspaceSettings.ASK_WORKSPACE_ID].
     *
     * The steps run in order against one value rather than as exclusive branches, so a
     * pre-v1 Windows file passes through browser-only on its way to "ask" and a single
     * launch moves a never-updated install all the way forward.
     */
    fun migrate(
        loaded: WorkspaceSettings,
        isWindows: Boolean = SystemUtils.isWindows,
    ): WorkspaceSettings? {
        if (loaded.settingsVersion >= WorkspaceSettings.CURRENT_SETTINGS_VERSION) return null

        var id = loaded.defaultWorkspaceId
        if (loaded.settingsVersion < VERSION_WINDOWS_BROWSER_ONLY &&
            isWindows &&
            id == PredefinedWorkspaces.CLAUDE_CODE_ID
        ) {
            id = PredefinedWorkspaces.BROWSER_ONLY_ID
        }
        if (id == WorkspaceSettings.previousPlatformDefault(isWindows)) {
            id = WorkspaceSettings.ASK_WORKSPACE_ID
        }

        return loaded.copy(
            defaultWorkspaceId = id,
            settingsVersion = WorkspaceSettings.CURRENT_SETTINGS_VERSION,
        )
    }
}

/**
 * What a window should do with its layout when a project is selected.
 *
 * A three-way answer, not a nullable workspace, because "apply nothing" now has two
 * meanings that must not collapse into each other: [Ask] leaves the window empty and
 * puts the choice in front of the user, [None] leaves it empty and says nothing. The
 * old `LayoutWorkspace?` could only express the second.
 */
sealed interface ProjectSelectionWorkspace {
    /** Apply [workspace], the way every build before this one applied its platform default. */
    data class Apply(
        val workspace: LayoutWorkspace,
    ) : ProjectSelectionWorkspace

    /** Ask which workspace to open. The default - see [WorkspaceSettings.ASK_WORKSPACE_ID]. */
    data object Ask : ProjectSelectionWorkspace

    /** Leave the window exactly as it is. */
    data object None : ProjectSelectionWorkspace
}

/**
 * Resolve [WorkspaceSettings.defaultWorkspaceId] into what should actually happen.
 *
 * An id that matches no predefined workspace resolves to [ProjectSelectionWorkspace.None],
 * which is what the previous nullable lookup did: a stale id left over from a build that
 * shipped a workspace this one does not must not turn into a prompt.
 */
fun WorkspaceSettings.resolveOnProjectSelection(): ProjectSelectionWorkspace =
    when (defaultWorkspaceId) {
        WorkspaceSettings.ASK_WORKSPACE_ID -> {
            ProjectSelectionWorkspace.Ask
        }

        WorkspaceSettings.NO_WORKSPACE_ID -> {
            ProjectSelectionWorkspace.None
        }

        else -> {
            PredefinedWorkspaces.allWorkspaces
                .find { it.id == defaultWorkspaceId }
                ?.let(ProjectSelectionWorkspace::Apply)
                ?: ProjectSelectionWorkspace.None
        }
    }

/**
 * What to do with the workspace being left, when switching to another.
 *
 * Three answers rather than a boolean, because "ask" is a real answer and not the absence of
 * one - same reason [ProjectSelectionWorkspace] has an [ProjectSelectionWorkspace.Ask] member.
 */
enum class WorkspaceSwitchAction {
    /** Put the choice in front of the user. */
    ASK,

    /** Preserve it: its tabs stay live and switching back is instant. */
    KEEP,

    /** Tear it down: its tabs close, and switching back rebuilds from the saved layout. */
    CLOSE,
}

/**
 * Resolve [WorkspaceSettings.onWorkspaceSwitch] into what should actually happen.
 *
 * An unrecognised value resolves to [WorkspaceSwitchAction.ASK] rather than silently keeping or
 * closing: a settings file written by a build this one does not know about must not decide, on
 * its own, to throw away someone's tabs.
 */
fun WorkspaceSettings.resolveOnWorkspaceSwitch(): WorkspaceSwitchAction =
    when (onWorkspaceSwitch) {
        WorkspaceSettings.SWITCH_KEEP -> WorkspaceSwitchAction.KEEP
        WorkspaceSettings.SWITCH_CLOSE -> WorkspaceSwitchAction.CLOSE
        else -> WorkspaceSwitchAction.ASK
    }

/**
 * Manager for workspace settings.
 * Handles persistence and retrieval of workspace configuration.
 */
expect object WorkspaceSettingsManager {
    /**
     * Current workspace settings as a reactive flow.
     */
    val currentSettings: StateFlow<WorkspaceSettings>

    /**
     * Save current settings to persistent storage.
     */
    suspend fun saveSettings()

    /**
     * Update settings and persist.
     */
    suspend fun updateSettings(settings: WorkspaceSettings)

    /**
     * Update the default workspace ID.
     */
    suspend fun setDefaultWorkspaceId(workspaceId: String)

    /** Update what happens to the workspace being left on a switch. */
    suspend fun setOnWorkspaceSwitch(behaviour: String)

    /**
     * The workspace to apply on its own, or null when the setting is
     * [WorkspaceSettings.ASK_WORKSPACE_ID] / [WorkspaceSettings.NO_WORKSPACE_ID].
     *
     * Callers that need to tell those two apart - anything that can put a prompt on
     * screen - want [resolveOnProjectSelection] instead.
     */
    fun getDefaultWorkspace(): LayoutWorkspace?
}
