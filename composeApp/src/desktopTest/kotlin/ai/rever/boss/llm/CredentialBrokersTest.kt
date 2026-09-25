package ai.rever.boss.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The broker registry is the security boundary for this feature: a plugin names a broker by
 * id, and the host decides what that means. These pin the properties that make that true.
 */
class CredentialBrokersTest {
    @Test
    fun `a plugin can only reach a broker this build declares`() {
        // The whole point of id-not-URL. An unknown id is a refusal, not an attempt.
        assertNull(CredentialBrokers.find("not-a-broker"))
        assertNull(CredentialBrokers.find("https://attacker.example/collect"))
        assertNotNull(CredentialBrokers.find(CredentialBrokers.RISA_GLM))
    }

    @Test
    fun `an unknown broker id fails without touching the network`() {
        val result = kotlinx.coroutines.runBlocking { CredentialBrokerClient.exchange("nope") }

        assertTrue(result.isFailure)
        assertTrue(
            result
                .exceptionOrNull()
                ?.message
                .orEmpty()
                .contains("does not know"),
        )
    }

    @Test
    fun `every broker declares an https endpoint and what it is scoped to`() {
        // scopedTo is published to plugins so a careful one can check where it is about to
        // post a bearer token. A broker without it gives them nothing to check against.
        CredentialBrokers.all().forEach { broker ->
            assertTrue(broker.tokenUrl.startsWith("https://"), "${broker.id} token URL is not https")
            val scope = assertNotNull(broker.scopedTo, "${broker.id} declares no scope")
            assertTrue(scope.startsWith("https://"), "${broker.id} scope is not https")
        }
    }

    @Test
    fun `broker ids are unique`() {
        // find() takes the first match, so a duplicate would silently shadow one.
        val ids = CredentialBrokers.all().map { it.id }

        assertEquals(ids.distinct(), ids)
    }

    @Test
    fun `a broker error message never carries the raw body`() {
        val leaky = "sk-live-secret and api_base=https://internal.gateway.example/v1"

        val message = CredentialBrokerClient.parseBrokerError(leaky)

        assertFalse(message.contains("sk-live-secret"), message)
        assertFalse(message.contains("internal.gateway.example"), message)
    }

    @Test
    fun `the RISA endpoint is overridable for a staging build but defaults to production`() {
        // The override exists so a dev build can point at staging. It comes from the
        // environment, which is the host's, not from anything a plugin supplies.
        val broker = assertNotNull(CredentialBrokers.find(CredentialBrokers.RISA_GLM))

        assertEquals(
            CredentialBrokers.resolveRisaTokenUrl(System.getenv("RISA_LLM_TOKEN_URL")),
            broker.tokenUrl,
        )
    }

    @Test
    fun `an unset or blank override resolves to the built-in endpoint`() {
        assertEquals(PRODUCTION_TOKEN_URL, CredentialBrokers.resolveRisaTokenUrl(null))
        assertEquals(PRODUCTION_TOKEN_URL, CredentialBrokers.resolveRisaTokenUrl(""))
        assertEquals(PRODUCTION_TOKEN_URL, CredentialBrokers.resolveRisaTokenUrl("   "))
    }

    @Test
    fun `an https override under risa inc is accepted`() {
        // Staging on a subdomain is the whole reason the env var exists.
        val staging = "https://staging-llm.risa.inc/auth/token"
        assertEquals(staging, CredentialBrokers.resolveRisaTokenUrl(staging))
        assertEquals(
            "https://llm.risa.inc:8443/auth/token",
            CredentialBrokers.resolveRisaTokenUrl("https://llm.risa.inc:8443/auth/token"),
        )
    }

    @Test
    fun `an override that does not name a risa inc https host falls back`() {
        // Whatever is returned gets Authorization: Bearer <live session token>, so every
        // one of these must resolve to the built-in endpoint, not the attacker-chosen URL.
        val rejected =
            listOf(
                "http://llm.risa.inc/auth/token", // right host, no TLS
                "https://evil.example/auth/token",
                "https://llm.risa.inc.evil.example/auth/token", // suffix lookalike
                "http://127.0.0.1:9/x",
                "https://user@evil.example/", // userinfo must not smuggle a host
                "not a url",
                "llm.risa.inc/auth/token", // no scheme
            )
        rejected.forEach { override ->
            assertEquals(
                PRODUCTION_TOKEN_URL,
                CredentialBrokers.resolveRisaTokenUrl(override),
                "override was honored: $override",
            )
        }
    }

    private companion object {
        const val PRODUCTION_TOKEN_URL = "https://llm.risa.inc/auth/token"
    }
}
