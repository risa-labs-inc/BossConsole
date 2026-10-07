package ai.rever.boss.components.plugin

import java.io.Closeable
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertSame

class PluginUpdateLeaseCleanupTest {
    @Test
    fun `an ordinary release failure cannot invalidate a completed installation`() {
        val events = mutableListOf<String>()
        val reports = mutableListOf<Pair<String, String>>()
        val lease =
            object : Closeable {
                override fun close() =
                    cleanupPluginUpdateLease(
                        release = {
                            events += "release"
                            throw IOException("sensitive payload must never be reported")
                        },
                        close = { events += "close" },
                        afterClose = { events += "unfence" },
                        reportFailure = { phase, error -> reports += phase to error },
                    )
            }

        assertEquals("committed", lease.use { "committed" })
        assertEquals(listOf("release", "close", "unfence"), events)
        assertEquals(listOf("release" to "IOException"), reports)
    }

    @Test
    fun `ordinary close and diagnostic failures still finish cleanup`() {
        val events = mutableListOf<String>()
        cleanupPluginUpdateLease(
            release = { events += "release" },
            close = {
                events += "close"
                throw IOException("close")
            },
            afterClose = { events += "unfence" },
            reportFailure = { _, _ -> throw IOException("logging unavailable") },
        )
        assertEquals(listOf("release", "close", "unfence"), events)
    }

    @Test
    fun `fatal release and close errors propagate after remaining cleanup`() {
        for (fatalPhase in listOf("release", "close")) {
            val fatal = OutOfMemoryError("fatal cleanup")
            val events = mutableListOf<String>()
            val actual =
                assertFails {
                    cleanupPluginUpdateLease(
                        release = {
                            events += "release"
                            if (fatalPhase == "release") throw fatal
                        },
                        close = {
                            events += "close"
                            if (fatalPhase == "close") throw fatal
                        },
                        afterClose = { events += "unfence" },
                        reportFailure = { _, _ -> error("Fatal errors must not become warnings") },
                    )
                }
            assertSame(fatal, actual)
            assertEquals(listOf("release", "close", "unfence"), events)
        }
    }

    @Test
    fun `fatal diagnostics propagate after channel close and unfencing`() {
        val fatal = NoClassDefFoundError("logger")
        val events = mutableListOf<String>()
        val actual =
            assertFails {
                cleanupPluginUpdateLease(
                    release = {
                        events += "release"
                        throw IOException("release")
                    },
                    close = { events += "close" },
                    afterClose = { events += "unfence" },
                    reportFailure = { _, _ -> throw fatal },
                )
            }
        assertSame(fatal, actual)
        assertEquals(listOf("release", "close", "unfence"), events)
    }
}
