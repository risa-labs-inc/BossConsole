package ai.rever.boss.filetypes

import ai.rever.boss.utils.DefaultHandlerState
import ai.rever.boss.utils.LinuxDefaultBrowserHandler
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A category and who currently owns it, for the Settings screen. */
internal data class DefaultAppStatus(
    val category: FileTypeCategory,
    val state: DefaultHandlerState,
)

/** What happened when BOSS tried to claim a category. */
internal sealed interface ClaimOutcome {
    /** BOSS is now the handler for everything in the category. */
    data object Claimed : ClaimOutcome

    /**
     * BOSS registered itself but the OS will not make it the default without the
     * user: Windows since 10 forbids writing the choice, and macOS refuses when
     * the app is not where Launch Services expects it.
     *
     * @property instruction what the user has to do, which differs per platform
     *   and cannot be worded generically. Windows opens a Settings page and the
     *   message points at it; macOS has no page for an arbitrary content type -
     *   the route is Finder's Get Info - and telling a mac user to look in System
     *   Settings would send them somewhere the option does not exist.
     */
    data class NeedsUserAction(
        val instruction: String,
    ) : ClaimOutcome

    data class Failed(
        val message: String,
    ) : ClaimOutcome
}

/**
 * Reads and sets the OS default handler for BOSS's file-type categories.
 *
 * The general form of [ai.rever.boss.utils.DefaultBrowserManager], which stays
 * as the browser-only facade its Settings card and tests already use. This is
 * what the new Default Apps screen talks to.
 *
 * Every call is suspending and runs on IO: on macOS a status is one native call
 * per type (55 for the source-code category), on Windows it is a `reg query` per
 * extension and on Linux an `xdg-mime` per MIME type. None of that belongs on the
 * UI thread - see docs/THREADING.md.
 */
internal object DefaultAppsManager {
    private val logger = BossLogger.forComponent("DefaultAppsManager")

    private val osName = System.getProperty("os.name").lowercase()
    private val isMacOS = osName.contains("mac")
    private val isWindows = osName.contains("windows")

    /**
     * Whether this build can report or change defaults at all.
     *
     * False on macOS when the Launch Services binding could not be loaded: the
     * screen then explains that instead of showing rows whose buttons do nothing.
     */
    fun isSupported(): Boolean =
        FileTypeCategories.isAvailable() &&
            when {
                isMacOS -> MacOSFileTypeHandler.isAvailable()
                else -> true
            }

    val categories: List<FileTypeCategory> get() = FileTypeCategories.categories

    /** Status for every category, in the resource's order. */
    suspend fun statuses(categories: List<FileTypeCategory> = this.categories): List<DefaultAppStatus> =
        withContext(Dispatchers.IO) {
            categories.map { category -> DefaultAppStatus(category, statusOf(category)) }
        }

    private fun statusOf(category: FileTypeCategory): DefaultHandlerState =
        try {
            when {
                isMacOS -> MacOSFileTypeHandler.statusOf(category)
                isWindows -> WindowsFileTypeHandler.statusOf(category)
                else -> LinuxFileTypeHandler.statusOf(category)
            }
        } catch (e: Exception) {
            // A status is decoration; a throw here would take the Settings screen
            // with it. Unknown is the honest answer and the screen shows it.
            logger.warn(
                LogCategory.SYSTEM,
                "Could not read a default-handler status",
                mapOf("category" to category.id),
                e,
            )
            DefaultHandlerState.Other(null)
        }

    /**
     * Claims [category] for BOSS.
     *
     * On Linux this also (re)writes the `.desktop` file through
     * `LinuxDefaultBrowserHandler`, because `xdg-mime default` only associates a
     * type the desktop entry already declares - so claiming a category without
     * that step records an association that can never match a file.
     */
    private suspend fun claim(category: FileTypeCategory): ClaimOutcome =
        withContext(Dispatchers.IO) {
            try {
                when {
                    isMacOS -> {
                        if (MacOSFileTypeHandler.setDefault(category)) {
                            ClaimOutcome.Claimed
                        } else {
                            // No System Settings page covers an arbitrary content
                            // type, so this names the route that does exist.
                            ClaimOutcome.NeedsUserAction(
                                "macOS refused some of these. In Finder, select a file of that kind, " +
                                    "press Command-I and use \"Open with\" then \"Change All\".",
                            )
                        }
                    }

                    isWindows -> {
                        // Registering is all BOSS may do; Windows 10+ verifies a
                        // hash on the UserChoice key and reverts anything written
                        // directly, so the user finishes in Settings.
                        //
                        // Deliberately does NOT open Settings here. This runs once
                        // per category and claimAll maps it over the selection, so
                        // taking the five-category first-run offer launched
                        // ms-settings:defaultapps five times. Opening it is the
                        // batch's job, once - see openSettingsForUserAction.
                        WindowsFileTypeHandler.register(category)
                        ClaimOutcome.NeedsUserAction(
                            "Windows does not let an app make itself the default. " +
                                "Choose BOSS under Default apps in the Settings window to finish.",
                        )
                    }

                    else -> {
                        LinuxDefaultBrowserHandler.ensureDesktopEntry()
                        if (LinuxFileTypeHandler.setDefault(category)) {
                            ClaimOutcome.Claimed
                        } else {
                            ClaimOutcome.NeedsUserAction(
                                "Some associations were refused. Check that xdg-utils is installed, " +
                                    "then set BOSS in your desktop's Default Applications.",
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error(
                    LogCategory.SYSTEM,
                    "Could not claim a file-type category",
                    mapOf("category" to category.id),
                    e,
                )
                ClaimOutcome.Failed(e.message ?: "Could not change the default application.")
            }
        }

    /**
     * Claims several categories, reporting the worst outcome, and opens the OS
     * settings page at most once if any of them needs the user.
     *
     * An empty batch is [ClaimOutcome.Failed], not [ClaimOutcome.Claimed]:
     * reporting "BOSS now opens every type" having claimed nothing is the kind of
     * false success a third call site inherits without noticing. Both current
     * callers guard on a non-empty list, so this is a guard rather than a
     * behaviour change.
     */
    suspend fun claimAll(categories: List<FileTypeCategory>): ClaimOutcome {
        if (categories.isEmpty()) return ClaimOutcome.Failed("There was nothing to change.")

        val outcomes = categories.map { claim(it) }
        val worst =
            outcomes.firstOrNull { it is ClaimOutcome.Failed }
                ?: outcomes.firstOrNull { it is ClaimOutcome.NeedsUserAction }
                ?: ClaimOutcome.Claimed
        if (worst is ClaimOutcome.NeedsUserAction) openSettingsForUserAction()
        return worst
    }

    /**
     * Claims one category and opens the settings page if it needs the user.
     *
     * The single-row entry point, so a row's Set button behaves like the batch
     * without every caller having to remember the second step.
     */
    suspend fun claimOne(category: FileTypeCategory): ClaimOutcome {
        val outcome = claim(category)
        if (outcome is ClaimOutcome.NeedsUserAction) openSettingsForUserAction()
        return outcome
    }

    /**
     * Opens the OS page where the user finishes the job, on the platforms that
     * have one. Called at most once per user action - see [claimAll].
     */
    private fun openSettingsForUserAction() {
        if (isWindows) WindowsFileTypeHandler.openDefaultAppsSettings()
        // macOS has no settings page for an arbitrary content type (the route is
        // Finder's Get Info) and Linux has no single page at all, so on both the
        // instruction text is the whole of the guidance.
    }
}
