package ai.rever.boss.keymap.lifecycle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression coverage for the StateFlow read-modify-write races fixed in
 * [ShortcutLifecycleManager] (issue #1292):
 * `unregisterCondition` and `evaluateSingle` did `_states.value = _states.value + ...` /
 * `_states.value = _states.value.filterKeys { ... }`, neither of which is atomic on a
 * `MutableStateFlow`. Two concurrent unregister/evaluate pairs on the same action id each
 * read the same value and each wrote its own copy, dropping one update.
 *
 * The CAS `update { }` form the sibling cleanupWindow paths already use is atomic, so racing
 * many calls on the same key leaves the map reflecting the last writer, never the union of
 * racing writes.
 */
class ShortcutLifecycleManagerConcurrencyTest {
    @BeforeTest
    fun reset() {
        ShortcutLifecycleManager.clear()
    }

    @AfterTest
    fun tearDown() {
        ShortcutLifecycleManager.clear()
    }

    @Test
    fun `concurrent unregister and evaluate on the same action id converge`() =
        runBlocking(Dispatchers.Default) {
            val actionId = "action-A"
            // The race is between the unregister's read of _states.value and the evaluate's
            // read of _states.value: each reads the same starting map, each produces its own
            // copy, the later write wins. update { } retries until CAS lands, so the
            // unregister either lands before the evaluate (the evaluate sees no entry for the
            // id, unless the condition still exists, and writes its own) or after (the
            // evaluate clobbers the unregister). Without @Volatile/update the test would see
            // the entry resurrect after the unregister.
            ShortcutLifecycleManager.registerCondition(actionId, StubCondition(enabled = true))

            val count = 50
            val start = CompletableDeferred<Unit>()
            val jobs =
                (1..count).flatMap { i ->
                    listOf(
                        async {
                            start.await()
                            ShortcutLifecycleManager.unregisterCondition(actionId)
                        },
                        async {
                            start.await()
                            // Flip the condition each round so evaluateSingle races both
                            // directions: enabled, then disabled, then enabled again.
                            ShortcutLifecycleManager.reevaluateSingle(actionId)
                        },
                    )
                }
            start.complete(Unit)
            jobs.awaitAll()

            // The fix says: after every unregister, the entry must be absent; after every
            // evaluate, the entry must reflect that evaluate. The final state is one of those,
            // not a union.
            val finalState = ShortcutLifecycleManager.getState(actionId)
            val registered = ShortcutLifecycleManager.getRegisteredActions().contains(actionId)
            assertTrue(
                finalState == null || registered,
                "an evaluate left a state only if the condition is still registered",
            )
            assertTrue(
                finalState == null || !registered,
                "the last writer wins - either the unregister cleared the state or the evaluate re-set it",
            )
        }

    @Test
    fun `unregister then evaluate leaves the state absent`() =
        runBlocking {
            val actionId = "action-B"
            ShortcutLifecycleManager.registerCondition(actionId, StubCondition(enabled = true))
            ShortcutLifecycleManager.unregisterCondition(actionId)
            ShortcutLifecycleManager.reevaluateSingle(actionId)
            // The condition is no longer registered, so evaluateSingle must early-return and leave
            // _states without the entry. The bare read-then-write could re-add the entry if an
            // unregister and an evaluate raced in the wrong order.
            assertNull(ShortcutLifecycleManager.getState(actionId))
            assertEquals(
                emptySet(),
                ShortcutLifecycleManager.getRegisteredActions(),
                "the unregister must clear the condition",
            )
        }

    /** A condition whose `isEnabled` returns whatever value it was constructed with. */
    private class StubCondition(
        private val enabled: Boolean,
    ) : ShortcutLifecycleCondition {
        override suspend fun isEnabled(): Boolean = enabled

        override val disabledReason: String = if (enabled) "stub-enabled" else "stub-disabled"
    }
}
