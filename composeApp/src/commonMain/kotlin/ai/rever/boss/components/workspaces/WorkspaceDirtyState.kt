package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.workspace.BreadcrumbConfig
import ai.rever.boss.plugin.workspace.SplitConfig.HorizontalSplit
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.plugin.workspace.SplitConfig.VerticalSplit

/*
 * Whether the Space on screen has changes that are not on disk.
 *
 * The comparison is the whole difficulty, and a naive `saved != live` is PERMANENTLY TRUE. That is
 * not a guess: the differences below were measured by extracting the same unchanged window twice,
 * and by applying a saved Space and extracting it straight back (see `WorkspaceDirtyStateTest`,
 * which reproduces both).
 *
 * Two extracts of a window nobody touched differ in:
 *
 *     id        workspace-1788834771145   vs   workspace-1788834771152
 *     timestamp 1788834771145             vs   1788834771152
 *
 * because `extractCurrentWorkspace` mints `LayoutWorkspace.generateId()` and reads the clock every
 * time it is called. It also always writes `name = "Current"` and
 * `description = "Current layout workspace"`, where the saved copy carries the Space's real ones -
 * so those three are the SAVED Space's identity, not something the live layout has an opinion
 * about, and a comparison that included them would mark every Space unsaved for ever.
 *
 * And the layout itself differs after a restore, in exactly one place:
 *
 *     saved panel ids   [main, split--1997346227960953891]
 *     live  panel ids   [main, split-2247261763438958480]
 *
 * with identical tabs, identical pinned counts and an identical split shape. `applyWorkspace`
 * DISCARDS the saved panel ids - it calls `clearAllPanels()` and then `splitPanel`, which mints a
 * fresh id per pane - so a saved id is a record of the session that wrote it and can never match
 * the session reading it. It is also unused on the way back in: restore maps panes by POSITION in
 * the tree, which is what makes normalising by position the right normalisation rather than a
 * convenience.
 *
 * What is NOT normalised, because it is real dirt the user would want saved: the split shape, the
 * tabs in each pane and their order, each pane's `pinnedCount`, and `projectPath` - a Space
 * remembers the project it was saved with, and switching project genuinely changes what it should
 * come back as.
 */

/**
 * How a pane is named for comparison: by its POSITION in the tree, depth-first.
 *
 * Not a hash and not a drop: two panes in one tree have to stay distinguishable, or a tab moved
 * from the left pane to the right one would compare equal to where it started.
 */
private const val PANE_PREFIX = "pane-"

/**
 * [this] with everything a comparison must ignore stripped, so two extracts of one unchanged
 * window are equal.
 *
 * `breadcrumbConfig` goes too, and it is the one entry here that is not measured but reasoned:
 * nothing in the app reads or writes it, so `extractCurrentWorkspace` can only ever produce the
 * default, and a hand-edited file carrying anything else would be permanently unsaved with no way
 * for the user to fix it - the Save button would write the default back and clear it, which is a
 * change nobody asked for.
 */
internal fun LayoutWorkspace.comparable(): LayoutWorkspace =
    copy(
        // The saved Space's identity, which the live layout does not carry.
        id = "",
        name = "",
        description = "",
        // A clock read per extract.
        timestamp = 0L,
        breadcrumbConfig = BreadcrumbConfig(),
        layout = layout.withPositionalPanelIds(),
    )

private fun SplitConfig.withPositionalPanelIds(): SplitConfig {
    var next = 0

    // Left/top FIRST, so the counter walks the tree in the order `applyWorkspace` builds it. Any
    // consistent order would make two trees of one shape compare equal; this one makes the numbers
    // mean what the restore does with them.
    fun walk(node: SplitConfig): SplitConfig =
        when (node) {
            is SinglePanel -> SinglePanel(node.panel.copy(id = "$PANE_PREFIX${next++}"))
            is VerticalSplit -> VerticalSplit(left = walk(node.left), right = walk(node.right))
            is HorizontalSplit -> HorizontalSplit(top = walk(node.top), bottom = walk(node.bottom))
        }

    return walk(this)
}

/**
 * Whether [live] differs from its [saved] copy, or has never been saved at all.
 *
 * A null [saved] is UNSAVED, and that is the case the flag exists for as much as the other:
 * `WorkspaceManager.workspaces` holds what is on disk, so a Space missing from it is one nothing
 * can restore. That covers a fresh window before anything has been saved, and a built-in TEMPLATE
 * applied as-is, whose entry in the list still carries `{projectPath}` where the live layout has
 * real paths.
 */
internal fun isUnsaved(
    live: LayoutWorkspace,
    saved: LayoutWorkspace?,
): Boolean = saved == null || saved.comparable() != live.comparable()
