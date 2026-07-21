package ai.rever.boss.config

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.util.concurrent.TimeUnit

/**
 * Configuration for GitHub API access.
 *
 * GitHub API rate limits:
 * - Unauthenticated: 60 requests/hour
 * - Authenticated: 5,000 requests/hour
 *
 * The GitHub token is obtained from multiple sources (in order):
 * 1. Environment variable: GITHUB_TOKEN
 * 2. System property: GITHUB_TOKEN
 * 3. local.properties file: GITHUB_TOKEN=ghp_...
 * 4. GitHub CLI (gh auth token)
 * 5. No token (fallback to unauthenticated access)
 *
 * To set up authentication:
 * - **Option 1 (Easiest)**: Run `gh auth login` to authenticate via GitHub CLI
 * - **Option 2**: Create token at https://github.com/settings/tokens (no scopes needed)
 *                 and add to local.properties: GITHUB_TOKEN=ghp_your_token_here
 */
object GitHubConfig {
    private val logger = BossLogger.forComponent("GitHubConfig")
    private const val GH_TIMEOUT_SECONDS = 5L

    /**
     * Encapsulates GitHub authentication state including token, source, and validity.
     */
    data class GitHubAuthContext(
        val token: String?,
        val source: TokenSource,
        val isValid: Boolean
    ) {
        enum class TokenSource {
            ENVIRONMENT_VARIABLE,
            SYSTEM_PROPERTY,
            LOCAL_PROPERTIES,
            GITHUB_CLI,
            NONE
        }

        val isAuthenticated: Boolean get() = token != null && isValid
        val rateLimit: Int get() = if (isAuthenticated) 5000 else 60
    }

    /**
     * Get fresh authentication context for each API call.
     * This method does not cache results to ensure token validity is checked on every use.
     */
    fun getAuthContext(): GitHubAuthContext {
        // Try explicit config sources first (fast: env var, system prop, local.properties)
        ConfigLoader.getConfig("GITHUB_TOKEN")?.let { token ->
            val source = when {
                System.getenv("GITHUB_TOKEN") != null -> GitHubAuthContext.TokenSource.ENVIRONMENT_VARIABLE
                System.getProperty("GITHUB_TOKEN") != null -> GitHubAuthContext.TokenSource.SYSTEM_PROPERTY
                else -> GitHubAuthContext.TokenSource.LOCAL_PROPERTIES
            }
            return GitHubAuthContext(
                token = token,
                source = source,
                isValid = validateToken(token)
            )
        }

        // Try GitHub CLI with timeout (slow: subprocess call)
        val cliToken = getTokenFromGitHubCLI()
        return if (cliToken != null) {
            GitHubAuthContext(
                token = cliToken,
                source = GitHubAuthContext.TokenSource.GITHUB_CLI,
                isValid = validateToken(cliToken)
            )
        } else {
            GitHubAuthContext(
                token = null,
                source = GitHubAuthContext.TokenSource.NONE,
                isValid = false
            )
        }
    }

    /**
     * Attempt to retrieve token from GitHub CLI (gh auth token) with timeout.
     * Returns null if gh is not installed, not authenticated, or times out.
     */
    private fun getTokenFromGitHubCLI(): String? {
        var process: Process? = null
        return try {
            process = ProcessBuilder("gh", "auth", "token")
                .redirectErrorStream(true)
                .start()

            // Wait for process to complete with timeout
            if (!process.waitFor(GH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                logger.warn(LogCategory.NETWORK, "GitHub CLI token retrieval timed out", mapOf("timeoutSeconds" to GH_TIMEOUT_SECONDS))
                return null
            }

            // Process completed - now safely read output
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            val exitCode = process.exitValue()

            when {
                exitCode != 0 -> {
                    logger.debug(LogCategory.NETWORK, "GitHub CLI not authenticated", mapOf("exitCode" to exitCode))
                    null
                }
                output.isBlank() -> {
                    logger.debug(LogCategory.NETWORK, "GitHub CLI returned empty token")
                    null
                }
                output.contains("not logged in", ignoreCase = true) -> {
                    logger.debug(LogCategory.NETWORK, "GitHub CLI: not logged in")
                    null
                }
                else -> {
                    logger.debug(LogCategory.NETWORK, "Retrieved GitHub token from GitHub CLI")
                    output
                }
            }
        } catch (e: Exception) {
            logger.debug(LogCategory.NETWORK, "GitHub CLI not available")
            null
        } finally {
            process?.let {
                if (it.isAlive) it.destroyForcibly()
            }
        }
    }

    /**
     * Validate GitHub token format.
     * Supports all GitHub token types:
     * - Classic PAT: ghp_[A-Za-z0-9]{36}
     * - Fine-grained PAT: github_pat_[A-Za-z0-9_]{82}
     * - OAuth access token: gho_[A-Za-z0-9]{36} (used by gh CLI)
     * - User-to-server: ghu_[A-Za-z0-9]{36}
     * - Server-to-server: ghs_[A-Za-z0-9]{36}
     * - Refresh token: ghr_[A-Za-z0-9]{36}
     */
    private fun validateToken(token: String): Boolean {
        return when {
            token.startsWith("ghp_") -> token.length >= 40  // Classic PAT
            token.startsWith("gho_") -> token.length >= 40  // OAuth (gh CLI)
            token.startsWith("ghu_") -> token.length >= 40  // User-to-server
            token.startsWith("ghs_") -> token.length >= 40  // Server-to-server
            token.startsWith("ghr_") -> token.length >= 40  // Refresh token
            token.startsWith("github_pat_") -> token.length >= 93  // Fine-grained PAT
            else -> false
        }
    }

    // Deprecated properties for backward compatibility

    /**
     * @deprecated Use getAuthContext() instead. This lazy property caches the token forever,
     * which can lead to stale/expired tokens being used.
     */
    @Deprecated("Use getAuthContext() instead")
    val token: String? by lazy {
        ConfigLoader.getConfig("GITHUB_TOKEN") ?: getTokenFromGitHubCLI()
    }

    /**
     * @deprecated Use getAuthContext().isAuthenticated instead
     */
    @Deprecated("Use getAuthContext() instead")
    val hasToken: Boolean
        get() = getAuthContext().isAuthenticated
}
