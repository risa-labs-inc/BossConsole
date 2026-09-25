package ai.rever.boss.cli

import ai.rever.boss.plugin.launchpad.launchpadJson
import ai.rever.boss.plugin.launchpad.scan.PluginJarScanner
import ai.rever.boss.plugin.launchpad.scan.ScanDiff
import ai.rever.boss.plugin.launchpad.scan.ScanReportJson
import ai.rever.boss.plugin.launchpad.scan.ScanReportText
import ai.rever.boss.plugin.launchpad.scan.ScanResult
import ai.rever.boss.plugin.launchpad.scan.ScanRisk
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.serialization.json.JsonObject

/**
 * Reports what a plugin JAR can do before it is loaded.
 *
 * Usage: `boss plugin scan <jar> [--against <older.jar>] [--json] [--fail-on high|medium|low]`
 *
 * A static, read-only scan of the JAR's class constant pools: processes, network, files, native and dynamic
 * code, and each sensitive host API, plus manifest, signature and archive findings. With `--against` it compares
 * two builds and reports what the newer one GAINED. `--fail-on` turns the result into an exit status for CI: it
 * fails when the scan's top risk (or, with `--against`, the risk of what was added) reaches the given level.
 *
 * It reports what classes reference, not proof of behaviour, and never says "safe".
 */
class BossPluginScanCommand : CliktCommand(name = "scan") {
    override fun help(context: Context) = "Reports what a plugin JAR can do, statically, before it is loaded"

    val jar by argument(help = "Path to the plugin JAR").path(mustExist = true, canBeDir = false)
    val against by option("--against", help = "An older build of the same plugin: report what this one gained")
        .path(mustExist = true, canBeDir = false)
    val json by option("--json", help = "Output machine-readable JSON").flag(default = false)
    val failOn by option("--fail-on", help = "Exit 1 when the risk reaches this level (CI gate)")
        .choice("high", "medium", "low")

    override fun run() {
        val newer = PluginJarScanner.scan(jar.toFile().absoluteFile)
        val older = against?.let { PluginJarScanner.scan(it.toFile().absoluteFile) }
        val threshold = failOn?.let { ScanRisk.valueOf(it.uppercase()) }

        val diff = older?.let { ScanDiff.of(it, newer) }
        val unreadable = newer.unreadableReason != null || older?.unreadableReason != null
        val risk = diff?.addedRisk ?: newer.topRisk
        val failed = unreadable || (threshold != null && risk >= threshold)

        echo(render(newer, diff))
        if (failed) throw ProgramResult(1)
    }

    private fun render(
        newer: ScanResult,
        diff: ScanDiff?,
    ): String =
        if (json) {
            val obj: JsonObject = if (diff != null) ScanReportJson.diff(diff) else ScanReportJson.scan(newer)
            launchpadJson.encodeToString(JsonObject.serializer(), obj)
        } else if (diff != null) {
            ScanReportText.diff(diff)
        } else {
            ScanReportText.scan(newer)
        }
}
