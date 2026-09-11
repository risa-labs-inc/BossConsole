package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PluginUnloadIntent
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * A pending question: "these plugins depend on the one you are about to unload - restart them?"
 *
 * Carries its own answer, which makes it unlike [MissingDependencyPrompt]: that one reports
 * something and moves on, this one blocks an unload until a person decides. The unload path is
 * suspending all the way from `PluginManagerAPIImpl.updatePlugin`, so waiting here is waiting in
 * the Toolbox's own coroutine, not on any UI thread.
 *
 * @param targetPluginId the plugin being unloaded
 * @param dependents everything loaded that declares a dependency on it, optional included
 * @param openInstances open tabs per dependent plugin id, so the dialog can say what closes
 */
data class DependentRestartPrompt(
    val targetPluginId: String,
    val targetDisplayName: String,
    val intent: PluginUnloadIntent,
    val dependents: List<DependentPlugin>,
    val openInstances: Map<String, Int> = emptyMap(),
    val answer: CompletableDeferred<Boolean> = CompletableDeferred(),
) {
    /** Open tabs of [dependent] that confirming would close, or 0 when nothing is open. */
    fun openInstancesOf(dependent: DependentPlugin): Int = openInstances[dependent.pluginId] ?: 0
}

/**
 * Carries a dependent-restart question from an unload to whichever window can ask it.
 *
 * A `Channel`, not a `SharedFlow`, so exactly one window asks: a broadcast would let two windows
 * answer the same question, and the second answer - carried on [DependentRestartPrompt.answer],
 * a single-use `CompletableDeferred` - would arrive after the unload had already run on the
 * first. [PluginDependencyBus] used to justify its own delivery the same way, but was later
 * redesigned (BossConsole#465) into a claim-based map broadcast instead, once its own
 * one-collector-per-prompt guarantee turned out to be satisfiable by [PluginDependencyBus.claim]
 * rather than by a channel - a redesign this bus has no matching reason to make, since here the
 * answer really is a single value only one asker may resolve, not a broadcast-then-claim question.
 *
 * A class with a singleton subclass so a test can hold its own bus; the shared buffer otherwise
 * carries prompts between tests.
 */
open class DependentRestartBus {
    private val logger = BossLogger.forComponent("DependentRestartBus")

    /**
     * Buffered like the missing-dependency bus, so reporting never suspends the unload path and
     * a second question queues rather than replacing the first.
     */
    private val prompts = Channel<DependentRestartPrompt>(capacity = 4)

    val restartPrompts = prompts.receiveAsFlow()

    /**
     * Asks, and returns what the user answered.
     *
     * **Every failure mode answers `false`, never a hang.** The caller is mid-unload with the
     * plugin still loaded, so "we could not ask" and "the user said no" want the same outcome -
     * which is also exactly today's behaviour, a refusal. The three:
     *
     * - **no dependents**: nothing to ask about, so `true` without a dialog. A plugin with no
     *   dependents must not see any change in its update flow.
     * - **nobody to ask**: `trySend` fails when no window has collected yet or the buffer is
     *   full. Logged, because a question that never appears is indistinguishable from a feature
     *   that does not exist.
     * - **never answered**: the window can close with the dialog open, which drops the collector
     *   and with it the only thing that would complete the deferred. [ANSWER_TIMEOUT_MS] bounds
     *   that; without it the Toolbox's update coroutine would wait for the life of the process.
     */
    suspend fun ask(prompt: DependentRestartPrompt): Boolean =
        when {
            prompt.dependents.isEmpty() -> {
                true
            }

            prompts.trySend(prompt).isFailure -> {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Dropped a dependent-restart prompt; refusing the unload",
                    mapOf(
                        "pluginId" to prompt.targetPluginId,
                        "dependents" to prompt.dependents.joinToString(", ") { it.pluginId },
                    ),
                )
                false
            }

            else -> {
                awaitAnswer(prompt)
            }
        }

    private suspend fun awaitAnswer(prompt: DependentRestartPrompt): Boolean {
        val answered = withTimeoutOrNull(ANSWER_TIMEOUT_MS) { prompt.answer.await() }
        if (answered == null) {
            logger.warn(
                LogCategory.SYSTEM,
                "Dependent-restart prompt went unanswered; refusing the unload",
                mapOf("pluginId" to prompt.targetPluginId),
            )
        }
        return answered ?: false
    }

    companion object {
        /**
         * Long enough to be a person deciding, short enough that a dropped dialog does not pin
         * the caller's coroutine for the life of the process.
         */
        const val ANSWER_TIMEOUT_MS = 5 * 60 * 1000L
    }
}

/** The bus the host actually uses. */
object DependentRestartEventBus : DependentRestartBus()

/**
 * The user declined the dependent-restart prompt.
 *
 * A distinct type so the caller can word it as a cancellation. Reported through
 * `Result.failure` because the update did not happen, but "Update failed" is the wrong sentence
 * for an answer the user gave on purpose.
 */
class DependentRestartDeclinedException(
    val pluginId: String,
) : Exception("Cancelled: $pluginId was left as it is")

/**
 * Asks about [pluginId]'s dependents and, on confirmation, arranges for them to be restarted.
 *
 * For the host's own update paths, which pass `force = true` to `uninstallPlugin` and so never
 * meet the veto in `checkCanUnload`. They had the opposite bug to the Toolbox's: they always
 * succeeded, and left every dependent running against a classloader that had closed. The prompt
 * is the same one the delegate raises, so a person sees one dialog whichever button started the
 * update.
 *
 * Returns true when there was nothing to ask or the user agreed. The pending restarts are
 * recorded here, and flushed by `installPlugin` when the new version registers.
 */
suspend fun confirmDependentRestart(
    pluginId: String,
    displayName: String,
    intent: PluginUnloadIntent,
    manager: DynamicPluginManager,
): Boolean {
    val dependents = manager.dependentsOf(pluginId)
    if (dependents.isEmpty()) return true
    val confirmed =
        DependentRestartEventBus.ask(
            DependentRestartCoordinator.promptFor(
                targetPluginId = pluginId,
                targetDisplayName = displayName,
                intent = intent,
                dependents = dependents,
            ),
        )
    if (confirmed) {
        DependentRestartCoordinator.record(pluginId, dependents.map { it.pluginId })
    }
    return confirmed
}

/**
 * Owns restarting the plugins that depend on one being updated or removed.
 *
 * Separate from `DynamicPluginManager`'s companion, which is already the process-wide home of
 * every plugin hook there is: putting three more functions and two more hooks there made a
 * crowded object crowded enough for detekt to say so, and none of this is about managing
 * plugins in a window. It is one arrangement - who gets restarted, and when - so it lives in
 * one place, next to the question that creates it.
 */
object DependentRestartCoordinator {
    private val logger = BossLogger.forComponent("DependentRestartCoordinator")

    /**
     * Runs restarts decoupled from whoever triggered them.
     *
     * The trigger is usually the plugin being updated, or the Toolbox that started the update;
     * either can be torn down by the very restart this schedules. Mirrors
     * `DynamicPluginManager.swapScope`, which exists for the same reason.
     */
    private val restartScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Closes ONE plugin's open tabs and reloads it, so it re-resolves everything it holds - the
     * delegate's `resetPluginInstances`, reachable from commonMain.
     *
     * A plain reload would not do: the dependent's open tabs were composed against the old
     * classloader, which is why `resetPluginInstances` closes them on the EDT first.
     *
     * Set once by the desktop layer; null in headless/test contexts, where nothing is open and
     * nothing needs restarting.
     */
    @Volatile
    var restartPlugin: (suspend (pluginId: String) -> Unit)? = null

    /**
     * How many tabs/panels a plugin currently has open, so the prompt can say what closing them
     * costs. Counting them walks the split-view registries, which is desktop work.
     */
    @Volatile
    var instanceCount: ((pluginId: String) -> Int)? = null

    /**
     * How long [record] waits before restarting dependents whose plugin never came back.
     *
     * A `var` purely so a test does not have to wait a real minute; nothing in the host writes
     * it. The timer runs on a plain scope rather than a virtual-time one because it must survive
     * the caller, which is the whole point of it.
     */
    internal var restartDeadlineMs: Long = PendingDependentRestarts.EXPIRY_MS

    /** A prompt with each dependent's open-tab count filled in. */
    fun promptFor(
        targetPluginId: String,
        targetDisplayName: String,
        intent: PluginUnloadIntent,
        dependents: List<DependentPlugin>,
    ): DependentRestartPrompt {
        val counter = instanceCount
        return DependentRestartPrompt(
            targetPluginId = targetPluginId,
            targetDisplayName = targetDisplayName,
            intent = intent,
            dependents = dependents,
            openInstances =
                dependents.associate { dependent ->
                    dependent.pluginId to (counter?.invoke(dependent.pluginId) ?: 0)
                },
        )
    }

    /**
     * Notes that [dependentIds] agreed to be restarted once [targetPluginId] is loaded again.
     *
     * An empty list cancels the arrangement, which is what the delegate does when the forced
     * unload it just got permission for turns out to have failed.
     */
    fun record(
        targetPluginId: String,
        dependentIds: List<String>,
    ) {
        PendingDependentRestarts.record(targetPluginId, dependentIds)
        if (dependentIds.isEmpty()) return
        // Arm a real deadline rather than waiting for the next load to sweep.
        //
        // Found live, on the first end-to-end run: the AI Gateway's update was confirmed, the
        // gateway unloaded, and its store download then returned HTTP 404. Nothing loaded after
        // that, so a sweep triggered by loading never ran and three plugins sat holding a handle
        // into a classloader that had closed - the exact state this whole change exists to
        // prevent, reached by the change itself. "Some plugin will load again soon" is an
        // assumption about a session, not a guarantee.
        restartScope.launch {
            delay(restartDeadlineMs)
            // Claims only if a successful load has not already claimed it, so the normal path
            // costs nothing and this can never restart the same dependents twice.
            val stranded = PendingDependentRestarts.take(targetPluginId)
            if (stranded.isNotEmpty()) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Restarting dependents of a plugin that never reloaded",
                    mapOf(
                        "pluginId" to targetPluginId,
                        "dependents" to stranded.joinToString(", "),
                    ),
                )
                restartAll(stranded, afterPluginId = targetPluginId)
            }
        }
    }

    /**
     * Restarts whatever was recorded against [targetPluginId], plus any record that has gone
     * stale because its plugin never came back.
     *
     * **Detached, and never called with the manager mutex held.** Each restart re-enters
     * `uninstallPlugin` and `installPlugin`, both of which take that mutex, so calling this from
     * inside the locked section would deadlock the manager against itself. `installPlugin`
     * therefore calls it after its `withLock` returns.
     */
    fun flushAfterLoad(targetPluginId: String) {
        val due =
            buildList {
                addAll(PendingDependentRestarts.take(targetPluginId))
                PendingDependentRestarts.takeExpired().values.forEach(::addAll)
            }.distinct()
        restartAll(due, afterPluginId = targetPluginId)
    }

    /**
     * Restarts [dependentIds] now, for a removal.
     *
     * Nothing is coming back, so there is no load to wait for. They are restarted anyway rather
     * than left alone: each resolved a handle to a plugin whose classloader is now closed, and a
     * restart makes them re-resolve it to null, which is at least the truth about what is
     * installed.
     */
    fun restartNow(dependentIds: List<String>) {
        restartAll(dependentIds, afterPluginId = null)
    }

    /**
     * One failure does not strand the rest, matching the api hot swap's unload loop.
     *
     * `runCatching` rather than a `catch` block so an `Error` from a closed classloader is held
     * too - that is precisely the failure a stale dependent produces. Cancellation is rethrown:
     * this scope is only cancelled on shutdown, and swallowing it there would keep the loop
     * running against a dying process.
     */
    private fun restartAll(
        dependentIds: List<String>,
        afterPluginId: String?,
    ) {
        val restart = restartPlugin ?: return
        if (dependentIds.isEmpty()) return
        restartScope.launch {
            for (dependentId in dependentIds) {
                runCatching { restart(dependentId) }
                    .onFailure { cause ->
                        if (cause is CancellationException) throw cause
                        logger.warn(
                            LogCategory.SYSTEM,
                            "Failed to restart a dependent plugin (continuing)",
                            mapOf(
                                "dependentPluginId" to dependentId,
                                "afterPluginId" to (afterPluginId ?: "removal"),
                                "error" to (cause.message ?: cause::class.simpleName ?: "unknown"),
                            ),
                        )
                    }
            }
        }
    }
}

/**
 * Dependents waiting to be restarted once the plugin they depend on is back.
 *
 * An update is an unload followed by a load that this side does not perform - the Toolbox
 * downloads and loads the new jar itself, and `PluginUpdateBridge` hands the load to
 * `PluginUpdateManager`. So the restart cannot happen at the moment of the unload: the
 * dependents would reload, resolve their dependency to null, and be wrong again a second later
 * when the new version registered. Recording the intent and flushing when the target next loads
 * is what makes the restart land on the new version.
 *
 * Removal is the opposite case and is not recorded here at all: nothing is coming back, so the
 * caller flushes immediately.
 */
object PendingDependentRestarts {
    private val logger = BossLogger.forComponent("PendingDependentRestarts")

    private data class Entry(
        val dependentIds: List<String>,
        val recordedAtMs: Long,
    )

    private val pending = ConcurrentHashMap<String, Entry>()

    /**
     * Notes that [dependentIds] should be restarted when [targetPluginId] next loads.
     *
     * Ordered by the caller (load priority); this keeps the order it is given. A second record
     * for the same target replaces the first rather than merging: the set is recomputed from the
     * live manifests each time it is asked, so the newer answer is the accurate one.
     */
    fun record(
        targetPluginId: String,
        dependentIds: List<String>,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (dependentIds.isEmpty()) {
            pending.remove(targetPluginId)
            return
        }
        pending[targetPluginId] = Entry(dependentIds.toList(), nowMs)
    }

    /**
     * Claims the dependents recorded for [targetPluginId], leaving nothing behind.
     *
     * Claiming rather than reading, because the flush must happen exactly once: the target can
     * load more than once in a session, and a set that survived its flush would restart the
     * dependents again on an unrelated reload.
     */
    fun take(targetPluginId: String): List<String> = pending.remove(targetPluginId)?.dependentIds.orEmpty()

    /**
     * Claims every record older than [ttlMs], for the update that never completed.
     *
     * A download can fail, a jar can be rejected as binary-incompatible, and a person can close
     * the window between the unload and the load. In all of those the target never comes back,
     * and the dependents are left holding a handle into a classloader that closed - the exact
     * state this whole change exists to prevent. Restarting them late is not ideal, but it
     * leaves them consistent with what is actually loaded.
     *
     * **A second line of defence, not the main one.** [DependentRestartCoordinator.record] arms a
     * timer per record, which is what actually catches the failed update; this sweep runs on the
     * next load of any plugin and covers a record whose timer did not survive. Both claim through
     * [take], so whichever gets there first is the only one that restarts anything.
     */
    fun takeExpired(
        nowMs: Long = System.currentTimeMillis(),
        ttlMs: Long = EXPIRY_MS,
    ): Map<String, List<String>> {
        val expired = pending.filterValues { entry -> nowMs - entry.recordedAtMs >= ttlMs }
        expired.keys.forEach { key -> pending.remove(key) }
        if (expired.isNotEmpty()) {
            logger.warn(
                LogCategory.SYSTEM,
                "Restarting dependents of a plugin that never reloaded",
                mapOf("plugins" to expired.keys.joinToString(", ")),
            )
        }
        return expired.mapValues { (_, entry) -> entry.dependentIds }
    }

    /** Test seam; the host never needs this. */
    fun clear() {
        pending.clear()
    }

    /** Longer than a store download, short enough that a dependent is not left stale for long. */
    const val EXPIRY_MS = 60_000L
}

/**
 * The words the dialog uses, kept out of the composable so they can be tested.
 *
 * Update and remove are genuinely different promises - one says the dependents come back on a
 * newer version, the other says one of them is about to lose what it needs - and getting that
 * backwards is the kind of thing a screenshot review misses.
 */
object DependentRestartCopy {
    fun title(intent: PluginUnloadIntent): String =
        when (intent) {
            PluginUnloadIntent.REMOVE -> "Remove a plugin others use?"
            PluginUnloadIntent.UPDATE, PluginUnloadIntent.UNSPECIFIED -> "Restart dependent plugins?"
        }

    fun confirmLabel(intent: PluginUnloadIntent): String =
        when (intent) {
            PluginUnloadIntent.REMOVE -> "Remove and Restart"
            PluginUnloadIntent.UPDATE, PluginUnloadIntent.UNSPECIFIED -> "Update and Restart"
        }

    /**
     * The sentence under the title, naming the target and what confirming does.
     *
     * `UNSPECIFIED` is worded for neither case, because it is what the pre-1.0.79
     * `unloadPlugin` reports and that method serves both the Toolbox's Update and its Remove.
     * Promising "they will reopen on the new version" there would be a lie half the time.
     */
    fun message(
        intent: PluginUnloadIntent,
        targetDisplayName: String,
        dependents: List<DependentPlugin>,
    ): String {
        val they = if (dependents.size == 1) "it" else "them"
        val needs = if (dependents.size == 1) "needs" else "need"
        return when (intent) {
            PluginUnloadIntent.UPDATE -> {
                "Updating $targetDisplayName restarts the plugins below. Their open tabs close, " +
                    "and reopening one loads it against the new version."
            }

            PluginUnloadIntent.REMOVE -> {
                "The plugins below $needs $targetDisplayName. Removing it closes their open tabs " +
                    "and restarts $they without it - anything of theirs that needs " +
                    "$targetDisplayName stops working."
            }

            PluginUnloadIntent.UNSPECIFIED -> {
                "The plugins below depend on $targetDisplayName. Continuing closes their open " +
                    "tabs and restarts $they."
            }
        }
    }
}
