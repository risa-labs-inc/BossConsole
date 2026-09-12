package ai.rever.boss.health

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The `health` object carried by `boss status --json` and printed by `boss doctor --json`.
 *
 * Field names and the lowercase [HealthArea.wireName] and [HealthSeverity.wireName] values are a
 * contract with scripts and agents: add fields, never rename them. An absent `subject` or `remedy`
 * is omitted rather than sent as null.
 */
internal fun WorkspaceHealthReport.toJson(): JsonObject =
    buildJsonObject {
        put("degraded", degraded)
        putJsonArray("findings") {
            for (finding in findings) {
                addJsonObject {
                    put("area", finding.area.wireName)
                    put("severity", finding.severity.wireName)
                    put("code", finding.code)
                    put("summary", finding.summary)
                    finding.subject?.let { put("subject", it) }
                    finding.remedy?.let { put("remedy", it) }
                }
            }
        }
        putJsonArray("unchecked") {
            for (area in unchecked.sortedBy { it.ordinal }) add(area.wireName)
        }
    }
