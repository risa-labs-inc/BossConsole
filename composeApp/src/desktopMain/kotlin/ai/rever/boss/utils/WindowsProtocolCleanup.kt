package ai.rever.boss.utils

/**
 * The pure decision layer behind `BOSS.exe --unregister-protocol`.
 *
 * Split from [WindowsProtocolHandler] (which owns the `reg.exe` I/O) for the same reason
 * `DebControl` lives in buildSrc: the registry needs Windows, the policy does not, and the
 * policy is where the dangerous mistakes are. Everything here is a pure function over
 * already-collected facts, and every branch is covered by `WindowsProtocolCleanupTest`.
 *
 * The governing asymmetry: leaving an orphan registry key behind is cosmetic, while deleting
 * a registration that belongs to a live install — or that we merely failed to read — breaks
 * `boss://` links for someone else's working BOSS. Every ambiguous case therefore reports
 * rather than deletes.
 */

/**
 * Namespaced so these generic names (`parseCommandState`, `exitCodeFor`, …) do not sit
 * loose in `ai.rever.boss.utils` as module-visible top-level functions.
 */
internal object WindowsProtocolCleanup {
    /** `%VARIABLE%` reference left unexpanded in a REG_EXPAND_SZ value. */
    private val UNEXPANDED_VARIABLE = Regex("""%[A-Za-z_][A-Za-z0-9_()]*%""")

    /**
     * `reg query` value line. The default-value name is localized ("(Default)", "(Standard)",
     * "(Par défaut)"), so match any parenthesized name; the value type is not localized.
     */
    private val REG_VALUE_LINE = Regex("""^\s+\(.+?\)\s+REG_(?:EXPAND_)?SZ\s+(.+)$""", RegexOption.MULTILINE)

    /** Leading quoted executable of a `shell\open\command` value. */
    private val QUOTED_EXECUTABLE = Regex("""^"([^"]+)"""")

    /** Leading separator of a UNC path, which may point at an offline share. */
    private const val UNC_PREFIX = """\\"""

    /** Highest code point that decodes identically under ANSI, OEM and UTF-8. */
    private const val MAX_ASCII = 0x7F

    /**
     * What the registered `shell\open\command` currently holds.
     *
     * The distinction matters for cleanup: "the value is not there" is a partial registration
     * this code produced and may delete, while "the value is there but I cannot read it" must be
     * left alone — it can be a perfectly working registration written by someone else (an
     * installer authoring a `REG_EXPAND_SZ` or unquoted command), and a failure to *read* the
     * registry must never escalate to *deleting* it.
     */
    internal sealed interface CommandState {
        /** `reg query` positively reports the key/value as absent (not merely a failed query). */
        object Missing : CommandState

        /** Present, but not readable here: unexpected value type, no value text, or the query failed. */
        object Unreadable : CommandState

        /** Present and read. */
        data class Present(
            val command: String,
        ) : CommandState
    }

    /**
     * Classify `reg query <root key>`: true when it exists, false when reg positively says it
     * does not, null when the answer is unknown.
     *
     * The exit code alone is not enough for the same reason it is not enough in
     * [parseCommandState]: `reg.exe` exits 1 for access-denied, policy/EDR blocks and truncated
     * output as well as for absence. Reporting one of those as "absent" would make cleanup
     * return ABSENT → exit 0, telling an uninstaller that nothing was left to clean when in
     * fact nothing was checked and a live registration remains.
     */
    internal fun parseRootKeyPresence(
        exitCode: Int,
        output: String,
    ): Boolean? =
        when {
            exitCode == 0 -> true
            output.contains("unable to find", ignoreCase = true) -> false
            else -> null
        }

    /**
     * Classify the result of `reg query <key> /ve`.
     *
     * Ordered so that failing closed is the default, because the stakes are asymmetric:
     * leaving an orphan key behind is cosmetic, deleting a live third-party registration is
     * not.
     *  * A value that parses is [CommandState.Present], whatever the exit code says.
     *  * [CommandState.Missing] — the only state that licenses a delete — additionally
     *    requires reg's own "unable to find" text. `reg.exe` exits 1 for absence *and* for
     *    access-denied, policy/EDR blocks and truncated output, so the exit code alone cannot
     *    distinguish "not there" from "could not read".
     *  * Everything else, including a localized not-found message on a non-English Windows, is
     *    [CommandState.Unreadable]: cleanup then reports rather than deletes.
     *  * Preferring a parsed value over the exit code cannot license a delete on truncated
     *    output either: [extractExecutablePath] requires the closing quote, so a value cut
     *    mid-path yields null and the classifier reports UNREADABLE.
     *
     * `REG_EXPAND_SZ` is matched as well as `REG_SZ` — an installer-authored command such as
     * `"%LOCALAPPDATA%\BOSS\BOSS.exe" "%1"` is ordinary and must not read as missing.
     */
    internal fun parseCommandState(
        exitCode: Int,
        output: String,
    ): CommandState {
        // Parse: "    (Default)    REG_SZ    C:\Path\To\BOSS.exe "%1""
        val command =
            // The default-value name is localized ("(Default)", "(Standard)", "(Par défaut)"),
            // so match any parenthesized name; the value type is not localized. `find` returns
            // the first match and the type always precedes the value, so a command whose own
            // text contains "REG_SZ" cannot be picked up instead.
            REG_VALUE_LINE
                .find(output)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        return when {
            command != null -> CommandState.Present(command)
            exitCode != 0 && output.contains("unable to find", ignoreCase = true) -> CommandState.Missing
            else -> CommandState.Unreadable
        }
    }

    /**
     * Extract executable path from registry command string.
     * Example: `"C:\Path\To\BOSS.exe" "%1"` -> `C:\Path\To\BOSS.exe`
     *
     * Only the quoted form is recognized; an unquoted command yields null, which callers must
     * treat as "unreadable", never as "stale".
     */
    internal fun extractExecutablePath(command: String): String? {
        val match = QUOTED_EXECUTABLE.find(command)
        return match?.groupValues?.get(1)
    }

    /**
     * What to do about the current `boss:` registration.
     *
     * A dedicated type rather than a nullable outcome: [Delete] destroys a registry key tree,
     * and that branch should name itself at every call site and in every test.
     */
    internal sealed interface CleanupDecision {
        /** The registration is ours, dead, or an orphan this code produced: remove it. */
        object Delete : CleanupDecision

        /** Leave the registry alone and report this outcome. */
        data class Report(
            val outcome: WindowsProtocolHandler.UnregisterOutcome,
        ) : CleanupDecision
    }

    /**
     * Decide whether the `boss:` registration may be deleted — the whole safety policy of
     * [WindowsProtocolHandler.unregisterProtocol], kept pure so every branch is testable off
     * Windows (see `WindowsProtocolCleanupTest`). Only the `reg delete` itself needs a real
     * registry.
     *
     * @param rootPresent whether the protocol root key exists, or null when that could not be
     *   determined (a failed read is not evidence of absence)
     * @param command state of its `shell\open\command` value
     * @param appPath this installation's executable, or null when it cannot be determined
     *   (development runs)
     * @param exeExists existence check for the registered executable
     */
    internal fun classifyProtocolCleanup(
        rootPresent: Boolean?,
        command: CommandState,
        appPath: String?,
        exeExists: (String) -> Boolean,
    ): CleanupDecision {
        val registeredExe = (command as? CommandState.Present)?.command?.let { extractExecutablePath(it) }
        return when {
            // A failed read of the root key is not evidence of absence.
            rootPresent == null -> {
                CleanupDecision.Report(WindowsProtocolHandler.UnregisterOutcome.UNREADABLE)
            }

            !rootPresent -> {
                CleanupDecision.Report(WindowsProtocolHandler.UnregisterOutcome.ABSENT)
            }

            // Root key exists and reg positively reports no command value: a partial `.reg`
            // import may have written the root and metadata before failing, or an orphan root
            // may have been left without a handler. registerProtocol already self-heals this
            // state by re-importing, so cleanup owns it rather than orphaning keys forever.
            command is CommandState.Missing -> {
                CleanupDecision.Delete
            }

            // Present but not readable here. Never delete what we cannot parse.
            registeredExe == null -> {
                CleanupDecision.Report(WindowsProtocolHandler.UnregisterOutcome.UNREADABLE)
            }

            // Exactly our own install path: safe to remove, and checked BEFORE the decode
            // guards below. appPath comes from jpackage.app-path — a correctly decoded JVM
            // string, and the very string performRegistration wrote — so an exact
            // case-insensitive match is itself proof that the registry value decoded
            // correctly. Ordering this after the ASCII rule would make cleanup refuse to
            // remove *our own* registration for every account whose name is not ASCII
            // (Björn, Müller, Łukasz), i.e. the hook's primary case: jpackage installs
            // per-user under C:\Users\<account>\AppData\Local. A %VARIABLE%-bearing value
            // can never equal a resolved absolute path, so nothing is weakened.
            registeredExe.equals(appPath, ignoreCase = true) -> {
                CleanupDecision.Delete
            }

            // A REG_EXPAND_SZ path still contains %VARIABLE% references and nothing here expands
            // them, so exeExists() is always false for one — which would classify a perfectly
            // live installer-authored registration as dead and delete it. Not evaluatable means
            // unreadable.
            UNEXPANDED_VARIABLE.containsMatchIn(registeredExe) -> {
                CleanupDecision.Report(WindowsProtocolHandler.UnregisterOutcome.UNREADABLE)
            }

            // Same rule for anything outside ASCII. reg.exe writes in the console output
            // code page (GetConsoleOutputCP) while the JVM decodes with the ANSI one
            // (native.encoding / GetACP) — different, and both single-byte, so a wrong
            // decode produces a *plausible* wrong path (CP850 "ö" 0x94 read as
            // windows-1252 is a curly quote) rather than U+FFFD. Such a path parses, is
            // not ours, and is not on disk, which the rule below would read as "dead,
            // delete it" against a live third-party registration. Bytes < 0x80 decode
            // identically under ANSI, OEM and UTF-8; anything else is unverifiable here,
            // and unverifiable means untouchable. Cost: cleanup reports UNREADABLE for a
            // non-ASCII install path instead of removing it — an orphan key, versus
            // deleting someone's working registration.
            registeredExe.any { it.code > MAX_ASCII } -> {
                CleanupDecision.Report(WindowsProtocolHandler.UnregisterOutcome.UNREADABLE)
            }

            // A UNC path (\\server\share\...) or a removable drive can be offline while the
            // registration is perfectly live, and exeExists() cannot tell that apart from
            // "uninstalled". Same conservative rule.
            registeredExe.startsWith(UNC_PREFIX) -> {
                CleanupDecision.Report(WindowsProtocolHandler.UnregisterOutcome.UNREADABLE)
            }

            // Pointing at an executable that is gone: safe to remove. Below the decode
            // guards deliberately — a mis-decoded path is also a path that does not exist,
            // and that must not license a delete.
            !exeExists(registeredExe) -> {
                CleanupDecision.Delete
            }

            else -> {
                CleanupDecision.Report(WindowsProtocolHandler.UnregisterOutcome.OTHER_INSTALL)
            }
        }
    }

    /**
     * Exit-code contract for `BOSS.exe --unregister-protocol`, which an uninstall action reads:
     * 0 nothing left to clean, 1 the registration was deliberately left in place, 2 the delete
     * itself failed. Extracted so the contract is unit-tested rather than inspected.
     */
    internal fun exitCodeFor(outcome: WindowsProtocolHandler.UnregisterOutcome): Int =
        when (outcome) {
            WindowsProtocolHandler.UnregisterOutcome.REMOVED,
            WindowsProtocolHandler.UnregisterOutcome.ABSENT,
            WindowsProtocolHandler.UnregisterOutcome.NOT_APPLICABLE,
            -> 0

            WindowsProtocolHandler.UnregisterOutcome.OTHER_INSTALL,
            WindowsProtocolHandler.UnregisterOutcome.UNREADABLE,
            -> 1

            WindowsProtocolHandler.UnregisterOutcome.FAILED -> 2
        }

    /**
     * Strip the Windows account name out of a path before logging it.
     *
     * Covers `C:\Users\<name>\…` and the legacy `C:\Documents and Settings\<name>\…`.
     * AGENTS.md requires sanitizing user data in logs and `LogSanitizer` has no path masker yet —
     * a shared one belongs there (it lives in the plugin-facing plugin-logging module), which is
     * why this is local for now.
     */
    internal fun maskUserPath(path: String?): String {
        if (path == null) return "(none)"
        return path
            .replace(Regex("""(?i)(\\Users\\)([^\\"]+)"""), "$1***")
            .replace(Regex("""(?i)(\\Documents and Settings\\)([^\\"]+)"""), "$1***")
    }
}
