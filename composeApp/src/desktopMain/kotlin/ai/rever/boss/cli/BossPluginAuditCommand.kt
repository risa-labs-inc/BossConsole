package ai.rever.boss.cli

import ai.rever.boss.plugin.PluginPersistence
import ai.rever.boss.plugin.launchpad.PluginManifest
import ai.rever.boss.plugin.launchpad.PluginPermission
import ai.rever.boss.plugin.launchpad.PluginValidator
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.ComponentLogger
import ai.rever.boss.utils.logging.LogCategory
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Cross-plugin permission and capability surface audit.
 *
 * The companion to `boss plugin inspect` (per-plugin): reads every installed plugin's
 * manifest and aggregates:
 *
 *  - **Permission surface** — which plugins request each canonical permission, plus
 *    any unrecognised identifiers that are NOT in [PluginPermission] and so bypass the
 *    host's vocabulary check (the kind a hostile plugin could hide behind).
 *  - **MCP tool surface** — every declared MCP tool, with the ones that are NOT
 *    `adminOnly = true` flagged: an agent running without admin scope can still
 *    invoke them, so they are the larger blast radius and worth surfacing.
 *  - **Unreadable installs** — recorded in `installed.json` but the jar has gone
 *    missing or the manifest was never parsed. The tool still runs and lists them
 *    under a separate section so the user knows they are stale rather than
 *    silently dropping them.
 *
 * The output is the answer to "what can every plugin I have installed do?" - the
 * plugin store never shows this; an inspector sees it one plugin at a time. Two
 * `boss plugin inspect <a>` calls is not the same question; this is the answer.
 *
 * Usage: `boss plugin audit [--json]`
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
class BossPluginAuditCommand : CliktCommand(name = "audit") {
    override fun help(context: Context) = "Aggregates permissions and MCP tools across every installed plugin"

    private val logger = BossLogger.forComponent("BossPluginAuditCommand")

    val json by option("--json", help = "Output machine-readable JSON").flag(default = false)

    override fun run() {
        val installed = PluginPersistence.getInstalledPlugins()
        val report = collectAudit(installed, logger)
        renderAndExit(report, json)
    }

    data class McpToolRow(
        val pluginId: String,
        val toolName: String,
        val adminOnly: Boolean,
    )

    data class UnreadableRow(
        val pluginId: String,
        val installedVersion: String?,
        val reason: String,
    )

    /**
     * One audit's worth of data, after aggregation. Pure data, no I/O - testable
     * without standing up PluginPersistence or reading jars.
     */
    data class AuditReport(
        val total: Int,
        val readable: Int,
        val unreadable: Int,
        val byPermission: Map<String, List<String>>,
        val byUnrecognised: Map<String, List<String>>,
        val mcpTools: List<McpToolRow>,
        val unreadableEntries: List<UnreadableRow>,
    ) {
        val adminOnlyCount: Int = mcpTools.count { it.adminOnly }
        val nonAdminCount: Int = mcpTools.size - adminOnlyCount
    }

    @Suppress("TooManyFunctions") // collectAudit's helpers: read/record split keeps each flat
    companion object {
        /**
         * Aggregate one audit's data from a list of installed plugin entries.
         *
         * Splitting the read step from the render step is what makes this testable
         * without a plugin directory on disk: the test can hand-build entries whose
         * `jarPath` points into a temp dir it owns, and the rest of the surface
         * follows from the manifest it writes.
         */
        fun collectAudit(
            installed: List<PluginPersistence.InstalledPluginEntry>,
            logger: ComponentLogger,
        ): AuditReport {
            val byPermission = sortedMapOf<String, MutableList<String>>()
            val byUnrecognised = sortedMapOf<String, MutableList<String>>()
            val mcpTools = mutableListOf<McpToolRow>()
            val unreadable = mutableListOf<UnreadableRow>()
            var readableCount = 0
            for (entry in installed) {
                val manifest = readManifestOf(entry, unreadable)
                if (manifest != null) {
                    readableCount += 1
                    recordPermissionsOf(manifest, entry.pluginId, byPermission, byUnrecognised)
                    recordToolsOf(manifest, entry.pluginId, mcpTools)
                }
            }
            for (bucket in byPermission.values) bucket.sort()
            for (bucket in byUnrecognised.values) bucket.sort()
            mcpTools.sortWith(compareBy({ it.pluginId }, { it.toolName }))
            logger.debug(
                LogCategory.SYSTEM,
                "audit: aggregated ${installed.size} plugin(s), $readableCount readable, ${unreadable.size} unreadable",
            )
            return AuditReport(
                total = installed.size,
                readable = readableCount,
                unreadable = unreadable.size,
                byPermission = byPermission,
                byUnrecognised = byUnrecognised,
                mcpTools = mcpTools.toList(),
                unreadableEntries = unreadable.toList(),
            )
        }

        /**
         * One entry's manifest, or null with the failure recorded in [unreadable].
         *
         * Extracted so [collectAudit] stays flat: the read's three failure modes
         * (jar missing, not a jar, manifest unparseable) all surface as a row the
         * operator can act on, and the specific [PluginManifestException] is the
         * documented failure type of [PluginManifestReader.readFromJar] - anything
         * else escaping it would be a fault worth a stack trace, not an audit row.
         */
        private fun recordPermissionsOf(
            manifest: PluginManifest,
            pluginId: String,
            byPermission: MutableMap<String, MutableList<String>>,
            byUnrecognised: MutableMap<String, MutableList<String>>,
        ) {
            for (permission in manifest.requiredPermissions) {
                val bucket =
                    if (PluginPermission.isValid(permission)) byPermission else byUnrecognised
                bucket.getOrPut(permission) { mutableListOf() }.add(pluginId)
            }
        }

        private fun recordToolsOf(
            manifest: PluginManifest,
            pluginId: String,
            mcpTools: MutableList<McpToolRow>,
        ) {
            for (tool in manifest.mcpTools) {
                mcpTools += McpToolRow(pluginId, tool.name, tool.adminOnly)
            }
        }

        private fun readManifestOf(
            entry: PluginPersistence.InstalledPluginEntry,
            unreadable: MutableList<UnreadableRow>,
        ): PluginManifest? {
            val jar = File(entry.jarPath)
            if (!jar.exists()) {
                unreadable += UnreadableRow(entry.pluginId, entry.installedVersion, "jar missing")
                return null
            }
            val manifest = PluginValidator.readManifestFromJar(jar)
            if (manifest == null) {
                unreadable +=
                    UnreadableRow(
                        entry.pluginId,
                        entry.installedVersion,
                        "manifest missing or unparseable",
                    )
            }
            return manifest
        }

        /**
         * Render an [AuditReport] to stdout (or stderr on partial failure) and
         * throw [ProgramResult] when the report carries findings the operator
         * should investigate (unrecognised permissions or unreadable installs).
         *
         * Splitting render from collect keeps `run()` short and lets the same
         * data feed a future JSON output without re-walking the manifest path.
         */
        fun renderAndExit(
            report: AuditReport,
            json: Boolean,
        ) {
            if (report.total == 0) {
                if (json) println(emptyReportJson()) else println("No plugins installed.")
                return
            }
            if (json) println(fullReportJson(report)) else renderHuman(report)
            if (report.byUnrecognised.isNotEmpty() || report.unreadableEntries.isNotEmpty()) {
                throw ProgramResult(2)
            }
        }

        /** The zero-plugins report, machine-readable shape. */
        private fun emptyReportJson(): String =
            buildJsonObject {
                put("status", "no_plugins")
                put("total", 0)
                put("permissions", buildJsonObject { })
                put("unrecognisedPermissions", buildJsonObject { })
                put("mcpTools", buildJsonArray { })
                put("adminOnlyMcpTools", 0)
                put("nonAdminMcpTools", 0)
                put("unreadable", buildJsonArray { })
            }.toString()

        /** The full report, machine-readable shape. */
        private fun fullReportJson(report: AuditReport): String =
            buildJsonObject {
                put("status", "ok")
                put("total", report.total)
                put("readable", report.readable)
                put("unreadable", report.unreadable)
                put("permissions", permissionMapJson(report.byPermission))
                put("unrecognisedPermissions", permissionMapJson(report.byUnrecognised))
                put("mcpTools", mcpToolsJson(report))
                put("adminOnlyMcpTools", report.adminOnlyCount)
                put("nonAdminMcpTools", report.nonAdminCount)
                put("unreadable", unreadableJson(report))
            }.toString()

        private fun permissionMapJson(byPermission: Map<String, List<String>>): JsonObject =
            buildJsonObject {
                byPermission.forEach { (permission, plugins) ->
                    put(permission, buildJsonArray { plugins.forEach { add(it) } })
                }
            }

        private fun mcpToolsJson(report: AuditReport): JsonArray =
            buildJsonArray {
                report.mcpTools.forEach { row ->
                    addJsonObject {
                        put("plugin", row.pluginId)
                        put("tool", row.toolName)
                        put("adminOnly", row.adminOnly)
                    }
                }
            }

        private fun unreadableJson(report: AuditReport): JsonArray =
            buildJsonArray {
                report.unreadableEntries.forEach { row ->
                    addJsonObject {
                        put("plugin", row.pluginId)
                        put("version", row.installedVersion ?: "unknown")
                        put("reason", row.reason)
                    }
                }
            }

        /** The full report, human-readable shape. */
        private fun renderHuman(report: AuditReport) {
            println(
                "Permission surface across ${report.readable} of ${report.total} installed plugin(s):",
            )
            println("")
            for ((permission, plugins) in report.byPermission) {
                println("  $permission  (${plugins.size} plugin${if (plugins.size == 1) "" else "s"})")
                for (plugin in plugins) {
                    println("    - $plugin")
                }
            }
            if (report.byUnrecognised.isNotEmpty()) {
                println("")
                println("Unrecognised permissions (NOT in the host vocabulary):")
                for ((permission, plugins) in report.byUnrecognised) {
                    println("  $permission  (${plugins.size} plugin${if (plugins.size == 1) "" else "s"})")
                    for (plugin in plugins) {
                        println("    - $plugin")
                    }
                }
            }
            println("")
            println(
                "MCP tools declared: ${report.mcpTools.size} " +
                    "(admin-only=${report.adminOnlyCount}, " +
                    "callable-by-any-agent=${report.nonAdminCount})",
            )
            for (row in report.mcpTools) {
                val scope = if (row.adminOnly) "admin" else "any-agent"
                println("  [$scope] ${row.pluginId}.${row.toolName}")
            }
            if (report.unreadableEntries.isNotEmpty()) {
                println("")
                println("Unreadable installs (recorded but jar gone or manifest broken):")
                for (row in report.unreadableEntries) {
                    println("  ${row.pluginId}@${row.installedVersion ?: "unknown"}  -  ${row.reason}")
                }
            }
        }
    }
}
