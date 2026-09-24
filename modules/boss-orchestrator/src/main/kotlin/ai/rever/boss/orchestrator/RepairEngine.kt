package ai.rever.boss.orchestrator

import ai.rever.boss.ipc.proto.ProcessFailureReport
import ai.rever.boss.ipc.proto.ProcessManifest
import ai.rever.boss.ipc.proto.RepairStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ

/**
 * Executes repair strategies for failing processes.
 *
 * Strategy selection:
 * - HIGH/MEDIUM confidence from CrashAnalyzer → use analyzer's strategy directly.
 * - LOW confidence → walk the [defaultLadder] based on consecutive failure count.
 *
 * For PATCH_SOURCE and PATCH_CONFIG strategies, an optional [aiClient] is consulted
 * to generate AI-powered proposals. If [aiClient] is null or returns null, sensible
 * static descriptions are used instead.
 *
 * Restart requests are delegated to [onRequestRestart] (typically calls
 * KernelService.RequestShutdown so the kernel handles the actual process lifecycle).
 *
 * The engine writes no file. It reads source files, and only from inside [projectRoot] —
 * see [readSourceFiles].
 */
class RepairEngine(
    private val analyzer: CrashAnalyzer,
    private val snapshotManager: SnapshotManager,
    private val aiClient: AiRepairClient? = null,
    /**
     * Root directory used to resolve relative source file paths from process manifests, and
     * the only directory those files may be read from. **Null means no source file may be
     * read at all**, which is the default.
     *
     * It defaulted to this process's working directory, which is not a project — the kernel
     * spawns the orchestrator with `ProcessConfig.workDir`, itself defaulting to `File(".")`,
     * so the child inherits whatever the parent had. For a packaged macOS app that is `/`,
     * which [AllowedRoots] refuses; but a `.desktop` launch or a service gets `$HOME` or the
     * install tree, and those are ordinary directories that [AllowedRoots] accepts. Reads
     * were then confined to somewhere real and arbitrary rather than to nothing, and no
     * caller had said they should happen at all.
     *
     * Requiring the root to be stated keeps "the host didn't say" and "the working directory
     * happened to be somewhere plausible" from being the same state.
     */
    private val projectRoot: String? = null,
    /** Called to request a restart. Kernel handles the actual re-spawn. */
    private val onRequestRestart: suspend (processId: String, jvmArgsOverride: List<String>) -> Unit = { _, _ -> },
) {
    private val logger = LoggerFactory.getLogger(RepairEngine::class.java)

    /**
     * Floor for the heap a tuned restart applies. A user's configured heap above this is left
     * alone; anything below is bumped up to it. Expressed in MiB so the comparison with the
     * parsed current value does not need to think about units.
     */
    private val minTunedHeapMb = 512

    /**
     * Ceiling for the heap a tuned restart applies. Beyond this we stop growing and give up
     * the restart - a 1g process that OOMs twice should land on 2g, not on the JVM's addressable
     * ceiling or on the box's swap budget. 8g covers every realistic plugin out to a typical
     * desktop-class workflow; raise if a real case demands more.
     */
    private val maxTunedHeapMb = 8 * 1024

    /**
     * Growth applied to the heap on each tuned restart. The 3/2 multiplier leaves the next
     * restart visibly different from the previous one (1g -> 1.5g -> 2.25g) without the
     * doubling that would skip past the cap and bounce off it.
     */
    private val tunedGrowthNumerator = 3
    private val tunedGrowthDenominator = 2

    /** Manifest source file paths come from the diagnosed process, so they are confined. */
    private val sourceRoots =
        if (projectRoot == null) AllowedRoots.none() else AllowedRoots.of(File(projectRoot))

    init {
        // Say it once at construction. Whether manifest source reads are on, and where they
        // point, is otherwise invisible: a usable root logs nothing, and the refusals are
        // warnings that only appear for the roots that were rejected.
        val roots = sourceRoots.rootPaths()
        if (roots.isEmpty()) {
            logger.info("Manifest source reads are off: no usable project root was given")
        } else {
            logger.info("Manifest source reads are confined to {}", roots)
        }
    }

    // Escalation ladder applied when analyzer confidence is LOW
    private val defaultLadder =
        listOf(
            RepairStrategy.REPAIR_STRATEGY_RESTART,
            RepairStrategy.REPAIR_STRATEGY_RESTART,
            RepairStrategy.REPAIR_STRATEGY_RESET_STATE,
            RepairStrategy.REPAIR_STRATEGY_PATCH_CONFIG,
            RepairStrategy.REPAIR_STRATEGY_ESCALATE,
        )

    suspend fun handleFailure(request: ProcessFailureReport): RepairOutcome =
        withContext(Dispatchers.IO) {
            val report = RepairLimits.bounded(request)
            val processId = report.processId
            val manifest: ProcessManifest? = if (report.hasManifest()) report.manifest else null
            val diagnostic = analyzer.analyze(report, manifest)

            logger.info(
                "Handling failure for {}: rootCause={}, strategy={}, confidence={}",
                processId,
                diagnostic.rootCause,
                diagnostic.strategy,
                diagnostic.confidence,
            )

            val strategy =
                if (diagnostic.confidence != Confidence.LOW) {
                    diagnostic.strategy
                } else {
                    val idx = (report.consecutiveFailures - 1).coerceIn(0, defaultLadder.lastIndex)
                    defaultLadder[idx]
                }

            executeStrategy(processId, strategy, report, manifest, diagnostic)
        }

    private suspend fun executeStrategy(
        processId: String,
        strategy: RepairStrategy,
        report: ProcessFailureReport,
        manifest: ProcessManifest?,
        diagnostic: DiagnosticResult,
    ): RepairOutcome =
        when (strategy) {
            RepairStrategy.REPAIR_STRATEGY_RESTART -> {
                try {
                    onRequestRestart(processId, emptyList())
                    logger.info("Restart requested for process: {}", processId)
                    RepairOutcome.Restarted(processId)
                } catch (e: CancellationException) {
                    // Before the Exception arm: CancellationException *is* an Exception, so
                    // catching it here would turn "the caller hung up" into a repair failure
                    // and swallow the cancellation the coroutine machinery needs to see.
                    throw e
                } catch (e: Exception) {
                    logger.error("Failed to request restart for process: {}", processId, e)
                    RepairOutcome.Failed(processId, "Restart request failed: ${e.message}")
                }
            }

            RepairStrategy.REPAIR_STRATEGY_RESTART_TUNED -> {
                try {
                    val tunedArgs = tunedJvmArgs(report)
                    onRequestRestart(processId, tunedArgs)
                    logger.info("Tuned restart requested for process: {} with {}", processId, tunedArgs)
                    RepairOutcome.Restarted(processId, tunedArgs)
                } catch (e: CancellationException) {
                    // Before the Exception arm: CancellationException *is* an Exception, so
                    // catching it here would turn "the caller hung up" into a repair failure
                    // and swallow the cancellation the coroutine machinery needs to see.
                    throw e
                } catch (e: Exception) {
                    logger.error("Failed to request tuned restart for process: {}", processId, e)
                    RepairOutcome.Failed(processId, "Tuned restart request failed: ${e.message}")
                }
            }

            RepairStrategy.REPAIR_STRATEGY_RESET_STATE -> {
                resetState(processId)
            }

            RepairStrategy.REPAIR_STRATEGY_PATCH_CONFIG -> {
                logger.info("Config patch for process {} - consulting AI client", processId)

                val aiProposal =
                    aiClient?.proposeConfigFix(
                        processId = processId,
                        rootCause = diagnostic.rootCause,
                        suggestedFix = diagnostic.suggestedFix,
                        errorMessage = report.errorMessage,
                    )

                val description =
                    aiProposal?.toDescription()
                        ?: diagnostic.suggestedFix
                        ?: "Manual config review required"

                RepairOutcome.ConfigPatched(processId, description)
            }

            RepairStrategy.REPAIR_STRATEGY_PATCH_SOURCE -> {
                logger.info("Source patch for process {} - consulting AI client", processId)

                val sourceFiles = readSourceFiles(manifest)
                val aiProposal =
                    aiClient?.proposeSourceFix(
                        rootCause = diagnostic.rootCause,
                        sourceFiles = sourceFiles,
                        stackTrace = report.stackTrace,
                        errorMessage = report.errorMessage,
                    )

                val diff =
                    aiProposal?.toSummary()
                        ?: "// AI analysis unavailable - manual review required for: ${diagnostic.rootCause}"

                RepairOutcome.CodeFixProposed(processId, diff)
            }

            else -> {
                logger.warn("Escalating failure for process: {}", processId)
                RepairOutcome.Escalated(processId, buildEscalationReport(processId, report, diagnostic))
            }
        }

    /**
     * Restarts [processId] on the state it last snapshotted, and deletes nothing.
     *
     * A state reset is the third rung of [defaultLadder], so it runs automatically and
     * without approval. Its wire form, `ResetStateAction`, carries `restore_snapshot` and
     * `snapshot_id`: the reset is meant to bring a process back on a recorded state, so the
     * newest snapshot is named here and reported in the outcome. Removing the snapshots
     * would be the opposite — it would leave a later ROLLBACK with nothing to roll back to,
     * at the moment a process is already failing repeatedly. With no snapshot recorded this
     * is a plain restart, which is all the delete-everything form ever amounted to.
     */
    private suspend fun resetState(processId: String): RepairOutcome {
        val snapshotId = latestSnapshotId(processId)
        return try {
            onRequestRestart(processId, emptyList())
            logger.info(
                "State reset + restart requested for process: {} (restoring snapshot: {})",
                processId,
                snapshotId ?: "none recorded",
            )
            RepairOutcome.StateReset(processId, snapshotId)
        } catch (e: CancellationException) {
            // Before the Exception arm: CancellationException *is* an Exception, so catching
            // it here would report "state reset failed" when the caller simply hung up, and
            // swallow the cancellation the coroutine machinery needs to see.
            throw e
        } catch (e: Exception) {
            logger.error("Failed to reset state for process: {}", processId, e)
            RepairOutcome.Failed(processId, "State reset failed: ${e.message}")
        }
    }

    private fun latestSnapshotId(processId: String): String? =
        try {
            snapshotManager.listSnapshots(processId).firstOrNull()?.id
        } catch (_: Exception) {
            null
        }

    /**
     * Reads source files listed in the process manifest.
     * Paths are tried as absolute first, then relative to [projectRoot] — and a relative one
     * is dropped outright when no root was given. Files that cannot be read are omitted.
     *
     * The list comes from the manifest the diagnosed process sent, and the contents go to
     * [AiRepairClient], i.e. off this machine — so which files the host is willing to read is
     * not the reporting process's choice. Every candidate is resolved and must land inside
     * [projectRoot] ([sourceRoots]); one that does not is logged and dropped, never fatal,
     * because a manifest with a single bad entry should still be diagnosable.
     */
    private fun readSourceFiles(manifest: ProcessManifest?): Map<String, String> {
        if (manifest == null || manifest.sourceFilesList.isEmpty()) return emptyMap()
        return manifest.sourceFilesList
            .take(RepairLimits.SOURCE_COUNT)
            .associateWith { path -> readConfinedSourceFile(path) }
            .filterValues { it.isNotBlank() }
    }

    private fun readConfinedSourceFile(declaredPath: String): String =
        try {
            // A relative path needs a root to hang off. Without one there is nothing to
            // resolve against — and `File(null, path)` would quietly resolve it against the
            // working directory, which is the behaviour a null root exists to refuse.
            val candidate =
                File(declaredPath).takeIf { it.isAbsolute }
                    ?: projectRoot?.let { root -> File(root, declaredPath) }
            val confined = candidate?.let(sourceRoots::resolve)
            when {
                confined == null -> {
                    logger.warn(
                        "Manifest source file {} is outside the roots {} this process reads from - dropped",
                        declaredPath,
                        sourceRoots.rootPaths().ifEmpty { listOf("(none)") },
                    )
                    ""
                }

                !confined.isFile -> {
                    ""
                }

                else -> {
                    readBoundedSource(confined)
                }
            }
        } catch (_: Exception) {
            ""
        }

    private fun readBoundedSource(file: File): String {
        val bytes =
            Files.newByteChannel(file.toPath(), setOf(READ, NOFOLLOW_LINKS)).use { channel ->
                Channels.newInputStream(channel).use { it.readNBytes(RepairLimits.SOURCE_BYTES + 1) }
            }
        if (bytes.size > RepairLimits.SOURCE_BYTES) {
            logger.warn("Manifest source file exceeds the read limit; omitted from repair analysis")
            return ""
        }
        return bytes.toString(Charsets.UTF_8)
    }

    /**
     * JVM args a tuned restart should apply: the [currentArgs] with the `-Xmx` entry
     * replaced by a 3/2-grown value (capped at [maxTunedHeapMb], floored at [minTunedHeapMb]),
     * every other flag kept in place.
     *
     * Three things that have to be true:
     *
     *  - **Repeated OOMs grow the heap.** A 1g process that OOMs must come back at 1.5g,
     *    not at the same 1g labeled "tuned". The growth is a 3/2 multiplier; once the cap
     *    is hit, this returns [currentArgs] unchanged so the restart does not loop on the
     *    same heap.
     *  - **Missing or unparseable `-Xmx` never lowers the heap.** The current JVM args
     *    carry no parseable value (or no `-Xmx` at all); we don't know what the user has,
     *    so we return [currentArgs] rather than picking a fallback that could be smaller
     *    than what the user actually configured.
     *  - **Other JVM flags are preserved in order.** `-Xmx` is swapped in place; flags like
     *    `-Xss`, `-Dfoo=bar`, `--add-opens`, GC settings, etc. stay where the user put them.
     */
    private fun tunedJvmArgs(report: ProcessFailureReport): List<String> {
        val currentArgs = report.currentJvmArgsList.toList()
        // No parseable heap flag: we don't know what the user has, so leave the args alone
        // (returning the user's current args means the restart never lowers the heap).
        val currentMb = parseXmxMb(currentArgs) ?: return currentArgs

        val grownMb =
            (currentMb.toLong() * tunedGrowthNumerator / tunedGrowthDenominator)
                .coerceAtMost(maxTunedHeapMb.toLong())
                .toInt()
        // Below the floor: bump up to the floor (rare - only when the configured heap is
        // genuinely tiny, e.g. a 256m plugin that OOMs).
        val newXmx = "-Xmx${maxOf(grownMb, minTunedHeapMb)}m"
        val swapped =
            currentArgs.map { arg -> if (XMX_PATTERN.matchEntire(arg) != null) newXmx else arg }
        return when {
            // No growth possible (already at/over the cap, or growth rounds back to the same
            // value): restart the process with the args it already had.
            grownMb <= currentMb -> currentArgs

            // parseXmxMb found the -Xmx we read currentMb from, so an existing entry is the
            // common case; the else is defensive against a future refactor that decouples
            // the parser from this swap.
            currentArgs.any { XMX_PATTERN.matchEntire(it) != null } -> swapped

            else -> swapped + newXmx
        }
    }

    /**
     * The largest `-Xmx{N}[g|G|m|M|k|K]` (in MiB) in [args], or null when none parses.
     *
     * A process can name multiple `-Xmx` flags (the last wins), but [args] here is the kernel's
     * actual spawn list, so there is at most one. The unit suffix is required: an unrecognised
     * unit or an unparseable number is "no floor" rather than zero, because zero would floor
     * every process against a meaningless minimum.
     */
    private fun parseXmxMb(args: List<String>): Int? =
        args
            .asSequence()
            .mapNotNull { arg ->
                val match = XMX_PATTERN.matchEntire(arg) ?: return@mapNotNull null
                val value = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val unit = match.groupValues[2]
                when (unit.lowercase()) {
                    "g" -> value * 1024L
                    "m" -> value
                    "k" -> (value + 1023L) / 1024L
                    else -> null
                }
            }.maxOrNull()
            ?.let { Math.min(Math.max(it, 1L), Long.MAX_VALUE).toInt() }

    private companion object {
        // Accepts -Xmx<digits><g|G|m|M|k|K> end-to-end. Anything else (no unit, bare -Xmx with no
        // value, or whitespace inside) is "not a heap flag" and falls through to null.
        private val XMX_PATTERN = Regex("""-Xmx(\d+)([gGmMkK])""")
    }

    private fun buildEscalationReport(
        processId: String,
        report: ProcessFailureReport,
        diagnostic: DiagnosticResult,
    ): String =
        buildString {
            appendLine("=== ESCALATION REPORT: $processId ===")
            appendLine("Root Cause: ${diagnostic.rootCause}")
            appendLine("Error Type: ${report.errorType}")
            appendLine("Error Message: ${report.errorMessage}")
            appendLine("Consecutive Failures: ${report.consecutiveFailures}")
            appendLine("Suggested Fix: ${diagnostic.suggestedFix ?: "None"}")
            if (report.stackTrace.isNotBlank()) {
                appendLine("Stack Trace:")
                appendLine(report.stackTrace.take(2000))
            }
        }
}

sealed class RepairOutcome {
    data class Restarted(
        val processId: String,
        /** The JVM args the restart must apply (e.g. a tuned -Xmx); empty for a plain restart. */
        val jvmArgs: List<String> = emptyList(),
    ) : RepairOutcome()

    data class StateReset(
        val processId: String,
        /** The snapshot the restarted process should come back on, or null if none is recorded. */
        val restoredSnapshotId: String? = null,
    ) : RepairOutcome()

    data class ConfigPatched(
        val processId: String,
        val patchDescription: String,
    ) : RepairOutcome()

    data class CodeFixProposed(
        val processId: String,
        val diff: String,
    ) : RepairOutcome()

    data class Escalated(
        val processId: String,
        val report: String,
    ) : RepairOutcome()

    data class Failed(
        val processId: String,
        val reason: String,
    ) : RepairOutcome()
}
