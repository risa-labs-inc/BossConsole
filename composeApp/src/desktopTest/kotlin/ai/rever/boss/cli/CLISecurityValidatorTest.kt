package ai.rever.boss.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [CLISecurityValidator], the path and command policy every external
 * entry point funnels through.
 *
 * It was promoted to commonMain in the #794 workspace-tool wave so that deep
 * links, the `boss` CLI and the MCP workspace/terminal tools all reject the
 * same shapes ([CLICommandHandler], [ai.rever.boss.utils.DeepLinkHandler] and
 * [ai.rever.boss.mcp.WorkspaceMcpToolProvider] each call it before acting). A
 * gate this central must fail the same way everywhere, so this class pins each
 * rule the validator enforces, one test per rule - including the URL rules,
 * which no other test touched.
 *
 * [OpenTargetPathTest] pins why the read-path rules are deliberately looser
 * than the shell-facing ones, and [TerminalCommandOriginTest] pins *who* may
 * run a command rather than what it says; this class stays on the validator
 * itself.
 */
class CLISecurityValidatorTest {
    // ------------------------------------------------------------------
    // isValidUrl - the back-compat scheme check. It has no in-tree caller
    // (normalizeAndValidateUrl is the real gate below), so these tests are
    // what keeps it from silently drifting from its documented contract.
    // ------------------------------------------------------------------

    @Test
    fun `isValidUrl accepts http and https and nothing else`() {
        assertTrue(CLISecurityValidator.isValidUrl("https://example.com"))
        assertTrue(CLISecurityValidator.isValidUrl("http://example.com"))

        // No other scheme is a URL the browser should be handed.
        assertFalse(CLISecurityValidator.isValidUrl("ftp://example.com"))
        assertFalse(CLISecurityValidator.isValidUrl("file:///etc/passwd"))
        // A bare domain is not a URL yet - that is normalizeAndValidateUrl's job.
        assertFalse(CLISecurityValidator.isValidUrl("example.com"))
    }

    @Test
    fun `isValidUrl matches the scheme prefix exactly, so anything else fails closed`() {
        // No trimming and no case-folding: the check is a literal prefix test,
        // and a padded or capitalised scheme is refused rather than guessed at.
        assertFalse(CLISecurityValidator.isValidUrl(" https://example.com"))
        assertFalse(CLISecurityValidator.isValidUrl("HTTPS://example.com"))
        assertFalse(CLISecurityValidator.isValidUrl(""))
    }

    @Test
    fun `isValidUrl tests the prefix and nothing after it`() {
        // The scheme alone decides; the rest of the string is not examined
        // here, so callers must not read more validation into it than exists.
        assertTrue(CLISecurityValidator.isValidUrl("https://example.com\n"))
        assertTrue(CLISecurityValidator.isValidUrl("http://"))
    }

    // ------------------------------------------------------------------
    // normalizeAndValidateUrl - the real URL gate, on CLICommandHandler's
    // `boss open <url>` path. Returns the URL to open, or null to refuse.
    // ------------------------------------------------------------------

    @Test
    fun `normalizeAndValidateUrl prefixes a bare domain with https`() {
        assertEquals("https://example.com", CLISecurityValidator.normalizeAndValidateUrl("example.com"))
        // Only the scheme is added; the rest of the address is kept as typed.
        assertEquals(
            "https://example.com/docs/getting-started",
            CLISecurityValidator.normalizeAndValidateUrl("example.com/docs/getting-started"),
        )
    }

    @Test
    fun `normalizeAndValidateUrl keeps an http or https URL as typed after trimming`() {
        assertEquals("https://example.com", CLISecurityValidator.normalizeAndValidateUrl("https://example.com"))
        // http is not upgraded: the caller asked for it explicitly.
        assertEquals("http://example.com", CLISecurityValidator.normalizeAndValidateUrl("http://example.com"))
        // Padding is trimmed away on either side.
        assertEquals("https://example.com", CLISecurityValidator.normalizeAndValidateUrl("  https://example.com  "))
        assertEquals("https://example.com", CLISecurityValidator.normalizeAndValidateUrl("https://example.com\n"))
    }

    @Test
    fun `normalizeAndValidateUrl fails closed on empty and blank input`() {
        assertNull(CLISecurityValidator.normalizeAndValidateUrl(""))
        assertNull(CLISecurityValidator.normalizeAndValidateUrl("   "))
        assertNull(CLISecurityValidator.normalizeAndValidateUrl("\n\t"))
    }

    @Test
    fun `normalizeAndValidateUrl refuses input with no domain shape`() {
        // The domain test is a literal dot: it is what separates a domain from
        // any random string - and from a dotless filesystem path.
        assertNull(CLISecurityValidator.normalizeAndValidateUrl("invalidurl"))
        assertNull(CLISecurityValidator.normalizeAndValidateUrl("localhost"))
        assertNull(CLISecurityValidator.normalizeAndValidateUrl("/etc/passwd"))
    }

    @Test
    fun `normalizeAndValidateUrl refuses whitespace smuggled into a bare input`() {
        // A space or line break inside the address is how a second, unseen
        // target rides along in one argument; the bare-input branch rejects all three.
        assertNull(CLISecurityValidator.normalizeAndValidateUrl("example .com"))
        assertNull(CLISecurityValidator.normalizeAndValidateUrl("exa\nmple.com"))
        assertNull(CLISecurityValidator.normalizeAndValidateUrl("exa\rmple.com"))
    }

    @Test
    fun `normalizeAndValidateUrl protocol branch remains a prefix check only`() {
        // The explicit-protocol branch returns before the bare-domain whitespace guard. Pin that
        // legacy asymmetry so tightening it later is a deliberate compatibility decision. These
        // values are still refused downstream by URLHandlerService's UrlOpenValidation gate; this
        // test records where validation lives rather than implying that BOSS opens these inputs.
        assertEquals(
            "https://example.com\nsecond line",
            CLISecurityValidator.normalizeAndValidateUrl("https://example.com\nsecond line"),
        )
        assertEquals("https://exa mple.com", CLISecurityValidator.normalizeAndValidateUrl("https://exa mple.com"))
        assertEquals("http://", CLISecurityValidator.normalizeAndValidateUrl("http://"))
    }

    // ------------------------------------------------------------------
    // isValidPath - the shell-facing path gate (deep links, the CLI's
    // file/folder/terminal paths, the MCP workspace/terminal tools).
    // ------------------------------------------------------------------

    @Test
    fun `isValidPath accepts an ordinary path`() {
        assertTrue(CLISecurityValidator.isValidPath("/home/me/project/src/Main.kt"))
        assertTrue(CLISecurityValidator.isValidPath("src/Main.kt"))
        // A space is not one of the rejected characters - it is an ordinary
        // filename character, and only line breaks are smugglers.
        assertTrue(CLISecurityValidator.isValidPath("/home/me/My Documents/file.txt"))
    }

    @Test
    fun `isValidPath rejects a NUL byte`() {
        // A NUL truncates the path at the first native call underneath, so the
        // file checked is not the file opened.
        assertFalse(CLISecurityValidator.isValidPath("/home/me/notes.md\u0000.png"))
    }

    @Test
    fun `isValidPath rejects path traversal, wherever the dot-dot sits`() {
        assertFalse(CLISecurityValidator.isValidPath("/tmp/../etc/passwd"))
        assertFalse(CLISecurityValidator.isValidPath("../../etc/passwd"))
        // The rule is a substring test, so a legal filename with two dots in
        // it is refused too - the price of refusing every traversal shape.
        assertFalse(CLISecurityValidator.isValidPath("/home/me/notes..draft.md"))
    }

    @Test
    fun `isValidPath rejects every shell metacharacter it guards against`() {
        // Each character is one way a path argument could become two commands.
        listOf(
            "/tmp/a;b" to "a command separator",
            "/tmp/a&b" to "a backgrounded second command",
            "/tmp/a|b" to "a pipe into a second command",
            "/tmp/back`tick`" to "command substitution",
            "/tmp/pay\$b" to "variable expansion",
            "/tmp/a\nb" to "a smuggled second line",
            "/tmp/a\rb" to "a smuggled second line",
        ).forEach { (path, why) ->
            assertFalse(CLISecurityValidator.isValidPath(path), "expected $why to be refused: $path")
        }
    }

    @Test
    fun `isValidPath rejects a chained-command payload`() {
        // The classic: one argument, two commands.
        assertFalse(CLISecurityValidator.isValidPath("/tmp/a;rm -rf /"))
    }

    @Test
    fun `isValidPath rejects a command-substitution payload`() {
        // Either quoting style, the old backtick or the newer $(...), runs a
        // command the operator never asked for.
        assertFalse(CLISecurityValidator.isValidPath("/tmp/\$(whoami).txt"))
        assertFalse(CLISecurityValidator.isValidPath("/tmp/`whoami`.txt"))
    }

    @Test
    fun `isValidPath leaves the empty-string rejection to its callers`() {
        // Every check inside is a `contains`, so an empty string carries
        // nothing and passes. The blank guards live at the call sites (callers
        // pass File.absolutePath or check isNullOrBlank first), and this pin
        // keeps anyone from assuming the validator does that job.
        assertTrue(CLISecurityValidator.isValidPath(""))
    }

    // ------------------------------------------------------------------
    // isValidOpenTargetPath - the gate for a path that is only ever read,
    // never handed to a shell (double-clicked files, MCP workspacePath).
    // ------------------------------------------------------------------

    @Test
    fun `isValidOpenTargetPath accepts an ordinary path`() {
        assertTrue(CLISecurityValidator.isValidOpenTargetPath("/home/me/project/src/Main.kt"))
    }

    @Test
    fun `isValidOpenTargetPath accepts characters the shell-facing gate must refuse`() {
        // No shell ever sees this path, so metacharacters are ordinary
        // filename characters here. OpenTargetPathTest pins the full contrast.
        assertTrue(CLISecurityValidator.isValidOpenTargetPath("/home/me/Documents/Q&A notes.md"))
        assertTrue(CLISecurityValidator.isValidOpenTargetPath("/home/me/scripts/pay\$.sh"))
    }

    @Test
    fun `isValidOpenTargetPath refuses blank input`() {
        assertFalse(CLISecurityValidator.isValidOpenTargetPath(""))
        assertFalse(CLISecurityValidator.isValidOpenTargetPath("   "))
    }

    @Test
    fun `isValidOpenTargetPath refuses a NUL byte`() {
        assertFalse(CLISecurityValidator.isValidOpenTargetPath("/home/me/notes.md\u0000.png"))
    }

    @Test
    fun `isValidOpenTargetPath refuses an absurdly long path`() {
        // The 32,768-character guard intentionally sits beyond real filesystem limits, so the
        // public result cannot distinguish the explicit guard from canonicalisation refusing the
        // same input. This verifies the honest observable contract rather than claiming to pin
        // an unobservable constant. A roughly 500-character absolute path stays comfortably under
        // macOS's 1,024-character PATH_MAX while catching the guard being lowered below this range.
        val representable = "/" + List(128) { "abc" }.joinToString("/")
        assertTrue(CLISecurityValidator.isValidOpenTargetPath(representable))
        assertFalse(CLISecurityValidator.isValidOpenTargetPath("a".repeat(32_768)))
        assertFalse(CLISecurityValidator.isValidOpenTargetPath("a".repeat(32_769)))
    }

    // ------------------------------------------------------------------
    // isValidCommand - the shape check on what `boss terminal -c` and the
    // MCP open_terminal tool will type into a shell. One non-empty line of
    // printable text: what is displayed must be what runs.
    // ------------------------------------------------------------------

    @Test
    fun `isValidCommand accepts one printable line`() {
        assertTrue(CLISecurityValidator.isValidCommand("ls -la"))
        // Quotes are printable characters - a quoted argument is one line, not
        // an injection.
        assertTrue(CLISecurityValidator.isValidCommand("git commit -m \"a message\""))
        // The rule is "printable", not "ASCII".
        assertTrue(CLISecurityValidator.isValidCommand("echo héllo · 中文"))
        // Padding spaces are ordinary.
        assertTrue(CLISecurityValidator.isValidCommand("  ls -la  "))
    }

    @Test
    fun `isValidCommand refuses blank input`() {
        assertFalse(CLISecurityValidator.isValidCommand(""))
        assertFalse(CLISecurityValidator.isValidCommand("   "))
    }

    @Test
    fun `isValidCommand bounds the length at 4096 characters`() {
        assertTrue(CLISecurityValidator.isValidCommand("a".repeat(4096)))
        assertFalse(CLISecurityValidator.isValidCommand("a".repeat(4097)))
    }

    @Test
    fun `isValidCommand rejects a second line smuggled in a line break`() {
        // The command is typed into the shell followed by a single Enter, so
        // an embedded break would submit a line nobody was shown.
        assertFalse(CLISecurityValidator.isValidCommand("echo hi\nwhoami"))
        assertFalse(CLISecurityValidator.isValidCommand("echo hi\rwhoami"))
    }

    @Test
    fun `isValidCommand rejects every control character`() {
        // From the NUL that truncates the line to the escape that rewrites the
        // terminal: keeping the command to printable text is what makes the
        // text shown equal to the text that runs.
        listOf('\u0000', '\t', '\u001B', '\u007F', '\u0085').forEach { c ->
            assertFalse(
                CLISecurityValidator.isValidCommand("echo a" + c + "b"),
                "expected control character 0x" + c.code.toString(16) + " to be refused",
            )
        }
    }

    // ------------------------------------------------------------------
    // normalizePath and isRestrictedSystemPath - defensive bounds for
    // workspace and terminal tools against sensitive operating system paths.
    // ------------------------------------------------------------------

    @Test
    fun `normalizePath resolves redundant slashes dots and traversals`() {
        assertEquals("/etc/passwd", CLISecurityValidator.normalizePath("/etc/./passwd"))
        assertEquals("/etc/shadow", CLISecurityValidator.normalizePath("/var/log/../../etc/shadow"))
        assertEquals("C:/Windows/System32", CLISecurityValidator.normalizePath("c:\\Windows\\System32"))
        assertEquals("C:/windows/system32", CLISecurityValidator.normalizePath("c:\\windows\\system32"))
        assertEquals("C:/Windows", CLISecurityValidator.normalizePath("C:/Windows/System32/.."))
        assertEquals("/", CLISecurityValidator.normalizePath("/"))
        assertEquals("C:/", CLISecurityValidator.normalizePath("c:\\"))
        assertEquals("C:/Windows/System32", CLISecurityValidator.normalizePath("\\\\?\\C:\\Windows\\System32"))
        assertEquals("C:/Windows", CLISecurityValidator.normalizePath("\\\\.\\C:\\Windows"))
        assertEquals("", CLISecurityValidator.normalizePath("a".repeat(32_769)))
    }

    @Test
    fun `isRestrictedSystemPath flags sensitive operating system paths`() {
        // POSIX roots
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/etc"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/etc/shadow"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/proc"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/sys"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/dev"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/boot"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/bin"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/sbin"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/usr/bin"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/usr/sbin"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/usr/libexec"))

        // macOS roots and canonical forms
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/System"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/system/Library"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/Library"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/library/Preferences"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/private/etc"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/private/etc/hosts"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/private/var/db"))

        // Windows roots
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("C:\\Windows"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("c:/windows/system32"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("C:\\Program Files"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("C:\\Program Files (x86)"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("\\\\?\\C:\\Windows"))

        // Bare roots
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("C:\\"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("c:/"))

        // Traversals into restricted roots
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/var/log/../../etc/passwd"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("C:\\Users\\..\\Windows\\System32"))

        // Safe user paths (including /root to avoid blocking root user home directory in containers)
        assertFalse(CLISecurityValidator.isRestrictedSystemPath("/home/user/workspace/repo"))
        assertFalse(CLISecurityValidator.isRestrictedSystemPath("C:\\Users\\developer\\projects\\boss"))
        assertFalse(CLISecurityValidator.isRestrictedSystemPath("/root"))
        assertFalse(CLISecurityValidator.isRestrictedSystemPath("/root/myproject"))
    }

    // #1651: normalizePath gives "" for input it will not normalize, and "" used to read as "not
    // restricted" - so padding a restricted path past the length cap walked it through this check.
    @Test
    fun `an over-long path is restricted rather than waved through`() {
        val padded = "/etc/" + "./".repeat(16_500)
        check(padded.length > 32_768) { "the fixture must exceed the normalize cap" }

        assertTrue(CLISecurityValidator.isRestrictedSystemPath(padded))
        // Fail closed regardless of where it points: its target cannot be known.
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/home/user/" + "a/".repeat(16_500)))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("C:\\Windows\\" + ".\\".repeat(16_500)))
    }

    @Test
    fun `a path at the cap is still judged on where it points, and a blank path names nothing`() {
        val atCap = "/home/user/" + "a".repeat(32_768 - "/home/user/".length)
        check(atCap.length == 32_768)

        assertFalse(CLISecurityValidator.isRestrictedSystemPath(atCap))
        assertFalse(CLISecurityValidator.isRestrictedSystemPath(""))
        assertFalse(CLISecurityValidator.isRestrictedSystemPath("   "))
    }
}
