package ai.rever.boss.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the pure [mergeUserPath] helper used by [CLIInstaller] on Windows.
 *
 * The previous `setx PATH "..."` implementation truncated the merged PATH at
 * 1024 characters silently, dropping the BOSS bin entry on long-PATH machines.
 * `mergeUserPath` is the unit-testable seam: it produces the full string that
 * `reg add HKCU\Environment /v Path` will write, so the tests assert the
 * round-trip is faithful at the lengths the bug used to break at.
 *
 * The tests use a synthetic long-PATH entry rather than poking the real
 * registry - the registry round-trip is covered by the installer's hand/manual
 * smoke path, not here.
 */
class CLIInstallerPathMergeTest {
    private val binDir = "C:\\Users\\me\\bin"

    @Test
    fun `appends binDir to a short existing user PATH`() {
        val current = "C:\\Users\\me\\.local\\bin"
        val merged = mergeUserPath(current, binDir)
        assertEquals("C:\\Users\\me\\.local\\bin;C:\\Users\\me\\bin;", merged)
    }

    @Test
    fun `preserves existing entries without reordering them`() {
        val current = "C:\\A;C:\\B;C:\\C"
        val merged = mergeUserPath(current, binDir)
        assertEquals("C:\\A;C:\\B;C:\\C;C:\\Users\\me\\bin;", merged)
    }

    @Test
    fun `does not duplicate when binDir is already present`() {
        val current = "C:\\A;$binDir;C:\\B"
        val merged = mergeUserPath(current, binDir)
        assertEquals(current, merged)
    }

    @Test
    fun `duplicate check is case-insensitive on Windows paths`() {
        val current = "c:\\users\\me\\BIN;C:\\Other"
        val merged = mergeUserPath(current, binDir)
        assertEquals(current, merged)
    }

    @Test
    fun `duplicate check ignores trailing whitespace inside entries`() {
        val current = "C:\\Users\\me\\bin  ;C:\\Other"
        val merged = mergeUserPath(current, binDir)
        assertEquals(current, merged)
    }

    @Test
    fun `PATH longer than 1024 chars is not truncated`() {
        // Build a 1500-char user PATH (entry strings, semicolon-joined).
        val fillerEntries = (1..30).map { "C:\\Toolchains\\SomeLongToolName_${"%010d".format(it)}\\bin" }
        val current = fillerEntries.joinToString(";")
        val beforeLength = current.length
        assertTrue(beforeLength > 1024, "fixture should exceed the 1024-char truncation boundary")

        val merged = mergeUserPath(current, binDir)
        // The merged string keeps every original entry, in order, plus binDir at the end.
        fillerEntries.forEach { entry ->
            assertTrue(merged.contains(entry), "missing original entry: $entry")
        }
        assertTrue(merged.endsWith("$binDir;"), "binDir must be appended verbatim, not truncated")
        assertEquals(beforeLength + 1 + binDir.length + 1, merged.length)
    }

    @Test
    fun `PATH longer than 2048 chars is not truncated`() {
        // 60 long entries push us past 2048 chars; this is the case that also used
        // to break under setx.
        val fillerEntries = (1..60).map { "C:\\Program Files\\LongApplicationName_${"%010d".format(it)}\\bin" }
        val current = fillerEntries.joinToString(";")
        assertTrue(current.length > 2048, "fixture should exceed 2048 chars")

        val merged = mergeUserPath(current, binDir)
        fillerEntries.forEach { entry ->
            assertTrue(merged.contains(entry), "missing original entry: $entry")
        }
        assertTrue(merged.endsWith("$binDir;"), "binDir must be appended verbatim, not truncated")
    }

    @Test
    fun `empty current PATH still produces a well-formed value`() {
        val merged = mergeUserPath("", binDir)
        assertEquals("$binDir;", merged)
    }

    @Test
    fun `blank binDir returns the current PATH unchanged`() {
        val current = "C:\\A;C:\\B"
        assertEquals(current, mergeUserPath(current, ""))
        assertEquals(current, mergeUserPath(current, "   "))
    }

    @Test
    fun `existing trailing semicolon does not add a double separator`() {
        val current = "C:\\A;C:\\B;"
        val merged = mergeUserPath(current, binDir)
        assertEquals("C:\\A;C:\\B;$binDir;", merged)
    }

    @Test
    fun `output is well-formed REG_EXPAND_SZ - one separator per join`() {
        val current = "C:\\A;C:\\B"
        val merged = mergeUserPath(current, binDir)
        // No ";;" or trailing ";;;" anywhere - the only ";" is the separator between
        // entries or between the last entry and binDir.
        assertTrue(";;" !in merged, "found doubled separator in: $merged")
    }
}

/**
 * Tests for the read/merge/write decision on a present-but-empty HKCU\Environment
 * Path value.
 *
 * The previous `readUserPath` returned null whenever the captured value was an
 * empty string (`takeIf { it.isNotEmpty() }`), so an HKCU key that existed but
 * held nothing - a real Windows state - was indistinguishable from a missing
 * key. `updateWindowsPath` then took the null branch and silently skipped the
 * write, so a user with a cleared user PATH got no BOSS entry added.
 *
 * `parseRegPathOutput` is the pure seam: it parses a literal `reg query`
 * response and returns null for an absent key, "" for a present-but-empty
 * value, or the captured value. The two helpers together make the regression
 * testable without a live registry.
 */
class CLIInstallerEmptyUserPathTest {
    private val binDir = "C:\\Users\\me\\bin"

    @Test
    fun `present but empty user PATH parses as empty string - not null`() {
        // `reg query HKCU\Environment /v Path` for a key that exists but holds
        // no characters. The trailing capture group is intentionally empty.
        val regOutput =
            """
            HKEY_CURRENT_USER\Environment
                Path    REG_EXPAND_SZ
            """.trimIndent()
        val parsed = parseRegPathOutput(regOutput)
        assertEquals("", parsed)
    }

    @Test
    fun `absent user PATH key parses as null`() {
        // `reg query` exits non-zero when the value is missing and writes a
        // message instead of the key/value pair - this branch is what the
        // caller sees for a fresh user profile with no Path value at all.
        val regOutput = "ERROR: The system was unable to find the specified registry key or value."
        val parsed = parseRegPathOutput(regOutput)
        assertNull(parsed)
    }

    @Test
    fun `normal user PATH parses as the captured value`() {
        val regOutput =
            """
            HKEY_CURRENT_USER\Environment
                Path    REG_EXPAND_SZ    C:\Users\me\.local\bin;C:\Tools\bin
            """.trimIndent()
        val parsed = parseRegPathOutput(regOutput)
        assertEquals("C:\\Users\\me\\.local\\bin;C:\\Tools\\bin", parsed)
    }

    @Test
    fun `REG_SZ variant is also accepted`() {
        val regOutput =
            """
            HKEY_CURRENT_USER\Environment
                Path    REG_SZ    C:\Other
            """.trimIndent()
        val parsed = parseRegPathOutput(regOutput)
        assertEquals("C:\\Other", parsed)
    }

    /**
     * End-to-end regression for the silent skip: parse a `reg query` response
     * that reports a present-but-empty Path value, feed that into
     * [mergeUserPath], and assert the merged string is exactly what `reg add`
     * would write to add BOSS to a previously-cleared user PATH. This is the
     * decision the install actually makes; if `parseRegPathOutput` regresses
     * to returning null here, the assertion below fails.
     */
    @Test
    fun `empty user PATH read produces a merge that adds BOSS`() {
        val regOutput =
            """
            HKEY_CURRENT_USER\Environment
                Path    REG_EXPAND_SZ
            """.trimIndent()
        val currentPath = parseRegPathOutput(regOutput)
        // The point of the regression: this MUST be "", not null. A null would
        // tell updateWindowsPath to return false without writing anything.
        assertEquals("", currentPath)

        val merged = mergeUserPath(currentPath!!, binDir)
        assertEquals("$binDir;", merged)
    }
}
