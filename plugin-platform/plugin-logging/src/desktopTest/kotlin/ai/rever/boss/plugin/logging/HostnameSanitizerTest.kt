package ai.rever.boss.plugin.logging

import kotlin.test.Test
import kotlin.test.assertEquals

class HostnameSanitizerTest {
    @Test
    fun `sentence final private hosts are redacted without consuming punctuation`() {
        assertEquals(
            "Could not reach [HOST].",
            LogSanitizer.sanitizeExceptionMessage("Could not reach Proxy.Corp.Internal."),
        )
        assertEquals("[HOST]", LogSanitizer.sanitizeExceptionMessage("Status.INTERNAL"))
    }

    @Test
    fun `private and public hostname ports remain diagnostic`() {
        listOf("proxy.corp.internal", "api.risaboss.com").forEach { host ->
            assertEquals("[HOST]:443", LogSanitizer.sanitizeExceptionMessage("$host:443"), host)
        }
    }

    @Test
    fun `mixed case private hosts are completely redacted before the public matcher`() {
        listOf("Proxy.Corp.Internal", "Acme.corp.internal", "INTERNAL.Service.LOCAL").forEach { host ->
            assertEquals("[HOST]:3128", LogSanitizer.sanitizeExceptionMessage("$host:3128"), host)
            assertEquals("Caused by: [HOST]", LogSanitizer.sanitizeStackTrace("Caused by: $host"), host)
        }
    }

    @Test
    fun `private host rule respects surrounding dotted tokens and location precedence`() {
        listOf("Some.Type", "foo.bar", "corp.internal.Client", "settings.local.json").forEach { text ->
            assertEquals(text, LogSanitizer.sanitizeExceptionMessage(text))
        }
        assertEquals("[PATH]", LogSanitizer.sanitizeExceptionMessage("/path/to/file.local"))
        assertEquals("[EMAIL]", LogSanitizer.sanitizeExceptionMessage("user@corp.internal"))
    }

    @Test
    fun `selected lowercase hosts are redacted`() {
        assertEquals(
            "Connect to [HOST]:3128 failed",
            LogSanitizer.sanitizeExceptionMessage("Connect to proxy.corp.internal:3128 failed"),
        )
        assertEquals("[HOST]", LogSanitizer.sanitizeExceptionMessage("api.risaboss.com"))
    }

    @Test
    fun `dotted diagnostics remain intact`() {
        listOf(
            "kotlinx.io.EOFException",
            "kotlinx.coroutines.internal.ScopeCoroutine",
            "ai.rever.boss.services.supabase.SecretService",
            "settings.local.json",
            "requires gradle 9.5.8 or newer",
        ).forEach { diagnostic ->
            assertEquals(diagnostic, LogSanitizer.sanitizeExceptionMessage(diagnostic))
        }
    }

    @Test
    fun `stack frames retain package and class names`() {
        val trace =
            "kotlinx.io.EOFException: Unexpected end of input\n" +
                "\tat kotlinx.coroutines.internal.ScopeCoroutine.afterResume(Scopes.kt:33)\n" +
                "\tat ai.rever.boss.example.Client.run(Client.kt:42)"

        assertEquals(trace, LogSanitizer.sanitizeStackTrace(trace))
    }
}
