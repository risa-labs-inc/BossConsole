package ai.rever.boss.platform

/**
 * The OS command that hands a web URL to the user's default browser.
 *
 * Pure: it returns the argument list and starts nothing, so what is about to be launched is testable
 * without launching it.
 *
 * On Windows this is `rundll32 url.dll,FileProtocolHandler <url>`, not `cmd /c start "" <url>`. cmd parses the
 * whole line, so `&`, `|`, `%` and `^` in a URL are commands and variables to it, and the JVM only quotes an
 * argument that contains a space. A sign-in URL is full of `&`, so cmd cut it at the first one and read the rest
 * as another command. rundll32 takes the target as data and hands it to the shell's own open verb, which is what
 * a click on a link does.
 */
internal object SystemOpenCommand {
    /** The command for [url] on the OS named [osName] (`os.name`, any case), or null when it is not a plain web URL. */
    fun forUrl(
        osName: String,
        url: String,
    ): List<String>? {
        val os = osName.lowercase()
        return when {
            !isPlainWebUrl(url) -> null
            os.contains("mac") -> listOf("open", url)
            os.contains("windows") -> listOf("rundll32.exe", "url.dll,FileProtocolHandler", url)
            os.contains("linux") -> listOf("xdg-open", url)
            else -> null
        }
    }

    /** http or https, a host, no whitespace or control characters, and a sane length. */
    internal fun isPlainWebUrl(url: String): Boolean =
        url.length in MIN_URL..MAX_URL &&
            url.none { it.isWhitespace() || it.isISOControl() } &&
            (url.startsWith("http://") || url.startsWith("https://")) &&
            url.substringAfter("://").isNotEmpty() &&
            !url.substringAfter("://").startsWith("/")

    private const val MIN_URL = 8
    private const val MAX_URL = 8192
}
