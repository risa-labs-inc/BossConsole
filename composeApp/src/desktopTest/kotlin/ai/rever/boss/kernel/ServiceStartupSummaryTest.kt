package ai.rever.boss.kernel

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceStartupSummaryTest {
    @Test
    fun `partial startup retains missing and failed services in the same notice`() {
        val summary = serviceStartupSummary(2, listOf("auth"), listOf("editor"))
        assertTrue(summary.contains("2 service(s) spawned"))
        assertTrue(summary.startsWith("Missing JARs: auth"))
        assertTrue(summary.contains("Failed to spawn: editor"))
        assertTrue(summary.contains("readiness not verified"))
    }

    @Test
    fun `a fully successful spawn raises no user notice`() {
        // BossConsole#450's review: reporting every healthy KERNEL launch toasted a
        // developer-worded summary for 12 seconds; only missing/failed services are news.
        assertFalse(serviceStartupSummaryNeedsNotice(emptyList(), emptyList()))
    }

    @Test
    fun `missing jars or failed spawns raise a user notice`() {
        assertTrue(serviceStartupSummaryNeedsNotice(listOf("auth"), emptyList()))
        assertTrue(serviceStartupSummaryNeedsNotice(emptyList(), listOf("editor")))
    }
}
