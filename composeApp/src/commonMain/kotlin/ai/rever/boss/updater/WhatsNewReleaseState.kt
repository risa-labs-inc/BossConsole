package ai.rever.boss.updater

import ai.rever.boss.utils.Version

internal fun isUnseenRelease(
    version: Version,
    lastSeenReleaseVersion: String?,
): Boolean {
    val savedVersion = lastSeenReleaseVersion?.let { Version.parse(it) }
    return savedVersion == null || version > savedVersion
}

internal fun advanceLastSeenReleaseVersion(
    currentVersion: String?,
    viewedVersion: Version,
): String {
    val savedVersion = currentVersion?.let { Version.parse(it) }

    return if (savedVersion == null || viewedVersion > savedVersion) {
        viewedVersion.toString()
    } else {
        savedVersion.toString()
    }
}
