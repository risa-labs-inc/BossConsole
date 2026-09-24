package ai.rever.boss.cli

import ai.rever.boss.utils.SingleInstanceManager
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File

/** `boss pack apply --wait` exit code: the pack applied only in part. */
internal const val EXIT_PACK_PARTIAL = 2

/** Largest pack file read. Matches the host's own argument limit, so a larger file could never apply. */
private const val MAX_PACK_FILE_BYTES = 16_000L

/** The CLI channel's own ceiling; the host still bounds the tool itself at 30 seconds. */
private const val TOOL_TIMEOUT_MS = 60_000L
private const val POLL_INTERVAL_MS = 1_000L
private const val MAX_WAIT_MS = 30L * 60L * 1_000L

/**
 * `boss pack`: plan and apply plugin packs in the running BOSS.
 *
 * A thin client over the host's `pack_plan`, `pack_apply` and `pack_status` MCP tools, so a pack
 * applied from a shell is governed exactly like one applied by an agent: `pack_apply` waits for the
 * operator in the MCP approval dialog unless their policy already allows it.
 */
class BossPackCommand : NoOpCliktCommand(name = "pack") {
    override fun help(context: Context) = "Plans and applies plugin packs in the running BOSS instance"
}

class BossPackPlanCommand : CliktCommand(name = "plan") {
    override fun help(context: Context) = "Shows what applying a pack would change, without changing anything"

    val file by argument(help = "Pack file (JSON)")
    val json by option("--json", help = "Output the plan as JSON").flag(default = false)

    override fun run() {
        val content = invokePackTool("pack_plan", readPackFile(file))
        echo(if (json) content.toString() else formatPackPlan(content))
    }
}

class BossPackApplyCommand : CliktCommand(name = "apply") {
    override fun help(context: Context) = APPLY_HELP

    val file by argument(help = "Pack file (JSON)")
    val wait by option("--wait", help = "Wait for the apply to finish and report the result").flag(default = false)
    val json by option("--json", help = "Output JSON").flag(default = false)

    override fun run() {
        val started = invokePackTool("pack_apply", readPackFile(file))
        val jobId = started.packText("job") ?: failPack("Error: BOSS did not return a job id.")
        if (!wait) {
            val pack = started.packText("pack")
            echo(if (json) started.toString() else "Applying pack '$pack' as job $jobId. $STATUS_HINT $jobId")
            return
        }
        val finished = awaitJob(jobId)
        echo(if (json) finished.toString() else formatPackJob(finished))
        throw ProgramResult(exitCodeFor(finished))
    }
}

class BossPackStatusCommand : CliktCommand(name = "status") {
    override fun help(context: Context) = "Shows the progress and result of a pack apply"

    val job by argument(help = "Job id (defaults to the most recent)").optional()
    val json by option("--json", help = "Output JSON").flag(default = false)

    override fun run() {
        val content = invokePackTool("pack_status", job?.let { jobArgs(it) } ?: "{}")
        echo(if (json) content.toString() else formatPackJob(content))
    }
}

private const val STATUS_HINT = "Check it with: boss pack status"

private const val APPLY_HELP =
    "Applies a pack. BOSS asks for approval first unless your MCP policy already allows pack_apply"

private fun jobArgs(jobId: String): String = JsonObject(mapOf("job" to JsonPrimitive(jobId))).toString()

private fun CliktCommand.readPackFile(path: String): String {
    val file = File(path)
    if (!file.isFile) failPack("Error: $path is not a file.")
    if (file.length() > MAX_PACK_FILE_BYTES) failPack("Error: $path is larger than $MAX_PACK_FILE_BYTES bytes.")
    val text = runCatching { file.readText() }.getOrElse { failPack("Error: could not read $path: ${it.message}") }
    return parseObject(text)?.toString() ?: failPack("Error: $path is not a JSON object.")
}

/** The tool's JSON payload, or exit 1 with the host's message. */
private fun CliktCommand.invokePackTool(
    tool: String,
    args: String,
): JsonObject {
    val response =
        SingleInstanceManager
            .invokeMcpTool(tool, args, TOOL_TIMEOUT_MS)
            .getOrElse { failPack("Error: ${it.message}") }
    val envelope = parseObject(response) ?: failPack("Error: malformed response from BOSS.")
    val content = envelope.packText("content").orEmpty()
    if (envelope.packFlag("isError")) {
        val problems = parseObject(content)?.get("problems") as? JsonArray
        failPack(
            problems
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.joinToString("\n", prefix = "The pack is not valid:\n") { "  - $it" }
                ?: "Error: $content",
        )
    }
    return parseObject(content) ?: failPack("Error: unexpected response from BOSS: $content")
}

private fun CliktCommand.awaitJob(jobId: String): JsonObject {
    val deadline = System.currentTimeMillis() + MAX_WAIT_MS
    while (true) {
        val job = invokePackTool("pack_status", jobArgs(jobId))
        if (job.packText("state") != "running") return job
        if (System.currentTimeMillis() > deadline) failPack("Error: job $jobId is still running. $STATUS_HINT $jobId")
        Thread.sleep(POLL_INTERVAL_MS)
    }
}

private fun parseObject(text: String): JsonObject? =
    try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private fun CliktCommand.failPack(message: String): Nothing {
    echo(message, err = true)
    throw ProgramResult(1)
}
