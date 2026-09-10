package ai.rever.boss.services.supabase

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
         *    returned message; `SecretService` (BossConsole#145) does the same but passes
         *    `safe` straight to `Result.failure` rather than re-wrapping `safe.message` in a
         *    new `Exception`, which is a second legitimate shape of the same "sanitize once"
         *    rule, not a second rule. This helper sanitizes serialization and REST failures;
         *    the legacy error = safe exemption is not a guarantee for arbitrary server errors.
         *    SecretService is checked separately below and must not log the throwable at all;
         *  - `validate().getOrElse { return Result.failure(it) }`, which returns an
         *    `IllegalArgumentException` this code constructed from the caller's own request.
         *    No server payload has been touched at that point, so there is nothing to strip.
         *
         * Kept as an explicit allow-list rather than by narrowing the patterns, so that every
         * exemption is visible and has to be argued for.
         */
        val ALLOWED =
            Regex(
                """sanitizeSupabaseFailure\(|error = safe|\$\{safe\.message\}|Result\.failure\(safe\)|""" +
                    """validate\(\)\.getOrElse""",
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
            val lines = file.readLines()
            lines
                .withIndex()
                .filter { (_, line) -> pattern.containsMatchIn(line) && !ALLOWED.containsMatchIn(line) }
                // Only the shared decoder declaration may construct Json in this package.
                .filterNot { (index, line) ->
                    file.name == "SupabaseJson.kt" && line.trim() == "Json {" &&
                        lines.getOrNull(index - 1)?.trim() == "internal val supabaseJson ="
                }.map { (i, line) -> "${file.name}:${i + 1}: ${line.trim()}" }
        }

    @Test
    fun `authorization lists reject incomplete decoding instead of returning a denial`() {
        val source = File(sourceDir(), "RoleService.kt").readText()
        assertTrue(source.contains("supabaseJson.decodeFromJsonElement<List<UserRole>>"))
        assertTrue(source.contains("supabaseJson.decodeFromJsonElement<List<RolePermission>>"))
        assertFalse(source.contains("decodeListRecovering"))
    }

    @Test
    fun `paginated secrets preserve the row count used by plugin offsets`() {
        val source = File(sourceDir(), "SecretService.kt").readText()
        val operations =
            listOf(
                "getUserSecrets",
                "searchSecrets",
                "getUserSecretsWithShared",
                "getUserSecretsWithSharingInfo",
            )
        operations.forEach { operation ->
            assertTrue(source.contains("suspend fun $operation("), "$operation moved or was renamed; update this guard")
            val body = source.substringAfter("suspend fun $operation(").substringBefore("catch (e: Exception)")
            assertTrue(body.contains("supabaseJson.decodeFromJsonElement<List<"), "$operation must decode atomically")
            assertFalse(body.contains("decodeListRecovering"), "$operation needs a cursor API before dropping rows")
        }
    }

    @Test
    fun `security response fields cannot silently acquire coercible defaults`() {
        val contracts =
            mapOf(
                "RoleCreationService.kt" to
                    mapOf(
                        "RpcResponse" to listOf("success: Boolean"),
                        "RolesResponseNew" to listOf("success: Boolean"),
                        "PermissionsResponseNew" to listOf("success: Boolean"),
                        "RolePermissionsResponse" to listOf("success: Boolean"),
                        "RoleDataNew" to listOf("isSystem: Boolean"),
                        "PermissionDataNew" to listOf("isSystem: Boolean"),
                    ),
                "models/SecretModels.kt" to
                    mapOf(
                        "SecretEntryWithSharing" to listOf("isOwner: Boolean", "accessLevel: String"),
                        "SecretShareEntry" to listOf("shareId: String", "accessLevel: String", "createdAt: String"),
                    ),
            )
        contracts.forEach { (file, models) ->
            val source = File(sourceDir(), file).readText()
            models.forEach { (model, fields) ->
                val marker = "data class $model("
                assertTrue(source.contains(marker), "$model moved; update the coercion guard")
                val declaration = source.substringAfter(marker).substringBefore("\n)")
                fields.forEach { field ->
                    assertTrue(
                        declaration.lineSequence().any { it.trim() == "val $field," },
                        "$model.$field must remain required without a default",
                    )
                }
            }
        }
    }

    @Test
    fun `Supabase consumers throughout the repository avoid default Json decoding`() {
        val root =
            generateSequence(sourceDir()) { it.parentFile }
                .first { File(it, "settings.gradle.kts").isFile }
        val consumers =
            root
                .walkTopDown()
                .onEnter { it.name !in setOf("build", ".gradle", ".git", ".worktrees") }
                .filter { file ->
                    file.isFile && file.extension == "kt" &&
                        file.relativeTo(root).invariantSeparatorsPath.contains(Regex("/src/[^/]*Main/")) &&
                        file.readText().contains("import io.github.jan.supabase")
                }.toList()
        assertTrue(consumers.any { it.name == "SecretService.kt" }, "guard must find real production consumers")
        val defaultDecode = Regex("""(?<![A-Za-z0-9_])Json\.(Default\.)?(decodeFrom|parseToJsonElement)""")
        val violations =
            consumers.flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (defaultDecode.containsMatchIn(line) && !line.trimStart().startsWith("//")) {
                        "${file.relativeTo(root)}:${index + 1}"
                    } else {
                        null
                    }
                }
            }
        assertEquals(emptyList(), violations, "Supabase consumers must configure their decoder")
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
        // Check each catch rather than a file-wide sanitizer count. RAW_RETURNED also guards
        // direct raw returns across the package; here the same sanitized local must be returned.
        // These source checks cover wiring without sending secret RPCs to a live backend.
        val source = File(sourceDir(), "SecretService.kt").readText()
        val catches = Regex("""catch \(e: Exception\)""").findAll(source).count()
        val blocks = Regex("""catch \(e: Exception\) \{([^{}]*)}""").findAll(source).toList()

        assertTrue(catches > 0, "found no catch blocks - has the file moved?")
        assertEquals(catches, blocks.size, "a catch changed shape; update the wiring guard")
        blocks.forEach { match ->
            val body =
                match.groupValues[1]
                    .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                    .replace(Regex("""//[^\n]*"""), "")
            assertTrue(body.contains("val safe = sanitizeSupabaseFailure("), "catch must sanitize: $body")
            assertTrue(body.contains("Result.failure(safe)"), "catch must return the sanitized local: $body")
            assertTrue(body.contains("logger.warn("), "RPC failure must remain visible: $body")
            assertTrue(body.contains("\"errorType\" to e::class.simpleName"), "keep diagnostic type: $body")
            assertFalse(Regex("""error\s*=""").containsMatchIn(body), "never log the throwable: $body")
            assertFalse(body.contains(".message"), "server messages can echo secret values: $body")
        }
    }
}
