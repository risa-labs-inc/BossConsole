package ai.rever.boss.plugin

/**
 * Pure version/asset-selection helpers used to decide whether a system plugin JAR
 * needs installing or updating. Split out of [PluginStoreSetup] (BossConsole#447
 * step 6): each function is stateless and was already `internal` for test access,
 * so extracting them changes nothing about behavior - only where the logic lives.
 */
internal object PluginVersionComparator {
    /**
     * Check if version1 is newer than version2.
     *
     * Numeric major.minor.patch comparison; a segment's non-numeric suffix
     * counts only as its numeric prefix ("0-rc1" -> 0). On a numeric tie, a
     * version WITH a pre-release suffix is OLDER than one without
     * (1.4.0-rc1 < 1.4.0) — this comparator gates whether a system plugin
     * satisfies the host's [SystemPluginInfo.minVersion], and a pre-release
     * must not pass for its release. Internal for test access.
     */
    fun isNewerVersion(
        version1: String,
        version2: String,
    ): Boolean {
        fun numericParts(v: String) = v.split(".").map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

        fun hasPreReleaseSuffix(v: String) = v.split(".").any { seg -> seg.any { !it.isDigit() } }

        val v1Parts = numericParts(version1)
        val v2Parts = numericParts(version2)

        for (i in 0 until maxOf(v1Parts.size, v2Parts.size)) {
            val v1 = v1Parts.getOrElse(i) { 0 }
            val v2 = v2Parts.getOrElse(i) { 0 }
            if (v1 > v2) return true
            if (v1 < v2) return false
        }
        // Numeric tie: a release is newer than its own pre-release.
        return hasPreReleaseSuffix(version2) && !hasPreReleaseSuffix(version1)
    }

    /**
     * True when the host mandates a minimum plugin version and the installed
     * version is below it — or can't be determined at all (only very old JARs
     * lack a readable version). Internal for test access.
     */
    fun isTooOldForHost(
        installedVersion: String?,
        minVersion: String?,
    ): Boolean {
        if (minVersion == null) return false
        return installedVersion == null || isNewerVersion(minVersion, installedVersion)
    }

    /**
     * Pick the plugin JAR asset URL from a GitHub release JSON payload.
     * Skips "-thin.jar" assets — a module's default :jar output, missing
     * everything buildPluginJar bundles (editor-tab's BossEditor,
     * fluck-browser's tunnel deps, …) — which GitHub can list first.
     * Internal for test access.
     */
    fun pickPluginJarUrl(
        releaseJson: String,
        artifactPrefix: String,
    ): String? =
        Regex(""""browser_download_url"\s*:\s*"([^"]+${Regex.escape(artifactPrefix)}[^"]*\.jar)"""")
            .findAll(releaseJson)
            .map { it.groupValues[1] }
            .firstOrNull { !it.endsWith("-thin.jar") }

    /**
     * Extract the semver component from a plugin JAR filename.
     * Handles the `{prefix}-{version}.jar` and `{prefix}-{version}-all.jar`
     * patterns produced by Gradle. Returns null if the filename doesn't match.
     */
    fun extractVersionFromJarFileName(
        fileName: String,
        artifactPrefix: String,
    ): String? {
        val withoutPrefix = fileName.removePrefix("$artifactPrefix-")
        if (withoutPrefix == fileName) return null
        val version =
            withoutPrefix
                .removeSuffix(".jar")
                .removeSuffix("-all")
        return version.takeIf { it.matches(Regex("""\d+\.\d+\.\d+(?:[-+.][A-Za-z0-9.]+)*""")) }
    }
}
