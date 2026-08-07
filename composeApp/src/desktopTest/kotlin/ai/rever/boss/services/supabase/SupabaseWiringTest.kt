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
 * `SupabaseJsonTest` proves `sanitizeResponseFailure` strips the payload, and
 * `SecretDecodingTest` proves `supabaseJson` tolerates new columns. Neither goes through
 * `SecretService`, so reverting all ten call sites to `Result.failure(e)` passed the entire
 * suite - verified, not assumed. The behaviour was pinned and the wiring was not, which is
 * the half that actually broke production.
 *
 * These services construct their Postgrest client from `SupabaseConfig`, so exercising them
 * for real needs a live backend and there is no seam to inject one. Reading the source is
 * the honest alternative: it is a weaker assertion than an execution test, and a far
 * stronger one than nothing. The same approach guards the organisation function's views.
 */
class SupabaseWiringTest {
    private companion object {
        /** `Json.decodeFrom…` / `Json.parseToJsonElement`, but not `supabaseJson.` or `importJson.`. */
        val STRICT_JSON = Regex("""(?<![A-Za-z0-9_.])Json\.(decodeFrom|parseToJsonElement)""")

        /** A raw exception handed to a logger, which can quote the payload. */
        val RAW_ERROR_LOG = Regex("""error\s*=\s*e\s*[,)]""")
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

    private fun sources(): List<File> =
        sourceDir().listFiles { f: File -> f.extension == "kt" }?.toList()
            ?: fail("no sources under ${sourceDir()}")

    @Test
    fun `no service decodes with the strict Json default`() {
        // The wildcard `kotlinx.serialization.json.*` import in these files keeps Json.Default
        // in scope, so this is what a new call site gets by simply not thinking about it.
        val offenders =
            sources().flatMap { file ->
                file
                    .readLines()
                    .withIndex()
                    .filter { (_, line) -> STRICT_JSON.containsMatchIn(line) }
                    .map { (i, line) -> "${file.name}:${i + 1}: ${line.trim()}" }
            }

        assertEquals(
            emptyList(),
            offenders,
            "decode Supabase responses through supabaseJson, never the Json default",
        )
    }

    @Test
    fun `every SecretService failure is sanitised before it reaches a caller`() {
        // SecretService returns exceptions to callers that log e.message, and its response
        // bodies hold server-decrypted passwords. An unsanitised return is a credential leak,
        // not a style issue.
        val source = File(sourceDir(), "SecretService.kt").readText()

        val raw =
            Regex("""Result\.failure\(\s*e\s*\)""").findAll(source).count()
        val sanitised =
            Regex("""Result\.failure\(sanitizeResponseFailure\(""").findAll(source).count()

        assertEquals(0, raw, "SecretService returns $raw unsanitised exception(s) to callers")
        assertTrue(sanitised >= 10, "expected every catch block sanitised, found $sanitised")
    }

    @Test
    fun `the sanitiser is applied wherever a decode failure is logged`() {
        // RoleService's JWT path is the case that motivated this: it carries a comment saying
        // the payload is deliberately not logged, and its catch logged the raw exception,
        // which puts the whole claim set back in the log on a garbled payload.
        val offenders =
            sources().flatMap { file ->
                file
                    .readLines()
                    .withIndex()
                    .filter { (_, line) -> RAW_ERROR_LOG.containsMatchIn(line) }
                    .map { (i, line) -> "${file.name}:${i + 1}: ${line.trim()}" }
            }

        assertEquals(
            emptyList(),
            offenders,
            "log sanitizeResponseFailure(op, e), not the raw exception - it can quote the response body",
        )
    }
}
