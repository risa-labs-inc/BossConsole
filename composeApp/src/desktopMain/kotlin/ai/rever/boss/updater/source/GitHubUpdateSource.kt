package ai.rever.boss.updater.source

import ai.rever.boss.config.GitHubConfig
import ai.rever.boss.updater.GitHubRelease
import ai.rever.boss.utils.AppVersion
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Backup [UpdateSource] backed by the GitHub Releases API.
 *
 * This is the relocated GitHub logic that previously lived inline in
 * DesktopUpdateService: paginated `/releases`, `/releases/tags/{tag}`, and the
 * authenticated-with-unauthenticated-fallback request helper. Behavior is
 * unchanged; non-2xx responses are surfaced as [UpdateSourceException] so a
 * wrapping [FallbackUpdateSource] can react.
 */
class GitHubUpdateSource(
    private val apiClient: HttpClient = defaultApiClient(),
) : UpdateSource {
    override val name: String = "github"
    private val logger = BossLogger.forComponent("GitHubUpdateSource")

    companion object {
        private const val GITHUB_API_BASE = "https://api.github.com"
        private const val RELEASES_REPO = "risa-labs-inc/BossConsole-Releases"
        private const val RELEASES_ENDPOINT = "$GITHUB_API_BASE/repos/$RELEASES_REPO/releases"

        private fun defaultApiClient(): HttpClient =
            HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        },
                    )
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 15_000
                    socketTimeoutMillis = 15_000
                }
            }
    }

    /**
     * Make a GitHub API request with automatic fallback to unauthenticated access.
     * BossConsole-Releases is public, so authentication is optional; a 401 with a
     * token retries without it.
     */
    private suspend fun makeGitHubRequest(
        url: String,
        authContext: GitHubConfig.GitHubAuthContext,
    ): HttpResponse {
        if (authContext.isAuthenticated) {
            val authenticatedResponse =
                apiClient.get(url) {
                    headers {
                        append("Accept", "application/vnd.github.v3+json")
                        append("User-Agent", "BOSS-Desktop-${AppVersion.CURRENT}")
                        append("Authorization", "Bearer ${authContext.token}")
                    }
                }
            if (authenticatedResponse.status.value in 200..299) {
                return authenticatedResponse
            }
            if (authenticatedResponse.status.value == 401) {
                logger.warn(LogCategory.NETWORK, "Authenticated request failed - retrying without auth")
            } else {
                return authenticatedResponse
            }
        }
        return apiClient.get(url) {
            headers {
                append("Accept", "application/vnd.github.v3+json")
                append("User-Agent", "BOSS-Desktop-${AppVersion.CURRENT}")
            }
        }
    }

    override suspend fun listReleases(): List<GitHubRelease> {
        val authContext = GitHubConfig.getAuthContext()
        val allReleases = mutableListOf<GitHubRelease>()
        var page = 1
        val perPage = 100 // GitHub max per page

        while (true) {
            val url = "$RELEASES_ENDPOINT?page=$page&per_page=$perPage"
            val response = makeGitHubRequest(url, authContext)

            if (response.status.value !in 200..299) {
                if (page == 1) {
                    val body = response.bodyAsText()
                    val detail = if (body.contains("rate limit", ignoreCase = true)) " - rate limit exceeded" else ""
                    throw UpdateSourceException("GitHub releases request failed (HTTP ${response.status.value})$detail")
                }
                // Already have at least one page; stop paginating on a later-page error.
                logger.warn(
                    LogCategory.NETWORK,
                    "Failed to fetch releases page",
                    mapOf("page" to page, "status" to response.status.value),
                )
                break
            }

            val releases = response.body<List<GitHubRelease>>()
            if (releases.isEmpty()) break
            allReleases.addAll(releases)
            if (releases.size < perPage) break
            page++
        }
        return allReleases
    }

    override suspend fun getReleaseByTag(tag: String): GitHubRelease? {
        val authContext = GitHubConfig.getAuthContext()
        val response = makeGitHubRequest("$RELEASES_ENDPOINT/tags/$tag", authContext)
        if (response.status.value !in 200..299) {
            if (response.status.value == 404) return null
            throw UpdateSourceException("GitHub tag request failed for $tag (HTTP ${response.status.value})")
        }
        return response.body<GitHubRelease>()
    }
}
