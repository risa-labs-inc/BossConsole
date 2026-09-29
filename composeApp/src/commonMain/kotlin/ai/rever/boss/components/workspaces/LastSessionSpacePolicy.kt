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
