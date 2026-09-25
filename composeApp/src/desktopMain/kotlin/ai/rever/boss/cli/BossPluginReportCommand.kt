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
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import java.io.File
import kotlin.io.path.Path
import kotlin.io.path.writeText

/**
 * Exports a single plugin's manifest as a printable Markdown report.
 *
 * `boss plugin inspect <jar>` answers a single line of "what is this".
 * A security review or a PR description wants a multi-section report
 * the operator can paste into a wiki or a comment. This command reads
 * the same manifest and emits a markdown document with the plugin id,
 * version, API version, main class, the resolved permissions with a
 * one-line explanation of each, and the declared MCP tools with their
 * admin scope.
 *
 * Pure transform: the jar is read once for the manifest and never
 * executed. The output goes to stdout (or `--out <path>`); nothing
 * else.
 *
 * Usage:
 *   boss plugin report <path-to.jar> [--out <markdown-path>]
 *
 * Exit codes: 0 always (informational). 2 if the jar cannot be read
 * or its manifest cannot be parsed.
 */
class BossPluginReportCommand : CliktCommand(name = "report") {
    override fun help(context: Context) = "Exports a plugin jar's manifest as a printable Markdown report"

    private val jarPath by argument(help = "Path to the plugin jar to report on")
    val out: String? by option("--out", help = "Write the report to this path instead of stdout")

    override fun run() {
        val file = File(jarPath).absoluteFile
        if (!file.isFile) {
            echo("Error: not a file: $jarPath", err = true)
            throw ProgramResult(2)
        }
        val manifest =
            runCatching { PluginManifestReader.readFromJar(file.absolutePath) }
                .getOrElse {
                    echo("Error: failed to read manifest: ${it.message ?: it.javaClass.simpleName}", err = true)
                    throw ProgramResult(2)
                } ?: run {
                echo("Error: jar has no manifest entry at META-INF/boss-plugin/plugin.json", err = true)
                throw ProgramResult(2)
            }
        // The reader returns the api-core PluginManifest; convert to the
        // launchpad shape that the report generator operates on.
        val launchpad =
            launchpadJson.decodeFromString(
                PluginManifest.serializer(),
                launchpadJson.encodeToString(manifest),
            )
        val markdown = PluginReportMarkdown.render(launchpad)
        if (out != null) {
            Path(out!!).writeText(markdown)
        } else {
            echo(markdown)
        }
    }
}

/**
 * Pure markdown renderer. The output is grouped into sections an
 * operator can lift directly into a wiki template; each permission has
 * a one-line explanation so a non-engineer reading the report can
 * understand what "filesystem" grants without consulting the host
 * source.
 */
object PluginReportMarkdown {
    /** Manifest strings are untrusted, even when they came from a readable jar. */
    private fun singleLine(value: String): String = value.map { if (it.isISOControl()) ' ' else it }.joinToString("")

    private fun code(value: String): String {
        val content = singleLine(value)
        val fence = "`".repeat((Regex("`+").findAll(content).maxOfOrNull { it.value.length } ?: 0) + 1)
        val padding = if (content.startsWith('`') || content.endsWith('`')) " " else ""
        return "$fence$padding$content$padding$fence"
    }

    private fun text(value: String): String =
        buildString {
            for (char in singleLine(value)) {
                append(
                    when (char) {
                        '&' -> "&amp;"
                        '<' -> "&lt;"
                        '>' -> "&gt;"
                        else -> if (char in "\\`*_{}[]()#+-.!|") "\\$char" else char.toString()
                    },
                )
            }
        }

    private val permissionDescriptions: Map<String, String> =
        mapOf(
            "network" to "Read or open network sockets to remote hosts",
            "filesystem" to "Read, write, or delete files anywhere the host process can reach",
            "terminal" to "Spawn shell commands inside a terminal tab",
            "browser" to "Open or navigate browser tabs in the host",
            "notifications" to "Display OS-level desktop notifications",
            "auth" to "Read or write the authenticated session",
            "mcp" to "Declare and register MCP tools that other agents can call",
            "editor" to "Read or write editor buffers, LSP state, and open files",
            "clipboard" to "Read from or write to the system clipboard",
            "settings" to "Read or change host settings (saved preferences, keybindings, ...)",
            "system" to "Capture screens, control mouse / keyboard, or query OS",
            "storage" to "Read or write the plugin's persistent key/value store",
        )

    fun render(manifest: PluginManifest): String =
        buildString {
            appendLine("# Plugin report — ${code(manifest.pluginId)}")
            appendLine()
            appendLine("- Plugin id:    ${code(manifest.pluginId)}")
            appendLine("- Display name: ${code(manifest.displayName)}")
            appendLine("- Version:      ${code(manifest.version)}")
            appendLine("- API version:  ${code(manifest.apiVersion)} (host: `${HostMeta.CURRENT_API_VERSION}`)")
            appendLine("- Main class:   ${code(manifest.mainClass)}")
            if (manifest.author.isNotBlank()) appendLine("- Author:       ${code(manifest.author)}")
            if (manifest.license.isNotBlank()) appendLine("- License:      ${code(manifest.license)}")
            if (manifest.description.isNotBlank()) appendLine()
            if (manifest.description.isNotBlank()) appendLine("> ${text(manifest.description)}")
            appendLine()

            appendLine("## Permissions")
            appendLine()
            if (manifest.requiredPermissions.isEmpty()) {
                appendLine("_None declared._")
            } else {
                for (p in manifest.requiredPermissions) {
                    val canonical = if (PluginPermission.isValid(p)) "canonical" else "**UNRECOGNISED**"
                    val desc = permissionDescriptions[p] ?: "(no description for this permission id)"
                    appendLine("- ${code(p)} — $canonical — $desc")
                }
            }
            appendLine()

            appendLine("## MCP tools")
            appendLine()
            if (manifest.mcpTools.isEmpty()) {
                appendLine("_None declared._")
            } else {
                appendLine("| Tool name | Description | Admin-only |")
                appendLine("| --- | --- | --- |")
                for (t in manifest.mcpTools) {
                    val admin = if (t.adminOnly) "yes" else "no"
                    appendLine("| ${code(t.name)} | ${text(t.description)} | $admin |")
                }
            }
            appendLine()

            appendLine("## Review checklist")
            appendLine()
            appendLine("- [ ] Plugin id matches the store page")
            appendLine("- [ ] Version is the one expected for this update")
            appendLine("- [ ] API version is ≤ the host's API version")
            appendLine("- [ ] Each declared permission is justified in the PR description")
            appendLine("- [ ] No unrecognised permissions in the list above")
            appendLine("- [ ] MCP tools marked `admin-only: yes` cannot be invoked by an agent without admin scope")
        }
}
