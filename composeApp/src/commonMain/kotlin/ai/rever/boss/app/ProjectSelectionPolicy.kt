package ai.rever.boss.app

/**
 * Whether a project path a window just observed is a selection someone made, rather than
 * the one startup restored.
 *
 * Selecting a project is what triggers the default-workspace apply, and now the prompt for
 * one - but a Last Session restore *selects a project* on its way in:
 * `applyWorkspace(restoreProject = true)` calls `selectProject` with the path the saved
 * layout recorded. Nothing downstream could tell the two apart, so every launch that
 * restored a project re-applied a workspace over the layout that had just been restored.
 * With the default moved to "ask" that would have become a dialog on every launch, which
 * is what made this worth separating rather than living with.
 *
 * Compared by path rather than by a flag, because it has to survive the restore's
 * suspension points: `applyWorkspace` awaits plugin tab types and hops to IO, so the
 * selection is observed on a later frame than the one that started the restore. The caller
 * clears the recorded path once it matches, so deliberately re-opening the same project
 * later is a real selection and is treated as one.
 */
internal fun isUserProjectSelection(
    path: String,
    restoredProjectPath: String?,
): Boolean = path.isNotEmpty() && path != restoredProjectPath

/**
 * What the project-selection effect does with a newly observed [path], given the two "already
 * handled" marks a window can hold: the path a restore selected and the path a person placed by
 * answering "where should this open?".
 */
internal data class ProjectSelectionGate(
    /** Consult the default-Space setting. False when either mark already accounts for [path]. */
    val handle: Boolean,
    val restoredProjectPath: String?,
    val answeredProjectPath: String?,
)

/**
 * Consume whichever marks match [path], BOTH of them.
 *
 * Both, because they can hold the same path at once - a new window arriving with a project that a
 * restore also records. Clearing only the one checked first left the other behind, and a mark that
 * outlives its selection silently swallows the next real selection of that project.
 */
internal fun gateProjectSelection(
    path: String,
    restoredProjectPath: String?,
    answeredProjectPath: String?,
): ProjectSelectionGate {
    val restored = !isUserProjectSelection(path, restoredProjectPath)
    val answered = path == answeredProjectPath
    return ProjectSelectionGate(
        handle = !restored && !answered,
        restoredProjectPath = if (restored) null else restoredProjectPath,
        answeredProjectPath = if (answered) null else answeredProjectPath,
    )
}
