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
 * is called. The other half is [savedCopyOfBuiltIn], which stops a save on a built-in producing a
 * file with a built-in's id in the first place.
 */

/**
 * How a copy of a shipped layout is named: the layout's name and this.
 *
 * Not a project, and that constraint is the same one the pick makes: `spaceToOpen` refuses to name
 * a materialised template after a project the layout does not reference, and a copy of Browser
 * Only has no project at all. A suffix is acceptable here where a counter was not acceptable at
 * pick time, because an explicit save is the user asking to keep this thing rather than something
 * happening on every pick.
 */
const val SAVED_COPY_SUFFIX = " (saved)"

/**
 * [base], or [base] with a number, so the result is not in [taken].
 *
 * The Space list is keyed by NAME in two places that matter - `WorkspaceManager` writes to
 * `generateFileName(name)` and replaces the list entry whose name matches - so two entries sharing
 * a name means one of them cannot be saved without destroying the other. This is what keeps a
 * derived name from walking into that.
 *
 * Numbering starts at 2, because the unnumbered one is the first.
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
 * The Space an explicit save should write when the current one is a SHIPPED layout.
 *
 * **A new Space, not an overwrite**, which is what a template is for: the shipped entry stays
 * pristine in the picker's Templates section and the user gets their own copy of it. Writing over
 * it was what the old behaviour did, and the write did not survive a relaunch.
 *
 * [id] is a parameter because `LayoutWorkspace.generateId()` is a clock read; the caller passes a
 * fresh one. A NEW id is the load-bearing half - a file carrying a built-in's id is the legacy
 * shape [mergeSavedWorkspaces] has to clean up after, and minting one is what stops this creating
 * more of them.
 *
 * [requestedName] is honoured when the user typed one ("Save Space..." asks), because they named
 * it; only the automatic case derives a name. A typed name that collides with a built-in's is
 * their business and survives now, since the merge keys on id.
 */
internal fun savedCopyOfBuiltIn(
    current: LayoutWorkspace,
    id: String,
    now: Long,
    takenNames: Set<String>,
    requestedName: String? = null,
): LayoutWorkspace =
    current.copy(
        id = id,
        name = requestedName ?: uniqueWorkspaceName(current.name + SAVED_COPY_SUFFIX, takenNames),
        timestamp = now,
    )

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
                file.copy(
                    id = file.id + ADOPTED_ID_SUFFIX,
                    name = uniqueWorkspaceName(file.name + SAVED_COPY_SUFFIX, merged.map { it.name }.toSet()),
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
