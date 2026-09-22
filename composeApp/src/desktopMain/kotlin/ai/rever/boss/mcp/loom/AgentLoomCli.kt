package ai.rever.boss.mcp.loom

import java.io.File
import kotlin.system.exitProcess

private const val USAGE = "usage: agent-loom [ledger.jsonl] [output.html]"

/**
 * The Agent Loom export command: `mcp-calls.jsonl` in, `agent-loom-session.html` out.
 *
 * This is a console program, so standard output is its interface rather than a log sink, and it is
 * the only file in the feature that writes to it. Nothing here runs as part of the application: the
 * exporter is a build-time/CLI step, and the application itself never calls it.
 *
 * Run it with `./gradlew :composeApp:exportAgentLoom`, optionally with `-Pledger=` and `-Pout=`.
 */
fun main(args: Array<String>) {
    exitProcess(runAgentLoom(args))
}

/**
 * The export command's body, split out from [main] so it can be driven directly from a test.
 *
 * Each early return is a distinct exit code: usage, no ledger, then the export itself.
 */
@Suppress("ReturnCount")
fun runAgentLoom(args: Array<String>): Int {
    if (args.any { it == "-h" || it == "--help" }) {
        println(USAGE)
        return 0
    }
    val ledgerFile = File(args.getOrNull(0) ?: AgentLoomExporter.defaultLedgerFile().path)
    val outputFile = File(args.getOrNull(1) ?: "agent-loom-session.html")
    if (!ledgerFile.isFile) {
        println("Agent Loom: no ledger file at ${ledgerFile.absolutePath}")
        println(USAGE)
        return 2
    }

    val exported = AgentLoomExporter.exportLedger(ledgerFile, outputFile)
    val session = exported.session
    println("Agent Loom")
    println("  ledger      ${ledgerFile.absolutePath}")
    println("  sources     ${session.sources.joinToString(", ")}")
    println("  events      ${session.events.size}")
    println("  span        ${session.durationMs}ms")
    println("  diagnostics ${session.diagnostics.size} line(s) not reconstructed")
    println("  artifact    ${exported.outputFile.absolutePath} (${exported.bytes} bytes)")
    println("Open the artifact in any browser. It needs no BOSS, no login and no network.")
    return 0
}
