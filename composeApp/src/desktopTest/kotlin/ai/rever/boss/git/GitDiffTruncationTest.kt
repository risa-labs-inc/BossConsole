package ai.rever.boss.git

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The diff getters' refusal of a truncated stream, and the too-large marker
 * that replaces the blank tab.
 *
 * A truncated `git diff` parses into a PARTIAL diff that looks complete -
 * files silently missing, a hunk cut mid-way - which reads as "that's the
 * whole change". The refusal is therefore a user-visible behaviour: the tab
 * must say WHY it is empty, and a diff that still exceeds the cap after the
 * small-context retry must not be indistinguishable from "no changes".
 */
class GitDiffTruncationTest {
    @Test
    fun `a truncated diff is refused with an in-band explanation, not a blank`() {
        val result =
            GitService.GitCommandResult(
                output = "diff --git a/huge.txt b/huge.txt\n--- a/huge.txt\n+++ b/huge.txt\n",
                error = "",
                exitCode = 0,
                truncated = true,
            )

        val diffs = GitService.parseDiffSafely(result, "huge.txt")

        // One marker entry, not an empty list: an empty list is "no changes",
        // which is the lie the refusal exists to prevent.
        assertEquals(1, diffs.size, "a truncated stream must not parse into a partial diff")
        val marker = diffs.single()
        assertEquals("huge.txt", marker.path)
        assertTrue(
            marker.rawUnified.contains("too large to render"),
            "the marker must carry the user-visible reason: ${marker.rawUnified}",
        )
        assertTrue(marker.hunks.isEmpty(), "a refusal must not carry parsed hunks")
    }

    @Test
    fun `an untruncated diff parses normally`() {
        val result =
            GitService.GitCommandResult(
                output =
                    "diff --git a/x.txt b/x.txt\n" +
                        "index 1111111..2222222 100644\n" +
                        "--- a/x.txt\n" +
                        "+++ b/x.txt\n" +
                        "@@ -1 +1 @@\n" +
                        "-old\n" +
                        "+new\n",
                error = "",
                exitCode = 0,
                truncated = false,
            )

        val diffs = GitService.parseDiffSafely(result, "x.txt")

        assertEquals(1, diffs.size)
        assertEquals("x.txt", diffs.single().path)
        assertTrue(diffs.single().rawUnified.isNotEmpty())
        assertFalse(diffs.single().rawUnified.contains("too large to render"))
    }

    @Test
    fun `a parse failure on an untruncated stream degrades to no diff, not an exception`() {
        // A header shape the shape flags do not cover reaches the parser's
        // require(path.isNotEmpty()); the caller sees "no diff", never a throw.
        val result =
            GitService.GitCommandResult(
                output = "not a diff at all",
                error = "",
                exitCode = 0,
                truncated = false,
            )

        val diffs = GitService.parseDiffSafely(result, "odd.txt")

        assertTrue(diffs.isEmpty())
    }
}
