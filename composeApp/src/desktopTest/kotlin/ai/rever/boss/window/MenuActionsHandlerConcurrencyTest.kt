package ai.rever.boss.window

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for the StateFlow read-modify-write races fixed in
 * [MenuActionsHandler] (issue #1276):
 * `updateSplitEnabled` and `updatePanelCount` did `_state.value = _state.value + (key to value)`,
 * which is NOT atomic on a `MutableStateFlow`. Two concurrent callers on DIFFERENT window ids
 * each read the same starting map, each produced their own copy, and the later writer clobbered
 * the earlier one - so a keyboard interceptor and a menu action on different windows could each
 * overwrite the other's entry, leaving one of them absent from the final map. The user-visible
 * shape of that bug was a window that briefly claimed it had no tabs and stopped intercepting.
 *
 * The CAS `update { }` form the sibling functions already use is atomic, so racing many calls on
 * distinct keys must leave the map with every entry intact. The previous tests used the SAME key
 * across the contention (Map cannot hold duplicate keys, so the assertions held trivially against
 * the pre-fix code); these tests use distinct keys so a lost update drops a whole entry and the
 * assertion fails on the buggy read-modify-write.
 */
class MenuActionsHandlerConcurrencyTest {
    @Test
    fun `concurrent updateSplitEnabled on distinct windows keeps every entry`() =
        runBlocking(Dispatchers.Default) {
            val windowIds = (1..50).map { "split-window-$it" }
            // The race is between reads of `_splitEnabledState.value` and writes back: each caller
            // reads the same starting map, produces its own copy, the later write clobbers the
            // earlier. `update { it + (k to v) }` retries the read-modify-write until CAS lands,
            // so every call's contribution reaches the map.
            val start = CompletableDeferred<Unit>()
            val jobs =
                windowIds.map { id ->
                    async {
                        start.await()
                        MenuActionsHandler.updateSplitEnabled(id, enabled = true)
                    }
                }
            start.complete(Unit)
            jobs.awaitAll()

            // Every distinct window id must be present with enabled = true. On the buggy
            // read-modify-write, lost updates drop whole entries; on the fixed CAS update,
            // every entry survives. The Map size and the per-id value together pin the contract:
            // a size < windowIds.size means lost updates happened; a present key with the wrong
            // value means the CAS lost a race.
            val actual = MenuActionsHandler.splitEnabledState.value
            val missing = windowIds - actual.keys
            assertTrue(
                missing.isEmpty(),
                "lost updates dropped these window ids from splitEnabledState: $missing",
            )
            for (id in windowIds) {
                assertEquals(
                    true,
                    actual[id],
                    "split-enabled flag for $id must be the enabled=true the call wrote",
                )
            }
        }

    @Test
    fun `concurrent updatePanelCount on distinct windows keeps every count`() =
        runBlocking(Dispatchers.Default) {
            val countsByWindow = (1..50).associateBy { "count-window-$it" }
            val start = CompletableDeferred<Unit>()
            val jobs =
                countsByWindow.map { (id, count) ->
                    async {
                        start.await()
                        MenuActionsHandler.updatePanelCount(id, count = count)
                    }
                }
            start.complete(Unit)
            jobs.awaitAll()

            // Every distinct window id must be present with the exact count it wrote. The per-id
            // count matters here too, not just presence: a lost update that happens to land the
            // right key but the wrong value would not be caught by a presence check alone.
            val actual = MenuActionsHandler.panelCountState.value
            val missing = countsByWindow.keys - actual.keys
            assertTrue(
                missing.isEmpty(),
                "lost updates dropped these window ids from panelCountState: $missing",
            )
            for ((id, count) in countsByWindow) {
                assertEquals(
                    count,
                    actual[id],
                    "panel count for $id must equal the count the call wrote",
                )
            }
        }
}
