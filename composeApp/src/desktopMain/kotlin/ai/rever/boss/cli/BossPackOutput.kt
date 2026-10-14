package ai.rever.boss.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/*
 * Human output and exit codes for `boss pack`, read from the pack tools' JSON. Fields are read
 * leniently, so a newer BOSS can add to the JSON without breaking an older CLI.
 */

internal fun exitCodeFor(job: JsonObject): Int =
    when (job.packText("status")) {
        "applied", "already_satisfied" -> 0
        "partial" -> EXIT_PACK_PARTIAL
        else -> 1
    }

internal fun formatPackPlan(plan: JsonObject): String =
    buildString {
        appendLine("Pack ${plan.packText("pack")}")
        if (plan.packFlag("satisfied")) appendLine("Already satisfied: applying would change nothing.")
        plan.packObjects("plugins").forEach { row ->
            val optional = if (row.packFlag("optional")) " (optional)" else ""
            val plugin = "${row.packText("pluginId")}$optional"
            appendLine(pluginLine(row.packText("action"), plugin, row.packText("detail")))
        }
        plan.packObjects("rules").forEach { row ->
            val existing = row.packText("existing")?.let { " (you have $it)" }.orEmpty()
            appendLine(ruleLine(row, row.packText("outcome")) + existing)
        }
        val blocked = (plan["requiredBlocked"] as? JsonPrimitive)?.intOrNull ?: 0
        if (blocked > 0) appendLine("$blocked required plugin(s) cannot be satisfied; applying would be partial.")
    }.trimEnd()

internal fun formatPackJob(job: JsonObject): String =
    buildString {
        val status = job.packText("status")?.let { ", $it" }.orEmpty()
        appendLine("Pack ${job.packText("pack")}, job ${job.packText("job")}: ${job.packText("state")}$status")
        if (job.packText("state") == "running") {
            val current = job.packText("current")?.let { ", on $it" }.orEmpty()
            appendLine("  ${job.packText("done")}/${job.packText("total")} steps$current")
        }
        job.packText("error")?.let { appendLine("  Error: $it") }
        job.packObjects("plugins").forEach { row ->
            appendLine(pluginLine(row.packText("result"), row.packText("pluginId"), row.packText("message")))
        }
        job.packObjects("rules").forEach { row -> appendLine(ruleLine(row, row.packText("result"))) }
    }.trimEnd()

private const val PAD = 18
private const val RULE_PAD = 8

private fun pluginLine(
    outcome: String?,
    plugin: String?,
    detail: String?,
): String = "  plugin  ${outcome.orEmpty().padEnd(PAD)} $plugin - $detail"

private fun ruleLine(
    row: JsonObject,
    outcome: String?,
): String {
    val scope = row.packText("scope").orEmpty().padEnd(RULE_PAD)
    return "  $scope${outcome.orEmpty().padEnd(PAD)} ${row.packText("action")} ${row.packText("subject")}"
}

internal fun JsonObject.packText(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.packFlag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

private fun JsonObject.packObjects(key: String): List<JsonObject> {
    val rows = this[key] as? JsonArray
    return rows?.mapNotNull { it as? JsonObject }.orEmpty()
}
