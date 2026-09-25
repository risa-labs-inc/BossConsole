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

/**
 * The single-Space session record in the name a pre-[WorkspaceFileManagerCommon.fileNameForId]
 * install wrote it under - the name `RecoveredSpacesRoundTripTest`'s fixture carries, so real
 * disks hold it. No constant exists for it because the running code reaches the file only
 * through `WorkspaceManager.loadedFileNames`; this gate needs it directly because it sits in
 * the same directory the MCP create path and an import write into - and so does
 * [WorkspaceFileManagerCommon.reservedRecordFileNames] - and on a case-insensitive filesystem
 * it IS the file [WorkspaceFileManagerCommon.fileNameForId] of [LAST_SESSION_ID] resolves to.
 */
internal const val LEGACY_LAST_SESSION_FILE = "Last_Session.json"

/**
 * The reserved workspace-store file that saving a Space with caller-chosen id [id] would
 * overwrite, or null when [id] is an ordinary Space id (#926).
 *
 * `WorkspaceManager`'s load scan deliberately skips [LAST_SESSION_SET_FILE] and
 * [SPACE_THEMES_FILE] by name: they are records that live beside the Spaces without being one.
 * Those records therefore never deserialize as a Space, so an open_workspace with
 * `createIfAbsent` and a matching id falls through every lookup into the CREATE branch and
 * saves `<id>.json` straight over the theme store or the session record - silently, because
 * the file was never in the Space list to begin with. The session-set record breaks session
 * restore on the next launch; the themes record wipes every Space theme assignment. The two
 * single-Space record names are in the set for the same reason from the other direction: they
 * DO parse as Spaces, but on a case-insensitive filesystem (NTFS, default APFS) a caller-chosen
 * id differing only in case or underscores-dashes resolves to the record's file anyway.
 *
 * The file name is derived with the SAME [WorkspaceFileManagerCommon.fileNameForId] the save
 * uses, so no id slips past on a technicality: every id that writes a reserved file is caught,
 * and ids that merely RESEMBLE one (`Space__Themes`, with its double underscore) pass. The
 * caller's own `.json` suffix is stripped first, because the load path treats a suffixed id as
 * that file name and the save gate answers the same question rather than the accidental
 * `<id>.json.json` the raw id would produce. Comparison is case-insensitive for the filesystem
 * reason above; on a case-SENSITIVE filesystem that makes the gate stricter than the collision,
 * which is the safe side to err on.
 *
 * The list lives in [WorkspaceFileManagerCommon.reservedRecordFileNames] - the scan's skips
 * plus the session-record spellings - so the MCP refusal and the import gate consult one list
 * and cannot drift apart. A new DOCUMENT record joins it exactly when it becomes a scan skip;
 * a session-record spelling joins it without one.
 */
internal fun reservedWorkspaceStoreFileName(id: String): String? {
    val stem = id.removeSuffix(".json")
    if (stem.isEmpty()) return null
    val fileName = WorkspaceFileManagerCommon.fileNameForId(stem)
    return WorkspaceFileManagerCommon.reservedRecordFileNames.firstOrNull { it.equals(fileName, ignoreCase = true) }
}

/**
 * [this] with an identity it is safe to import under: [withStableId], and additionally a fresh id
 * when the one it carries is a slot or would save over a reserved record.
 *
 * `importWorkspace` (Open from File) saved a Space under whatever id its JSON carried. The MCP
 * create path refuses a slot id and a reserved file name (#926); the import had neither guard. So
 * a file whose id is `Space_Themes` overwrote the theme store and wiped every Space's theme, one
 * whose id is `Last_Session_Set` broke session restore, and one carrying `last-session` replaced
 * the crash-recovery record - which is exactly what an exported Last Session carries, so opening
 * one on another machine replaced THAT machine's record.
 *
 * Re-minted rather than refused, because an import is a person asking for a layout: the id is an
 * implementation detail they neither chose nor see, and a fresh one gives them the layout without
 * destroying anything. A refusal would be the MCP path's answer, where the caller chose the id.
 *
 * NOT folded into [withStableId]: the load scan uses that, and the real session record carries
 * `last-session` legitimately - re-minting it there would orphan the record on every launch.
 */
internal fun LayoutWorkspace.withImportableId(): LayoutWorkspace {
    val stable = withStableId()
    return if (isSpaceSlot(stable.id) || reservedWorkspaceStoreFileName(stable.id) != null) {
        stable.copy(id = mintWorkspaceId())
    } else {
        stable
    }
}
