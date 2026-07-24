package ai.rever.boss.updater.source

import ai.rever.boss.updater.GitHubRelease

/**
 * Abstraction over "where release metadata comes from".
 *
 * Implementations normalize their backend into the existing [GitHubRelease] shape
 * so the desktop update service's version-selection and platform-asset-matching
 * logic stays unchanged regardless of the source.
 *
 * Contract: transport/availability failures should throw (so a wrapping
 * [FallbackUpdateSource] can try the backup); a successful-but-empty result is
 * represented by an empty list / null.
 */
interface UpdateSource {
    /** Short identifier for logging, e.g. "supabase" / "github". */
    val name: String

    /** All known releases (order not guaranteed; the caller sorts by version). */
    suspend fun listReleases(): List<GitHubRelease>

    /** A single release by its tag (e.g. "v9.2.17"), or null if not present. */
    suspend fun getReleaseByTag(tag: String): GitHubRelease?
}

/** Thrown by an [UpdateSource] when its backend is unreachable or returns an error. */
class UpdateSourceException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
