package ai.rever.boss.plugin.browser

/**
 * Optional auth/state to seed into a browser profile before use (see
 * [BrowserConfig.auth]). General browser tabs leave this null; automation
 * (e.g. RPA) sets it to pre-authenticate an isolated profile.
 *
 * Scoping caveat: [cookies] are domain-scoped (each [BrowserCookieSpec] names
 * its domain), but [headers] and [basicAuth] are injected on **every** request
 * the profile makes — not restricted to a target host. If the run navigates or
 * issues cross-origin sub-requests, those headers (incl. the `Authorization`
 * derived from [basicAuth]) are sent to other origins too. Prefer domain-scoped
 * cookies for credentials, and only use [headers]/[basicAuth] for runs you keep
 * pointed at a single trusted origin.
 */
data class BrowserAuthSpec(
    val cookies: List<BrowserCookieSpec> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val basicAuth: BrowserBasicAuth? = null,
)

data class BrowserCookieSpec(
    /** Cookie domain (a full URL is also accepted; scheme/port/path are stripped). */
    val url: String,
    val name: String,
    val value: String,
    val path: String = "/",
    /** Defaults to false; set true for cookies that must only travel over HTTPS. */
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
    val expirationEpochMs: Long = 0,
    val sameSite: String = "LAX",
)

data class BrowserBasicAuth(val username: String, val password: String)

/** Metadata about a persistent named browser profile (see [BrowserService.listProfiles]). */
data class BrowserProfileInfo(
    val profileName: String,
    val diskBytes: Long,
    val lastUsedMs: Long,
    val exists: Boolean,
)
