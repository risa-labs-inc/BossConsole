package ai.rever.boss.cli

import ai.rever.boss.utils.SingleInstanceManager
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** `boss doctor` exit code: BOSS is running and reported at least one health finding. */
internal const val EXIT_DEGRADED = 2

/**
 * Reports workspace health from the running BOSS instance, for agents and scripts that cannot see
 * its windows. Read-only: it only reads the `health` object that `boss status --json` carries.
 *
 * Exit codes: `0` no findings, `2` running but degraded, `1` not running, the query failed, or the
 * running BOSS did not return a health report.
 */
class BossDoctorCommand : CliktCommand(name = "doctor") {
    override fun help(context: Context) = "Reports problems in the running BOSS instance, with suggested next steps"

    val json by option("--json", help = "Output the health report as JSON").flag(default = false)

    override fun run() {
        val rawStatus = SingleInstanceManager.queryStatus().getOrElse { fail("Error: ${it.message}") }
        val health =
            healthObjectOf(rawStatus)
                ?: fail("Error: the running BOSS did not return a health report. Update BOSS to use boss doctor.")
        echo(if (json) health.toString() else formatDoctorReport(health))
        if (health.isDegraded()) throw ProgramResult(EXIT_DEGRADED)
    }

    private fun fail(message: String): Nothing {
        echo(message, err = true)
        throw ProgramResult(1)
    }
}

/** The `health` object of a `boss status --json` response, or null when there is none. */
internal fun healthObjectOf(rawStatus: String): JsonObject? {
    val status =
        try {
            Json.parseToJsonElement(rawStatus) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
    return status?.get("health") as? JsonObject
}

/** The human `boss doctor` report. Fields are read leniently so a newer BOSS can add to the object. */
internal fun formatDoctorReport(health: JsonObject): String =
    buildString {
        appendLine("BOSS Doctor")
        appendLine("-----------")
        val findings = health.findings()
        if (findings.isEmpty()) {
            appendLine("No problems found.")
        } else {
            for (finding in findings) {
                appendLine("[${finding.text("severity") ?: "problem"}] ${finding.text("summary").orEmpty()}")
                finding.text("remedy")?.let { appendLine("    Suggested: $it") }
            }
            appendLine("${problemCount(findings.size)} found.")
        }
        val unchecked = health.uncheckedAreas()
        if (unchecked.isNotEmpty()) appendLine("Not checked: ${unchecked.joinToString(", ")}")
    }.trimEnd()

/** The value of the `Health:` line in `boss status`, or null for a BOSS that does not report health. */
internal fun healthSummaryOf(status: JsonObject): String? {
    val health = status["health"] as? JsonObject ?: return null
    val count = health.findings().size
    return if (count == 0) "OK" else "${problemCount(count)} (run 'boss doctor')"
}

/** Whether this health object reports a degraded workspace. */
internal fun JsonObject.isDegraded() = (get("degraded") as? JsonPrimitive)?.booleanOrNull ?: findings().isNotEmpty()

private fun JsonObject.findings() = (get("findings") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

private fun JsonObject.uncheckedAreas(): List<String> =
    (get("unchecked") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

/** A field on one line. Fault messages can contain line breaks, and the report prints one line per item. */
private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)
        ?.contentOrNull
        ?.lines()
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.joinToString(" ")

private fun problemCount(count: Int): String = if (count == 1) "1 problem" else "$count problems"
