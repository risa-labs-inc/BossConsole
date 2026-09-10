package ai.rever.boss.utils

import ai.rever.boss.services.supabase.models.SecretEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class WebsiteMatchingHostnameRegressionTest {
    private fun suggestions(
        pageUrl: String,
        vararg websites: String,
    ): List<WebsiteMatchingUtil.MatchedSecret> {
        val domain = assertNotNull(WebsiteMatchingUtil.extractMainDomain(pageUrl))
        val secrets =
            websites.mapIndexed { index, website ->
                SecretEntry(
                    id = index.toString(),
                    website = website,
                    username = "test-user",
                    password = "synthetic-test-password",
                    createdAt = "2026-01-01T00:00:00Z",
                    updatedAt = "2026-01-01T00:00:00Z",
                )
            }
        return WebsiteMatchingUtil.matchSecretsForDomain(domain, secrets)
    }

    @Test
    fun `unlisted multipart suffix does not collapse unrelated saved websites`() {
        val matches = suggestions("https://apple.com.mx/login", "https://google.com.mx", "https://apple.com.mx")
        assertEquals(listOf("https://apple.com.mx"), matches.map { it.secret.website })
        assertEquals("exact", matches.single().matchReason)
    }

    @Test
    fun `private suffix tenants retain their separate hostnames`() {
        val matches =
            suggestions("https://attacker.github.io/login", "https://victim.github.io", "https://attacker.github.io")
        assertEquals(listOf("https://attacker.github.io"), matches.map { it.secret.website })
    }

    @Test
    fun `sibling hosts do not inherit secrets while an explicit parent still matches`() {
        val matches =
            suggestions(
                "https://login.google.com/auth",
                "https://accounts.google.com",
                "https://google.com",
                "https://login.google.com",
            )
        assertEquals(listOf("https://login.google.com", "https://google.com"), matches.map { it.secret.website })
        assertEquals(listOf(1.0f, 0.9f), matches.map { it.matchScore })
    }

    @Test
    fun `previously listed suffixes do not erase sibling hosts either`() {
        val matches =
            suggestions("https://login.example.co.uk", "https://accounts.example.co.uk", "https://example.co.uk")
        assertEquals(listOf("https://example.co.uk"), matches.map { it.secret.website })
        assertEquals("subdomain", matches.single().matchReason)
    }

    @Test
    fun `www and bare saved authorities normalize without losing the rest of the host`() {
        val matches =
            suggestions("https://www.login.example.com:8443/auth", "www.login.example.com", "accounts.example.com")
        assertEquals(listOf("www.login.example.com"), matches.map { it.secret.website })
        assertEquals("exact", matches.single().matchReason)
    }
}
