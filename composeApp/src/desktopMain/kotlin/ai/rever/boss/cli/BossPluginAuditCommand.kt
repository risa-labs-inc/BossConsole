package ai.rever.boss.cli

import ai.rever.boss.plugin.PluginPersistence
import ai.rever.boss.plugin.launchpad.PluginManifest
import ai.rever.boss.plugin.launchpad.PluginPermission
import ai.rever.boss.plugin.loader.PluginManifestReader
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
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
    override fun help(context: Context) =
        "Aggregates permissions and MCP tools across every installed plugin"

    private val logger = BossLogger.forComponent("BossPluginAuditCommand")

    val json by option("--json", help = "Output machine-readable JSON").flag(default = false)

    override fun run() {
        val installed = PluginPersistence.getInstalledPlugins()
        val report = collectAudit(installed, logger)
        renderAndExit(report, json)
    }

    private data class McpToolRow(
        val pluginId: String,
        val toolName: String,
        val adminOnly: Boolean,
    )

    private data class UnreadableRow(
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
            logger: BossLogger,
        ): AuditReport {
            val byPermission = sortedMapOf<String, MutableList<String>>()
            val byUnrecognised = sortedMapOf<String, MutableList<String>>()
            val mcpTools = mutableListOf<McpToolRow>()
            val unreadable = mutableListOf<UnreadableRow>()
            var readableCount = 0
            for (entry in installed) {
                val jar = File(entry.jarPath)
                val manifest: PluginManifest? =
                    if (!jar.exists()) {
                        unreadable += UnreadableRow(entry.pluginId, entry.installedVersion, "jar missing")
                        null
                    } else {
                        try {
                            readableCount += 1
                            PluginManifestReader.readFromJar(entry.jarPath)
                        } catch (e: Exception) {
                            unreadable +=
                                UnreadableRow(
                                    entry.pluginId,
                                    entry.installedVersion,
                                    "manifest parse failed: ${e.message ?: e.javaClass.simpleName}",
                                )
                            logger.warn(
                                LogCategory.SYSTEM,
                                "audit: failed to read manifest for ${entry.pluginId}",
                                error = e,
                            )
                            null
                        }
                    }
                if (manifest != null) {
                    for (permission in manifest.requiredPermissions) {
                        val bucket =
                            if (PluginPermission.isValid(permission)) byPermission
                            else byUnrecognised
                        bucket.getOrPut(permission) { mutableListOf() }.add(entry.pluginId)
                    }
                    for (tool in manifest.mcpTools) {
                        mcpTools += McpToolRow(entry.pluginId, tool.name, tool.adminOnly)
                    }
                }
            }
            for (bucket in byPermission.values) bucket.sort()
            for (bucket in byUnrecognised.values) bucket.sort()
            mcpTools.sortWith(compareBy({ it.pluginId }, { it.toolName }))
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
         * Render an [AuditReport] to stdout (or stderr on partial failure) and
         * throw [ProgramResult] when the report carries findings the operator
         * should investigate (unrecognised permissions or unreadable installs).
         *
         * Splitting render from collect keeps `run()` short and lets the same
         * data feed a future JSON output without re-walking the manifest path.
         */
        @Suppress("ComplexMethod")
        fun renderAndExit(
            report: AuditReport,
            json: Boolean,
        ) {
            if (report.total == 0) {
                if (json) {
                    echo(
                        buildJsonObject {
                            put("status", "no_plugins")
                            put("total", 0)
                            put("permissions", buildJsonObject { })
                            put("unrecognisedPermissions", buildJsonObject { })
                            put("mcpTools", buildJsonArray { })
                            put("adminOnlyMcpTools", 0)
                            put("nonAdminMcpTools", 0)
                            put("unreadable", buildJsonArray { })
                        }.toString(),
                    )
                } else {
                    echo("No plugins installed.")
                }
                return
            }
            if (json) {
                echo(
                    buildJsonObject {
                        put("status", "ok")
                        put("total", report.total)
                        put("readable", report.readable)
                        put("unreadable", report.unreadable)
                        put(
                            "permissions",
                            buildJsonObject {
                                report.byPermission.forEach { (permission, plugins) ->
                                    put(
                                        permission,
                                        buildJsonArray {
                                            plugins.forEach { add(it) }
                                        },
                                    )
                                }
                            },
                        )
                        put(
                            "unrecognisedPermissions",
                            buildJsonObject {
                                report.byUnrecognised.forEach { (permission, plugins) ->
                                    put(
                                        permission,
                                        buildJsonArray {
                                            plugins.forEach { add(it) }
                                        },
                                    )
                                }
                            },
                        )
                        put(
                            "mcpTools",
                            buildJsonArray {
                                report.mcpTools.forEach { row ->
                                    addJsonObject {
                                        put("plugin", row.pluginId)
                                        put("tool", row.toolName)
                                        put("adminOnly", row.adminOnly)
                                    }
                                }
                            },
                        )
                        put("adminOnlyMcpTools", report.adminOnlyCount)
                        put("nonAdminMcpTools", report.nonAdminCount)
                        put(
                            "unreadable",
                            buildJsonArray {
                                report.unreadableEntries.forEach { row ->
                                    addJsonObject {
                                        put("plugin", row.pluginId)
                                        put("version", row.installedVersion ?: "unknown")
                                        put("reason", row.reason)
                                    }
                                }
                            },
                        )
                    }.toString(),
                )
            } else {
                echo(
                    "Permission surface across ${report.readable} of ${report.total} installed plugin(s):",
                )
                echo("")
                for ((permission, plugins) in report.byPermission) {
                    echo("  $permission  (${plugins.size} plugin${if (plugins.size == 1) "" else "s"})")
                    for (plugin in plugins) {
                        echo("    - $plugin")
                    }
                }
                if (report.byUnrecognised.isNotEmpty()) {
                    echo("")
                    echo("Unrecognised permissions (NOT in the host vocabulary):")
                    for ((permission, plugins) in report.byUnrecognised) {
                        echo("  $permission  (${plugins.size} plugin${if (plugins.size == 1) "" else "s"})")
                        for (plugin in plugins) {
                            echo("    - $plugin")
                        }
                    }
                }
                echo("")
                echo(
                    "MCP tools declared: ${report.mcpTools.size} " +
                        "(admin-only=${report.adminOnlyCount}, " +
                        "callable-by-any-agent=${report.nonAdminCount})",
                )
                for (row in report.mcpTools) {
                    val scope = if (row.adminOnly) "admin" else "any-agent"
                    echo("  [$scope] ${row.pluginId}.${row.toolName}")
                }
                if (report.unreadableEntries.isNotEmpty()) {
                    echo("")
                    echo("Unreadable installs (recorded but jar gone or manifest broken):")
                    for (row in report.unreadableEntries) {
                        echo("  ${row.pluginId}@${row.installedVersion ?: "unknown"}  -  ${row.reason}")
                    }
                }
            }
            if (report.byUnrecognised.isNotEmpty() || report.unreadableEntries.isNotEmpty()) {
                throw ProgramResult(2)
            }
        }
    }
}
