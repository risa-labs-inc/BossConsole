package ai.rever.boss.plugin.logging

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [LogSanitizer.redactUrlUserInfo] removes the userinfo of every URL inside a line of free text,
 * such as a line of `git clone` output, while keeping the rest of the line readable.
 *
 * The git lines below are the shapes measured with a real `git clone` (git 2.43) against a local
 * HTTP server, plus the pre-2.22 `fatal:` shape. git 2.22 and later strip the credential from
 * their own `fatal:` messages, but three things still print it: git older than 2.22,
 * `GIT_TRACE=1` inherited from the environment (it echoes the command line, token included),
 * and text a server sends back, which git prints verbatim after `remote error:`.
 */
class UrlUserInfoInTextTest {
    @Test
    fun `a pre-2_22 git fatal line keeps its failure text and host`() {
        assertEquals(
            "fatal: unable to access 'https://[REDACTED]@github.com/o/r.git/': Could not resolve host: github.com",
            LogSanitizer.redactUrlUserInfo(
                "fatal: unable to access 'https://x-access-token:ghs_CANARY0001@github.com/o/r.git/': " +
                    "Could not resolve host: github.com",
            ),
        )
    }

    @Test
    fun `a GIT_TRACE command line keeps its host and port`() {
        assertEquals(
            "14:09:46.779151 git.c:463               trace: built-in: git clone --progress " +
                "http://[REDACTED]@127.0.0.1:18765/notfound/r.git target",
            LogSanitizer.redactUrlUserInfo(
                "14:09:46.779151 git.c:463               trace: built-in: git clone --progress " +
                    "http://x-access-token:CANARY0002@127.0.0.1:18765/notfound/r.git target",
            ),
        )
    }

    @Test
    fun `every url in one line is redacted`() {
        assertEquals(
            "trace: run_command: git remote-http https://[REDACTED]@h1.example/r.git " +
                "https://[REDACTED]@h2.example:8443/r.git",
            LogSanitizer.redactUrlUserInfo(
                "trace: run_command: git remote-http https://a:CANARY0003@h1.example/r.git " +
                    "https://b:CANARY0004@h2.example:8443/r.git",
            ),
        )
    }

    @Test
    fun `a url with no path ends at whitespace so a later email is kept`() {
        assertEquals(
            "fatal: unable to access 'https://[REDACTED]@github.com': contact admin@corp.example",
            LogSanitizer.redactUrlUserInfo(
                "fatal: unable to access 'https://u:CANARY0005@github.com': contact admin@corp.example",
            ),
        )
    }

    @Test
    fun `a url a server echoes back is redacted`() {
        assertEquals(
            "fatal: remote error: access denied https://[REDACTED]@example.com/x",
            LogSanitizer.redactUrlUserInfo("fatal: remote error: access denied https://u:CANARY0006@example.com/x"),
        )
    }

    @Test
    fun `ordinary clone progress lines are unchanged`() {
        val lines =
            listOf(
                "Cloning into 'repo'...",
                "remote: Counting objects: 100% (5/5), done.",
                "Receiving objects:  45% (450/1000), 1.20 MiB | 2.00 MiB/s",
                "Resolving deltas: 100% (12/12), done.",
                "fatal: unable to access 'https://github.com/o/r.git/': The requested URL returned error: 403",
                "fatal: repository 'https://github.com/o/r.git/' not found",
            )
        lines.forEach { assertEquals(it, LogSanitizer.redactUrlUserInfo(it)) }
    }

    @Test
    fun `an scp-style remote and an at sign in a path are unchanged`() {
        assertEquals(
            "Cloning from git@github.com:o/r.git",
            LogSanitizer.redactUrlUserInfo("Cloning from git@github.com:o/r.git"),
        )
        assertEquals(
            "see https://github.com/@handle for details",
            LogSanitizer.redactUrlUserInfo("see https://github.com/@handle for details"),
        )
    }

    @Test
    fun `malformed input neither throws nor keeps a credential`() {
        assertEquals("", LogSanitizer.redactUrlUserInfo(""))
        assertEquals("://", LogSanitizer.redactUrlUserInfo("://"))
        assertEquals("https://", LogSanitizer.redactUrlUserInfo("https://"))
        assertEquals("@://", LogSanitizer.redactUrlUserInfo("@://"))
        assertEquals("a://[REDACTED]@", LogSanitizer.redactUrlUserInfo("a://@"))
        assertEquals("https://[REDACTED]@", LogSanitizer.redactUrlUserInfo("https://u:CANARY0007@"))
        assertEquals("x://[REDACTED]@ ://", LogSanitizer.redactUrlUserInfo("x://@@ ://"))
    }
}
