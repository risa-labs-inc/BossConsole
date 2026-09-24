package ai.rever.boss.cli

import ai.rever.boss.plugin.launchpad.HostMeta
import ai.rever.boss.plugin.launchpad.PluginManifest
import ai.rever.boss.plugin.launchpad.PluginPermission
import ai.rever.boss.plugin.launchpad.launchpadJson
import ai.rever.boss.plugin.loader.PluginManifestReader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Diffs two plugin manifests (jar files).
 *
 * When a plugin store update lands, the question is rarely "did the version
 * bump" - it is "what did the new jar declare that the old one did not".
 * Two `boss plugin inspect <a>` calls answered one jar at a time; this
 * answers both at once and surfaces the delta as a list of added / removed
 * permissions, MCP tools, and changes to the required API version.
 *
 * The diff is purely data - no jar is opened twice, no plugin is loaded,
 * nothing is written to disk. The output is a stable JSON-friendly shape
 * so a CI step can run this and parse the result.
 *
 * Usage:
 *   boss plugin diff <path-to-jar-a> <path-to-jar-b> [--json]
 *
 * Exit codes: 0 always (the diff is informational; the operator decides
 * what to do with it). 2 if either jar cannot be read.
 */
class BossPluginDiffCommand : CliktCommand(name = "diff") {
    override fun help(context: Context) = "Diffs two plugin jars and reports added/removed permissions and MCP tools"

    private val left by argument(help = "Path to the first plugin jar (typically the older one)")
    private val right by argument(help = "Path to the second plugin jar (typically the newer one)")
    val json by option("--json", help = "Output the diff as JSON").flag(default = false)

    override fun run() {
        val leftFile = File(left).absoluteFile
        val rightFile = File(right).absoluteFile
        if (!leftFile.exists()) {
            echo("Error: not a file: $left", err = true)
            throw ProgramResult(2)
        }
        if (!rightFile.exists()) {
            echo("Error: not a file: $right", err = true)
            throw ProgramResult(2)
        }
        val leftManifest = readOrFail(leftFile, "left")
        val rightManifest = readOrFail(rightFile, "right")
        val diff = PluginDiffer.diff(leftManifest, rightManifest)
        renderAndExit(diff, json)
    }

    private fun readOrFail(
        file: File,
        label: String,
    ): PluginManifest =
        try {
            val raw =
                PluginManifestReader.readFromJar(file.absolutePath)
                    ?: error("$label jar has no manifest entry at META-INF/boss-plugin/plugin.json")
            // PluginManifestReader returns the api-core type; convert it to the
            // launchpad shape that [PluginDiffer] and [PluginPermission] operate on.
            launchpadJson.decodeFromString(PluginManifest.serializer(), launchpadJson.encodeToString(raw))
        } catch (e: java.io.IOException) {
            echo("Error: failed to read $label manifest: ${e.message ?: e.javaClass.simpleName}", err = true)
            throw ProgramResult(2)
        } catch (e: java.io.FileNotFoundException) {
            echo("Error: failed to read $label manifest: ${e.message ?: e.javaClass.simpleName}", err = true)
            throw ProgramResult(2)
        } catch (e: kotlinx.serialization.SerializationException) {
            echo("Error: failed to read $label manifest: ${e.message ?: e.javaClass.simpleName}", err = true)
            throw ProgramResult(2)
        } catch (e: IllegalArgumentException) {
            echo("Error: failed to read $label manifest: ${e.message ?: e.javaClass.simpleName}", err = true)
            throw ProgramResult(2)
        }

    private fun renderAndExit(
        diff: PluginDiff,
        json: Boolean,
    ) {
        if (json) {
            echo(PluginDiffJson.encode(diff))
        } else {
            renderHuman(diff)
        }
    }

    private fun renderHuman(diff: PluginDiff) {
        echo(
            "Plugin diff: ${diff.leftId}@${diff.leftVersion}  →  " +
                "${diff.rightId}@${diff.rightVersion}",
        )
        if (diff.leftId != diff.rightId) {
            echo("  [warn] plugin ids differ: ${diff.leftId} vs ${diff.rightId}")
        }
        if (diff.versionDelta.isNotEmpty()) {
            echo(
                "  version: ${diff.leftVersion} → ${diff.rightVersion}  " +
                    "(${diff.versionDelta})",
            )
        }
        if (diff.apiVersionDelta != null) {
            echo("  api version: ${diff.leftApiVersion} → ${diff.rightApiVersion}")
        }
        if (diff.mainClassDelta != null) {
            echo("  main class: ${diff.leftMainClass} → ${diff.rightMainClass}")
        }
        renderPermissionBuckets(diff)
        renderUnrecognisedBuckets(diff)
        renderToolBuckets(diff)
        renderToolAdminScopeFlips(diff)
        if (!diff.hasChanges()) echo("  no manifest changes detected")
    }

    private fun renderPermissionBuckets(diff: PluginDiff) {
        if (diff.permissionsAdded.isNotEmpty()) {
            echo("  permissions added (${diff.permissionsAdded.size}):")
            for (p in diff.permissionsAdded) echo("    + $p")
        }
        if (diff.permissionsRemoved.isNotEmpty()) {
            echo("  permissions removed (${diff.permissionsRemoved.size}):")
            for (p in diff.permissionsRemoved) echo("    - $p")
        }
        if (diff.permissionsKept.isNotEmpty()) {
            echo(
                "  permissions kept (${diff.permissionsKept.size}): " +
                    diff.permissionsKept.joinToString(", "),
            )
        }
    }

    private fun renderUnrecognisedBuckets(diff: PluginDiff) {
        if (diff.unrecognisedPermissionsAdded.isNotEmpty()) {
            echo("  UNRECOGNISED permissions added (host has no handler for these):")
            for (p in diff.unrecognisedPermissionsAdded) echo("    +! $p")
        }
        if (diff.unrecognisedPermissionsRemoved.isNotEmpty()) {
            echo("  UNRECOGNISED permissions removed:")
            for (p in diff.unrecognisedPermissionsRemoved) echo("    -! $p")
        }
    }

    private fun renderToolBuckets(diff: PluginDiff) {
        if (diff.mcpToolsAdded.isNotEmpty()) {
            echo("  MCP tools added (${diff.mcpToolsAdded.size}):")
            for (t in diff.mcpToolsAdded) {
                val adminSuffix = if (t.adminOnly) " [admin]" else ""
                echo("    + ${t.pluginId}.${t.toolName}$adminSuffix")
            }
        }
        if (diff.mcpToolsRemoved.isNotEmpty()) {
            echo("  MCP tools removed (${diff.mcpToolsRemoved.size}):")
            for (t in diff.mcpToolsRemoved) {
                val adminSuffix = if (t.adminOnly) " [admin]" else ""
                echo("    - ${t.pluginId}.${t.toolName}$adminSuffix")
            }
        }
        if (diff.mcpToolsKept.isNotEmpty()) {
            echo(
                "  MCP tools kept (${diff.mcpToolsKept.size}): " +
                    diff.mcpToolsKept.joinToString(", ") { "${it.pluginId}.${it.toolName}" },
            )
        }
    }

    private fun renderToolAdminScopeFlips(diff: PluginDiff) {
        if (diff.mcpToolAdminScopeFlipped.isNotEmpty()) {
            echo("  MCP tool admin-scope flipped (tool kept but its adminOnly flag changed):")
            for (flip in diff.mcpToolAdminScopeFlipped) {
                echo(
                    "    ~ ${flip.pluginId}.${flip.toolName}: " +
                        "adminOnly ${flip.leftAdminOnly} → ${flip.rightAdminOnly}",
                )
            }
        }
    }
}

data class PluginDiff(
    val leftId: String,
    val leftVersion: String,
    val leftApiVersion: String,
    val leftMainClass: String,
    val rightId: String,
    val rightVersion: String,
    val rightApiVersion: String,
    val rightMainClass: String,
    val versionDelta: String,
    val apiVersionDelta: ApiDelta?,
    val mainClassDelta: String?,
    val permissionsAdded: List<String>,
    val permissionsRemoved: List<String>,
    val permissionsKept: List<String>,
    val unrecognisedPermissionsAdded: List<String>,
    val unrecognisedPermissionsRemoved: List<String>,
    val mcpToolsAdded: List<McpToolRow>,
    val mcpToolsRemoved: List<McpToolRow>,
    val mcpToolsKept: List<McpToolRow>,
    val mcpToolAdminScopeFlipped: List<McpToolScopeFlip>,
) {
    fun hasChanges(): Boolean =
        permissionsAdded.isNotEmpty() ||
            permissionsRemoved.isNotEmpty() ||
            unrecognisedPermissionsAdded.isNotEmpty() ||
            unrecognisedPermissionsRemoved.isNotEmpty() ||
            mcpToolsAdded.isNotEmpty() ||
            mcpToolsRemoved.isNotEmpty() ||
            mcpToolAdminScopeFlipped.isNotEmpty() ||
            apiVersionDelta != null ||
            mainClassDelta != null
}

data class McpToolRow(
    val pluginId: String,
    val toolName: String,
    val adminOnly: Boolean,
)

data class McpToolScopeFlip(
    val pluginId: String,
    val toolName: String,
    val leftAdminOnly: Boolean,
    val rightAdminOnly: Boolean,
)

data class ApiDelta(
    val left: String,
    val right: String,
)

/**
 * Pure diff logic, separated from the Clikt wiring so the precedence and
 * "unrecognised permission" rules are unit-testable against hand-built
 * manifests - no jar required.
 */
object PluginDiffer {
    fun diff(
        left: PluginManifest,
        right: PluginManifest,
    ): PluginDiff {
        val leftPerms = left.requiredPermissions.toSet()
        val rightPerms = right.requiredPermissions.toSet()
        val leftUnrec = sortedStringsOf(leftPerms.filter { !PluginPermission.isValid(it) })
        val rightUnrec = sortedStringsOf(rightPerms.filter { !PluginPermission.isValid(it) })
        val leftCanon = leftPerms - leftUnrec
        val rightCanon = rightPerms - rightUnrec

        val leftTools = left.mcpTools.map { McpToolRow(left.pluginId, it.name, it.adminOnly) }
        val rightTools = right.mcpTools.map { McpToolRow(right.pluginId, it.name, it.adminOnly) }
        val leftToolMap = leftTools.associateBy { it.toolName }
        val rightToolMap = rightTools.associateBy { it.toolName }

        val addedTools = rightTools.filter { it.toolName !in leftToolMap }
        val removedTools = leftTools.filter { it.toolName !in rightToolMap }
        val keptTools = rightTools.filter { it.toolName in leftToolMap }
        val flips =
            keptTools
                .filter {
                    leftToolMap[it.toolName]?.adminOnly != rightToolMap[it.toolName]?.adminOnly
                }.map {
                    val prior = leftToolMap.getValue(it.toolName)
                    McpToolScopeFlip(it.pluginId, it.toolName, prior.adminOnly, it.adminOnly)
                }

        val apiDelta =
            if (left.apiVersion != right.apiVersion) ApiDelta(left.apiVersion, right.apiVersion) else null
        val mainClassDelta =
            if (left.mainClass != right.mainClass) "${left.mainClass} → ${right.mainClass}" else null
        val versionDelta =
            if (left.version == right.version) {
                "no version change"
            } else {
                "${left.version} → ${right.version}"
            }

        return PluginDiff(
            leftId = left.pluginId,
            leftVersion = left.version,
            leftApiVersion = left.apiVersion,
            leftMainClass = left.mainClass,
            rightId = right.pluginId,
            rightVersion = right.version,
            rightApiVersion = right.apiVersion,
            rightMainClass = right.mainClass,
            versionDelta = versionDelta,
            apiVersionDelta = apiDelta,
            mainClassDelta = mainClassDelta,
            permissionsAdded = (rightCanon - leftCanon).sorted(),
            permissionsRemoved = (leftCanon - rightCanon).sorted(),
            permissionsKept = (leftCanon intersect rightCanon).sorted(),
            unrecognisedPermissionsAdded = (rightUnrec - leftUnrec).toList(),
            unrecognisedPermissionsRemoved = (leftUnrec - rightUnrec).toList(),
            mcpToolsAdded = addedTools,
            mcpToolsRemoved = removedTools,
            mcpToolsKept = keptTools,
            mcpToolAdminScopeFlipped = flips,
        )
    }
}

/**
 * JSON shape that is part of the CLI contract - keep keys stable across
 * PluginDiff renames.
 */
private object PluginDiffJson {
    fun encode(diff: PluginDiff): String =
        buildJsonObject {
            writeIdentity(diff)
            writePermissionBuckets(diff)
            writeToolBuckets(diff)
            writeToolAdminScopeFlips(diff)
            put("hasChanges", diff.hasChanges())
        }.toString()

    private fun kotlinx.serialization.json.JsonObjectBuilder.writeIdentity(diff: PluginDiff) {
        put("leftId", diff.leftId)
        put("leftVersion", diff.leftVersion)
        put("leftApiVersion", diff.leftApiVersion)
        put("leftMainClass", diff.leftMainClass)
        put("rightId", diff.rightId)
        put("rightVersion", diff.rightVersion)
        put("rightApiVersion", diff.rightApiVersion)
        put("rightMainClass", diff.rightMainClass)
        put("versionDelta", diff.versionDelta)
        diff.apiVersionDelta?.let {
            put(
                "apiVersionDelta",
                buildJsonObject {
                    put("left", it.left)
                    put("right", it.right)
                },
            )
        }
        diff.mainClassDelta?.let { put("mainClassDelta", it) }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.writePermissionBuckets(diff: PluginDiff) {
        put("permissionsAdded", buildJsonArray { diff.permissionsAdded.forEach { add(it) } })
        put("permissionsRemoved", buildJsonArray { diff.permissionsRemoved.forEach { add(it) } })
        put("permissionsKept", buildJsonArray { diff.permissionsKept.forEach { add(it) } })
        put(
            "unrecognisedPermissionsAdded",
            buildJsonArray { diff.unrecognisedPermissionsAdded.forEach { add(it) } },
        )
        put(
            "unrecognisedPermissionsRemoved",
            buildJsonArray { diff.unrecognisedPermissionsRemoved.forEach { add(it) } },
        )
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.writeToolBuckets(diff: PluginDiff) {
        put(
            "mcpToolsAdded",
            buildJsonArray {
                diff.mcpToolsAdded.forEach { tool ->
                    addJsonObject { toolJson(tool) }
                }
            },
        )
        put(
            "mcpToolsRemoved",
            buildJsonArray {
                diff.mcpToolsRemoved.forEach { tool ->
                    addJsonObject { toolJson(tool) }
                }
            },
        )
        put(
            "mcpToolsKept",
            buildJsonArray {
                diff.mcpToolsKept.forEach { tool ->
                    addJsonObject { toolJson(tool) }
                }
            },
        )
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.writeToolAdminScopeFlips(diff: PluginDiff) {
        put(
            "mcpToolAdminScopeFlipped",
            buildJsonArray {
                diff.mcpToolAdminScopeFlipped.forEach { flip ->
                    addJsonObject {
                        put("plugin", flip.pluginId)
                        put("tool", flip.toolName)
                        put("leftAdminOnly", flip.leftAdminOnly)
                        put("rightAdminOnly", flip.rightAdminOnly)
                    }
                }
            },
        )
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.toolJson(tool: McpToolRow) {
        put("plugin", tool.pluginId)
        put("tool", tool.toolName)
        put("adminOnly", tool.adminOnly)
    }
}

// Helper to convert any Iterable<String> into a sorted (TreeSet-backed) Set<String>.
// Used only for the unrecognised-permission buckets where ordering matters.
private fun sortedStringsOf(items: Iterable<String>): Set<String> = java.util.TreeSet<String>().apply { addAll(items) }
