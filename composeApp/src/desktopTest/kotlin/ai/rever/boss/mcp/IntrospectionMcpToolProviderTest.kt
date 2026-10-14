package ai.rever.boss.mcp

import ai.rever.boss.health.HealthArea
import ai.rever.boss.health.HealthFinding
import ai.rever.boss.health.HealthSeverity
import ai.rever.boss.health.WorkspaceHealthReport
import ai.rever.boss.health.toJson
import ai.rever.boss.performance.CpuMetrics
import ai.rever.boss.performance.GcMetrics
import ai.rever.boss.performance.HealthStatus
import ai.rever.boss.performance.MemoryMetrics
import ai.rever.boss.performance.PerformanceHealth
import ai.rever.boss.performance.PerformanceSnapshot
import ai.rever.boss.performance.ResourceMetrics
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the provider through its injected suppliers, so nothing here needs a running
 * monitor, a window, or a desktop.
 */
class IntrospectionMcpToolProviderTest {
    @AfterTest
    fun reset() {
        IntrospectionMcpToolProvider.healthSupplier = null
        IntrospectionMcpToolProvider.performanceSupplier = null
    }

    private fun snapshot(browserTabCount: Int = 0) =
        PerformanceSnapshot(
            timestamp = 1_700_000_000_000L,
            memory =
                MemoryMetrics(
                    heapUsedBytes = 512L * 1024 * 1024,
                    heapMaxBytes = 2048L * 1024 * 1024,
                    heapCommittedBytes = 1024L * 1024 * 1024,
                    nonHeapUsedBytes = 64L * 1024 * 1024,
                    nonHeapCommittedBytes = 96L * 1024 * 1024,
                ),
            cpu =
                CpuMetrics(
                    processLoad = 0.25,
                    systemLoad = 0.5,
                    availableProcessors = 8,
                    activeThreadCount = 42,
                ),
            gc =
                GcMetrics(
                    collectionCount = 7,
                    collectionTimeMs = 120,
                    gcTimeSinceLastSampleMs = 3,
                    gcCollectors = emptyList(),
                ),
            resources =
                ResourceMetrics(
                    browserTabCount = browserTabCount,
                    terminalCount = 2,
                    editorTabCount = 5,
                    panelCount = 3,
                    windowCount = 1,
                ),
        )

    // region health

    @Test
    fun `health reports the same wire shape boss status emits`() {
        IntrospectionMcpToolProvider.healthSupplier = {
            WorkspaceHealthReport(
                findings =
                    listOf(
                        HealthFinding(
                            area = HealthArea.PLUGINS,
                            severity = HealthSeverity.WARNING,
                            code = "plugin_needs_attention",
                            summary = "fluck-browser is unhealthy",
                            subject = "fluck-browser",
                            remedy = "Restart the plugin from the health centre",
                        ),
                    ),
            )
        }

        val json = IntrospectionMcpToolProvider.healthJson()
        assertTrue(json["available"]!!.jsonPrimitive.boolean)
        val health = json["health"]!!.jsonObject
        assertTrue(health["degraded"]!!.jsonPrimitive.boolean)

        val finding = health["findings"]!!.jsonArray.single().jsonObject
        // The codes are the contract scripts match on; they must survive verbatim.
        assertEquals("plugin_needs_attention", finding["code"]!!.jsonPrimitive.content)
        assertEquals("plugins", finding["area"]!!.jsonPrimitive.content)
        assertEquals("warning", finding["severity"]!!.jsonPrimitive.content)
        assertEquals("fluck-browser", finding["subject"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an unchecked area is not reported as healthy`() {
        IntrospectionMcpToolProvider.healthSupplier = {
            WorkspaceHealthReport(findings = emptyList(), unchecked = setOf(HealthArea.PLUGINS))
        }

        val health = IntrospectionMcpToolProvider.healthJson()["health"]!!.jsonObject
        // No findings, but plugins were never read - an agent must be able to tell the
        // difference between "nothing is wrong" and "nobody looked".
        assertFalse(health["degraded"]!!.jsonPrimitive.boolean)
        assertEquals(
            "plugins",
            health["unchecked"]!!
                .jsonArray
                .single()
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `the health value is the report's own json, not a second shape`() {
        // surfthewave-commits' first acceptance item on #1364: the object an agent reads
        // must be the one `boss status --json` emits, so the two can be diffed on one
        // machine. Pinned by equality against toJson() rather than by field-spotting.
        val report =
            WorkspaceHealthReport(
                findings =
                    listOf(
                        HealthFinding(
                            area = HealthArea.MCP,
                            severity = HealthSeverity.CRITICAL,
                            code = "mcp_tools_withheld",
                            summary = "every MCP tool is withheld",
                        ),
                    ),
                unchecked = setOf(HealthArea.BROWSER),
                partial = setOf(HealthArea.PLUGINS),
            )
        IntrospectionMcpToolProvider.healthSupplier = { report }

        assertEquals(report.toJson(), IntrospectionMcpToolProvider.healthJson()["health"])
    }

    @Test
    fun `health says so when the host never wired a supplier`() {
        val json = IntrospectionMcpToolProvider.healthJson()
        assertFalse(json["available"]!!.jsonPrimitive.boolean)
        assertNull(json["health"])
    }

    @Test
    fun `a throwing health source is reported, not propagated`() {
        IntrospectionMcpToolProvider.healthSupplier = { error("collector exploded") }

        val json = IntrospectionMcpToolProvider.healthJson()
        assertFalse(json["available"]!!.jsonPrimitive.boolean)
        assertTrue(json["detail"]!!.jsonPrimitive.content.contains("collector exploded"))
    }

    // endregion

    // region performance

    @Test
    fun `performance reports counts and aggregates`() {
        IntrospectionMcpToolProvider.performanceSupplier = {
            PerformanceReading(
                snapshot = snapshot(),
                health = PerformanceHealth(HealthStatus.GOOD, HealthStatus.WARNING, HealthStatus.WARNING),
            )
        }

        val json = IntrospectionMcpToolProvider.performanceJson()
        assertTrue(json["sampling"]!!.jsonPrimitive.boolean)
        assertEquals(
            536870912L,
            json["memory"]!!
                .jsonObject["heapUsedBytes"]!!
                .jsonPrimitive.content
                .toLong(),
        )
        assertEquals(
            8,
            json["cpu"]!!
                .jsonObject["availableProcessors"]!!
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals(
            2,
            json["resources"]!!
                .jsonObject["terminalCount"]!!
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals("warning", json["health"]!!.jsonObject["overall"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a missing snapshot is reported as not sampling, never as zero`() {
        IntrospectionMcpToolProvider.performanceSupplier = { PerformanceReading(snapshot = null, health = null) }

        val json = IntrospectionMcpToolProvider.performanceJson()
        assertTrue(json["available"]!!.jsonPrimitive.boolean)
        assertFalse(json["sampling"]!!.jsonPrimitive.boolean)
        // The dangerous failure is a caller reading 0 bytes used and concluding it has
        // headroom, so no memory object may appear at all.
        assertNull(json["memory"])
    }

    @Test
    fun `per-tab detail never leaves the process`() {
        // #1524 compacted retained snapshots: ResourceMetrics no longer holds per-tab detail
        // at all, so there is nothing per-tab to leak. What remains worth pinning is the wire
        // shape: the count is reported, and no tab collection key ever appears in the output.
        IntrospectionMcpToolProvider.performanceSupplier = {
            PerformanceReading(
                snapshot = snapshot(browserTabCount = 1),
                health = null,
            )
        }

        val text = IntrospectionMcpToolProvider.performanceJson().toString()
        assertFalse(text.contains("browserTabs"), "a per-tab collection reached the tool output")
        assertFalse(text.contains("tabTitle"), "a tab title field reached the tool output")
        assertTrue(text.contains("\"browserTabCount\":1"), "the count should still be reported")
    }

    @Test
    fun `performance says so when the host never wired a supplier`() {
        val json = IntrospectionMcpToolProvider.performanceJson()
        assertFalse(json["available"]!!.jsonPrimitive.boolean)
        assertNull(json["sampling"])
    }

    // endregion

    // region registration

    @Test
    fun `both tools are exposed under both names and all are read-only`() {
        val names = IntrospectionMcpToolProvider.tools().map { it.name }.toSet()
        assertEquals(
            setOf("get_workspace_health", "workspace_health", "get_performance_metrics", "performance_metrics"),
            names,
        )
        assertTrue(
            IntrospectionMcpToolProvider.tools().all { it.readOnly },
            "an observation tool that declares itself mutating would be gated for no reason",
        )
    }

    @Test
    fun `the provider id does not collide with the workspace provider`() {
        assertEquals("boss-introspection", IntrospectionMcpToolProvider.providerId)
        assertTrue(IntrospectionMcpToolProvider.providerId != WorkspaceMcpToolProvider.providerId)
    }

    // endregion
}
