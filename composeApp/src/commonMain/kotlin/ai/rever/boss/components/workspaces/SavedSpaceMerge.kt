package ai.rever.boss.components.workspaces

/*
 * Two defects, one cause: the Space list deduped SAVED files against the SHIPPED ones by NAME.
 *
 *     // Only add if not already in predefined list
 *     if (allWorkspaces.none { ws -> ws.name == workspaceWithId.name }) { … }
 *
 * - **A save made while the current Space was a built-in was silently discarded on relaunch.** The
 *   save wrote `Claude_Code.json` under the built-in's own name, and this dropped it in favour of
 *   the shipped entry on the next launch. The Save button exists so a modification survives, so a
 *   save that vanishes is the button not working. Reachable on Browser Only always, and on the
 *   other seven whenever no project was selected.
 * - **A user's own Space vanished if its name happened to match a built-in.** Hand-roll one called
 *   "Codex" and it was gone at the next launch, with nothing to say it had happened. Nothing to do
 *   with templates, and worse than the first because there was no hint at all.
 *
 * Both close by deduping on **id**: a saved file with a distinct id is a distinct Space whatever it
 * is called. The other half is [savedCopyOfSlot], which stops a save on a slot producing a
 * file with a built-in's id in the first place.
 */

/**
 * [base], or [base] with a number, so the result is not in [taken].
 *
 * **Cosmetic now, and deliberately kept.** It used to be load-bearing: the file path was
 * `generateFileName(name)`, so two Spaces sharing a name shared a file and the second save
 * destroyed the first layout. `WorkspaceFileManagerCommon.fileNameForId` closed that, so a
 * collision costs nothing - but two identical rows in a list the user picks from is still a bad
 * list, and the two paths that used to bypass this (a typed name, and
 * `materialisedTemplateName`) go through it now.
 *
 * Numbering starts at 2, because the unnumbered one is the first.
 *
 * **Only ever against other SAVED Spaces** - see [savedSpaceNames]. A Space is allowed to be
 * called "Code Review" while the shipped Code Review exists, because they are two sections of the
 * picker; numbering against the shipped names is what turned the four recovered Spaces into
 * "Code Review 2" on the first run of the round trip.
 */
internal fun uniqueWorkspaceName(
    base: String,
    taken: Set<String>,
): String {
    if (base !in taken) return base
    var suffix = 2
    while ("$base $suffix" in taken) suffix++
    return "$base $suffix"
}

/**
 * The names already taken by SPACES, which is what a new name has to avoid.
 *
 * **The shipped layouts are deliberately not in it.** A name is identity, and "Code Review" names
 * both a template BOSS ships and a Space of the user's built from it - they are two rows in two
 * sections of the picker, and neither has to give its name up. Two Spaces sharing a name is the
 * only collision worth numbering: it is a list the user picks from by name.
 */
internal fun savedSpaceNames(workspaces: List<LayoutWorkspace>): Set<String> =
    workspaces.filterNot { it.id in PredefinedWorkspaces.allIds }.map { it.name }.toSet()

/**
 * Whether the Space with [id] is the USER'S, so they may delete or rename it.
 *
 * **By id, and that is the fix.** It was `PredefinedWorkspaces.allWorkspaces.any { it.name == … }`
 * in four places, so a Space of the user's that happened to be called "Codex" was hidden from the
 * delete dialog and refused by the manager - the shipped Codex answers to that name, and after the
 * merge stopped keying on names those are two different Spaces.
 *
 * The session record is "the user's" here, which is not obviously right and is unchanged
 * behaviour: it was never a predefined NAME either, so the dialog has always listed it.
 */
internal fun isUserOwnedSpace(id: String): Boolean = id !in PredefinedWorkspaces.allIds

/**
 * The Spaces a delete or rename may act on.
 *
 * One definition, because there were two copies of this filter in `WorkspaceButton` - the menu
 * entry that opens the dialog and the dialog's own list - and they have to agree or the entry
 * appears for a list that turns out to be empty.
 */
internal fun deletableWorkspaces(workspaces: List<LayoutWorkspace>): List<LayoutWorkspace> {
    // A block body only because the expression form is 138 characters, over the line limit, while
    // ktlint requires an expression body to start on the signature's line.
    return workspaces.filter { isUserOwnedSpace(it.id) }
}

/**
 * Whether [id] names a SLOT rather than a document, so an explicit save has to write a new Space.
 *
 * Two kinds, and the second is the one that is easy to get wrong:
 *
 * - **The eight shipped layouts.** A template exists so you get your own copy; writing over it
 *   takes the pristine entry out of the picker's Templates section, and the write did not survive
 *   a relaunch anyway.
 * - **`last-session`.** The autosave record. **Its existing is not the same as the user's work
 *   being saved**: it is one slot, app-level, overwritten on every layout change and by every
 *   window, and nothing in it is addressable, nameable, or safe from the next session. Treating
 *   "the record matches the screen" as "saved" conflates a crash-recovery buffer with a document -
 *   and since every launch restores Last Session as the current Space, that conflation is the
 *   default state, not an edge case.
 *
 * `"Last Session"` must never become a saved Space's NAME either: `loadAllWorkspaces` finds the
 * record by that name, so a second claim on it would make which one restores a matter of scan
 * order.
 */
internal fun isSpaceSlot(id: String): Boolean = id in PredefinedWorkspaces.allIds || id == LAST_SESSION_ID

/**
 * The Space an explicit save should write when the current one is a [isSpaceSlot].
 *
 * **A new Space, not an overwrite.** [id] is a parameter because `LayoutWorkspace.generateId()` is
 * a clock read; the caller passes a fresh one. A NEW id is the load-bearing half - a file carrying
 * a slot's id is the legacy shape [mergeSavedWorkspaces] has to clean up after, and minting one is
 * what stops this creating more of them.
 *
 * The derived NAME depends on which kind of slot, because they have different things to say:
 *
 * - **a shipped layout is named after itself, with NO suffix.** A copy of Claude Code is called
 *   "Claude Code", and the shipped template of that name lives in the picker's Templates section,
 *   which is where the distinction belongs. It carried " (saved)", then " (custom)", then
 *   " (unsaved)" - three attempts at a word that should never have been there, the last of which
 *   sat in the Space button immediately left of the dot and the save button reporting the actual
 *   state. A name is identity; "unsaved" is a state, and the state has its own marks.
 * - **Last Session is named with the app's existing convention for a layout nobody named**,
 *   `"Workspace <epoch seconds>"`, which is exactly what the save path already produces for a
 *   window with no current Space at all. The record's own name says nothing about the layout in it,
 *   so there is nothing to name the copy after.
 *
 * [requestedName] is honoured when the user typed one ("Save Space..." asks), because they named
 * it - through [uniqueWorkspaceName], which it used to bypass. `"Last Session"` needs no special
 * refusal any more: the record is resolved by ID everywhere now, so a Space merely called that is
 * an ordinary Space.
 */
internal fun savedCopyOfSlot(
    current: LayoutWorkspace,
    id: String,
    now: Long,
    takenNames: Set<String>,
    requestedName: String? = null,
): LayoutWorkspace =
    current.copy(
        id = id,
        name = uniqueWorkspaceName(requestedName ?: baseNameForSlot(current, now), takenNames),
        timestamp = now,
    )

private fun baseNameForSlot(
    current: LayoutWorkspace,
    now: Long,
): String =
    if (current.id == LAST_SESSION_ID) {
        // The convention `BossAppMenuActionEffects` already uses for a window with no Space.
        "Workspace ${now / MILLIS_PER_SECOND}"
    } else {
        current.name
    }

private const val MILLIS_PER_SECOND = 1000L

/**
 * The Space list: the shipped layouts, then everything on disk that is a Space of the user's.
 *
 * Deduped by **id**, in three cases, and the third is a decision rather than a mechanism:
 *
 * - **A saved file with an id of its own is added.** This is every ordinary Space, and it is added
 *   whatever it is called - which is defect 2 above.
 * - **Two saved files with the SAME id: the newer `timestamp` wins.** Reachable by hand-copying a
 *   file, and reachable through the migration below: a legacy file adopted under a derived id, and
 *   then re-saved by the user, are two files claiming one id. The more recent write is the more
 *   recent intent.
 * - **A saved file whose id EQUALS a built-in's is ADOPTED as a distinct Space**, under a derived
 *   id and name, and the shipped layout stays. See below for why.
 *
 * **Why adopt rather than drop, and rather than let it replace the shipped entry.** These files
 * exist on disk right now - the old auto-save wrote the built-in's own id and name every two
 * seconds while you worked in one. On the machine this was written on, `~/Documents/BOSS/workspaces`
 * held four of them (`Browser_Only`, `Claude_Code`, `Code_Review`, `Gemini`), all with real
 * substituted paths and up to six tabs, and **all four were already being dropped on every launch**
 * by the name dedupe. So:
 *
 * - **Dropping** them by id would preserve exactly today's behaviour and lose layouts the user may
 *   have meant to keep - and there is no way to tell an auto-save dump from a deliberate save,
 *   because the old code wrote both identically.
 * - **Replacing** the shipped entry would take the pristine template out of the Templates section,
 *   which is the section's whole purpose, and would file a fully substituted layout under a
 *   built-in id - so the picker would call the user's own work a template.
 * - **Adopting** loses nothing and surfaces work that is currently invisible. Nobody's disk gets
 *   worse: the alternative for these files today is oblivion.
 *
 * The derived identity is **deterministic**, not generated: `<built-in id>-saved`, and the name
 * through [uniqueWorkspaceName]. A `generateId()` here would mint a different id for the same file
 * on every launch, so nothing could refer to that Space across a restart - the session set records
 * ids, and so does every preserved-state key. No built-in id ends in `-saved`, so the derived id
 * cannot collide with one, and the plugin's template set is the eight literal ids, so an adopted
 * Space files under Spaces where it belongs.
 *
 * **Nothing is rewritten on disk.** The migration is in memory, so a launch that reads a legacy
 * file cannot half-write anything, and the file keeps the name the user sees in the folder.
 */
internal fun mergeSavedWorkspaces(
    predefined: List<LayoutWorkspace>,
    saved: List<LayoutWorkspace>,
): List<LayoutWorkspace> {
    val builtInIds = predefined.map { it.id }.toSet()
    val merged = predefined.toMutableList()

    saved.forEach { file ->
        val adopted =
            if (file.id in builtInIds) {
                // The id is derived so it cannot collide with the shipped layout's; the NAME is
                // the file's own. "Code Review" is what this Space is called, and the shipped
                // Code Review sits in the Templates section, which is the distinction.
                file.copy(
                    id = file.id + ADOPTED_ID_SUFFIX,
                    name = uniqueWorkspaceName(file.name, savedSpaceNames(merged)),
                )
            } else {
                file
            }

        val existing = merged.indexOfFirst { it.id == adopted.id }
        when {
            existing < 0 -> merged.add(adopted)

            // Never over a shipped layout: an adopted id cannot equal one, so reaching here with a
            // built-in in that slot would mean two saved files disagreeing about a built-in's id.
            merged[existing].id in builtInIds -> Unit

            adopted.timestamp > merged[existing].timestamp -> merged[existing] = adopted

            else -> Unit
        }
    }

    return merged
}

/**
 * What a legacy file's id becomes when it is adopted.
 *
 * Deliberately not something `generateId()` could produce (`workspace-<epoch millis>`), so an
 * adopted id is recognisable as one and can never be mistaken for a Space the user saved normally.
 */

private const val ADOPTED_ID_SUFFIX = "-saved"

/**
 * [spaces] with each name replaced by the one the Space list currently carries for that id.
 *
 * **A name belongs to the Space catalogue, not to a layout snapshot.** A session set records whole
 * `LayoutWorkspace` values, so the name in it is whatever the Space was called on the day it was
 * written - and an adopted Space's name is DERIVED at load, so a user renaming a Space, or the
 * derivation itself changing (it has three times), leaves every set already on disk displaying the
 * old one for ever. Resolving by id at restore makes the set a record of layouts and lets the list
 * stay the single source of names.
 *
 * A Space the list does not know keeps its own name: it is the only name there is.
 */
internal fun withKnownNames(
    spaces: List<LayoutWorkspace>,
    known: List<LayoutWorkspace>,
): List<LayoutWorkspace> {
    val namesById = known.associate { it.id to it.name }
    return spaces.map { space -> namesById[space.id]?.let { space.copy(name = it) } ?: space }
}
