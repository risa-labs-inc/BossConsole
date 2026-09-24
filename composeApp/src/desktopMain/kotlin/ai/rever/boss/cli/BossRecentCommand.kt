package ai.rever.boss.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Lists the recent projects BOSS has on disk.
 *
 * The host stores up to ten recent projects in `~/.boss/recent-projects.json`
 * so the new-tab dialog can offer them after a fresh start. This command
 * reads the same file, applies the same "drop entries whose directory
 * no longer exists" filter the host does on load, and prints the result
 * so an operator can answer "what was I last working on" without
 * opening BOSS.
 *
 * The output is the same shape the host uses internally (name, path,
 * lastOpened), so a shell can pipe it into a quick `cd $(...)` or feed
 * it to `boss folder`.
 *
 * Usage:
 *   boss recent [--limit <n>] [--json]
 *
 * Exit codes: 0 always (informational). 2 if the recent-projects file
 * is present but unreadable, or if the operator's home directory
 * cannot be located.
 */
class BossRecentCommand : CliktCommand(name = "recent") {
    override fun help(context: Context) = "Lists the projects BOSS has recorded in ~/.boss/recent-projects.json"

    val limit by option("--limit", help = "Maximum number of entries to print (default 10)").int().default(10)
    val json by option("--json", help = "Output as JSON").flag(default = false)

    override fun run() {
        val file = recentProjectsFile()
        val projects =
            if (!file.exists()) {
                emptyList()
            } else {
                runCatching {
                    Json { ignoreUnknownKeys = true }
                        .decodeFromString<List<RecentProject>>(file.readText(Charsets.UTF_8))
                        .filter { File(it.path).isDirectory }
                }.getOrElse {
                    val detail = it.message ?: it.javaClass.simpleName
                    echo("Error: failed to parse recent-projects.json: $detail", err = true)
                    throw ProgramResult(2)
                }
            }
        renderAndExit(projects, json)
    }

    private fun recentProjectsFile(): File {
        val home =
            System.getProperty("user.home") ?: run {
                echo("Error: cannot resolve user.home", err = true)
                throw ProgramResult(2)
            }
        return File(home, ".boss/recent-projects.json")
    }

    private fun renderAndExit(
        projects: List<RecentProject>,
        json: Boolean,
    ) {
        val limited = projects.take(limit)
        if (json) {
            echo(
                buildJsonObject {
                    put(
                        "projects",
                        buildJsonArray {
                            limited.forEach { p ->
                                addJsonObject {
                                    put("name", p.name)
                                    put("path", p.path)
                                    put("lastOpened", p.lastOpened)
                                }
                            }
                        },
                    )
                }.toString(),
            )
        } else {
            if (limited.isEmpty()) {
                echo("No recent projects on disk.")
            } else {
                echo("Recent projects (up to $limit):")
                for (p in limited) {
                    echo("  ${p.name}\t${p.path}\t${p.lastOpened}")
                }
            }
        }
    }
}

@Serializable
internal data class RecentProject(
    val name: String,
    val path: String,
    val lastOpened: Long = 0L,
)
