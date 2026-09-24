package ai.rever.boss.components.workspaces

import kotlin.random.Random
import kotlin.time.Clock

/**
 * A fresh id for a Space that arrived without one.
 *
 * Hand-written and agent-authored workspace JSON commonly omits `id`, which
 * `LayoutWorkspace` then defaults to "". `LayoutWorkspace.generateId` alone is
 * timestamp-only, so two id-less files imported or scanned in the same
 * millisecond would share an id - and therefore one file, since the id is the
 * file's name. The random suffix is what makes same-millisecond mints
 * distinct. This mints only at the workspace site; the shared
 * timestamp-collision fix belongs to `generateId` itself (#1334/#1336), so do
 * not move this into it.
 */
internal fun mintWorkspaceId(): String {
    val timestamp = Clock.System.now().toEpochMilliseconds()
    return "workspace-$timestamp-${Random.nextLong().toULong().toString(16)}"
}

/**
 * [this] with an identity: unchanged when it has one, given a minted one when
 * it does not.
 *
 * A blank id is not a naming detail. `WorkspaceFileManagerCommon.fileNameForId`
 * maps it to the literal file `.json`, which every blank-id Space would share -
 * a second id-less import destroyed the first - and an id re-minted on every
 * load never matches the session-set ids or preserved-state keys recorded
 * under the previous one. Callers that mint through here must persist the
 * result back into the file once, so the identity stays stable across
 * launches.
 */
internal fun LayoutWorkspace.withStableId(): LayoutWorkspace = if (id.isBlank()) copy(id = mintWorkspaceId()) else this
