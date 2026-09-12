package ai.rever.boss.startup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShutdownSequenceTest {
    @Test
    fun executeRunsAllStepsInOrderEvenWhenExceptionsOccur() {
        val executionLog = mutableListOf<String>()
        var lockReleased = false

        val steps =
            listOf(
                ShutdownStep("step 1") {
                    executionLog.add("step1")
                },
                ShutdownStep("failing step 2") {
                    executionLog.add("step2")
                    throw LinkageError("Simulated linkage failure in step 2")
                },
                ShutdownStep("step 3") {
                    executionLog.add("step3")
                },
            )

        ShutdownSequence.execute(
            steps = steps,
            releaseLock = {
                lockReleased = true
            },
        )

        assertEquals(listOf("step1", "step2", "step3"), executionLog)
        assertTrue(lockReleased)
    }

    @Test
    fun defaultStepsContainsExpectedNamedSteps() {
        val steps = ShutdownSequence.defaultSteps()
        val stepNames = steps.map { it.name }

        assertEquals(
            listOf(
                "saving Last Session on exit",
                "stopping performance monitor",
                "closing browser engine",
                "closing favicon HTTP client",
                "uninstalling keyboard interceptor",
                "stopping app update realtime",
                "shutting down updater",
                "shutting down plugin store",
                "shutting down logger",
                "shutting down kernel",
            ),
            stepNames,
        )
    }

    @Test
    fun constructionFailureStillReleasesLock() {
        var released = false
        kotlin.test.assertFailsWith<LinkageError> {
            ShutdownSequence.executePrepared(
                prepareSteps = { throw LinkageError("construction") },
                releaseLock = { released = true },
            )
        }
        assertTrue(released)
        ShutdownSequence.execute(emptyList(), releaseLock = { throw LinkageError("release") })
    }
}
