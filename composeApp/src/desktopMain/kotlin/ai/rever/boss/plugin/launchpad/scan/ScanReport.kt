package ai.rever.boss.plugin.launchpad.scan

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The verdict line for one scan. It never says "safe": the best it says is that nothing risky was found. */
internal fun scanVerdict(r: ScanResult): String =
    when {
        r.unreadableReason != null -> {
            "NOT SCANNED - ${r.unreadableReason}"
        }

        r.topRisk == ScanRisk.HIGH -> {
            "REVIEW BEFORE LOADING - high-risk capabilities found"
        }

        r.topRisk == ScanRisk.MEDIUM -> {
            "REVIEW - notable capabilities found"
        }

        else -> {
            "NO RISKY CAPABILITIES FOUND in the constant pools. " +
                "This is not a safety guarantee: see the limits below."
        }
    }

/** Renders scans and diffs as text for a terminal. Every string that came from a JAR goes through [ScanText]. */
internal object ScanReportText {
    fun scan(r: ScanResult): String =
        buildString {
            appendLine("SCAN  ${ScanText.safe(r.fileName)}")
            r.manifest?.let { m ->
                val api = ScanText.safe(m.minApiVersion)
                appendLine("  plugin   ${ScanText.safe(m.id)}  v${ScanText.safe(m.version)}  (api $api)")
            }
            appendLine("  sha256   ${r.sha256 ?: "(unavailable)"}")
            appendLine("  size     ${r.sizeBytes} bytes, ${r.classesScanned} class(es) read")
            appendLine("  verdict  ${scanVerdict(r)}")
            if (r.capabilities.isNotEmpty()) {
                appendLine()
                appendLine("Capabilities (highest risk first)")
                r.capabilities.forEach { appendCapability(it) }
            }
            if (r.findings.isNotEmpty()) {
                appendLine()
                appendLine("Findings")
                for (f in r.findings.sortedByDescending { it.risk }) {
                    appendLine("  [${f.risk}] ${f.id} - ${f.title}")
                    appendLine("      ${f.detail}")
                }
            }
            if (r.hosts.isNotEmpty()) {
                appendLine()
                appendLine("Hosts named in string constants: ${r.hosts.joinToString(", ") { ScanText.safe(it, 80) }}")
            }
            appendLine()
            appendLine("Limits")
            r.limits.forEach { appendLine("  - $it") }
        }

    private fun StringBuilder.appendCapability(use: CapabilityUse) {
        val c = use.capability
        appendLine("  [${c.risk}] ${c.id} - ${c.title}")
        appendLine("      why: ${c.why}")
        val shown =
            use.evidence.joinToString("; ") { "${ScanText.safe(it.className, 80)} (${ScanText.safe(it.detail, 80)})" }
        val more =
            use.classCount -
                use.evidence
                    .map { it.className }
                    .toSet()
                    .size
        appendLine("      seen: $shown" + if (more > 0) " and $more more class(es)" else "")
    }

    fun diff(d: ScanDiff): String =
        buildString {
            appendLine("SCAN DIFF")
            appendLine("  old      ${describe(d.old)}")
            appendLine("  new      ${describe(d.new)}")
            if (!d.samePlugin) appendLine("  note     these JARs do not declare the same pluginId")
            appendLine("  verdict  ${d.verdict}")
            section(
                "Capabilities GAINED",
                d.addedCapabilities.map { "[${it.capability.risk}] ${it.capability.id} - ${it.capability.title}" },
            )
            section(
                "Capabilities dropped",
                d.removedCapabilities.map { "[${it.capability.risk}] ${it.capability.id} - ${it.capability.title}" },
            )
            section("Findings introduced", d.addedFindings.map { "[${it.risk}] ${it.id} - ${it.title}" })
            section("Findings resolved", d.removedFindings.map { "[${it.risk}] ${it.id} - ${it.title}" })
            section("Hosts newly named in the code", d.addedHosts.map { ScanText.safe(it, 80) })
            section("Hosts no longer named", d.removedHosts.map { ScanText.safe(it, 80) })
            section("requiredPermissions added", d.addedPermissions.map { ScanText.safe(it, 80) })
            section("requiredPermissions removed", d.removedPermissions.map { ScanText.safe(it, 80) })
            appendLine()
            appendLine("Compared by capability id. Both scans are static: neither sees code built at run time.")
        }

    private fun StringBuilder.section(
        title: String,
        lines: List<String>,
    ) {
        if (lines.isEmpty()) return
        appendLine()
        appendLine("$title (${lines.size})")
        lines.forEach { appendLine("  $it") }
    }

    private fun describe(r: ScanResult): String {
        val id = ScanText.safe(r.pluginId ?: r.fileName)
        val version =
            r.manifest
                ?.version
                ?.let { " v${ScanText.safe(it, 40)}" }
                .orEmpty()
        return "$id$version  (${ScanText.safe(r.fileName)}, sha256 ${r.sha256?.take(12) ?: "n/a"})"
    }
}

/** Renders scans and diffs as JSON, for CI or an agent. kotlinx escapes every control character. */
internal object ScanReportJson {
    fun scan(r: ScanResult): JsonObject =
        buildJsonObject {
            put("file", r.fileName)
            put("sha256", r.sha256?.let { JsonPrimitive(it) } ?: JsonNull)
            put("sizeBytes", r.sizeBytes)
            put("verdict", scanVerdict(r))
            put("topRisk", r.topRisk.name)
            put("unreadableReason", r.unreadableReason?.let { JsonPrimitive(it) } ?: JsonNull)
            put("pluginId", r.pluginId?.let { JsonPrimitive(it) } ?: JsonNull)
            put("version", r.manifest?.version?.let { JsonPrimitive(it) } ?: JsonNull)
            put(
                "requiredPermissions",
                JsonArray(
                    r.manifest
                        ?.permissions
                        .orEmpty()
                        .map { JsonPrimitive(it) },
                ),
            )
            put("classesScanned", r.classesScanned)
            put("classesUnreadable", r.classesUnreadable)
            put("capabilities", JsonArray(r.capabilities.map { capability(it) }))
            put("findings", JsonArray(r.findings.map { finding(it) }))
            put("hosts", JsonArray(r.hosts.map { JsonPrimitive(it) }))
            put("limits", JsonArray(r.limits.map { JsonPrimitive(it) }))
        }

    fun diff(d: ScanDiff): JsonObject =
        buildJsonObject {
            put("verdict", d.verdict)
            put("samePlugin", d.samePlugin)
            put("addedRisk", d.addedRisk.name)
            put("old", scan(d.old))
            put("new", scan(d.new))
            put("addedCapabilities", JsonArray(d.addedCapabilities.map { capability(it) }))
            put("removedCapabilities", JsonArray(d.removedCapabilities.map { capability(it) }))
            put("addedFindings", JsonArray(d.addedFindings.map { finding(it) }))
            put("removedFindings", JsonArray(d.removedFindings.map { finding(it) }))
            put("addedHosts", JsonArray(d.addedHosts.map { JsonPrimitive(it) }))
            put("removedHosts", JsonArray(d.removedHosts.map { JsonPrimitive(it) }))
            put("addedPermissions", JsonArray(d.addedPermissions.map { JsonPrimitive(it) }))
            put("removedPermissions", JsonArray(d.removedPermissions.map { JsonPrimitive(it) }))
        }

    private fun capability(u: CapabilityUse): JsonObject =
        buildJsonObject {
            put("id", u.capability.id)
            put("risk", u.capability.risk.name)
            put("title", u.capability.title)
            put("why", u.capability.why)
            put("classCount", u.classCount)
            put(
                "evidence",
                JsonArray(
                    u.evidence.map {
                        buildJsonObject {
                            put("class", it.className)
                            put("detail", it.detail)
                        }
                    },
                ),
            )
        }

    private fun finding(f: ScanFinding): JsonObject =
        buildJsonObject {
            put("id", f.id)
            put("risk", f.risk.name)
            put("title", f.title)
            put("detail", f.detail)
        }
}
