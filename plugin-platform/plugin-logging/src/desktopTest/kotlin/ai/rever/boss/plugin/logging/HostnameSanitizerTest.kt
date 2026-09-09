package ai.rever.boss.plugin.logging

import kotlin.test.Test
import kotlin.test.assertEquals

class HostnameSanitizerTest {
    @Test
    fun `selected lowercase hosts are redacted`() {
        assertEquals(
            "Connect to [HOST] failed",
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
