package ai.rever.boss.services.supabase

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The services must be WIRED to the shared decoder and the sanitiser, not merely have them
 * available.
 *
 * `SupabaseJsonTest` proves `sanitizeSupabaseFailure` strips the payload, and
 * `SecretDecodingTest` proves `supabaseJson` tolerates new columns. Neither goes through a
 * service, so reverting all ten `SecretService` call sites passed the entire suite -
 * verified, not assumed. The behaviour was pinned and the wiring was not, which is the half
 * that actually broke production.
 *
 * These services construct their Postgrest client from `SupabaseConfig` with no seam to
 * inject a fake, so exercising them for real needs a live backend. Reading the source is
 * the honest alternative: weaker than an execution test, far stronger than nothing. The
 * same approach guards the organisation function's views.
 *
 * The patterns below are deliberately broad. The first version matched only the exact
 * shapes already in the tree, which is the failure mode of a guard like this: it passes
 * forever while the thing it guards erodes around it.
 */
class SupabaseWiringTest {
    private companion object {
        /**
         * A strict `Json`, however it is spelled.
         *
         * Covers `Json.decodeFrom…`, the fully-qualified `kotlinx.serialization.json.Json.…`
         * (plausible here - this package already writes `…postgrest.query.Columns.raw(…)`
         * inline), the `Json.Default.…` an IDE inserts, and a locally-built `Json { … }`,
         * which is the likeliest accident of all. `supabaseJson` must not match, hence the
         * word boundary rather than a bare prefix.
         */
        val STRICT_JSON =
            Regex("""(?<![A-Za-z0-9_])Json\s*\{|(?<![A-Za-z0-9_])Json\.(Default\.)?(decodeFrom|parseToJsonElement)""")

        /** A raw throwable handed to a logger, whatever the variable is called. */
        val RAW_ERROR_LOG = Regex("""error\s*=\s*(?!sanitize)\w+\s*[,)]""")

        /** A raw throwable, or its message, handed back to a caller. */
        val RAW_RETURNED = Regex("""Result\.failure\(\s*\w+\s*\)|\$\{\w+\.message\}""")

        /**
         * Lines the scans must not flag, each exempt for a stated reason.
         *
         *  - the sanitised forms themselves, including the `safe` local that
         *    `SupabaseDataProviderImpl` sanitises once and uses for both the log and the
         *    returned message;
         *  - the declaration of `supabaseJson`, which is necessarily a `Json { }`;
         *  - `validate().getOrElse { return Result.failure(it) }`, which returns an
         *    `IllegalArgumentException` this code constructed from the caller's own request.
         *    No server payload has been touched at that point, so there is nothing to strip.
         *
         * Kept as an explicit allow-list rather than by narrowing the patterns, so that every
         * exemption is visible and has to be argued for.
         */
        val ALLOWED =
            Regex(
                """sanitizeSupabaseFailure\(|error = safe|\$\{safe\.message\}|""" +
                    """val supabaseJson = Json|validate\(\)\.getOrElse""",
            )
    }

    private val packageDir = "composeApp/src/commonMain/kotlin/ai/rever/boss/services/supabase"

    /**
     * Tests run with an unspecified working directory depending on the invocation, so walk
     * up until the repository root is underfoot rather than assuming one.
     */
    private fun sourceDir(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, packageDir)
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        fail("could not locate $packageDir from ${File(".").absolutePath}")
    }

    /** walkTopDown, not listFiles: `models/` and any future subpackage must be covered too. */
    private fun sources(): List<File> =
        sourceDir()
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
            .ifEmpty { fail("no sources under ${sourceDir()}") }

    private fun scan(pattern: Regex): List<String> =
        sources().flatMap { file ->
            file
                .readLines()
                .withIndex()
                .filter { (_, line) -> pattern.containsMatchIn(line) && !ALLOWED.containsMatchIn(line) }
                .map { (i, line) -> "${file.name}:${i + 1}: ${line.trim()}" }
        }

    @Test
    fun `no service decodes with the strict Json default`() {
        assertEquals(
            emptyList(),
            scan(STRICT_JSON),
            "decode Supabase payloads through supabaseJson, never a strict Json",
        )
    }

    @Test
    fun `no raw throwable reaches a logger`() {
        // RoleService's JWT path is the case that motivated this: it carries a comment saying
        // the payload is deliberately not logged, above a catch that logged the raw exception.
        assertEquals(
            emptyList(),
            scan(RAW_ERROR_LOG),
            "log sanitizeSupabaseFailure(op, e) - a raw failure can quote the payload",
        )
    }

    @Test
    fun `no raw throwable or message reaches a caller`() {
        // The half missed the first time. SupabaseDataProviderImpl sanitised its log and then
        // rebuilt the returned exception from the raw message - so the document was stripped
        // from our log and handed straight to the plugin, which is at least as likely to log
        // it. Sanitising one direction only is close to no fix at all.
        assertEquals(
            emptyList(),
            scan(RAW_RETURNED),
            "return sanitizeSupabaseFailure(op, e), or its .message - callers log what they get",
        )
    }

    @Test
    fun `every SecretService catch block is sanitised`() {
        // Derived from the number of catch blocks rather than hardcoded, so deleting a method
        // cannot quietly satisfy the assertion.
        val source = File(sourceDir(), "SecretService.kt").readText()
        val catches = Regex("""catch \(e: Exception\)""").findAll(source).count()
        val sanitised = Regex("""Result\.failure\(sanitizeSupabaseFailure\(""").findAll(source).count()

        assertTrue(catches > 0, "found no catch blocks - has the file moved?")
        assertEquals(catches, sanitised, "$catches catch blocks but $sanitised sanitised returns")
    }
}
