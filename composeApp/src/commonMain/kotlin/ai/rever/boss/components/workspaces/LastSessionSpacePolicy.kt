package ai.rever.boss.components.workspaces

/** Hide only the recovery slot, never a user-created Space with the same display name. */
internal fun visibleSessionSpaces(
    spaces: List<LayoutWorkspace>,
    enabled: Boolean,
): List<LayoutWorkspace> = spaces.filter { enabled || it.id != LAST_SESSION_ID }

/** Old recovery records have lost the original Space id. Keep their layout under a new identity. */
internal fun sessionSpaceIdentity(
    workspace: LayoutWorkspace,
    enabled: Boolean,
    newId: () -> String = LayoutWorkspace::generateId,
): LayoutWorkspace =
    if (!enabled && workspace.id == LAST_SESSION_ID) {
        workspace.copy(id = newId(), name = "Recovered Space")
    } else {
        workspace
    }

/** Repeated legacy restores update one recovery Space instead of minting another. */
internal fun reusableSessionSpaceIdentity(
    workspace: LayoutWorkspace,
    enabled: Boolean,
    known: List<LayoutWorkspace>,
): LayoutWorkspace {
    val recovery =
        known
            .filter { it.name == "Recovered Space" && it.id != LAST_SESSION_ID }
            .maxByOrNull { it.timestamp }
    return sessionSpaceIdentity(workspace, enabled) { recovery?.id ?: "space-recovered-session" }
}

/** Recognize recovery copies created by the old restore path, without hiding user namesakes. */
internal fun isRecoverySnapshot(space: LayoutWorkspace): Boolean =
    space.id == LAST_SESSION_ID || space.id == "space-recovered-session" ||
        (
            space.description == "Automatically saved session" &&
                Regex("""Recovered Space(?: \(\d+\))?""").matches(space.name)
        )

/** Recovery contributes its live layout to the default Space, never a new saved version. */
internal fun recoverIntoDefaultSpace(
    workspace: LayoutWorkspace,
    defaultSpace: LayoutWorkspace,
): LayoutWorkspace =
    if (isRecoverySnapshot(workspace)) {
        workspace.copy(id = defaultSpace.id, name = defaultSpace.name)
    } else {
        workspace
    }
