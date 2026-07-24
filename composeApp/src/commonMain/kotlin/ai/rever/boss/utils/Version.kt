package ai.rever.boss.utils

import ai.rever.boss.plugin.api.Version as ApiVersion

/**
 * Type alias to the canonical Version class in plugin-api.
 * This ensures a single Version implementation across the codebase.
 */
typealias Version = ApiVersion

/**
 * Application version utilities that depend on build-time constants.
 */
object AppVersion {
    /**
     * Current application version - automatically loaded from version.properties at build time.
     */
    val CURRENT =
        Version(
            major = VersionConstants.MAJOR,
            minor = VersionConstants.MINOR,
            patch = VersionConstants.PATCH,
            preRelease = VersionConstants.PRERELEASE,
        )

    /**
     * Get current version as string (e.g., "8.16.28" or "8.16.28-alpha.1").
     */
    fun currentVersionString(): String = CURRENT.toString()
}
