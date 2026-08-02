package ai.rever.boss.plugin.sandbox.ui

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import java.util.concurrent.ConcurrentHashMap

/**
 * Recovers the UI after a render exception that could not be attributed to a
 * plugin from its stack.
 *
 * ### Why stack attribution is not enough
 *
 * [PluginRenderBoundary] catches what is thrown while measuring, placing or
 * drawing *through* it. That covers a first layout and any pass that walks down
 * from the boundary's ancestors. It does not cover the path a real crash
 * actually took:
 *
 * ```
 * IllegalArgumentException: Key "coll-…" was already used.
 *   at LayoutNodeSubcompositionsState.subcompose
 *   at LazyListKt$rememberLazyListMeasurePolicy$1$1.measure
 *   at LayoutNode.remeasure
 *   at MeasureAndLayoutDelegate.doRemeasure
 *   at MeasureAndLayoutDelegate.remeasureIfNeeded          <- straight from the dirty list
 * ```
 *
 * Compose re-measured the plugin's `LazyColumn` node directly, using its cached
 * constraints, without descending from any ancestor. Nothing of the boundary —
 * and nothing of the plugin — is on that stack, so neither the boundary nor
 * [PluginCrashInterceptor] can see whose fault it is. The exception escapes to
 * the window handler, which can keep the window alive but has no idea what to
 * fix.
 *
 * ### What this does instead
 *
 * Attribution comes from *what is mounted* rather than from the stack: every
 * [PluginErrorBoundary] registers the plugin it is currently rendering. On an
 * unattributed render exception:
 *
 * 1. **First failure — rebuild.** Bump [generation], which every plugin panel
 *    includes in a `key(...)`, tearing its subtree down and composing it fresh.
 *    A subcomposition left inconsistent by a half-finished measure is discarded
 *    rather than retried, and a transient fault ends here.
 * 2. **Failure again within [REBUILD_GRACE_MILLIS] — quarantine.** The rebuild
 *    did not help, which means the content reproduces the fault every time (bad
 *    data, a genuine plugin bug). Rebuilding again would loop forever, so every
 *    mounted plugin is recorded as crashed instead: their panels swap to the
 *    error fallback and stop rendering plugin content, which is what finally
 *    stops the exception recurring.
 *
 * Step 2 suspects **one** plugin at a time, not all of them, and corrects itself.
 * If the fault recurs while a suspect is quarantined, that suspect was innocent:
 * it is released and the next one is tried. The cycle ends when the exceptions
 * stop, which leaves exactly the culprit quarantined.
 *
 * Quarantining everything at once was the first attempt and it was wrong in
 * practice, not just in theory. Against the real crash it disabled four plugins
 * for one plugin's bug, put an error in every open panel, and — because
 * [PluginCrashRegistry.recordCrash] closes a registered tab — **closed the user's
 * terminal and browser tabs**, destroying live sessions over a duplicate
 * LazyColumn key somewhere else. Quarantine now goes through
 * [PluginCrashRegistry.recordRenderFault], which shows the fallback without
 * closing anything.
 */
object PluginRenderRecovery {
    private val logger = BossLogger.forComponent("PluginRenderRecovery")

    /**
     * How long after a rebuild a further failure counts as "the rebuild did not
     * help". Long enough to cover the frames a rebuild takes to land, short
     * enough that two unrelated faults minutes apart each get their own retry.
     */
    const val REBUILD_GRACE_MILLIS = 4_000L

    /**
     * How many live boundaries each plugin currently has, and in what order they
     * first appeared.
     *
     * Reference-counted, not a set. PluginErrorBoundary is instantiated per
     * surface — per tab and per side panel, across windows — so one plugin
     * routinely has several live boundaries at once. With a plain set, closing one
     * of two terminal tabs removed the terminal plugin from the mounted list while
     * it was still rendering, after which it could never be suspected: the real
     * culprit would be ruled out by omission and the incident would end
     * Unexplained, leaving a permanently broken window — the exact outcome this
     * class exists to prevent.
     *
     * A LinkedHashMap for recency: iteration order is first-appearance order, and
     * a re-mount after the count drops to zero moves the plugin to the end, which
     * plain `LinkedHashSet.add` would not have done for an element already
     * present. Guarded by its own monitor since it is both mutated and iterated.
     */
    private val mountCounts = LinkedHashMap<String, Int>()

    /** The single plugin currently held responsible, if any. */
    @Volatile
    private var suspect: String? = null

    /** Suspects already tried and released this incident, so we do not retry them. */
    private val cleared = ConcurrentHashMap.newKeySet<String>()

    private val _generation = mutableStateOf(0)

    /** Read from composition so a bump rebuilds the plugin subtree. */
    val generation: Int
        @Composable get() = _generation.value

    @Volatile
    private var lastRebuildAt = 0L

    /**
     * Note that [pluginId]'s panel is rendering plugin content, and return the
     * function that clears it when the panel goes away.
     */
    fun registerMounted(pluginId: String): () -> Unit {
        synchronized(mountCounts) {
            mountCounts[pluginId] = (mountCounts[pluginId] ?: 0) + 1
        }
        // One unregister per register; the plugin stays mounted while any other
        // boundary still holds a count.
        var released = false
        return {
            synchronized(mountCounts) {
                if (!released) {
                    released = true
                    val remaining = (mountCounts[pluginId] ?: 1) - 1
                    if (remaining <= 0) mountCounts.remove(pluginId) else mountCounts[pluginId] = remaining
                }
            }
        }
    }

    /** Candidates, most recently mounted first, skipping any already ruled out. */
    private fun nextSuspect(): String? =
        mountedPlugins()
            .asReversed()
            .firstOrNull { it !in cleared && it != suspect }

    /** Plugins currently rendering content, first-appearance order. */
    fun mountedPlugins(): List<String> = synchronized(mountCounts) { mountCounts.keys.toList() }

    /**
     * Handle a render exception nobody could attribute.
     *
     * @param now injectable clock — the retry-versus-quarantine decision is
     *   time-based, and a test should not have to sleep to reach the second half.
     * @return what was done, for the caller to log and for tests to assert on.
     */
    fun onUnattributedRenderException(
        error: Throwable,
        now: Long = System.nanoTime() / 1_000_000,
    ): Outcome {
        val affected = mountedPlugins().toSet()
        val recentlyRebuilt = lastRebuildAt != 0L && now - lastRebuildAt <= REBUILD_GRACE_MILLIS
        return when {
            affected.isEmpty() -> {
                notPluginRelated(error)
            }

            // A fault well clear of the last one is its own incident.
            !recentlyRebuilt -> {
                startFreshCycle(affected, error, now)
            }

            else -> {
                // The fault survived a rebuild. Anyone we were already holding is
                // thereby proven innocent.
                releaseSuspectAsInnocent()
                quarantineNextSuspect(error, now)
            }
        }
    }

    private fun notPluginRelated(error: Throwable): Outcome {
        // Nothing plugin-shaped is on screen, so this is the host's own fault and
        // not ours to quarantine. The window handler still contains it.
        logger.warn(
            LogCategory.UI,
            "Unattributed render exception with no plugin panel mounted — not a plugin fault",
            mapOf("errorType" to error::class.simpleName.orEmpty()),
        )
        return Outcome.NotPluginRelated
    }

    private fun startFreshCycle(
        affected: Set<String>,
        error: Throwable,
        now: Long,
    ): Outcome {
        releaseSuspect()
        cleared.clear()
        logger.warn(
            LogCategory.UI,
            "Unattributed render exception — rebuilding plugin panels",
            mapOf(
                "plugins" to affected.joinToString(),
                "errorType" to error::class.simpleName.orEmpty(),
            ),
        )
        lastRebuildAt = now
        _generation.value += 1
        return Outcome.Rebuilt(affected)
    }

    private fun releaseSuspectAsInnocent() {
        suspect?.let { wronglyHeld ->
            logger.info(
                LogCategory.UI,
                "Fault persisted while suspected — releasing and trying the next plugin",
                mapOf("released" to wronglyHeld),
            )
            cleared.add(wronglyHeld)
            releaseSuspect()
        }
    }

    private fun quarantineNextSuspect(
        error: Throwable,
        now: Long,
    ): Outcome {
        val next = nextSuspect()
        if (next == null) {
            // Everyone mounted has been tried and released, so nothing on screen
            // explains it. Stop churning: the window handler keeps containing it.
            logger.error(
                LogCategory.UI,
                "Render fault persists with every mounted plugin ruled out — leaving it contained",
                mapOf("tried" to cleared.joinToString(), "errorType" to error::class.simpleName.orEmpty()),
                error,
            )
            lastRebuildAt = 0L
            cleared.clear()
            return Outcome.Unexplained
        }

        logger.error(
            LogCategory.UI,
            "Rebuild did not clear the render fault — quarantining one suspect",
            mapOf(
                "suspect" to next,
                "alreadyRuledOut" to cleared.joinToString(),
                "errorType" to error::class.simpleName.orEmpty(),
            ),
            error,
        )
        suspect = next
        // recordRenderFault, not recordCrash: this is a guess, and a guess must
        // never close somebody's terminal tab.
        PluginCrashRegistry.recordRenderFault(next, error)
        lastRebuildAt = now
        _generation.value += 1
        return Outcome.Quarantined(setOf(next))
    }

    /** Let the current suspect render again. */
    private fun releaseSuspect() {
        suspect?.let {
            PluginCrashRegistry.clearCrash(it)
            suspect = null
        }
    }

    /** Forget the retry window. For tests, and for a clean slate after recovery. */
    internal fun resetForTest() {
        lastRebuildAt = 0L
        synchronized(mountCounts) { mountCounts.clear() }
        cleared.clear()
        suspect = null
        _generation.value = 0
    }

    /** What [onUnattributedRenderException] decided. */
    sealed interface Outcome {
        /** No plugin panel was mounted; the fault is the host's. */
        data object NotPluginRelated : Outcome

        /** Plugin subtrees were torn down and rebuilt. */
        data class Rebuilt(
            val plugins: Set<String>,
        ) : Outcome

        /** The rebuild did not help; this plugin is being held responsible for now. */
        data class Quarantined(
            val plugins: Set<String>,
        ) : Outcome

        /** Every mounted plugin was tried and released; none of them explains it. */
        data object Unexplained : Outcome
    }
}
