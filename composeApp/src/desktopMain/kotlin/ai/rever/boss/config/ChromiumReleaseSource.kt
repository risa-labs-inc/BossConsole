package ai.rever.boss.config

import ai.rever.boss.updater.source.GitHubUpdateSource
import ai.rever.boss.updater.source.SupabaseUpdateSource
import ai.rever.boss.updater.source.UpdateSource
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory

/**
 * One place an engine archive can be fetched from, tried in list order.
 *
 * @property sha256 Expected archive hash when the catalog provides one (Supabase
 *   `app_releases` rows do). The constructed GitHub backup URL has no hash of
 *   its own, so its candidate reuses the hash the same catalog lookup returned
 *   for that archive — the release pipeline publishes one artifact to both
 *   sources, so the catalog's hash binds the backup's bytes exactly as it binds
 *   the primary's. Like the app updater, this is an integrity check against
 *   Storage/CDN corruption, not authenticity — hash and URL come from the same
 *   catalog row.
 */
data class EngineDownloadCandidate(
    val sourceName: String,
    val url: String,
    val sha256: String? = null,
)

/**
 * Installable engine versions, newest first. Every listed version has a catalog
 * checksum for the current platform's archive.
 */
data class EngineVersionListing(
    val versions: List<String>,
    val failedSources: List<String> = emptyList(),
)

/**
 * Resolves where BOSS-branded Chromium engine archives can be downloaded from.
 *
 * Engine releases live in the same `app_releases` table / Storage bucket as app
 * releases (published by build-chromium-branding.yml via publish-supabase-release.sh
 * under the app id "boss-chromium"), with the GitHub BossConsole-Releases repo as
 * the backup — mirroring the app updater's Supabase-primary/GitHub-backup order.
 *
 * Sources are injectable for tests; app code uses the [ChromiumReleaseSource]
 * singleton.
 */
class ChromiumReleaseResolver(
    private val supabaseSource: UpdateSource,
    private val gitHubSource: UpdateSource,
    /**
     * Base of the constructed GitHub backup URLs. Injectable so the end-to-end
     * fallback-checksum regression test (BossConsole#798 follow-up) can point
     * the backup at a local HTTP server it controls; production keeps the real
     * BossConsole-Releases base.
     */
    private val gitHubReleasesBase: String = GITHUB_RELEASES_BASE,
) {
    private val logger = BossLogger.forComponent("ChromiumReleaseSource")

    /**
     * Ordered download URLs for one engine archive (Supabase first, GitHub backup).
     * The GitHub URL is constructed rather than queried so it stays available even
     * when the GitHub API is rate-limited.
     */
    suspend fun downloadCandidates(
        version: String,
        archiveName: String,
    ): List<EngineDownloadCandidate> {
        val candidates = mutableListOf<EngineDownloadCandidate>()

        // The catalog's hash for this archive, when the lookup provides one.
        // Remembered across the try block because the GitHub backup below
        // fetches the SAME archive of the SAME version and is verified against
        // it too.
        var catalogSha256: String? = null

        try {
            val asset =
                supabaseSource
                    .getReleaseByTag("v$version")
                    ?.assets
                    ?.firstOrNull { it.name == archiveName }
            // Capture the row's hash even when it cannot serve a primary URL:
            // the backup below can still be verified with it.
            catalogSha256 = asset?.sha256
            val assetUrl = asset?.browser_download_url
            if (assetUrl != null) {
                candidates += EngineDownloadCandidate(supabaseSource.name, assetUrl, asset.sha256)
            } else {
                logger.info(
                    LogCategory.BROWSER,
                    "Engine release not on Supabase, will use GitHub",
                    mapOf(
                        "version" to version,
                        "archive" to archiveName,
                    ),
                )
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Supabase engine release lookup failed, will use GitHub", error = e)
        }

        candidates +=
            EngineDownloadCandidate(
                gitHubSource.name,
                "$gitHubReleasesBase/$GITHUB_TAG_PREFIX$version/$archiveName",
                // The backup runs precisely because the primary source is
                // already misbehaving, and its archive is extracted and later
                // EXECUTED as the browser engine — so this is the download that
                // needs the integrity check most. Building it with
                // `sha256 = null` (the gap BossConsole#798 recorded in its own
                // body) meant installFromCandidates extracted whatever the
                // backup served with no check at all. The catalog's hash binds
                // these bytes exactly as it binds the primary's (one artifact,
                // two sources); a genuine build difference between the sources
                // must now fail loudly and fall through to the next candidate,
                // never install unverified. When the catalog lookup itself
                // failed there is no hash and the backup cannot be installed.
                sha256 = catalogSha256,
            )
        return candidates
    }

    /**
     * Only versions with a catalog checksum for [archiveName] are installable.
     * Older GitHub-only releases remain available at their source, but offering
     * them here would download a full archive and inevitably refuse to install it.
     */
    suspend fun availableVersions(archiveName: String): EngineVersionListing {
        val versions = linkedSetOf<String>()
        try {
            supabaseSource.listReleases().forEach { release ->
                if (release.assets.any { it.name == archiveName && !it.sha256.isNullOrBlank() }) {
                    versions += release.tag_name.removePrefix("v")
                }
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Failed to list engine versions from Supabase", error = e)
            throw IllegalStateException(
                "Could not list checksum-verified engine versions: ${e.message}",
                e,
            )
        }

        return EngineVersionListing(
            versions = versions.sortedWith(compareByDescending(versionComparator(), ::versionKey)),
        )
    }

    /** Semver-ish sort key: numeric release identifiers + optional pre-release identifiers. */
    internal data class EngineVersionKey(
        val release: List<Int>,
        val preRelease: List<String>?,
    )

    companion object {
        private const val GITHUB_RELEASES_BASE =
            "https://github.com/risa-labs-inc/BossConsole-Releases/releases/download"
        const val GITHUB_TAG_PREFIX = "chromium-v"

        /** "9.1.2-rc.1" -> release [9, 1, 2], preRelease ["rc", "1"]. */
        internal fun versionKey(version: String): EngineVersionKey {
            val releasePart = version.substringBefore('-')
            val prePart = version.substringAfter('-', "")
            return EngineVersionKey(
                release = releasePart.split('.').mapNotNull { it.toIntOrNull() },
                preRelease = prePart.takeIf { it.isNotEmpty() }?.split('.', '-'),
            )
        }

        /**
         * Numeric (not lexicographic) release comparison; per semver, a stable
         * version outranks its own pre-releases and pre-release identifiers
         * compare numerically when both sides are numeric.
         */
        internal fun versionComparator(): Comparator<EngineVersionKey> =
            Comparator { a, b ->
                for (i in 0 until maxOf(a.release.size, b.release.size)) {
                    val cmp = (a.release.getOrElse(i) { 0 }).compareTo(b.release.getOrElse(i) { 0 })
                    if (cmp != 0) return@Comparator cmp
                }
                when {
                    a.preRelease == null && b.preRelease == null -> 0
                    a.preRelease == null -> 1
                    b.preRelease == null -> -1
                    else -> comparePreRelease(a.preRelease, b.preRelease)
                }
            }

        private fun comparePreRelease(
            a: List<String>,
            b: List<String>,
        ): Int {
            for (i in 0 until maxOf(a.size, b.size)) {
                val ai = a.getOrNull(i) ?: return -1 // fewer identifiers = lower (semver)
                val bi = b.getOrNull(i) ?: return 1
                val an = ai.toIntOrNull()
                val bn = bi.toIntOrNull()
                val cmp =
                    when {
                        an != null && bn != null -> an.compareTo(bn)

                        an != null -> -1

                        // numeric identifiers sort below alphanumeric (semver)
                        bn != null -> 1

                        else -> ai.compareTo(bi)
                    }
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}

/** App-wide singleton resolver wired to the real Supabase + GitHub sources. */
object ChromiumReleaseSource {
    const val APP_ID = "boss-chromium"

    // GitHub's listing paginates ALL BossConsole-Releases releases, and the
    // unauthenticated API allows only 60 requests/hour — so re-fetching on every
    // Settings-section open is both slow and rate-limit-hungry. Engine releases
    // are rare; a short TTL keeps the section snappy without going stale.
    private val VERSIONS_CACHE_TTL_MS =
        java.util.concurrent.TimeUnit.MINUTES
            .toMillis(10)

    @Volatile
    private var cachedVersions: Pair<Long, EngineVersionListing>? = null

    private val resolver by lazy {
        ChromiumReleaseResolver(
            supabaseSource = SupabaseUpdateSource(appId = APP_ID),
            gitHubSource = GitHubUpdateSource(),
        )
    }

    suspend fun downloadCandidates(
        version: String,
        archiveName: String,
    ): List<EngineDownloadCandidate> = resolver.downloadCandidates(version, archiveName)

    suspend fun availableVersions(): EngineVersionListing {
        cachedVersions?.let { (fetchedAt, listing) ->
            if (System.currentTimeMillis() - fetchedAt < VERSIONS_CACHE_TTL_MS) return listing
        }
        val archiveName = "boss-chromium-${ChromiumAutoDownloader.detectPlatform()}.zip"
        val listing = resolver.availableVersions(archiveName)
        // Don't cache partial results: a retry should get another chance at the failed source.
        if (listing.failedSources.isEmpty()) {
            cachedVersions = System.currentTimeMillis() to listing
        }
        return listing
    }
}
