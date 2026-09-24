package ai.rever.boss.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Parses BOSS log lines into structured records.
 *
 * Each input line is split into (timestamp, level, category, component,
 * message) when it matches the structured format the host emits; lines
 * that don't match are kept verbatim under `unparsed` so the operator
 * never loses a line, just learns the parser didn't recognise it.
 *
 * The parser is tolerant of whitespace, accepts both ISO-8601 timestamps
 * (`2026-01-15T10:30:45.123Z`) and the SLF4J-legacy
 * `2026-01-15 10:30:45.123` shape, and ignores lines that are blank or
 * pure log rotation markers (a line whose only content is `--`).
 *
 * Usage:
 *   boss log-parse [--file <path>] [--level <min>] [--json]
 *
 * Reads from stdin if no `--file` is given; that lets the operator pipe
 * a tail directly: `tail -n 500 ~/.boss/logs/boss.log | boss log-parse`.
 *
 * Exit codes: 0 always (parsing is informational). 2 if `--file` does
 * not exist or cannot be read.
 */
class BossLogParseCommand : CliktCommand(name = "log-parse") {
    override fun help(context: Context) = "Parses BOSS log lines into structured records"

    private val parser = LogLineParser()

    val file: String? by option("--file", help = "Log file to read (defaults to stdin)")
    val minLevel by option(
        "--level",
        help = "Minimum log level to include (TRACE, DEBUG, INFO, WARN, ERROR). Defaults to TRACE (no filter).",
    ).default("TRACE")
    val json by option("--json", help = "Output the report as JSON").flag(default = false)

    override fun run() {
        val threshold = LogLevel.parse(minLevel)
        val lines =
            if (file != null) {
                val f = File(file!!).absoluteFile
                if (!f.isFile) {
                    echo("Error: not a file: $file", err = true)
                    throw ProgramResult(2)
                }
                runCatching { f.readLines(Charsets.UTF_8) }
                    .getOrElse {
                        echo("Error: failed to read $file: ${it.message ?: it.javaClass.simpleName}", err = true)
                        throw ProgramResult(2)
                    }
            } else {
                generateSequence { readLine() }.toList()
            }
        val report = parser.parse(lines, threshold)
        renderAndExit(report, json)
    }

    private fun renderAndExit(
        report: LogReport,
        json: Boolean,
    ) {
        if (json) {
            echo(LogReportJson.encode(report))
        } else {
            echo("Parsed ${report.records.size} records (${report.unparsed.size} unparsed)")
            for (rec in report.records) {
                val cat = rec.category ?: "?"
                val comp = rec.component ?: "?"
                val ts = rec.timestamp ?: "?"
                echo("  $ts  [${rec.level}]  $cat/$comp - ${rec.message}")
            }
            if (report.unparsed.isNotEmpty()) {
                echo("Unparsed lines:")
                for (u in report.unparsed) echo("  $u")
            }
        }
    }
}

data class LogRecord(
    val timestamp: String?,
    val level: LogLevel,
    val category: String?,
    val component: String?,
    val message: String,
)

data class LogReport(
    val records: List<LogRecord>,
    val unparsed: List<String>,
)

enum class LogLevel(
    val priority: Int,
) {
    TRACE(0),
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4),
    ;

    companion object {
        fun parse(name: String): LogLevel =
            values().firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Unknown log level '$name'. Allowed: TRACE, DEBUG, INFO, WARN, ERROR.",
                )
    }
}

/**
 * Pure parser. The regex covers the timestamp, level, category, and
 * component; the rest of the line is the message. Lines that fail the
 * match go to [LogReport.unparsed].
 */
class LogLineParser {
    /**
     * Two timestamp shapes are accepted: ISO-8601 with the `T` separator
     * (what SLF4J SimpleLogger emits when configured with `ISO_8601`)
     * and the legacy `yyyy-MM-dd HH:mm:ss.SSS` shape (what it emits by
     * default).
     */
    private val pattern: Regex =
        Regex(
            "^(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?Z?)\\s+" +
                "\\[?(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\\]?\\s+" +
                "\\[?([^\\]]+)\\]?\\s+" +
                "([A-Z]+)\\s+([\\w.]+)\\s+-\\s+(.*)$",
        )

    fun parse(
        lines: List<String>,
        threshold: LogLevel,
    ): LogReport {
        val records = mutableListOf<LogRecord>()
        val unparsed = mutableListOf<String>()
        for (line in lines) {
            val record = parseLine(line.trimEnd(), threshold, unparsed)
            if (record != null) {
                records += record
            }
        }
        return LogReport(records = records, unparsed = unparsed)
    }

    @Suppress("ReturnCount")
    private fun parseLine(
        trimmed: String,
        threshold: LogLevel,
        unparsed: MutableList<String>,
    ): LogRecord? {
        if (trimmed.isEmpty() || trimmed == "--") return null
        val primary = pattern.matchEntire(trimmed)
        val match = primary ?: tryLowercaseLevelMatch(trimmed)
        if (match == null) {
            unparsed += trimmed
            return null
        }
        val parts = match.destructured
        val timestamp = parts.component1()
        val levelName = parts.component2()
        val category = parts.component4()
        val component = parts.component5()
        val message = parts.component6()
        val level = parseLevel(levelName)
        if (level.priority < threshold.priority) return null
        return LogRecord(timestamp, level, category, component, message)
    }

    /**
     * Re-runs the match after upper-casing the level token in the input,
     * since the rest of the pattern is case-sensitive (the category is
     * always uppercase, the thread/component names are always their natural
     * case) but the level token shows up lower-case in some wrapped loggers.
     *
     * The synthesised line uses `INFO` as a placeholder level name so the
     * primary [pattern] matches; the caller's [parseLevel] is what maps
     * the original token back to a [LogLevel], and [LogLevel.parse] /
     * [parseLevel] cover non-canonical levels (FINE → INFO etc.).
     */
    private fun tryLowercaseLevelMatch(line: String): MatchResult? {
        val match = lowercaseLevel.matchEntire(line) ?: return null
        val parts = match.destructured
        val timestamp = parts.component1()
        val threadName = parts.component3()
        val category = parts.component4()
        val component = parts.component5()
        val message = parts.component6()
        // Synthesise with `INFO` so the primary pattern is satisfied; the
        // original level token is lost in this re-match because the
        // primary regex's level capture group would have been "INFO"
        // anyway - the level itself is recovered from the original
        // match group via the calling loop's destructuring of THIS
        // match, which carries the levelName we want.
        val synthesised = "$timestamp [INFO] [$threadName] $category $component - $message"
        return pattern.matchEntire(synthesised)?.let { match }
    }

    private val lowercaseLevel: Regex =
        Regex(
            "^(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?Z?)\\s+" +
                "\\[?([A-Za-z]+)\\]?\\s+" +
                "\\[?([^\\]]+)\\]?\\s+" +
                "([A-Z]+)\\s+([\\w.]+)\\s+-\\s+(.*)$",
        )

    private fun parseLevel(name: String): LogLevel =
        when (name.uppercase()) {
            "FATAL" -> LogLevel.ERROR
            else -> LogLevel.values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: LogLevel.INFO
        }
}

private object LogReportJson {
    fun encode(report: LogReport): String =
        buildJsonObject {
            put(
                "records",
                buildJsonArray {
                    report.records.forEach { rec ->
                        addJsonObject {
                            put("level", rec.level.name)
                            rec.timestamp?.let { put("timestamp", it) }
                            rec.category?.let { put("category", it) }
                            rec.component?.let { put("component", it) }
                            put("message", rec.message)
                        }
                    }
                },
            )
            put(
                "unparsed",
                buildJsonArray { report.unparsed.forEach { add(it) } },
            )
        }.toString()
}
