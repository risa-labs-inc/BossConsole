package ai.rever.boss.plugin.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CredentialRedactionTest {
    @Test
    fun credentialsNeverRetainPrefixesOrSuffixes() {
        for (secret in listOf("abc123456789xyz", "a", "session-1234567890-abcdef", "ghs_0123456789ab")) {
            assertEquals("[REDACTED]", LogSanitizer.maskToken(secret))
            assertEquals("[REDACTED]", LogSanitizer.maskSessionId(secret))
        }
        assertEquals("[empty]", LogSanitizer.maskToken(null))
        assertEquals("[empty]", LogSanitizer.maskSessionId(""))
    }

    @Test
    fun sessionCapabilitiesAreRedactedInEverySupportedRepresentation() {
        for (name in listOf("sessionId", "session_id", "SESSION_ID", "sessionid")) {
            val secret = "prefix-CAPABILITY:suffix"
            val uri = "boss://passkey/authenticated?$name=$secret&status_code=200"
            assertFalse(LogSanitizer.maskUriParams(uri).contains(secret))
            assertEquals("[REDACTED]", LogSanitizer.sanitizeMap(mapOf(name to secret))[name])
            val message = LogSanitizer.sanitizeExceptionMessage("$name=$secret status_code=200 exit_code=1")
            assertFalse(message.contains("prefix"))
            assertFalse(message.contains("suffix"))
            assertTrue(message.contains("status_code=200"))
            assertTrue(message.contains("exit_code=1"))
        }
    }

    @Test
    fun passkeyPageParametersDoNotExposeCredentials() {
        val url =
            "https://example.com/passkey/auth/mobile?challenge=CHALLENGE&email=user%40example.com&" +
                "sessionId=SESSION&credentialId=CREDENTIAL&rpId=example.com"
        assertEquals(
            "https://example.com/passkey/auth/mobile?challenge=[REDACTED]&email=[REDACTED]&" +
                "sessionId=[REDACTED]&credentialId=[REDACTED]&rpId=example.com",
            LogSanitizer.maskUriParams(url),
        )
    }

    @Test
    fun prefixedAndSeparatedSessionNamesAreRedacted() {
        for (name in listOf("crossDeviceSessionId", "authSessionId", "session-id", "session.id")) {
            assertEquals("[REDACTED]", LogSanitizer.sanitizeMap(mapOf(name to "short"))[name])
            assertEquals("$name=[REDACTED]", LogSanitizer.sanitizeExceptionMessage("$name=short"))
            assertFalse(LogSanitizer.sanitizeStackTrace("Exception: $name=short").contains("short"))
        }
        assertEquals("session_status=active", LogSanitizer.sanitizeExceptionMessage("session_status=active"))
    }
}
