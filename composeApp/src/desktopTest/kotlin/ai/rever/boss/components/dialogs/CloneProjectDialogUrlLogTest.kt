package ai.rever.boss.components.dialogs

import ai.rever.boss.testsupport.repoRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the clone dialog's "Starting clone operation" line may carry (#1602): the URL masked
 * and control-character-free, never raw.
 *
 * The dialog's log call runs BEFORE [ai.rever.boss.git.GitService.cloneRepository] hands the
 * URL to any validation, and BossLogger appends the data map to the log line as-is, so this
 * call site is the one a user can actually reach with a pasted URL. Two pins:
 *
 * - the seam: the source assertion holds the log call to `cloneUrlForLog(repositoryUrl)`, so a
 *   `https://user:<PAT>@host` clone URL or an interior control character cannot reach the log
 *   file and the Console capture verbatim. A convention test rather than review vigilance
 *   because the leak is one missing call in an argument list (the same shape
 *   `BrowserUrlLogConventionTest` guards the browser sites against).
 * - the behavior: [cloneUrlForLog] removes the credential and every record-breaking
 *   character while keeping an ordinary URL - and a local path's spaces - readable.
 */
class CloneProjectDialogUrlLogTest {
    @Test
    fun `the logged clone URL carries no credential userinfo`() {
        val logged = cloneUrlForLog("https://x-access-token:ghp_secret123@github.com/risa-labs-inc/BossConsole.git")

        assertFalse(logged.contains("ghp_secret123"), logged)
        assertFalse(logged.contains("x-access-token"), logged)
        assertEquals("https://[REDACTED]@github.com/risa-labs-inc/BossConsole.git", logged)
    }

    @Test
    fun `the logged clone URL masks sensitive query parameters and keeps the rest readable`() {
        val logged = cloneUrlForLog("https://example.com/o/r.git?token=abc123&dir=left")

        assertFalse(logged.contains("abc123"), logged)
        assertTrue(logged.contains("dir=left"), logged)
    }

    @Test
    fun `an interior newline cannot forge a second log record`() {
        val logged = cloneUrlForLog("https://example.com/o/r.git\n[WARN] [SYSTEM] forged record")

        assertFalse(logged.contains('\n'), logged)
        assertFalse(logged.contains('\r'), logged)
    }

    @Test
    fun `every record-breaking character is stripped, including the ones past U+007F`() {
        val recordBreaks =
            listOf(
                '\u0000', // NUL
                '\u000B', // vertical tab
                '\r', // carriage return
                '\n', // line feed
                '\u001C', // file separator
                '\u007F', // DEL
                '\u0085', // NEL: the record break a `code < 0x20` check misses
                '\u2028', // LINE SEPARATOR
                '\u2029', // PARAGRAPH SEPARATOR
            )
        recordBreaks.forEach { control ->
            val logged = cloneUrlForLog("https://example.com/a${control}b.git")
            assertFalse(logged.contains(control), "U+%04X survived masking: $logged".format(control.code))
        }
    }

    @Test
    fun `an ordinary clone URL survives masking readable`() {
        assertEquals(
            "https://github.com/risa-labs-inc/BossConsole.git",
            cloneUrlForLog("https://github.com/risa-labs-inc/BossConsole.git"),
        )
    }

    @Test
    fun `spaces survive, because a local clone path legitimately contains them`() {
        assertEquals("/srv/git/my repo.git", cloneUrlForLog("/srv/git/my repo.git"))
    }

    @Test
    fun `the dialog logs the URL through cloneUrlForLog, never raw`() {
        val source =
            File(repoRoot(), "composeApp/src/commonMain/kotlin/ai/rever/boss/components/dialogs/CloneProjectDialog.kt")
                .readText()

        assertTrue(
            Regex("""mapOf\(\s*"url" to cloneUrlForLog\(repositoryUrl\)""").containsMatchIn(source),
            "the clone log call no longer puts the URL through cloneUrlForLog",
        )
        assertTrue(
            Regex(""""target" to targetDirectory\.filterNot""").containsMatchIn(source),
            "the clone log call no longer strips control characters from the target path",
        )
        assertFalse(
            Regex("""mapOf\(\s*"url" to repositoryUrl""").containsMatchIn(source),
            "a log call puts the raw clone URL into the data map again",
        )
    }
}
