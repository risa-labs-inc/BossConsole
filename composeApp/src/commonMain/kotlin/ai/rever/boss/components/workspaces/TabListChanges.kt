package ai.rever.boss.components.workspaces

import ai.rever.boss.components.window_panel.SplitViewState
import androidx.compose.runtime.snapshotFlow
import com.arkivanov.decompose.value.Value
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * A signal every time a pane's TAB LIST changes: a tab added, closed, reordered, or moved in or out
 * of the pane.
 *
 * **This exists because `snapshotFlow` cannot see those.** `BossTabsComponent.tabsState` is a
 * Decompose `Value`, not Compose state, and `WorkspaceExtractor` reads it as
 * `node.tabsComponent.tabsState.value` - a plain property read that registers **no snapshot read**.
 * So the layout watcher's `snapshotFlow { extractCurrentWorkspace(…) }` re-emitted only when
 * something it really did observe changed: the split TREE (`_rootNode`, `mutableStateOf`), each
 * pane's `_pinnedCount` (also `mutableStateOf`), and each tab's own `title` / `currentUrl` /
 * `workingDirectory`. Adding a tab changes none of those, so nothing re-extracted, nothing was
 * marked unsaved, and nothing was written to the Last Session record until some later change
 * happened to trigger an extract.
 *
 * **The bridge is the one the app already uses.** `subscribeAsState()` is how
 * `BossBottomBar` and `BossMainWindowPanel` read this same `Value` inside a composition; a
 * `callbackFlow` over `Value.subscribe` is the same subscription outside one.
 *
 * **Observing the source rather than counting mutations.** A revision counter bumped where tabs
 * change would have to be bumped in every one of `addTab`, `removeTab`, `moveTab`,
 * `reorderWithinPanel`, `setPinnedCount`, `detachTab` and `adoptTab` - and one missed call site is
 * this same silent bug again, which is exactly how a whole class of change went unnoticed. The
 * subscription cannot miss a mutation, because the mutation is what publishes it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun SplitViewState.tabListChanges(): Flow<Unit> =
    // The panes themselves are Compose state, so THIS half is a snapshot read, and re-emitting on
    // a tree change is what re-subscribes: a pane created by a split has a tab list of its own, and
    // a subscription set up once at collection would never hear from it.
    snapshotFlow { getAllPanels().map { it.tabsComponent } }
        .flatMapLatest { components ->
            if (components.isEmpty()) {
                flowOf(Unit)
            } else {
                components.map { component -> component.tabsState.changes() }.merge()
            }
        }
        // The watcher answers with one re-extract however many panes changed at once - a
        // cross-pane move publishes twice, once either side.
        .conflate()

/**
 * [this] as a flow of its values.
 *
 * Decompose calls a new observer immediately with the current value, so collecting this emits once
 * up front. That is wanted: it is what makes the first extract happen when the watcher starts.
 */
private fun <T : Any> Value<T>.changes(): Flow<Unit> =
    callbackFlow {
        val cancellation = subscribe { trySend(Unit) }
        awaitClose { cancellation.cancel() }
    }.map { }
