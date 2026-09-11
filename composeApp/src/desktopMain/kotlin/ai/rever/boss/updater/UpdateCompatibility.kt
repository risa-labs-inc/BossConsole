package ai.rever.boss.updater

import ai.rever.boss.utils.Version

private val CATALOG_OS_VERSION = Regex("""\d+(\.\d+){0,3}""")

/** Catalog key for the package family selected by [platform]. */
internal fun updateOsKey(platform: String): String? =
    when {
        platform.equals("macOS", ignoreCase = true) -> "macos"
        platform.startsWith("Windows", ignoreCase = true) -> "windows"
        platform.startsWith("Linux", ignoreCase = true) -> "linux"
        else -> null
    }

/**
 * Whether [currentVersion] satisfies [minimumVersion].
 *
 * Missing or malformed values return true. OS metadata is an availability hint,
 * not an install authority, so an unreadable value must leave the existing signed
 * bundle inspection reachable. Components are zero-padded for comparison, making
 * 13, 13.0 and 13.0.0 equivalent.
 */
internal fun satisfiesMinimumOsVersion(
    currentVersion: String?,
    minimumVersion: String?,
): Boolean {
    val current = currentVersion?.validCatalogOsVersion()
    val minimum = minimumVersion?.validCatalogOsVersion()
    return current == null || minimum == null || compareVersions(current, minimum) >= 0
}

private fun String.validCatalogOsVersion(): String? {
    val value = trim()
    return value
        .takeIf { it.matches(CATALOG_OS_VERSION) }
        ?.takeIf { version -> version.split('.').all { component -> component.toIntOrNull() != null } }
}

internal fun GitHubRelease.supportsOs(
    platform: String,
    currentOsVersion: String?,
): Boolean {
    val key = updateOsKey(platform) ?: return true
    return satisfiesMinimumOsVersion(currentOsVersion, minimumOs[key])
}

/**
 * Select the newest release that is both channel-eligible and runnable here.
 * Compatibility is filtered before max selection so an incompatible newest row
 * cannot hide an older compatible update.
 */
internal fun selectLatestCompatibleRelease(
    releases: List<GitHubRelease>,
    includePreReleases: Boolean,
    platform: String,
    currentOsVersion: String?,
): GitHubRelease? =
    releases
        .asSequence()
        .filter { release -> !release.draft && (includePreReleases || !release.prerelease) }
        .filter { release -> release.supportsOs(platform, currentOsVersion) }
        .mapNotNull { release -> Version.parse(release.tag_name)?.let { version -> release to version } }
        .maxByOrNull { it.second }
        ?.first
