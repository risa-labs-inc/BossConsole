package ai.rever.boss.utils

import ai.rever.boss.services.supabase.models.SecretEntry
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory

/**
 * Utility for matching secrets to website domains.
 *
 * Handles:
 * - Domain extraction from URLs
 * - Subdomain normalization (login.google.com → google.com)
 * - Fuzzy matching between secret website and current domain
 * - Scoring and ranking of matched secrets
 *
 * Used by Issue #56 - Secret Access Integration with Fluck Browser
 */
object WebsiteMatchingUtil {
    private val logger = BossLogger.forComponent("WebsiteMatchingUtil")

    /**
     * Represents a matched secret with its relevance score
     */
    data class MatchedSecret(
        val secret: SecretEntry,
        val matchScore: Float,  // 0.0 - 1.0
        val matchReason: String  // "exact", "subdomain", "partial", "domain"
    ) : Comparable<MatchedSecret> {
        override fun compareTo(other: MatchedSecret): Int {
            return other.matchScore.compareTo(this.matchScore)  // Descending
        }
    }

    /**
     * Extract the main domain from a URL.
     *
     * Examples:
     * - https://login.google.com/auth → google.com
     * - https://www.github.com/login → github.com
     * - https://accounts.google.com → google.com
     * - http://localhost:3000 → localhost
     * - https://example.co.uk → example.co.uk
     *
     * @param url The URL to extract domain from
     * @return Cleaned main domain, or null if invalid
     */
    fun extractMainDomain(url: String): String? {
        return try {
            val cleanUrl = url.trim()

            // Handle empty or invalid URLs
            if (cleanUrl.isEmpty() || cleanUrl == "about:blank") {
                return null
            }

            // Add protocol if missing for parsing
            val urlWithProtocol = if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                "https://$cleanUrl"
            } else {
                cleanUrl
            }

            // Parse URL
            val javaUrl = java.net.URL(urlWithProtocol)
            var host = javaUrl.host.lowercase()

            // Handle localhost and IP addresses
            if (host.startsWith("localhost") || host.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))) {
                return host
            }

            // Remove www. prefix
            host = host.removePrefix("www.")

            // Remove common subdomains for matching
            // But keep subdomains that might be meaningful for secrets
            val commonSubdomains = listOf("login", "accounts", "auth", "signin", "signup", "sso", "id", "portal", "app", "my")
            val parts = host.split(".")

            // Keep TLD + main domain (e.g., google.com, github.com)
            // Special handling for .co.uk, .com.au, etc.
            val twoPartTlds = listOf("co.uk", "com.au", "co.in", "co.jp", "com.br", "co.za")

            host = when {
                // Handle two-part TLDs (example.co.uk)
                parts.size >= 3 && twoPartTlds.any { host.endsWith(it) } -> {
                    parts.takeLast(3).joinToString(".")
                }
                // Remove common subdomain (login.google.com → google.com)
                parts.size >= 3 && parts[0] in commonSubdomains -> {
                    parts.drop(1).joinToString(".")
                }
                // Keep as is if short enough
                parts.size <= 2 -> host
                // For longer domains, keep last 2 parts (subdomain.example.com → example.com)
                else -> parts.takeLast(2).joinToString(".")
            }

            host
        } catch (e: Exception) {
            logger.debug(LogCategory.BROWSER, "Failed to extract domain", mapOf("url" to url))
            null
        }
    }

    /**
     * Match secrets for a specific domain with scoring.
     *
     * Returns secrets sorted by relevance (highest score first).
     * Matching logic:
     * - Exact match (google.com == google.com): score 1.0
     * - Subdomain match (login.google.com vs google.com): score 0.9
     * - Domain contains (google.com contains "google"): score 0.7
     * - Partial match ("google" in "google-workspace.com"): score 0.5
     *
     * @param domain Current website domain (e.g., "google.com")
     * @param secrets List of all available secrets
     * @param maxResults Maximum number of results to return (default: 5)
     * @return List of matched secrets with scores, sorted by relevance
     */
    fun matchSecretsForDomain(
        domain: String,
        secrets: List<SecretEntry>,
        maxResults: Int = 5
    ): List<MatchedSecret> {
        val normalizedDomain = domain.lowercase().removePrefix("www.")

        val matchedSecrets = secrets.mapNotNull { secret ->
            val secretDomain = extractMainDomain(secret.website) ?: secret.website.lowercase()
            val score = calculateMatchScore(secretDomain, normalizedDomain)

            if (score.score > 0.3f) {  // Threshold for relevance
                MatchedSecret(
                    secret = secret,
                    matchScore = score.score,
                    matchReason = score.reason
                )
            } else {
                null
            }
        }

        return matchedSecrets
            .sorted()  // Sort by score descending
            .take(maxResults)
    }

    /**
     * Calculate match score between secret website and current domain.
     *
     * @return MatchScore with score (0.0-1.0) and reason
     */
    fun calculateMatchScore(secretWebsite: String, currentDomain: String): MatchScore {
        val secretNorm = secretWebsite.lowercase().trim()
        val domainNorm = currentDomain.lowercase().trim()

        return when {
            // Exact match
            secretNorm == domainNorm -> MatchScore(1.0f, "exact")

            // Subdomain match (login.google.com vs google.com)
            secretNorm.endsWith(".$domainNorm") || domainNorm.endsWith(".$secretNorm") ->
                MatchScore(0.9f, "subdomain")

            // Domain contains other (google.com contains google)
            secretNorm.contains(domainNorm) || domainNorm.contains(secretNorm) ->
                MatchScore(0.7f, "domain")

            // Partial match (same keywords)
            else -> {
                val secretParts = secretNorm.split(".", "-", "_")
                val domainParts = domainNorm.split(".", "-", "_")
                val commonParts = secretParts.intersect(domainParts.toSet())

                if (commonParts.isNotEmpty()) {
                    MatchScore(0.5f, "partial")
                } else {
                    MatchScore(0.0f, "no_match")
                }
            }
        }
    }

    /**
     * Match score with reasoning
     */
    data class MatchScore(val score: Float, val reason: String)

    /**
     * Get a user-friendly display name for a website.
     *
     * Examples:
     * - google.com → Google
     * - github.com → GitHub
     * - example-site.com → Example Site
     *
     * @param website Website domain or URL
     * @return Formatted display name
     */
    fun getDisplayName(website: String): String {
        val domain = extractMainDomain(website) ?: website

        // Remove TLD
        val nameWithoutTld = domain.split(".").first()

        // Handle special cases
        return when (nameWithoutTld.lowercase()) {
            "google" -> "Google"
            "github" -> "GitHub"
            "facebook" -> "Facebook"
            "linkedin" -> "LinkedIn"
            "twitter" -> "Twitter (X)"
            "microsoft" -> "Microsoft"
            "apple" -> "Apple"
            "amazon" -> "Amazon"
            "netflix" -> "Netflix"
            else -> {
                // Generic formatting: example-site → Example Site
                nameWithoutTld
                    .split("-", "_")
                    .joinToString(" ") { it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else it } }
            }
        }
    }

    /**
     * Check if a URL is likely a login/authentication page.
     *
     * Helps prioritize showing secret menu on login pages.
     *
     * @param url Current page URL
     * @return true if URL suggests login page
     */
    fun isLikelyLoginPage(url: String): Boolean {
        val lowerUrl = url.lowercase()
        val loginKeywords = listOf(
            "login", "signin", "sign-in", "auth", "authenticate",
            "password", "sso", "oauth", "accounts", "signup", "sign-up", "register"
        )

        return loginKeywords.any { lowerUrl.contains(it) }
    }

    /**
     * Extract subdomain from URL if present.
     *
     * Examples:
     * - login.google.com → login
     * - www.example.com → www
     * - example.com → null
     *
     * @param url URL to extract subdomain from
     * @return Subdomain or null
     */
    fun extractSubdomain(url: String): String? {
        return try {
            val domain = extractMainDomain(url) ?: return null
            val parts = domain.split(".")

            if (parts.size > 2) {
                parts.dropLast(2).joinToString(".")
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
