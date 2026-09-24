package ai.rever.boss.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP reachability checker for known BOSS endpoints.
 *
 * Before a CI step runs BOSS, before a fresh install, or before debugging
 * "login is broken", an operator wants to know whether the host can talk
 * to Supabase, the plugin store, or any other endpoint BOSS depends on.
 * The doctor / status commands report on the running process; this one
 * reports on the network.
 *
 * Each check is a plain TCP connect with a hard timeout - no HTTP, no TLS
 * handshake, no auth header. That keeps it cheap and gives a useful answer
 * ("the port is reachable" vs "the host is dead") without depending on
 * whatever auth state BOSS happens to be in. A successful connect means
 * "something is listening"; a failed connect means "firewall, DNS, or
 * nobody is home".
 *
 * Usage:
 *   boss port-doctor [--target <host:port>]... [--timeout-ms <ms>] [--json]
 *
 * Without `--target`, the command probes the default endpoint set: the
 * Supabase functions URL, the Supabase REST URL, and a TCP probe against
 * the BOSS plugin store. Custom targets are additive.
 *
 * Exit codes: 0 if every target is reachable, 1 if any is not, 2 if a
 * target could not be parsed or resolved.
 */
class BossPortDoctorCommand : CliktCommand(name = "port-doctor") {
    override fun help(context: Context) = "Probes TCP reachability for BOSS endpoints"

    private val prober = PortProber()

    val target by option(
        "--target",
        help = "host:port to probe (repeatable)",
    ).multiple()
    val timeoutMs by option(
        "--timeout-ms",
        help = "TCP connect timeout in milliseconds (default 3000)",
    ).int().default(3000)
    val json by option("--json", help = "Output the report as JSON").flag(default = false)

    override fun run() {
        val targets =
            if (target.isEmpty()) {
                PortProber.defaultTargets()
            } else {
                target.mapNotNull { parseTarget(it) }
            }
        if (targets.size != target.size) {
            echo("Error: could not parse one or more --target values (expect host:port)", err = true)
            throw ProgramResult(2)
        }
        val report = prober.probe(targets, timeoutMs.toLong())
        renderAndExit(report, json)
    }

    @Suppress("ReturnCount")
    private fun parseTarget(raw: String): Target? {
        val parts = raw.split(":")
        if (parts.size != 2) return null
        val host = parts[0].trim()
        val port = parts[1].trim().toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return Target(label = "$host:$port", host = host, port = port)
    }

    private fun renderAndExit(
        report: PortReport,
        json: Boolean,
    ) {
        if (json) {
            echo(PortReportJson.encode(report))
        } else {
            echo("TCP reachability (timeout=${report.timeoutMs}ms):")
            for (r in report.results) {
                echo("  ${r.target.label}: ${describe(r)}")
            }
        }
        if (report.results.any { !it.reachable }) throw ProgramResult(1)
    }

    private fun describe(r: TargetResult): String =
        if (r.reachable) {
            "reachable in ${r.latencyMs}ms"
        } else {
            "unreachable: ${r.error ?: "unknown"}"
        }
}

data class Target(
    val label: String,
    val host: String,
    val port: Int,
)

data class TargetResult(
    val target: Target,
    val reachable: Boolean,
    val latencyMs: Long,
    val error: String?,
)

data class PortReport(
    val timeoutMs: Long,
    val results: List<TargetResult>,
)

/**
 * Pure TCP probing. Each [Socket] is opened with [Socket.connect] which
 * honours the supplied timeout, and closed in `finally` so the test never
 * leaks file descriptors even on a slow DNS lookup.
 */
class PortProber {
    companion object {
        /**
         * Default endpoint set BOSS depends on. The Supabase URLs come from
         * the BOSS configuration; the plugin store is a separate URL the
         * release config carries. Each is a (label, host, port) triple,
         * resolved into a [Target] at probe time so the operator can see
         * what was probed.
         */
        fun defaultTargets(): List<Target> =
            listOf(
                Target(label = "supabase-rest", host = "api.risaboss.com", port = 443),
                Target(label = "supabase-functions", host = "api.risaboss.com", port = 443),
                Target(label = "plugin-store", host = "plugins.risaboss.com", port = 443),
            )
    }

    fun probe(
        targets: List<Target>,
        timeoutMs: Long,
    ): PortReport {
        val results = targets.map { probeOne(it, timeoutMs) }
        return PortReport(timeoutMs = timeoutMs, results = results)
    }

    private fun probeOne(
        target: Target,
        timeoutMs: Long,
    ): TargetResult {
        val started = System.nanoTime()
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(target.host, target.port), timeoutMs.toInt())
            val latencyMs = (System.nanoTime() - started) / 1_000_000
            return TargetResult(target = target, reachable = true, latencyMs = latencyMs, error = null)
        } catch (e: IOException) {
            val latencyMs = (System.nanoTime() - started) / 1_000_000
            return TargetResult(
                target = target,
                reachable = false,
                latencyMs = latencyMs,
                error = (e.message ?: e.javaClass.simpleName),
            )
        } finally {
            runCatching { socket.close() }
        }
    }
}

private object PortReportJson {
    fun encode(report: PortReport): String =
        buildJsonObject {
            put("timeoutMs", report.timeoutMs)
            put(
                "results",
                buildJsonArray {
                    report.results.forEach { result ->
                        addJsonObject {
                            put("label", result.target.label)
                            put("host", result.target.host)
                            put("port", result.target.port)
                            put("reachable", result.reachable)
                            put("latencyMs", result.latencyMs)
                            result.error?.let { put("error", it) }
                        }
                    }
                },
            )
        }.toString()
}
