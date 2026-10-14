package ai.rever.boss.plugin.packs

import ai.rever.boss.mcp.McpPolicyAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** Guards [PluginPackJobs]: a job always ends in a reported state, and history stays bounded. */
class PluginPackJobsTest {
    private val pack = PluginPack("team", emptyList(), listOf(PackRule(PackRuleScope.TOOL, "t", McpPolicyAction.ASK)))

    private suspend fun PluginPackJobs.awaitDone(id: String): PackJob =
        withTimeout(10_000) {
            var job = checkNotNull(status(id))
            while (job.state == PackJobState.RUNNING) {
                delay(10)
                job = checkNotNull(status(id))
            }
            job
        }

    @Test
    fun `a finished job carries its result, and the latest job is reported without an id`() =
        runBlocking<Unit> {
            val jobs = PluginPackJobs(PluginPackApplier(FakePackEffects()))

            val started = assertIs<PluginPackJobs.Start.Started>(jobs.start(pack)).job
            val done = jobs.awaitDone(started.id)

            assertEquals(PackJobState.FINISHED, done.state)
            assertEquals(PackApplyStatus.APPLIED, done.result?.status)
            assertEquals(started.id, jobs.status(null)?.id)
        }

    @Test
    fun `a crash inside the apply ends the job as CRASHED with the reason, not stuck RUNNING`() =
        runBlocking<Unit> {
            val effects = FakePackEffects()
            effects.beforeSnapshot = { throw IllegalStateException("plugin manager disposed") }
            val jobs = PluginPackJobs(PluginPackApplier(effects))

            val started = assertIs<PluginPackJobs.Start.Started>(jobs.start(pack)).job
            val done = jobs.awaitDone(started.id)

            assertEquals(PackJobState.CRASHED, done.state)
            assertEquals("plugin manager disposed", done.error)
        }

    @Test
    fun `a new apply is accepted once the running one has finished`() =
        runBlocking<Unit> {
            val gate = CompletableDeferred<Unit>()
            val effects = FakePackEffects()
            effects.beforeSnapshot = { gate.await() }
            val jobs = PluginPackJobs(PluginPackApplier(effects))

            val first = assertIs<PluginPackJobs.Start.Started>(jobs.start(pack)).job
            assertEquals(first.id, assertIs<PluginPackJobs.Start.Busy>(jobs.start(pack)).running.id)
            gate.complete(Unit)
            jobs.awaitDone(first.id)

            assertIs<PluginPackJobs.Start.Started>(jobs.start(pack))
        }

    @Test
    fun `history keeps the most recent jobs only`() =
        runBlocking<Unit> {
            var n = 0
            val jobs = PluginPackJobs(PluginPackApplier(FakePackEffects()), newId = { "job-${n++}" })

            repeat(20) {
                val job = assertIs<PluginPackJobs.Start.Started>(jobs.start(pack)).job
                jobs.awaitDone(job.id)
            }

            assertEquals(null, jobs.status("job-0"))
            assertNotNull(jobs.status("job-19"))
        }
}
