package ai.rever.boss.plugin.browser

/**
 * Configuration for creating a browser instance.
 *
 * @property url Initial URL to load in the browser
 * @property enableDevTools Whether to enable developer tools (F12)
 * @property enableDownloads Whether to enable file downloads
 * @property enableFullscreen Whether to allow fullscreen video
 * @property userAgent Optional custom user agent string
 * @property initialPostData Optional POST body to use for the very first navigation
 *   (replays a form-submit popup as POST instead of GET). Used only for the first
 *   load; subsequent navigations are normal.
 * @property initialPostContentType Content-Type header for [initialPostData]
 *   (e.g. "application/x-www-form-urlencoded"). Required if initialPostData is set.
 *
 * ## Managed-profile options ([profileName] / [ephemeralProfile] / [auth])
 *
 * These are deliberately **not** primary-constructor parameters. Adding fields to
 * this `data class`'s constructor is source-compatible but **binary-incompatible**:
 * it changes the synthetic `<init>` signature, so every already-compiled plugin
 * that constructs a `BrowserConfig` (e.g. the Fluck browser tab) references the old
 * constructor and gets dropped by `BinaryCompatibilityValidator` — breaking the
 * browser entirely. Keeping them as post-construction properties preserves the
 * original constructor, so old plugins keep linking.
 *
 * Set them after construction:
 * ```
 * BrowserConfig(url = "https://example.com").apply { ephemeralProfile = true }
 * ```
 */
data class BrowserConfig(
    val url: String = "",
    val enableDevTools: Boolean = false,
    val enableDownloads: Boolean = true,
    val enableFullscreen: Boolean = true,
    val userAgent: String? = null,
    val initialPostData: ByteArray? = null,
    val initialPostContentType: String? = null,
) {
    /**
     * Name of the browser profile to create this browser on. Null = the default
     * profile. Used to run a browser on an isolated profile (e.g. RPA sessions).
     */
    var profileName: String? = null

    /**
     * If true, the profile is treated as ephemeral: the host generates a
     * throwaway isolated profile and deletes it when this browser is disposed.
     */
    var ephemeralProfile: Boolean = false

    /**
     * Optional auth (cookies/headers/basic-auth) to seed into the profile before
     * the browser is used. Null = no seeding (general tabs). See [BrowserAuthSpec].
     */
    var auth: BrowserAuthSpec? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BrowserConfig) return false
        return url == other.url &&
            enableDevTools == other.enableDevTools &&
            enableDownloads == other.enableDownloads &&
            enableFullscreen == other.enableFullscreen &&
            userAgent == other.userAgent &&
            initialPostContentType == other.initialPostContentType &&
            initialPostData.contentEqualsOrNull(other.initialPostData)
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + enableDevTools.hashCode()
        result = 31 * result + enableDownloads.hashCode()
        result = 31 * result + enableFullscreen.hashCode()
        result = 31 * result + (userAgent?.hashCode() ?: 0)
        result = 31 * result + (initialPostData?.contentHashCode() ?: 0)
        result = 31 * result + (initialPostContentType?.hashCode() ?: 0)
        return result
    }
}

private fun ByteArray?.contentEqualsOrNull(other: ByteArray?): Boolean {
    if (this == null) return other == null
    if (other == null) return false
    return this.contentEquals(other)
}
