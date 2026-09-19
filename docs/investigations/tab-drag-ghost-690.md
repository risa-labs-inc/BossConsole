# Tab ghost stuck to pointer - #690

Issue: https://github.com/risa-labs-inc/BossConsole/issues/690

## Live evidence

The user reported a persistent tab ghost after moving tabs between panes on macOS on
2026-09-14. BOSS MCP `console_search(query="drag", limit=100)` found no drag messages.
The recent console tail did not identify a drag-related exception. The app-window
screenshot showed compact favicon strips above editor/browser panes; it excluded the
separate native ghost window. The exact input sequence of that incident is not recorded.

## Reproduced cause

`TabFaviconChip.tabChipDrag` keys `pointerInput` on the tab, panel and tab index. A
changed index restarts its coroutine. `detectDragGestures` does not invoke the normal
end/cancel callback on this restart, leaving `TabDraggableComponent.draggingTab` set.

`HeavyweightGhost.FollowCursor` polls `MouseInfo` every frame while composed. It does
not depend on gesture movement, so orphaned drag state leaves the ghost attached to
the cursor indefinitely, even after the gesture handler has stopped.

A real Compose mouse-input regression confirms this: start dragging a chip, change
its index, and inspect the shared drag state after recomposition. Against the original
chip code, the assertion that dragging ended fails. Against the fix, it passes.
The companion source-removal test passes with both versions; removal alone is not
the reproduced failure. The original `BossTabButton` disposal hook also cannot cover
an index-key restart while that composable remains mounted.

## Fix

Both tab surfaces use `withDragSession`, with cleanup in `finally`. Each session owns
its exact `DraggingTabInfo` instance. Only that owner may update, end or cancel it;
an old coroutine cannot clear a replacement drag, even for the same tab id.
Normal drop handling still clears state before invoking the result callback.
The old tab-id-only disposal hook is removed because another surface for that tab
could otherwise cancel a drag it did not start.

## Validation

- `TabChipDragCancellationTest`: actual chip mouse gestures, index restart and removal.
- `TabDragSessionTest`: coroutine cancellation, callback exception, replacement
  ownership, non-owning handler and normal cross-pane drop.
- Existing `ReorderIndexTest` covers destination insertion/reordering behavior.
- `:composeApp:detekt`, common-main and desktop-test ktlint checks.

Command:

```sh
./gradlew :composeApp:desktopTest \
  --tests 'ai.rever.boss.components.model.Tab*' \
  --tests 'ai.rever.boss.components.model.ReorderIndexTest' \
  :composeApp:detekt \
  :composeApp:ktlintCommonMainSourceSetCheck \
  :composeApp:ktlintDesktopTestSourceSetCheck
```

The worktree was launched with `:composeApp:run` and copied, git-ignored local
properties for manual verification. Startup succeeded; manual verification of cross-pane
drops over the hardware browser surface has not been reported. The automated
reproduction establishes the lifecycle defect, not the precise trigger of the original incident.
