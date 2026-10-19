package ai.rever.boss.plugin

import ai.rever.boss.utils.sha256Of
import java.io.File

/** Bind the downloaded bytes to approval before promoting or unloading any plugin. */
internal fun verifyApprovedDownload(
    path: String,
    expectedSha256: String?,
): Throwable? {
    if (expectedSha256 == null) return null
    return runCatching {
        require(expectedSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid approved SHA-256 digest" }
        check(sha256Of(File(path)).equals(expectedSha256, ignoreCase = true)) {
            "Downloaded plugin does not match the approved SHA-256 digest. Prepare and approve the install again."
        }
    }.exceptionOrNull()
}
