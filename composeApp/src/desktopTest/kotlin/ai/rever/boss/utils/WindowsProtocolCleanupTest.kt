package ai.rever.boss.utils

import ai.rever.boss.utils.WindowsProtocolCleanup.CleanupDecision
import ai.rever.boss.utils.WindowsProtocolCleanup.CommandState
import ai.rever.boss.utils.WindowsProtocolCleanup.classifyProtocolCleanup
import ai.rever.boss.utils.WindowsProtocolCleanup.exitCodeFor
import ai.rever.boss.utils.WindowsProtocolCleanup.extractExecutablePath
import ai.rever.boss.utils.WindowsProtocolCleanup.maskUserPath
import ai.rever.boss.utils.WindowsProtocolCleanup.parseCommandState
import ai.rever.boss.utils.WindowsProtocolCleanup.parseRootKeyPresence
import ai.rever.boss.utils.WindowsProtocolHandler.UnregisterOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for the safety policy behind `BOSS.exe --unregister-protocol`:
 * [parseCommandState] (what the registry currently holds) and [classifyProtocolCleanup]
 * (whether that licenses a delete).
 *
 * Only the `reg delete` itself needs a real registry. Both halves of the decision are pure,
 * and both are where the dangerous mistakes live: deleting a registration that belongs to a
 * live install — or that we merely failed to read — breaks `boss://` links for someone
 * else's working BOSS. Leaving an orphan key behind is cosmetic by comparison, so every
 * ambiguous case must land on "report", not "delete".
 */
class WindowsProtocolCleanupTest {
    private val thisApp = """C:\Users\me\AppData\Local\BOSS\BOSS.exe"""
    private val otherApp = """C:\Program Files\BOSS\BOSS.exe"""

    private fun classify(
        rootPresent: Boolean? = true,
        command: CommandState,
        appPath: String? = thisApp,
        existing: Set<String> = setOf(thisApp, otherApp),
    ) = classifyProtocolCleanup(rootPresent, command, appPath) { it in existing }

    private fun regOutput(
        type: String = "REG_SZ",
        name: String = "Default",
        value: String,
    ) = "\n$PROTOCOL_KEY_TEST\\shell\\open\\command\n    ($name)    $type    $value\n\n"

    // region parseCommandState

    @Test
    fun `parses a quoted REG_SZ command`() {
        assertEquals(
            CommandState.Present(""""$thisApp" "%1""""),
            parseCommandState(0, regOutput(value = """"$thisApp" "%1"""")),
        )
    }

    /** An installer-authored value is commonly REG_EXPAND_SZ; it must not read as missing. */
    @Test
    fun `parses a REG_EXPAND_SZ command`() {
        assertEquals(
            CommandState.Present(""""%LOCALAPPDATA%\BOSS\BOSS.exe" "%1""""),
            parseCommandState(0, regOutput(type = "REG_EXPAND_SZ", value = """"%LOCALAPPDATA%\BOSS\BOSS.exe" "%1"""")),
        )
    }

    @Test
    fun `treats reg's not-found output as missing`() {
        assertEquals(
            CommandState.Missing,
            parseCommandState(1, "ERROR: The system was unable to find the specified registry key or value."),
        )
    }

    /**
     * Regression: `reg.exe` exits 1 for access-denied, policy/EDR blocks and truncated
     * output too. Only its own not-found text may license a delete.
     */
    @Test
    fun `treats access denied as unreadable, not missing`() {
        assertEquals(CommandState.Unreadable, parseCommandState(1, "ERROR: Access is denied."))
    }

    @Test
    fun `treats a localized not-found message as unreadable`() {
        assertEquals(CommandState.Unreadable, parseCommandState(1, "FEHLER: Der angegebene Registrierungsschlüssel"))
    }

    @Test
    fun `treats an empty value as unreadable`() {
        assertEquals(CommandState.Unreadable, parseCommandState(0, regOutput(value = "")))
    }

    @Test
    fun `treats an unexpected value type as unreadable`() {
        assertEquals(CommandState.Unreadable, parseCommandState(0, regOutput(type = "REG_DWORD", value = "0x1")))
    }

    /** A command whose text merely contains "REG_SZ" must not confuse the parser. */
    @Test
    fun `parses a command containing the literal REG_SZ`() {
        assertEquals(
            CommandState.Present(""""C:\Tools\REG_SZ\BOSS.exe" "%1""""),
            parseCommandState(0, regOutput(value = """"C:\Tools\REG_SZ\BOSS.exe" "%1"""")),
        )
    }

    /** A parsed value wins over a non-zero exit code — failing closed the other way round. */
    @Test
    fun `prefers a parsed command over a nonzero exit code`() {
        assertEquals(
            CommandState.Present(""""$otherApp" "%1""""),
            parseCommandState(1, regOutput(value = """"$otherApp" "%1"""")),
        )
    }

    // endregion

    // region classifyProtocolCleanup

    @Test
    fun `absent when nothing is registered`() {
        assertEquals(
            CleanupDecision.Report(UnregisterOutcome.ABSENT),
            classify(rootPresent = false, command = CommandState.Missing),
        )
    }

    /** Root key without a command value: a partial registration this code produced. */
    @Test
    fun `deletes a partial registration that has no command value`() {
        assertEquals(CleanupDecision.Delete, classify(command = CommandState.Missing))
    }

    /**
     * An unreadable command must NOT be treated as a missing one. A `REG_EXPAND_SZ` or
     * unquoted value is what an installer-authored registration looks like, and a failed
     * registry *read* must never escalate to a *delete*.
     */
    @Test
    fun `leaves an unreadable command alone`() {
        assertEquals(
            CleanupDecision.Report(UnregisterOutcome.UNREADABLE),
            classify(command = CommandState.Unreadable),
        )
    }

    @Test
    fun `leaves an unquoted command alone`() {
        assertEquals(
            CleanupDecision.Report(UnregisterOutcome.UNREADABLE),
            classify(command = CommandState.Present("""C:\Program Files\BOSS\BOSS.exe "%1"""")),
        )
    }

    @Test
    fun `deletes a registration pointing at this installation`() {
        assertEquals(CleanupDecision.Delete, classify(command = CommandState.Present(""""$thisApp" "%1"""")))
    }

    @Test
    fun `matches this installation case-insensitively`() {
        assertEquals(
            CleanupDecision.Delete,
            classify(command = CommandState.Present(""""${thisApp.uppercase()}" "%1"""")),
        )
    }

    @Test
    fun `deletes a registration pointing at a deleted executable`() {
        assertEquals(
            CleanupDecision.Delete,
            classify(
                command = CommandState.Present(""""C:\Old\BOSS\BOSS.exe" "%1""""),
                existing = setOf(thisApp),
            ),
        )
    }

    @Test
    fun `leaves another live installation registered`() {
        assertEquals(
            CleanupDecision.Report(UnregisterOutcome.OTHER_INSTALL),
            classify(command = CommandState.Present(""""$otherApp" "%1"""")),
        )
    }

    /** Development runs cannot determine their own path; that must not license a delete. */
    @Test
    fun `leaves another live installation registered when this app path is unknown`() {
        assertEquals(
            CleanupDecision.Report(UnregisterOutcome.OTHER_INSTALL),
            classify(command = CommandState.Present(""""$otherApp" "%1""""), appPath = null),
        )
    }

    @Test
    fun `still deletes a dead registration when this app path is unknown`() {
        assertEquals(
            CleanupDecision.Delete,
            classify(
                command = CommandState.Present(""""C:\Old\BOSS\BOSS.exe" "%1""""),
                appPath = null,
                existing = emptySet(),
            ),
        )
    }

    /**
     * Regression: supporting `REG_EXPAND_SZ` in the parser made an unexpanded path *readable*,
     * and nothing expands `%LOCALAPPDATA%`, so `exeExists` says false and the old rule would
     * have deleted a live installer-authored registration. Not evaluatable ⇒ leave alone.
     */
    @Test
    fun `leaves an unexpanded environment-variable path alone`() {
        assertEquals(
            CleanupDecision.Report(UnregisterOutcome.UNREADABLE),
            classify(command = CommandState.Present(""""%LOCALAPPDATA%\BOSS\BOSS.exe" "%1"""")),
        )
    }

    /** A root-key query that could not be run is not evidence of absence. */
    @Test
    fun `reports unreadable when the root key could not be queried`() {
        assertEquals(
            CleanupDecision.Report(UnregisterOutcome.UNREADABLE),
            classify(rootPresent = null, command = CommandState.Missing),
        )
    }

    /** Absent wins over everything: no key, nothing to decide. */
    @Test
    fun `absent takes precedence over a readable command`() {
        assertEquals(
            CleanupDecision.Report(UnregisterOutcome.ABSENT),
            classify(rootPresent = false, command = CommandState.Present(""""$otherApp" "%1"""")),
        )
    }

    /** `reg.exe` emits CRLF; the parser must not carry the \r into the command. */
    @Test
    fun `strips carriage returns from a CRLF value line`() {
        assertEquals(
            CommandState.Present(""""$thisApp" "%1""""),
            parseCommandState(0, "\r\n    (Default)    REG_SZ    \"$thisApp\" \"%1\"\r\n\r\n"),
        )
    }

    /** The default-value name is localized; a German install must still parse. */
    @Test
    fun `parses a localized default value name`() {
        assertEquals(
            CommandState.Present(""""$thisApp" "%1""""),
            parseCommandState(0, regOutput(name = "Standard", value = """"$thisApp" "%1"""")),
        )
    }

    // endregion

    // region parseRootKeyPresence

    @Test
    fun `root key present on exit zero`() {
        assertEquals(true, parseRootKeyPresence(0, regOutput(value = """"$thisApp" "%1"""")))
    }

    @Test
    fun `root key absent only on reg's not-found output`() {
        assertEquals(
            false,
            parseRootKeyPresence(1, "ERROR: The system was unable to find the specified registry key or value."),
        )
    }

    /**
     * Regression: an access-denied or policy-blocked query also exits 1. Reporting that as
     * "absent" would make cleanup return ABSENT → exit 0, telling an uninstaller nothing was
     * left to clean while a live registration remains.
     */
    @Test
    fun `root key presence is unknown when the query was blocked`() {
        assertNull(parseRootKeyPresence(1, "ERROR: Access is denied."))
    }

    @Test
    fun `root key presence is unknown for an unrecognized failure`() {
        assertNull(parseRootKeyPresence(1, ""))
    }

    // endregion

    // region exitCodeFor

    @Test
    fun `maps outcomes to the documented exit codes`() {
        assertEquals(0, exitCodeFor(UnregisterOutcome.REMOVED))
        assertEquals(0, exitCodeFor(UnregisterOutcome.ABSENT))
        assertEquals(0, exitCodeFor(UnregisterOutcome.NOT_APPLICABLE))
        assertEquals(1, exitCodeFor(UnregisterOutcome.OTHER_INSTALL))
        assertEquals(1, exitCodeFor(UnregisterOutcome.UNREADABLE))
        assertEquals(2, exitCodeFor(UnregisterOutcome.FAILED))
    }

    /**
     * Regression: `reg.exe` writes in the console code page, so a mis-decoded non-ASCII
     * install path arrives with U+FFFD. It parses, has no `%VARIABLE%`, does not equal our
     * own path and does not exist on disk — which used to mean "dead, delete it" for a live
     * install owned by someone else.
     */
    @Test
    fun `leaves a mis-decoded path alone`() {
        assertEquals(
            CleanupDecision.Report(UnregisterOutcome.UNREADABLE),
            classify(command = CommandState.Present("\"C:\\Users\\Bj\uFFFDrn\\BOSS\\BOSS.exe\" \"%1\"")),
        )
    }

    /**
     * The mis-decode that actually happens on Windows: reg.exe writes the console code page
     * (CP850 `ö` = 0x94) and the JVM decodes the ANSI one, so the path comes back plausible
     * but wrong — no U+FFFD to spot. Any non-ASCII character therefore means "unverifiable".
     */
    @Test
    fun `leaves a plausible-but-wrong single-byte mis-decode alone`() {
        assertEquals(
            CleanupDecision.Report(UnregisterOutcome.UNREADABLE),
            classify(command = CommandState.Present("\"C:\\Users\\Bj\u201Dorn\\BOSS\\BOSS.exe\" \"%1\"")),
        )
    }

    /** A correctly decoded non-ASCII path is also left alone — it cannot be told from the above. */
    @Test
    fun `leaves a correctly decoded non-ascii path alone`() {
        assertEquals(
            CleanupDecision.Report(UnregisterOutcome.UNREADABLE),
            classify(command = CommandState.Present("\"C:\\Users\\Bj\u00F6rn\\BOSS\\BOSS.exe\" \"%1\"")),
        )
    }

    /**
     * The hook's primary case on a non-ASCII account: jpackage installs per-user under
     * `C:\Users\<account>\AppData\Local`, so if the ASCII guard outranked the self-match,
     * cleanup would refuse to remove our *own* registration for Björn, Müller, Łukasz, …
     */
    @Test
    fun `deletes a non-ascii path that exactly matches this installation`() {
        val nonAscii = """C:\Users\Bj\u00F6rn\AppData\Local\BOSS\BOSS.exe"""
        assertEquals(
            CleanupDecision.Delete,
            classify(
                command = CommandState.Present(""""$nonAscii" "%1""""),
                appPath = nonAscii,
                existing = setOf(nonAscii),
            ),
        )
    }

    /** An offline UNC share is not the same as an uninstalled app. */
    @Test
    fun `leaves a UNC path alone`() {
        assertEquals(
            CleanupDecision.Report(UnregisterOutcome.UNREADABLE),
            classify(command = CommandState.Present(""""\\\\fileserver\\apps\\BOSS\\BOSS.exe" "%1"""")),
        )
    }

    /** A failed root-key read outranks an otherwise classifiable command. */
    @Test
    fun `unknown root key outranks a readable command`() {
        assertEquals(
            CleanupDecision.Report(UnregisterOutcome.UNREADABLE),
            classify(rootPresent = null, command = CommandState.Present(""""$otherApp" "%1"""")),
        )
    }

    /** The ordinary uninstall shape: it is ours *and* the exe is already gone. */
    @Test
    fun `deletes when the registration is ours and the executable is gone`() {
        assertEquals(
            CleanupDecision.Delete,
            classify(command = CommandState.Present(""""$thisApp" "%1""""), existing = emptySet()),
        )
    }

    // endregion

    // region maskUserPath

    @Test
    fun `masks the account name out of logged paths`() {
        assertEquals("""C:\Users\***\AppData\Local\BOSS\BOSS.exe""", maskUserPath(thisApp))
        assertEquals(
            """C:\Documents and Settings\***\BOSS\BOSS.exe""",
            maskUserPath("""C:\Documents and Settings\me\BOSS\BOSS.exe"""),
        )
        assertEquals(otherApp, maskUserPath(otherApp))
        assertEquals("(none)", maskUserPath(null))
    }

    @Test
    fun `masks the account name whichever separator the path uses`() {
        // Windows accepts both spellings, and the registry values masked here are not
        // all written by BOSS. Matching only backslashes logged the name verbatim.
        assertEquals(
            "C:/Users/***/AppData/Local/BOSS/BOSS.exe",
            maskUserPath("C:/Users/me/AppData/Local/BOSS/BOSS.exe"),
        )
        assertEquals(
            "C:/Documents and Settings/***/BOSS/BOSS.exe",
            maskUserPath("C:/Documents and Settings/me/BOSS/BOSS.exe"),
        )
    }

    @Test
    fun `only a whole path segment named Users counts`() {
        // This is the property that makes the `+` on the separators safe: a separator is
        // required on BOTH sides, so "Users" never matches as part of a longer name. If
        // it ever did, the masker would start eating unrelated directories. Cases from
        // @arjun28115's review on #1343.
        val notAccountRoots =
            listOf(
                """C:\PowerUsers\me\x""",
                """C:\Users-old\me\x""",
                """D:\Backup\MyUsers\me""",
                """C:\Data\Users.txt""",
                """C:\UsersData\me""",
            )
        notAccountRoots.forEach { untouched ->
            assertEquals(untouched, maskUserPath(untouched), "masked a directory that is not an account root")
        }
    }

    @Test
    fun `any Users segment qualifies, not only the one at the drive root`() {
        // Deliberate: an archived or copied per-user directory gets its next segment
        // masked too. For a privacy masker, over-masking is the safe direction, and a
        // segment sitting under a directory named Users is a plausible account name.
        // Pinned so the behaviour is a decision rather than an accident.
        assertEquals(
            """D:\Archive\Users\***\f.txt""",
            maskUserPath("""D:\Archive\Users\alice\f.txt"""),
        )
    }

    @Test
    fun `masks the account name when the separators arrived escaped`() {
        // A value that reached the log with its separators doubled is foreign text for
        // the same reason a forward-slash one is: BOSS did not write it. Matching a
        // single separator left the account-name class starting on the second one, so
        // the match was abandoned and the name went out verbatim.
        assertEquals(
            """C:\\Users\\***\\AppData""",
            maskUserPath("""C:\\Users\\me\\AppData"""),
        )
    }

    @Test
    fun `masks only the account segment of a mixed-separator path`() {
        // The account-name character class has to exclude BOTH separators. Excluding
        // only the backslash, the name ran on past a forward slash and took every
        // remaining segment with it - masking the whole tail rather than the name.
        assertEquals(
            """C:\Users/***/AppData/Local/BOSS/BOSS.exe""",
            maskUserPath("""C:\Users/me/AppData/Local/BOSS/BOSS.exe"""),
        )
    }

    // endregion

    @Test
    fun `extracts the executable from a quoted command only`() {
        assertEquals(thisApp, extractExecutablePath(""""$thisApp" "%1""""))
        assertNull(extractExecutablePath("""$thisApp "%1""""))
        assertNull(extractExecutablePath(""))
    }

    private companion object {
        /** Mirrors the production key; only used to shape realistic `reg query` output. */
        const val PROTOCOL_KEY_TEST = """HKEY_CURRENT_USER\Software\Classes\boss"""
    }
}
