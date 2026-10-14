package ai.rever.boss.cli

import ai.rever.boss.plugin.packs.InstalledPlugin
import ai.rever.boss.plugin.packs.PackApplyResult
import ai.rever.boss.plugin.packs.PackApplyStatus
import ai.rever.boss.plugin.packs.PackJob
import ai.rever.boss.plugin.packs.PackJobState
import ai.rever.boss.plugin.packs.PackPlugin
import ai.rever.boss.plugin.packs.PackSnapshot
import ai.rever.boss.plugin.packs.PluginPack
import ai.rever.boss.plugin.packs.PluginPackJson
import ai.rever.boss.plugin.packs.PluginPackPlanner
import ai.rever.boss.plugin.packs.PluginResult
import ai.rever.boss.plugin.packs.PluginResultKind
import ai.rever.boss.plugin.packs.StoreListing
import ai.rever.boss.startup.CliBootstrap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `boss pack`: headless dispatch, the exit code a script branches on, and that the human output
 * renders the host's JSON rather than dropping rows.
 */
class BossPackCliTest {
    @Test
    fun `boss pack runs headless, without booting the GUI`() {
        assertTrue(CliBootstrap.isHeadlessCli(arrayOf("pack", "plan", "team.json")))
        assertTrue(CliBootstrap.isHeadlessCli(arrayOf("pack", "apply", "--wait", "team.json")))
    }

    @Test
    fun `exit codes separate applied, partial and failed`() {
        fun job(status: String?) = JsonObject(buildMap { status?.let { put("status", JsonPrimitive(it)) } })

        assertEquals(0, exitCodeFor(job("applied")))
        assertEquals(0, exitCodeFor(job("already_satisfied")))
        assertEquals(EXIT_PACK_PARTIAL, exitCodeFor(job("partial")))
        assertEquals(1, exitCodeFor(job("failed")))
        assertEquals(1, exitCodeFor(job(null)), "a crashed job has no status and must not read as success")
    }

    @Test
    fun `the plan output shows every row from the host's JSON`() {
        val plugins = listOf(PackPlugin("a.present", null, false), PackPlugin("b.missing", null, true))
        val pack = PluginPack("team", plugins, emptyList())
        val snapshot =
            PackSnapshot(
                installed = mapOf("a.present" to InstalledPlugin("1.0.0", true)),
                store = mapOf("b.missing" to StoreListing.Published("3.1.0", setOf("3.1.0"))),
                toolRules = emptyMap(),
                providerRules = emptyMap(),
                policyReadable = true,
            )

        val text = formatPackPlan(PluginPackJson.plan(PluginPackPlanner.plan(pack, snapshot)))

        assertTrue("satisfied" in text && "a.present" in text, text)
        assertTrue("install" in text && "b.missing (optional)" in text && "3.1.0" in text, text)
    }

    @Test
    fun `the job output names the result of each row`() {
        val step =
            PluginPackPlanner
                .plan(
                    PluginPack("team", listOf(PackPlugin("a", null, false)), emptyList()),
                    emptySnapshot(),
                ).plugins
                .single()
        val job =
            PackJob(
                id = "abc12345",
                packId = "team",
                state = PackJobState.FINISHED,
                result =
                    PackApplyResult(
                        "team",
                        PackApplyStatus.PARTIAL,
                        listOf(PluginResult(step, PluginResultKind.FAILED, "offline")),
                        emptyList(),
                    ),
            )

        val text = formatPackJob(PluginPackJson.job(job))

        assertTrue("abc12345" in text && "partial" in text, text)
        assertTrue("failed" in text && "offline" in text, text)
    }

    private fun emptySnapshot() = PackSnapshot(emptyMap(), emptyMap(), emptyMap(), emptyMap(), policyReadable = true)
}
