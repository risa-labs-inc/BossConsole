package ai.rever.boss.updater.source

import ai.rever.boss.config.UpdateSourceConfig
import ai.rever.boss.updater.GitHubAsset
import ai.rever.boss.updater.GitHubRelease
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Primary [UpdateSource] backed by the Supabase `app_releases` table (queried over
 * PostgREST) — binaries live in Supabase Storage and the row's asset URLs point at
 * them. Plain Ktor REST (not the supabase-kt Postgrest module) is used so update
 * checks don't depend on the shared SupabaseConfig client's init ordering; checks
 * run early and pre-auth using only the public anon key.
 */
class SupabaseUpdateSource(
    private val appId: String = UpdateSourceConfig.appId,
    private val restBaseUrl: String = UpdateSourceConfig.restBaseUrl,
    private val anonKey: String = UpdateSourceConfig.supabaseAnonKey,
    private val apiClient: HttpClient = defaultApiClient(),
) : UpdateSource {
    override val name: String = "supabase"
    private val logger = BossLogger.forComponent("SupabaseUpdateSource")

    companion object {
        private const val MAX_RELEASES = 50

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

    private suspend fun query(
        versionFilter: String?,
        limit: Int,
    ): List<AppReleaseRow> {
        // URL-encode interpolated values: a version with '+build' metadata or any
        // space/&/# would otherwise corrupt the PostgREST query and silently mismatch.
        val appParam = appId.encodeURLParameter()
        val versionParam = versionFilter?.let { "&version=eq.${it.encodeURLParameter()}" } ?: ""
        val url =
            "$restBaseUrl/app_releases?app=eq.$appParam$versionParam" +
                "&order=published_at.desc&limit=$limit&select=*"
        val response =
            apiClient.get(url) {
                headers {
                    append("apikey", anonKey)
                    append(HttpHeaders.Authorization, "Bearer $anonKey")
                    append(HttpHeaders.Accept, "application/json")
                }
            }
        if (response.status.value !in 200..299) {
            throw UpdateSourceException(
                "Supabase app_releases request failed (HTTP ${response.status.value})",
            )
        }
        return response.body()
    }

    override suspend fun listReleases(): List<GitHubRelease> {
        val rows = query(versionFilter = null, limit = MAX_RELEASES)
        logger.debug(LogCategory.NETWORK, "Fetched releases from Supabase", mapOf("count" to rows.size))
        return rows.map { it.toGitHubRelease() }
    }

    override suspend fun getReleaseByTag(tag: String): GitHubRelease? {
        val version = tag.removePrefix("v")
        val rows = query(versionFilter = version, limit = 1)
        return rows.firstOrNull()?.toGitHubRelease()
    }
}

/** One row of the `app_releases` table. Field names match the PostgREST JSON. */
@Serializable
internal data class AppReleaseRow(
    val app: String,
    val version: String,
    val channel: String = "stable",
    val prerelease: Boolean = false,
    @SerialName("release_notes") val releaseNotes: String = "",
    val assets: List<AppReleaseAsset> = emptyList(),
    @SerialName("published_at") val publishedAt: String = "",
) {
    fun toGitHubRelease(): GitHubRelease =
        GitHubRelease(
            tag_name = "v$version",
            name = version,
            body = releaseNotes,
            draft = false,
            prerelease = prerelease,
            published_at = publishedAt,
            assets =
                assets.map { asset ->
                    GitHubAsset(
                        name = asset.name,
                        browser_download_url = asset.url,
                        size = asset.size,
                        content_type = "",
                        sha256 = asset.sha256,
                    )
                },
        )
}

@Serializable
internal data class AppReleaseAsset(
    val name: String,
    val url: String,
    val size: Long = 0,
    val sha256: String? = null,
)
