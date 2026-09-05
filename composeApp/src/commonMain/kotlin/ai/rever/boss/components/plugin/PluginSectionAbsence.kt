package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PluginState

/**
 * Why a plugin-backed settings section has nothing to render.
 *
 * Every member is a different sentence and a different thing to do about it, which is the whole
 * point: the sections used to report all of these as "isn't loaded yet", and that is true of
 * exactly one of them. A plugin that was never installed, that the user switched off, that the
 * host rejected, or that they cannot access, does not arrive however long they look at it.
 *
 * **`PluginState.DISABLED` alone is not enough to tell them apart.** `DynamicPluginManager` writes
 * it for at least three unrelated reasons - `disablePlugin`, a registration that failed as binary
 * incompatible, and a plugin hidden because the user lacks access - and "enable it in the Toolbox"
 * is only true for the first. The other two send the user to look for a row that either needs an
 * update or is not listed for them at all.
 */
internal enum class PluginSectionAbsence {
    /** Not on this machine. The only case that gets an Install button. */
    NOT_INSTALLED,

    /** The user switched it off. It will not register until they turn it back on. */
    DISABLED,

    /** The host rejected it as binary incompatible. It needs a newer build, not a switch. */
    INCOMPATIBLE,

    /** The user cannot access it, so the host never calls `register()`. An admin has to act. */
    NO_ACCESS,

    /** Loaded, and this version serves no panel for this section. It needs an update. */
    NO_PANEL,

    /** The host recorded a load failure. Not a wait either. */
    FAILED,

    /** Present and on its way: startup registration is asynchronous. The one honest wait. */
    STARTING,

    /** Not answerable here - no active manager, or the installer factory is not wired yet. */
    UNKNOWN,
}

/**
 * Which absence a plugin is in, as a pure function of everything that decides it.
 *
 * **The order is the whole thing**, and two steps of it are load-bearing:
 *
 * - `missingPermissions` is asked **first**. An inaccessible plugin is also recorded DISABLED, so
 *   asking the state first tells a user to switch on a plugin that is not listed for them.
 * - `isIncompatible` and `isDisabled` are asked **before** `installed`. `MissingPluginOffer
 *   .isInstalled` counts both as installed - the jar is on disk, and `MissingDependencyInstaller`
 *   documents that deliberately - so the other order puts an Install button in front of someone
 *   whose problem a download cannot fix, and pressing it fetches a jar they already have. That is
 *   not hypothetical: `MissingPluginOffer.isInstalled` records the same mistake shipping here with
 *   the bookmarks shelf.
 *
 * A separate function from the composable because this is the decision worth testing, and a
 * composable that reads three global singletons is not reachable from a unit test.
 *
 * @param installed the Install button's own predicate ([ai.rever.boss.components.plugin
 *   .MissingPluginOffer.isInstalled]), where null means "cannot answer" - which is different from
 *   "no" and must not become an offer
 * @param state the manager's recorded state, or null when it has no entry for this plugin
 * @param isIncompatible whether the host recorded it as binary incompatible
 * @param missingPermissions non-empty when the user cannot access it at all
 * @param servesNoPanel true when the plugin's API is present but serves no panel for this section,
 *   which only the section can know - it is the one input the manager cannot answer
 */
internal fun pluginSectionAbsence(
    installed: Boolean?,
    state: PluginState?,
    isIncompatible: Boolean,
    missingPermissions: List<String>,
    servesNoPanel: Boolean,
): PluginSectionAbsence =
    when {
        missingPermissions.isNotEmpty() -> PluginSectionAbsence.NO_ACCESS
        isIncompatible -> PluginSectionAbsence.INCOMPATIBLE
        state == PluginState.DISABLED -> PluginSectionAbsence.DISABLED
        installed == false -> PluginSectionAbsence.NOT_INSTALLED
        state == PluginState.ERROR -> PluginSectionAbsence.FAILED
        servesNoPanel -> PluginSectionAbsence.NO_PANEL
        installed == true -> PluginSectionAbsence.STARTING
        else -> PluginSectionAbsence.UNKNOWN
    }

/**
 * What the section says, as a pure function so the wording of each case is pinned too.
 *
 * `when (absence)` over the enum rather than a chain of `==`: a member added without a sentence
 * should be a compile error, not a silent fall-through to "isn't loaded yet", which is the failure
 * this whole type exists to end.
 *
 * @param what a **plural** noun phrase for the thing the user came here for ("AI provider
 *   settings", "Editor settings"), because every sentence below reads "$what are provided by"
 */
internal fun pluginSectionMessage(
    absence: PluginSectionAbsence,
    what: String,
    pluginName: String,
    missingPermissions: List<String>,
): String {
    val provided = "$what are provided by the $pluginName plugin"
    return when (absence) {
        PluginSectionAbsence.NOT_INSTALLED -> {
            "$provided, which is not installed."
        }

        PluginSectionAbsence.DISABLED -> {
            "$provided, which is installed but switched off. Enable it in the Toolbox."
        }

        PluginSectionAbsence.INCOMPATIBLE -> {
            "$provided, which this version of BOSS could not load. Update it in the Toolbox."
        }

        PluginSectionAbsence.NO_ACCESS -> {
            "$provided, which you do not have access to. " +
                "Ask an administrator to grant: ${missingPermissions.joinToString(", ")}."
        }

        PluginSectionAbsence.NO_PANEL -> {
            "$provided, and the installed version does not provide this panel. " +
                "Update it in the Toolbox."
        }

        PluginSectionAbsence.FAILED -> {
            "$provided, which failed to load. See the BOSS log for why."
        }

        PluginSectionAbsence.STARTING, PluginSectionAbsence.UNKNOWN -> {
            "$provided, which isn't loaded yet."
        }
    }
}

/**
 * Whether the section offers to install the plugin.
 *
 * One expression, named, because it is asked in two places that must agree - the button's
 * visibility and what the press does - and because it is the single cell of the matrix where a
 * download is the answer.
 */
internal fun pluginSectionOffersInstall(absence: PluginSectionAbsence): Boolean {
    // A block body, not an expression one: ktlint wants the expression on the signature's line
    // and detekt then calls that line too long. This is the shape both accept.
    return absence == PluginSectionAbsence.NOT_INSTALLED
}
