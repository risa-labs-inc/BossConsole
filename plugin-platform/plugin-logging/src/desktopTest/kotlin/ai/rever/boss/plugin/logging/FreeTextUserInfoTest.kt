package ai.rever.boss.plugin.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The free-text sanitizers - [LogSanitizer.sanitizeExceptionMessage],
 * [LogSanitizer.sanitizeLogMessage] and [LogSanitizer.sanitizeStackTrace] - must not leave a URL's
 * userinfo credential in the line.
 *
 * BossConsole#640 closed this for [LogSanitizer.maskUriParams] and recorded the free-text path as
 * a separate change: `filePathPattern`'s `(?:/[^\s:]+)+` consumes `//alice` before `urlPattern`
 * can match the whole URL, so `http://alice:pass@10.0.0.5:3128` came out as
 * `http:[PATH]:pass@10.0.0.5:3128` with the password intact.
 *
 * **The leak needed an IP-literal host.** With a DNS name, `emailPattern` matched
 * `s3cr3t@proxy.corp.example` and masked the password by accident, which is why the shape below
 * uses `10.0.0.5`. The DNS cases are kept as guards rather than as evidence: they passed before
 * this change too, and a test that cannot fail is worth saying so about.
 *
 * These functions are where text BOSS did not write arrives: `McpToolRegistryImpl.executeUncapped`
 * puts a plugin's own exception message through [LogSanitizer.sanitizeExceptionMessage] before
 * returning it to the agent, and `CrashReport` puts every captured log entry through
 * [LogSanitizer.sanitizeLogMessage]. Neither can predict what it is handed.
 */
class FreeTextUserInfoTest {
    private val proxyFailure = "Failed to connect to proxy http://alice:CANARY0011@10.0.0.5:3128"

    @Test
    fun `a proxy failure keeps no password when the host is an ip literal`() {
        val sanitized = LogSanitizer.sanitizeExceptionMessage(proxyFailure)
        assertFalse(sanitized.contains("CANARY0011"), sanitized)
        assertTrue(sanitized.startsWith("Failed to connect to proxy "), sanitized)
        assertEquals("Failed to connect to proxy http:[PATH]:3128", sanitized)
    }

    @Test
    fun `the same line in a stack frame keeps no password`() {
        val sanitized = LogSanitizer.sanitizeStackTrace("\tat Client.connect($proxyFailure)")
        assertFalse(sanitized.contains("CANARY0011"), sanitized)
    }

    @Test
    fun `the log-message entry point keeps no password`() {
        val sanitized = LogSanitizer.sanitizeLogMessage(proxyFailure)
        assertFalse(sanitized.contains("CANARY0011"), sanitized)
    }

    @Test
    fun `a credential against an ip host is removed whatever follows it`() {
        listOf(
            "connect failed: ftp://carol:CANARY0012@10.1.2.3/pub",
            "retrying https://svc:CANARY0012@192.168.0.9:8443/health",
            "gave up on http://u:CANARY0012@127.0.0.1",
        ).forEach { line ->
            assertFalse(LogSanitizer.sanitizeExceptionMessage(line).contains("CANARY0012"), line)
        }
    }

    /** Guard, not evidence: `emailPattern` already masked these before this change. */
    @Test
    fun `a credential against a dns host stays removed`() {
        listOf(
            "Proxy auth failed for https://bob:CANARY0013@proxy.corp.example:8443",
            "fatal: unable to access 'https://user:CANARY0013@github.com/o/r.git/'",
        ).forEach { line ->
            assertFalse(LogSanitizer.sanitizeExceptionMessage(line).contains("CANARY0013"), line)
        }
    }

    @Test
    fun `a line with no url is untouched by the new pass`() {
        assertEquals(
            "could not read [PATH]",
            LogSanitizer.sanitizeExceptionMessage("could not read /home/n/p/Main.kt"),
        )
        assertEquals(
            "Connect to [HOST]:3128 failed",
            LogSanitizer.sanitizeExceptionMessage("Connect to proxy.corp.internal:3128 failed"),
        )
        assertEquals(
            "nothing to redact here",
            LogSanitizer.sanitizeExceptionMessage("nothing to redact here"),
        )
    }

    /**
     * BossConsole#1639: a protocol-relative authority has no `://`, so the userinfo pass used to skip
     * it and `filePathPattern` left `[PATH]:CANARY0015@10.0.0.5[PATH]` - the #956 leak without a
     * scheme.
     */
    @Test
    fun `a protocol-relative url keeps no password`() {
        val line = "Failed to connect to //alice:CANARY0015@10.0.0.5/x"
        assertEquals("Failed to connect to [PATH]", LogSanitizer.sanitizeExceptionMessage(line))
        assertFalse(LogSanitizer.sanitizeLogMessage(line).contains("CANARY0015"))
        assertFalse(LogSanitizer.sanitizeStackTrace("\tat Client.connect($line)").contains("CANARY0015"))
    }

    @Test
    fun `a protocol-relative credential is removed wherever a reference can start`() {
        listOf(
            "//u:CANARY0016@10.0.0.5/x",
            "proxy='//u:CANARY0016@10.0.0.5:3128'",
            "proxy=\"//u:CANARY0016@10.0.0.5:3128\"",
            "retry (//u:CANARY0016@10.0.0.5)",
            "proxy=//u:CANARY0016@10.0.0.5",
            "hosts //a@10.0.0.4,//u:CANARY0016@10.0.0.5",
        ).forEach { line ->
            assertFalse(LogSanitizer.redactUrlUserInfo(line).contains("CANARY0016"), line)
            assertFalse(LogSanitizer.sanitizeExceptionMessage(line).contains("CANARY0016"), line)
        }
        assertEquals(
            "Failed to connect to //[REDACTED]@10.0.0.5/x",
            LogSanitizer.redactUrlUserInfo("Failed to connect to //alice:CANARY0017@10.0.0.5/x"),
        )
    }

    /** A `//` inside a path or a word is not a reference, so an `@` after it is left alone. */
    @Test
    fun `a double slash inside a path or after a third slash is not an authority`() {
        listOf(
            "copied /srv//user@host.txt",
            "see a//b@c",
            "file:///home/u@x/y",
            "npm config set //registry.npmjs.org/:_authToken \${TOKEN}",
            "// TODO ask owner@example.com",
        ).forEach { line -> assertEquals(line, LogSanitizer.redactUrlUserInfo(line)) }
    }

    /**
     * The query-parameter pass has to keep running first: its whole job is removing a colon before
     * `filePathPattern` trips on it, so anything inserted ahead of it would be too late.
     */
    @Test
    fun `a sensitive query parameter is still redacted`() {
        assertEquals(
            "Failed https:[PATH] retry",
            LogSanitizer.sanitizeExceptionMessage("Failed https://api.example.com/v1?access_token=CANARY0014 retry"),
        )
    }
}
