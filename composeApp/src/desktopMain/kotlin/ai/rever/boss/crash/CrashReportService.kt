package ai.rever.boss.crash

import ai.rever.boss.config.GitHubConfig
import ai.rever.boss.config.SupabaseClientConfig
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
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Service for submitting crash reports to GitHub Issues.
 *
 * Primary path: the `crash-report` Supabase Edge Function, which holds the
 * GitHub token server-side (end users have no local token) and routes the
 * issue to the crashing plugin's own repository when the report carries a
 * pluginId, falling back to BossConsole-Releases for host crashes.
 *
 * Fallback path (proxy unreachable): direct GitHub API with a locally
 * configured token — dev machines with GITHUB_TOKEN or `gh` CLI auth. The
 * direct path always files in BossConsole-Releases since plugin→repo
 * resolution lives server-side.
 *
 * Both paths deduplicate by crash signature (comment on the existing issue
 * instead of opening a duplicate).
 */
object CrashReportService {
    private val logger = BossLogger.forComponent("CrashReportService")

    private const val GITHUB_API_BASE = "https://api.github.com"
    private const val CRASH_REPO = "risa-labs-inc/BossConsole-Releases"
    private const val ISSUES_ENDPOINT = "$GITHUB_API_BASE/repos/$CRASH_REPO/issues"

    private val proxyEndpoint: String
        get() = "${SupabaseClientConfig.functionUrl}/crash-report"

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val httpClient =
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(json)
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 15_000
            }
        }

    /**
     * Result of submitting a crash report.
     */
    sealed class SubmitResult {
        data class Success(
            val issueUrl: String,
            val isNewIssue: Boolean,
        ) : SubmitResult()

        data class Error(
            val message: String,
        ) : SubmitResult()
    }

    /**
     * Submit a crash report.
     *
     * Tries the server-side proxy first (works for every user, no local GitHub
     * token needed). If the proxy fails AND a local GitHub token is available
     * (dev machines), falls back to the direct GitHub API.
     *
     * @param report The crash report to submit
     * @return Result indicating success with issue URL, or error
     */
    suspend fun submitCrashReport(report: CrashReport): SubmitResult =
        withContext(Dispatchers.IO) {
            val proxyResult = submitViaProxy(report)
            if (proxyResult is ProxyResult.Done) {
                // Success, or a deliberate server rejection (4xx: throttled,
                // rejected payload) — re-filing directly would sidestep the
                // server's decision, so no fallback for those.
                return@withContext proxyResult.result
            }
            proxyResult as ProxyResult.FallbackWorthy

            val authContext = GitHubConfig.getAuthContext()
            if (!authContext.isAuthenticated) {
                // No local token to fall back to — report the proxy's error.
                return@withContext proxyResult.error
            }

            // Accepted edge: if the proxy filed the issue but its response was lost
            // (timeout mid-flight), this fallback can file a near-duplicate. Dev-only
            // (requires a local token) and rare; the duplicate is cheap to close.
            logger.warn(LogCategory.NETWORK, "Crash report proxy failed, falling back to direct GitHub API")
            try {
                // Search for existing issue with same signature
                val existingIssue = searchForExistingIssue(report.signature, authContext)

                return@withContext if (existingIssue != null) {
                    // Add comment to existing issue
                    addCommentToIssue(existingIssue.number, report, authContext)
                } else {
                    // Create new issue
                    createNewIssue(report, authContext)
                }
            } catch (e: Exception) {
                logger.error(LogCategory.NETWORK, "Failed to submit crash report", error = e)
                SubmitResult.Error("Failed to submit: ${e.message ?: "Unknown error"}")
            }
        }

    /**
     * Proxy submission outcome: [Done] carries a final answer (success or a
     * deliberate 4xx rejection the client must respect); [FallbackWorthy]
     * means the proxy could not be reached or failed server-side (transport
     * error / 5xx), where retrying via the direct GitHub path makes sense.
     */
    private sealed class ProxyResult {
        data class Done(
            val result: SubmitResult,
        ) : ProxyResult()

        data class FallbackWorthy(
            val error: SubmitResult.Error,
        ) : ProxyResult()
    }

    /**
     * Submit the crash report through the crash-report Edge Function.
     * The server resolves the target repository (plugin repo or
     * BossConsole-Releases) and handles signature deduplication.
     */
    private suspend fun submitViaProxy(report: CrashReport): ProxyResult =
        try {
            val response =
                httpClient.post(proxyEndpoint) {
                    headers {
                        append("User-Agent", "BOSS-Desktop-${AppVersion.CURRENT}")
                        // The function gates on the anon key (verify_jwt=false, so it
                        // checks this itself) to keep the endpoint from being an
                        // anonymous issue-creation API.
                        append("apikey", SupabaseClientConfig.anonKey)
                    }
                    contentType(ContentType.Application.Json)
                    setBody(
                        ProxySubmitRequest(
                            signature = report.signature,
                            title = "${CrashSignature.formatForTitle(report.signature)} Crash: ${report.exceptionType}",
                            body = formatIssueBody(report, isNewReport = true),
                            commentBody = formatIssueBody(report, isNewReport = false),
                            pluginId = report.pluginId,
                            appVersion = report.appInfo.version,
                        ),
                    )
                }

            val status = response.status.value
            when {
                status in 200..299 -> {
                    val result = response.body<ProxySubmitResponse>()
                    logger.info(
                        LogCategory.NETWORK,
                        "Crash report submitted via proxy",
                        mapOf(
                            "repo" to result.repo,
                            "isNewIssue" to result.isNewIssue,
                            "signature" to report.signature,
                        ),
                    )
                    ProxyResult.Done(SubmitResult.Success(result.issueUrl, result.isNewIssue))
                }

                status in 400..499 -> {
                    // Deliberate rejection (throttled, invalid payload, bad key)
                    // — final, do not re-file via the direct path.
                    logger.error(
                        LogCategory.NETWORK,
                        "Crash report proxy rejected the report",
                        mapOf(
                            "status" to status,
                            "error" to response.bodyAsText().take(200),
                        ),
                    )
                    ProxyResult.Done(
                        SubmitResult.Error(
                            "Crash report was rejected (HTTP $status). Please try again later.",
                        ),
                    )
                }

                else -> {
                    logger.error(
                        LogCategory.NETWORK,
                        "Crash report proxy failed server-side",
                        mapOf(
                            "status" to status,
                            "error" to response.bodyAsText().take(200),
                        ),
                    )
                    ProxyResult.FallbackWorthy(
                        SubmitResult.Error(
                            "Failed to submit crash report (HTTP $status). Please try again later.",
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            logger.error(LogCategory.NETWORK, "Crash report proxy unreachable", error = e)
            ProxyResult.FallbackWorthy(
                SubmitResult.Error(
                    "Failed to submit crash report: ${e.message ?: "network error"}",
                ),
            )
        }

    /**
     * Search for an existing issue with the same crash signature.
     */
    private suspend fun searchForExistingIssue(
        signature: String,
        authContext: GitHubConfig.GitHubAuthContext,
    ): GitHubIssue? {
        try {
            val searchQuery = "repo:$CRASH_REPO is:issue [$signature] in:title"
            val searchUrl = "$GITHUB_API_BASE/search/issues?q=${searchQuery.encodeURLParameter()}"

            val response =
                httpClient.get(searchUrl) {
                    headers {
                        append("Accept", "application/vnd.github.v3+json")
                        append("User-Agent", "BOSS-Desktop-${AppVersion.CURRENT}")
                        append("Authorization", "Bearer ${authContext.token}")
                    }
                }

            if (response.status.value in 200..299) {
                val searchResult = response.body<GitHubSearchResult>()
                return searchResult.items.firstOrNull()
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.NETWORK, "Failed to search for existing issue", error = e)
        }

        return null
    }

    /**
     * Create a new issue for the crash report.
     */
    private suspend fun createNewIssue(
        report: CrashReport,
        authContext: GitHubConfig.GitHubAuthContext,
    ): SubmitResult {
        val title = "${CrashSignature.formatForTitle(report.signature)} Crash: ${report.exceptionType}"
        val body = formatIssueBody(report, isNewReport = true)

        val response =
            httpClient.post(ISSUES_ENDPOINT) {
                headers {
                    append("Accept", "application/vnd.github.v3+json")
                    append("User-Agent", "BOSS-Desktop-${AppVersion.CURRENT}")
                    append("Authorization", "Bearer ${authContext.token}")
                }
                contentType(ContentType.Application.Json)
                setBody(
                    CreateIssueRequest(
                        title = title,
                        body = body,
                        labels = listOf("crash-report", "automated"),
                    ),
                )
            }

        return when {
            response.status.value in 200..299 -> {
                val issue = response.body<GitHubIssue>()
                logger.info(
                    LogCategory.NETWORK,
                    "Created crash report issue",
                    mapOf(
                        "issue" to issue.number,
                        "signature" to report.signature,
                    ),
                )
                SubmitResult.Success(issue.htmlUrl, isNewIssue = true)
            }

            response.status.value == 401 -> {
                SubmitResult.Error("GitHub authentication failed. Token may be invalid or expired.")
            }

            response.status.value == 403 -> {
                SubmitResult.Error("Permission denied. Token may lack 'repo' scope.")
            }

            else -> {
                val errorBody = response.bodyAsText()
                logger.error(
                    LogCategory.NETWORK,
                    "Failed to create issue",
                    mapOf(
                        "status" to response.status.value,
                        "error" to errorBody.take(200),
                    ),
                )
                SubmitResult.Error("Failed to create issue (HTTP ${response.status.value})")
            }
        }
    }

    /**
     * Add a comment to an existing issue.
     */
    private suspend fun addCommentToIssue(
        issueNumber: Int,
        report: CrashReport,
        authContext: GitHubConfig.GitHubAuthContext,
    ): SubmitResult {
        val commentBody = formatIssueBody(report, isNewReport = false)
        val commentsUrl = "$ISSUES_ENDPOINT/$issueNumber/comments"

        val response =
            httpClient.post(commentsUrl) {
                headers {
                    append("Accept", "application/vnd.github.v3+json")
                    append("User-Agent", "BOSS-Desktop-${AppVersion.CURRENT}")
                    append("Authorization", "Bearer ${authContext.token}")
                }
                contentType(ContentType.Application.Json)
                setBody(CreateCommentRequest(body = commentBody))
            }

        return when {
            response.status.value in 200..299 -> {
                logger.info(
                    LogCategory.NETWORK,
                    "Added comment to existing crash issue",
                    mapOf(
                        "issue" to issueNumber,
                        "signature" to report.signature,
                    ),
                )
                SubmitResult.Success(
                    "$GITHUB_API_BASE/repos/$CRASH_REPO/issues/$issueNumber".replace(
                        "api.github.com/repos",
                        "github.com",
                    ),
                    isNewIssue = false,
                )
            }

            else -> {
                SubmitResult.Error("Failed to add comment (HTTP ${response.status.value})")
            }
        }
    }

    /**
     * Format the issue body in markdown.
     */
    private fun formatIssueBody(
        report: CrashReport,
        isNewReport: Boolean,
    ): String {
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
        val timestamp =
            Instant
                .ofEpochMilli(report.timestamp)
                .atZone(ZoneId.systemDefault())
                .format(dateFormatter)

        return buildString {
            if (!isNewReport) {
                appendLine("## Additional Occurrence")
                appendLine()
            }

            // Signature and timestamp
            appendLine("**Signature:** `${report.signature}`")
            appendLine("**Timestamp:** $timestamp")
            appendLine()

            // User description if provided
            report.userNotes?.let { notes ->
                appendLine("## User Description")
                appendLine(notes)
                appendLine()
            }

            // Environment table
            appendLine("## Environment")
            appendLine()
            appendLine("| Property | Value |")
            appendLine("|----------|-------|")
            appendLine("| BOSS Version | ${report.appInfo.version} |")
            report.pluginId?.let { appendLine("| Plugin | $it |") }
            appendLine("| Platform | ${report.appInfo.platform} |")
            appendLine("| OS | ${report.systemInfo.osName} ${report.systemInfo.osVersion} |")
            appendLine("| Architecture | ${report.systemInfo.osArch} |")
            appendLine("| Java | ${report.systemInfo.javaVersion} (${report.systemInfo.javaVendor}) |")
            appendLine("| Heap Memory | ${report.systemInfo.heapUsedMB} MB / ${report.systemInfo.heapMaxMB} MB |")
            appendLine("| Non-Heap Memory | ${report.systemInfo.nonHeapUsedMB} MB |")
            appendLine("| CPUs | ${report.systemInfo.availableProcessors} |")
            appendLine("| Debug Mode | ${report.appInfo.isDebug} |")
            appendLine()

            // Exception info
            appendLine("## Exception")
            appendLine()
            appendLine("**Type:** `${report.exceptionType}`")
            appendLine("**Message:** ${report.exceptionMessage}")
            appendLine()

            // Stack trace
            appendLine("## Stack Trace")
            appendLine()
            appendLine("```")
            appendLine(report.stackTrace.take(5000)) // Limit stack trace length
            if (report.stackTrace.length > 5000) {
                appendLine("... (truncated)")
            }
            appendLine("```")

            // Recent logs in collapsible section (if included)
            report.recentLogs?.let { logs ->
                if (logs.isNotEmpty()) {
                    appendLine()
                    appendLine("<details>")
                    appendLine("<summary>Recent Activity Logs (${logs.size} entries)</summary>")
                    appendLine()
                    appendLine("```")
                    logs.forEach { entry ->
                        val entryTime =
                            Instant
                                .ofEpochMilli(entry.timestamp)
                                .atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
                        appendLine("[$entryTime] [${entry.level}] [${entry.category}] ${entry.component}: ${entry.message}")
                    }
                    appendLine("```")
                    appendLine()
                    appendLine("</details>")
                }
            }
        }
    }

    // Data classes for the crash-report Edge Function

    @Serializable
    private data class ProxySubmitRequest(
        val signature: String,
        val title: String,
        val body: String,
        val commentBody: String,
        val pluginId: String? = null,
        val appVersion: String,
    )

    @Serializable
    private data class ProxySubmitResponse(
        val issueUrl: String,
        val isNewIssue: Boolean,
        val repo: String,
    )

    // Data classes for GitHub API

    @Serializable
    private data class CreateIssueRequest(
        val title: String,
        val body: String,
        val labels: List<String>,
    )

    @Serializable
    private data class CreateCommentRequest(
        val body: String,
    )

    @Serializable
    private data class GitHubSearchResult(
        @SerialName("total_count") val totalCount: Int,
        val items: List<GitHubIssue>,
    )

    @Serializable
    private data class GitHubIssue(
        val number: Int,
        val title: String,
        @SerialName("html_url") val htmlUrl: String,
        val state: String,
    )
}
