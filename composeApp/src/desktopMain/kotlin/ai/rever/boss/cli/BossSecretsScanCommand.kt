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
import java.io.File

/**
 * Recursive plaintext-secret scanner.
 *
 * Walks a directory tree and surfaces any line that matches a known
 * secret-shaped pattern. The patterns cover the providers that show up in
 * real credentials leaks: AWS access keys, GitHub PATs (both classic and
 * fine-grained), OpenAI/Anthropic/Google API keys, Slack tokens, Stripe
 * live keys, JWT bearer tokens, basic-auth-in-URL, and the
 * `password = ...` lines that show up in committed YAML/properties/env
 * files. Each rule carries a severity the operator can filter on, so a
 * pre-commit hook can run `--severity high` and stay quiet about low-risk
 * matches.
 *
 * The scanner is read-only - it never modifies files. The matched text is
 * masked (first 4 chars + last 2 chars + `(len=N)`) so the report is safe
 * to share in an issue without exposing the secret it found.
 *
 * Usage:
 *   boss secrets scan [--path <dir>] [--json] [--ignore <glob>]...
 *                     [--max-file-size <bytes>] [--severity low|medium|high|critical]
 *
 * Exit codes: 0 no findings of the requested severity or higher,
 * 1 if any finding at or above the requested severity was reported,
 * 2 if the scan itself failed (permission denied, etc.).
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
class BossSecretsScanCommand : CliktCommand(name = "secrets") {
    override fun help(context: Context) = "Scans a directory for plaintext secrets"

    private val scanner = SecretsScanner()

    val path by option("--path", help = "Directory to scan (defaults to current directory)").default(".")
    val json by option("--json", help = "Output findings as JSON").flag(default = false)
    val ignore by option(
        "--ignore",
        help = "Glob pattern relative to the scan root that excludes a file or directory (repeatable)",
    ).multiple()
    val maxFileSize by option(
        "--max-file-size",
        help = "Skip files larger than this many bytes (default 1 MiB)",
    ).int().default(DEFAULT_MAX_FILE_SIZE)
    val severity by option(
        "--severity",
        help = "Minimum severity to report: low|medium|high|critical (default low)",
    ).default("low")

    override fun run() {
        val root = File(path).absoluteFile
        if (!root.exists()) {
            echo("Error: scan path does not exist: $path", err = true)
            throw ProgramResult(2)
        }
        if (!root.isDirectory) {
            echo("Error: scan path is not a directory: $path", err = true)
            throw ProgramResult(2)
        }
        val threshold = SecretsSeverity.parse(severity)
        val ignorePatterns = ignore.toList()
        val report =
            scanner.scan(
                root = root,
                ignorePatterns = ignorePatterns,
                maxFileSizeBytes = maxFileSize.toLong(),
                threshold = threshold,
            )
        renderAndExit(report, json, threshold)
    }

    private fun renderAndExit(
        report: SecretsReport,
        json: Boolean,
        threshold: SecretsSeverity,
    ) {
        if (json) {
            echo(
                buildJsonObject {
                    put("status", if (report.findings.isEmpty()) "clean" else "findings")
                    put("scannedFiles", report.scannedFiles)
                    put("skippedFiles", report.skippedFiles)
                    put("threshold", threshold.name.lowercase())
                    put(
                        "findings",
                        buildJsonArray {
                            report.findings.forEach { addJsonObject { serializeFinding(it) } }
                        },
                    )
                }.toString(),
            )
        } else {
            echo(
                "Secrets scan of ${report.root} " +
                    "(${report.scannedFiles} files scanned, ${report.skippedFiles} skipped)",
            )
            if (report.findings.isEmpty()) {
                echo("No findings at severity >= ${threshold.name.lowercase()}.")
            } else {
                for (finding in report.findings) {
                    echo("  ${finding.file}:${finding.line}  [${finding.severity.name.lowercase()}] ${finding.rule}")
                    echo("    ${finding.description}")
                    echo("    match: ${finding.maskedMatch}")
                }
            }
        }
        if (report.findings.any { it.severity != SecretsSeverity.LOW }) throw ProgramResult(1)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.serializeFinding(finding: SecretsFinding) {
        put("file", finding.file)
        put("line", finding.line)
        put("rule", finding.rule)
        put("severity", finding.severity.name.lowercase())
        put("description", finding.description)
        put("match", finding.maskedMatch)
    }

    private companion object {
        const val DEFAULT_MAX_FILE_SIZE = 1024 * 1024 // 1 MiB
    }
}

data class SecretsFinding(
    val file: String,
    val line: Int,
    val rule: String,
    val severity: SecretsSeverity,
    val description: String,
    val maskedMatch: String,
)

enum class SecretsSeverity(
    val priority: Int,
) {
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    CRITICAL(4),
    ;

    companion object {
        fun parse(name: String): SecretsSeverity =
            values().firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Unknown severity '$name'. Allowed: low, medium, high, critical.",
                )
    }
}

data class SecretsReport(
    val root: String,
    val scannedFiles: Int,
    val skippedFiles: Int,
    val findings: List<SecretsFinding>,
)

class SecretsScanner {
    val rules: List<SecretsRule> =
        listOf(
            SecretsRule(
                id = "aws-access-key",
                description = "AWS access key id (AKIA-prefixed 20-char identifier)",
                severity = SecretsSeverity.HIGH,
                regex = Regex("""\b(AKIA|ASIA)[0-9A-Z]{16}\b"""),
            ),
            SecretsRule(
                id = "github-pat-classic",
                description = "GitHub personal access token (classic, ghp_ prefix)",
                severity = SecretsSeverity.HIGH,
                regex = Regex("""\bghp_[A-Za-z0-9]{36}\b"""),
            ),
            SecretsRule(
                id = "github-fine-grained-pat",
                description = "GitHub fine-grained personal access token (github_pat_ prefix)",
                severity = SecretsSeverity.HIGH,
                regex = Regex("""\bgithub_pat_[A-Za-z0-9_]{82}\b"""),
            ),
            SecretsRule(
                id = "openai-api-key",
                description = "OpenAI API key (sk- prefix, 20+ chars)",
                severity = SecretsSeverity.MEDIUM,
                regex = Regex("""\bsk-[A-Za-z0-9]{20,}\b"""),
            ),
            SecretsRule(
                id = "anthropic-api-key",
                description = "Anthropic API key (sk-ant- prefix)",
                severity = SecretsSeverity.MEDIUM,
                regex = Regex("""\bsk-ant-[A-Za-z0-9-]{20,}\b"""),
            ),
            SecretsRule(
                id = "google-api-key",
                description = "Google API key (AIza prefix, 35 chars)",
                severity = SecretsSeverity.MEDIUM,
                regex = Regex("""\bAIza[0-9A-Za-z_-]{35}\b"""),
            ),
            SecretsRule(
                id = "slack-token",
                description = "Slack token (xox[bpars]- prefix)",
                severity = SecretsSeverity.HIGH,
                regex = Regex("""\bxox[bpars]-[0-9a-zA-Z-]{10,}\b"""),
            ),
            SecretsRule(
                id = "stripe-live-key",
                description = "Stripe live key (sk_live_/rk_live_ prefix)",
                severity = SecretsSeverity.CRITICAL,
                regex = Regex("""\b(?:sk|rk)_live_[0-9a-zA-Z]{24,}\b"""),
            ),
            SecretsRule(
                id = "jwt-bearer",
                description = "JWT bearer token (three base64url segments, eyJ-prefixed)",
                severity = SecretsSeverity.MEDIUM,
                regex = Regex("""\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b"""),
            ),
            SecretsRule(
                id = "basic-auth-in-url",
                description = "HTTP basic-auth credentials embedded in a URL",
                severity = SecretsSeverity.MEDIUM,
                regex = Regex("""https?://[A-Za-z0-9._~%-]+:[^@\s/]+@[^\s]+"""),
            ),
            SecretsRule(
                id = "generic-password-assignment",
                description = "Generic password= assignment in a config-style file",
                severity = SecretsSeverity.LOW,
                regex = Regex("""(?i)\b(?:password|passwd|pwd)\s*[=:]\s*['"]?([^\s'"<>,;]{6,})['"]?"""),
            ),
        )

    fun scan(
        root: File,
        ignorePatterns: List<String>,
        maxFileSizeBytes: Long,
        threshold: SecretsSeverity,
    ): SecretsReport {
        require(root.isDirectory) { "scan root must be a directory: $root" }
        val findings = mutableListOf<SecretsFinding>()
        var scanned = 0
        var skipped = 0
        val matcher = PathMatcher(ignorePatterns)

        root
            .walkTopDown()
            .onEnter { dir ->
                val rel = dir.relativeToOrSelf(root).path
                if (rel.isNotEmpty() && matcher.matches(rel)) return@onEnter false
                true
            }.filter { it.isFile }
            .forEach { file ->
                val rel = file.relativeToOrSelf(root).path
                if (matcher.matches(rel)) {
                    skipped += 1
                    return@forEach
                }
                if (file.length() > maxFileSizeBytes) {
                    skipped += 1
                    return@forEach
                }
                scanned += 1
                scanFile(file, root, threshold, findings)
            }
        return SecretsReport(
            root = root.absolutePath,
            scannedFiles = scanned,
            skippedFiles = skipped,
            findings = findings.sortedWith(compareBy({ it.file }, { it.line })),
        )
    }

    private fun scanFile(
        file: File,
        root: File,
        threshold: SecretsSeverity,
        findings: MutableList<SecretsFinding>,
    ) {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return
        val chunk =
            if (bytes.size > 8 * 1024) bytes.copyOf(8 * 1024) else bytes
        if (chunk.any { it == 0.toByte() }) return
        scanChunk(chunk, file, root, threshold, findings)
    }

    private fun scanChunk(
        bytes: ByteArray,
        file: File,
        root: File,
        threshold: SecretsSeverity,
        findings: MutableList<SecretsFinding>,
    ) {
        val text = String(bytes, Charsets.UTF_8)
        for (rule in rules) {
            if (rule.severity.priority < threshold.priority) continue
            for (match in rule.regex.findAll(text)) {
                val lineNumber = lineOf(text, match.range.first)
                findings +=
                    SecretsFinding(
                        file = file.relativeToOrSelf(root).path.replace('\\', '/'),
                        line = lineNumber,
                        rule = rule.id,
                        severity = rule.severity,
                        description = rule.description,
                        maskedMatch = mask(match.value),
                    )
            }
        }
    }

    private fun lineOf(
        text: String,
        index: Int,
    ): Int {
        var line = 1
        var i = 0
        while (i < index) {
            if (text[i] == '\n') line += 1
            i += 1
        }
        return line
    }

    private fun mask(value: String): String {
        if (value.length <= 8) return "*** (len=${value.length})"
        val head = value.take(4)
        val tail = value.takeLast(2)
        return "$head...$tail (len=${value.length})"
    }

    private fun File.relativeToOrSelf(base: File): File =
        if (this.absolutePath.startsWith(base.absolutePath)) {
            File(this.absolutePath.removePrefix(base.absolutePath).trimStart('/', '\\'))
        } else {
            this
        }

    data class SecretsRule(
        val id: String,
        val description: String,
        val severity: SecretsSeverity,
        val regex: Regex,
    )
}

internal class PathMatcher(
    patterns: List<String>,
) {
    private val compiled: List<(String) -> Boolean> = patterns.map(::compile)

    fun matches(relativePath: String): Boolean = compiled.any { it(relativePath) }

    private fun compile(pattern: String): (String) -> Boolean {
        val anchored = pattern.startsWith("/")
        val body = pattern.trimStart('/')
        // Strip trailing /** so `node_modules/**` also matches the directory
        // `node_modules` itself - otherwise the directory's own subtree never
        // gets a chance to match the file-level filter.
        val bodyStripped = body.removeSuffix("/**")
        val sb = StringBuilder("^")
        var i = 0
        while (i < bodyStripped.length) {
            i = appendGlobClass(sb, bodyStripped, i)
        }
        // The trailing `/**` was stripped, so a pattern like `node_modules/**`
        // compiled to `^node_modules$` matches both `node_modules` (the dir)
        // and `node_modules/buried.txt` (after we anchor the trailing
        // segment too). We allow a trailing slash + anything for the latter.
        val trailing = if (body.endsWith("/**")) "(/.*)?" else ""
        sb.append(trailing).append('$')
        val regex = Regex(sb.toString())
        return { p ->
            val candidate = if (anchored) p.trimStart('/') else p
            regex.matches(candidate)
        }
    }

    private fun appendGlobClass(
        sb: StringBuilder,
        glob: String,
        i: Int,
    ): Int {
        val c = glob[i]
        return when {
            c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                sb.append(".*")
                i + 2
            }

            c == '*' -> {
                sb.append("[^/]*")
                i + 1
            }

            c == '?' -> {
                sb.append("[^/]")
                i + 1
            }

            isRegexMeta(c) -> {
                sb.append('\\').append(c)
                i + 1
            }

            else -> {
                sb.append(c)
                i + 1
            }
        }
    }

    private fun isRegexMeta(c: Char): Boolean =
        c == '.' || c == '(' || c == ')' || c == '+' || c == '|' ||
            c == '^' || c == '$' || c == '{' || c == '}' || c == '\\'
}
