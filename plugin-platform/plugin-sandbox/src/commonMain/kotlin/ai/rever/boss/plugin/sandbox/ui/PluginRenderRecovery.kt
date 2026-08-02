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
     * A LinkedHashMap for recency: every mount moves the plugin to the end — see
     * [registerMounted], which removes and re-puts rather than incrementing in
     * place, because neither `LinkedHashMap.put` nor `LinkedHashSet.add` reorders
     * a key that is already present. Guarded by its own monitor since it is both
     * mutated and iterated.
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
            // Removed and re-put, not incremented in place. LinkedHashMap is
            // insertion-ordered and does NOT reorder a key that is already
            // present, so a plain increment left "most recently mounted last"
            // false for exactly the case ref-counting was added for — opening a
            // second terminal tab would not make terminal the freshest suspect.
            val next = (mountCounts.remove(pluginId) ?: 0) + 1
            mountCounts[pluginId] = next
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

    /**
     * Candidates, most recently mounted first, skipping any already ruled out.
     *
     * A plugin that already has a crash recorded is skipped too. It is showing its
     * error fallback rather than plugin content, so it cannot be producing the
     * fault — and quarantining it would be actively harmful: [releaseSuspect]
     * clears the registry entry on release, so ruling out an already-broken plugin
     * would wipe its *genuine* crash state and put a known-broken plugin back on
     * screen. Mount registration lives in the branch that renders content, so this
     * only ever catches the window before that recomposition lands.
     */
    private fun nextSuspect(): String? =
        mountedPlugins()
            .asReversed()
            .firstOrNull { it !in cleared && it != suspect && !PluginCrashRegistry.hasCrashed(it) }

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

    /**
     * Rule out the plugin we were holding, because the fault outlived it.
     *
     * **Known limitation: there is no settle window.** A suspect is judged on the
     * very next fault, and quarantine only takes effect once the panel recomposes
     * and stops rendering plugin content. A fault thrown from a measure pass that
     * was already in flight would therefore convict-then-release the *actual*
     * culprit, after which narrowing exhausts the remaining plugins and ends
     * [Outcome.Unexplained] — the broken-window outcome this class exists to
     * avoid.
     *
     * Left as-is deliberately. The generation bump that accompanies a quarantine
     * discards the offending subtree synchronously, so in the live repro the next
     * fault was always a genuinely new one, and the cycle converged on the right
     * plugin. Adding a delay here is not free either: faults inside the settle
     * window would still reach the host's `RenderCrashPolicy` as unproductive and could
     * escalate *sooner*. That interaction needs testing on its own rather than a
     * timing constant tacked onto this change.
     */
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
        //
        // notify = false because the caller toasts this itself, with wording that
        // names the plugin and says how to restart it. The registry's generic
        // "Plugin X crashed" goes through invokeLater and so landed *after* the
        // tailored message, overwriting it in a single-slot status bar.
        PluginCrashRegistry.recordRenderFault(next, error, notify = false)
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

    /**
     * Forget every incident: mounted counts, the current suspect, the ruled-out
     * set and the retry window.
     *
     * Public and not named for tests, because it is a real operation — a caller
     * that has torn down and rebuilt the plugin surfaces wants recovery to start
     * from a clean slate rather than inherit a half-finished narrowing cycle.
     * Tests use it for the same reason.
     */
    fun reset() {
        // Through releaseSuspect, not `suspect = null`: setting the field alone
        // left whoever was held still recorded in PluginCrashRegistry, so a
        // "clean slate" kept rendering their error fallback. Both test classes
        // were papering over that with manual clearCrash calls in teardown,
        // which was the tell.
        releaseSuspect()
        lastRebuildAt = 0L
        synchronized(mountCounts) { mountCounts.clear() }
        cleared.clear()
        // Deliberately not resetting the generation. It only ever needs to
        // *change* to force a rebuild, and winding it back while panels are still
        // keyed on it would collide with a value they have already seen.
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
