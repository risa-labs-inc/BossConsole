package ai.rever.boss.utils.logging

import ai.rever.boss.testsupport.repoRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Asserts that the files carrying a browser or opened-link URL into a log line put it into the data map
 * only through [LogSanitizer.describeUri].
 *
 * `BossLogger` appends the data map to the line as-is, the line goes to `System.out`, and
 * `GlobalLogCapture` keeps it for the Console panel - which every plugin reads through
 * `PluginContext.logDataProvider`, and which the console plugin serves to MCP clients through
 * `console_tail` and `console_search`. A tab's URL is where an OAuth `code`, a magic-link `token` or a
 * presigned `X-Amz-Signature` lives, and "Browser created via BrowserService" logged it at INFO, the
 * default level, on every tab creation, hibernation wake and crash recovery.
 *
 * **`maskUriParams` does not count.** It redacts by exact parameter name, so it passes a credential
 * under any other name, and a deep link's query is often not a credential at all but another URL
 * (`boss://url?url=...`) or a command (`boss://terminal?command=...`), each carried whole under a name
 * no list would redact. `DeepLinkHandler` logged every incoming link through it.
 *
 * A convention test rather than review vigilance because the leak is one missing call in an argument
 * list, and every one of these files already sanitizes some of its URLs, so a raw one reads as
 * correct next to them.
 *
 * **Scoped to named files, not the whole tree.** Other open work owns the remaining raw sites
 * (authentication logging, and `URLHandlerService`, whose URL routing is being rewritten), and a
 * tree-wide scan would have to allowlist lines another change is about to move. Add a file here once
 * its URL logging is sanitized.
 *
 * Still a text check, with these limits:
 * - it reads calls on a receiver named `logger` only (`log.info(`, `BossLogger.forComponent(...).info(`
 *   are not seen);
 * - it looks at `"key" to value` pairs whose key **ends** in url, uri, link or href, so `"urlString"` is
 *   not seen, and a URL built into the message string is not seen - none of these three files has
 *   either shape today;
 * - it skips string literals, character literals and comments to find the end of a call, but not a
 *   raw string that contains a single `"`.
 */
class BrowserUrlLogConventionTest {
    private val guarded =
        listOf(
            "composeApp/src/desktopMain/kotlin/ai/rever/boss/plugin/browser/BrowserServiceImpl.kt",
            "composeApp/src/desktopMain/kotlin/ai/rever/boss/utils/DeepLinkHandler.kt",
            "composeApp/src/desktopMain/kotlin/ai/rever/boss/cli/CLICommandHandler.kt",
        )

    @Test
    fun `guarded files log no url-keyed value except through describeUri`() {
        val root = repoRoot()
        val offenders =
            guarded.flatMap { path ->
                val file = File(root, path)
                check(file.isFile) { "guarded file moved or renamed: $path" }
                unsanitizedUrlEntries(file.readText()).map { "$path:${it.line}  ${it.entry}" }
            }

        if (offenders.isNotEmpty()) {
            fail(
                "These log calls put a URL into the data map without LogSanitizer.describeUri, so a token " +
                    "in its query or fragment, or a URL or command carried in a deep link's query, reaches " +
                    "the Console capture that plugins and MCP clients read:\n  " +
                    offenders.joinToString("\n  "),
            )
        }
    }

    @Test
    fun `the scan finds single-line, multi-line and suffixed keys, and accepts only describeUri`() {
        val source =
            """
            fun f() {
                logger.info(LogCategory.BROWSER, "a", mapOf("url" to url))
                logger.warn(
                    LogCategory.BROWSER,
                    "b (not a closing paren",
                    mapOf(
                        "handleId" to id,
                        "targetUrl" to config.url,
                    ),
                )
                logger.debug(LogCategory.SYSTEM, "c", mapOf("uri" to uri, "n" to 1))
                logger.info(LogCategory.BROWSER, "d", mapOf("url" to LogSanitizer.describeUri(url)))
                logger.info(LogCategory.BROWSER, "e", mapOf("url" to LogSanitizer.maskUriParams(url)))
                logger.info(LogCategory.BROWSER, "f", mapOf("hasUrl" to (url != null)))
                val notALog = mapOf("url" to url)
            }
            """.trimIndent()

        assertEquals(
            listOf(
                2 to "\"url\" to url",
                8 to "\"targetUrl\" to config.url",
                11 to "\"uri\" to uri",
                13 to "\"url\" to LogSanitizer.maskUriParams(url)",
            ),
            unsanitizedUrlEntries(source).map { it.line to it.entry },
        )
    }

    @Test
    fun `a quote in a comment or a character literal does not end the call early`() {
        val source =
            """
            fun f() {
                logger.info(
                    LogCategory.BROWSER,
                    "a",
                    // a lone " in a comment
                    mapOf("n" to 1),
                )
                logger.info(LogCategory.BROWSER, "b", mapOf("sep" to '"', "url" to url))
            }
            """.trimIndent()

        // Read as a string delimiter, either quote runs the call on past its closing parenthesis.
        assertEquals(listOf(8 to "\"url\" to url"), unsanitizedUrlEntries(source).map { it.line to it.entry })
    }

    private data class Entry(
        val line: Int,
        val entry: String,
    )

    private val loggerCall = Regex("""\blogger\.(trace|debug|info|warn|error)\s*\(""")
    private val urlKeyed = Regex(""""([A-Za-z_]*(?:[Uu]rl|[Uu]ri|URL|URI|[Ll]ink|[Hh]ref))"\s+to\s+""")

    /** `hasUrl`, `isLink`: a flag about a URL, not the URL. */
    private val booleanKey = Regex("""^(has|is)[A-Z]""")

    private fun unsanitizedUrlEntries(source: String): List<Entry> =
        loggerCall
            .findAll(source)
            .flatMap { call ->
                val open = call.range.last
                val args = source.substring(open, closingParen(source, open) + 1)
                urlKeyed.findAll(args).mapNotNull { pair ->
                    val value = valueExpression(args, pair.range.last + 1)
                    val key = pair.groupValues[1]
                    if (value.startsWith("LogSanitizer.describeUri(") || booleanKey.containsMatchIn(key)) {
                        null
                    } else {
                        val line = source.substring(0, open + pair.range.first).count { it == '\n' } + 1
                        Entry(line, "\"$key\" to $value")
                    }
                }
            }.toList()

    /** Index of the parenthesis closing the one at [open], skipping string and character literals and comments. */
    private fun closingParen(
        source: String,
        open: Int,
    ): Int {
        var depth = 0
        var inString = false
        var i = open
        while (i < source.length) {
            val c = source[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                inString -> Unit
                c == '(' -> depth++
                c == ')' -> if (--depth == 0) return i
                else -> i = endOfCommentOrChar(source, i)
            }
            i++
        }
        error("unbalanced call starting at offset $open")
    }

    /**
     * When a comment or a character literal (`'"'`, `'\''`) starts at [start], the index of its last
     * character, so a quote inside it is not read as a string delimiter; otherwise [start] itself.
     */
    private fun endOfCommentOrChar(
        source: String,
        start: Int,
    ): Int {
        val escaped = source.getOrNull(start + 1) == '\\'
        val end =
            when {
                source.startsWith("//", start) -> source.indexOf('\n', start)
                source.startsWith("/*", start) -> source.indexOf("*/", start) + 1
                source[start] == '\'' -> source.indexOf('\'', if (escaped) start + 3 else start + 2)
                else -> start
            }
        return if (end < start) source.length else end
    }

    /** The value of a `key to value` pair: up to the next top-level comma or closing bracket. */
    private fun valueExpression(
        args: String,
        start: Int,
    ): String {
        var depth = 0
        for (i in start until args.length) {
            val c = args[i]
            if (depth == 0 && (c == ',' || c in ")]}")) return args.substring(start, i).trim()
            if (c in "([{") {
                depth++
            } else if (c in ")]}") {
                depth--
            }
        }
        return args.substring(start).trim()
    }
}
