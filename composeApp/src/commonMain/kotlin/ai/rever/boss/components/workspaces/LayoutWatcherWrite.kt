package ai.rever.boss.components.workspaces

/**
 * What the layout watcher does when the layout on screen changes.
 *
 * **A named Space is written only by an explicit save**, and that is the whole point of this
 * function. The watcher used to write the Space you were working in every two seconds, which made
 * "unsaved" a state that lasted two seconds and cleared itself - so a save button driven off it
 * could never be pressed in time to matter, and the file on disk followed every experiment. Editor
 * semantics instead: the buffer is dirty until you save, and the file stays at its last explicit
 * save.
 *
 * **What the watcher still writes, on the same cadence as before, is the Last Session record.**
 * That is not a weakening of anything - it is stronger than what it replaced. Before, working in a
 * named Space wrote that Space and left `Last_Session.json` stale from whenever the window last
 * had no Space; now the recovery record tracks the live layout whichever Space is on screen. It is
 * the only thing standing between an unsaved layout and a crash (the multi-Space set is written at
 * shutdown, which a hard kill does not reach), so it is written more often than before and never
 * less.
 *
 * Two things come out of one function because they have to agree, and the second is easy to lose:
 *
 * - **[record]** is the Last Session record, and the ONLY file the watcher touches.
 * - **[current]** is what the manager should hold as the window's current Space: the live layout
 *   under the identity it already has. `WorkspaceManager.currentWorkspace` is an in-memory notion -
 *   "the Space this window is in, as it looks now" - and things read the layout off it. The plugin
 *   api's `WorkspaceDataProvider.saveCurrentWorkspace(name)` is the one that matters: a plugin
 *   cannot reach the split tree, so Top of Mind's Save button saves whatever the manager holds. If
 *   the watcher stopped refreshing this along with the file, that button would silently save the
 *   layout as of load time. So the in-memory copy keeps tracking the live layout; only the WRITE
 *   went away.
 *
 * That split is also why the unsaved flag compares against `WorkspaceManager.savedCopyOf` - the
 * list of what is on DISK - rather than against `currentWorkspace`, which now deliberately runs
 * ahead of the file.
 */
internal data class LayoutWatcherWrite(
    /** What the manager should hold as the current Space: the live layout, existing identity. */
    val current: LayoutWorkspace,
    /** The Last Session record to write. The only file the watcher writes. */
    val record: LayoutWorkspace,
)

/**
 * [LayoutWatcherWrite] for a window showing [current] whose live layout is [live].
 *
 * A null [current], or one that IS Last Session, adopts the record as the current Space - which is
 * how a window that has never loaded a Space comes to be "in" Last Session, and the behaviour that
 * was already here for both of those cases. A NAMED Space keeps its own id, name and description
 * and takes only the layout, so the watcher can never rename the Space someone is working in.
 */
internal fun layoutWatcherWrite(
    current: LayoutWorkspace?,
    live: LayoutWorkspace,
    now: Long,
): LayoutWatcherWrite {
    val record = asLastSession(live).copy(timestamp = now)
    return LayoutWatcherWrite(
        current =
            if (current == null || current.name == LAST_SESSION_NAME) {
                record
            } else {
                current.copy(layout = live.layout, timestamp = now)
            },
        record = record,
    )
}
