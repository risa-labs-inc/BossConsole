package ai.rever.boss.plugin.packs

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

enum class PackJobState { RUNNING, FINISHED, CANCELLED, CRASHED }

/** One pack apply, as `pack_status` reports it. Immutable; the registry swaps whole snapshots. */
data class PackJob(
    val id: String,
    val packId: String,
    val state: PackJobState,
    val done: Int = 0,
    val total: Int = 0,
    val current: String = "",
    val result: PackApplyResult? = null,
    val error: String? = null,
)

/**
 * Runs pack applies detached from the tool call that started them, one at a time.
 *
 * Detached because an apply outlives any reasonable call: the CLI channel gives a tool 30 seconds
 * and a pack can download several plugins. `pack_apply` therefore returns a job id at once, and
 * `pack_status` reports progress and the final result.
 *
 * One at a time because two packs naming the same plugin would race the installers against each
 * other, and "which pack won" is not an outcome anyone could act on. A second apply while one is
 * running is refused with the running job's id rather than queued out of sight.
 *
 * The scope is never cancelled, for the same reason as the store installers' detached scopes: an
 * install interrupted between its unload and its load leaves the plugin gone with nothing in place.
 */
class PluginPackJobs(
    private val applier: PluginPackApplier,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val newId: () -> String = { UUID.randomUUID().toString().take(ID_LENGTH) },
) {
    private val logger = BossLogger.forComponent("PluginPackJobs")
    private val lock = Any()
    private val jobs = LinkedHashMap<String, PackJob>()
    private val handles = HashMap<String, Job>()

    /** The started job, or the job already running, which the caller must wait for. */
    sealed interface Start {
        data class Started(
            val job: PackJob,
        ) : Start

        data class Busy(
            val running: PackJob,
        ) : Start
    }

    fun start(pack: PluginPack): Start =
        synchronized(lock) {
            jobs.values.firstOrNull { it.state == PackJobState.RUNNING }?.let { return Start.Busy(it) }
            val job = PackJob(id = newId(), packId = pack.id, state = PackJobState.RUNNING)
            jobs[job.id] = job
            trim()
            handles[job.id] = scope.launch { run(job.id, pack) }
            Start.Started(job)
        }

    /** The job with [id], or the most recent job when [id] is null. */
    fun status(id: String?): PackJob? =
        synchronized(lock) {
            if (id == null) jobs.values.lastOrNull() else jobs[id]
        }

    @Suppress("TooGenericExceptionCaught") // A crashed apply must end as a reported job, never a stuck RUNNING one.
    private suspend fun run(
        id: String,
        pack: PluginPack,
    ) {
        try {
            val result =
                applier.apply(pack) { done, total, current ->
                    update(id) { it.copy(done = done, total = total, current = current) }
                }
            update(id) { it.copy(state = PackJobState.FINISHED, current = "", result = result) }
            logger.info(
                LogCategory.SYSTEM,
                "Applied plugin pack",
                mapOf("pack" to pack.id, "status" to result.status.name),
            )
        } catch (e: CancellationException) {
            update(id) { it.copy(state = PackJobState.CANCELLED, current = "") }
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            update(id) { it.copy(state = PackJobState.CRASHED, current = "", error = reason) }
            logger.error(LogCategory.SYSTEM, "Plugin pack apply crashed", mapOf("pack" to pack.id), error = e)
        } finally {
            synchronized(lock) { handles.remove(id) }
        }
    }

    private fun update(
        id: String,
        change: (PackJob) -> PackJob,
    ) {
        synchronized(lock) { jobs[id]?.let { jobs[id] = change(it) } }
    }

    /** Keep the most recent [MAX_JOBS], never dropping one still running. */
    private fun trim() {
        val finished = jobs.values.filter { it.state != PackJobState.RUNNING }
        finished.take((jobs.size - MAX_JOBS).coerceAtLeast(0)).forEach { jobs.remove(it.id) }
    }

    private companion object {
        const val MAX_JOBS = 16
        const val ID_LENGTH = 8
    }
}
