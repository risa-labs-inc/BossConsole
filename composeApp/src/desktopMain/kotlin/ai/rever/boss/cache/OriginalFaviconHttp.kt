package ai.rever.boss.cache

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.URI

internal const val MAX_ORIGINAL_FAVICON_BYTES = 256 * 1024

internal fun isPublicFaviconAddress(address: InetAddress): Boolean {
    val bytes = address.address
    val reserved = if (bytes.size == 4) isReservedFaviconIpv4(bytes) else isReservedFaviconIpv6(bytes)
    return !reserved && !address.isAnyLocalAddress && !address.isLoopbackAddress &&
        !address.isLinkLocalAddress && !address.isSiteLocalAddress && !address.isMulticastAddress
}

private fun isReservedFaviconIpv4(bytes: ByteArray): Boolean {
    val first = bytes[0].toInt() and 255
    val second = bytes[1].toInt() and 255
    val third = bytes[2].toInt() and 255
    return when (first) {
        0, in 224..255 -> true
        100 -> second in 64..127
        192 -> second == 0 && third in listOf(0, 2)
        198 -> second in 18..19 || (second == 51 && third == 100)
        203 -> second == 0 && third == 113
        else -> false
    }
}

private fun isReservedFaviconIpv6(bytes: ByteArray): Boolean {
    val first = bytes[0].toInt() and 255
    val second = bytes[1].toInt() and 255
    val third = bytes[2].toInt() and 255
    val fourth = bytes[3].toInt() and 255
    // Require direct global unicast. Do not let IPv4 tunnels/translation (6to4, Teredo,
    // NAT64) bypass the IPv4 policy, and reject IPv6 documentation space.
    return first !in 32..63 || (first == 32 && second == 2) ||
        (first == 32 && second == 1 && ((third == 0 && fourth == 0) || (third == 13 && fourth == 184)))
}

/** The numeric route is separate from TLS identity; certificate verification still uses the site. */
internal fun pinnedOriginalFaviconClient(host: String): HttpClient =
    HttpClient(CIO) {
        engine { https.serverName = host }
        configureOriginalFaviconClient()
    }

internal fun HttpClientConfig<*>.configureOriginalFaviconClient() {
    // Automatic redirects bypass destination checks. Handle at most three redirects explicitly
    // so legitimate public CDNs work without allowing a favicon to redirect to a private service.
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = 2500
        connectTimeoutMillis = 2500
        socketTimeoutMillis = 2500
    }
}

/** Image bodies must fit in full; for HTML, the bounded prefix is enough to discover head links. */
internal suspend fun fetchOriginalFavicon(
    client: HttpClient?,
    url: String,
    htmlPrefix: Boolean,
    clientForHost: ((String) -> HttpClient)? = null,
    resolveAddresses: suspend (String) -> List<InetAddress> = ::resolveOriginalFaviconHost,
): ByteArray? {
    var currentUrl = url
    repeat(4) {
        val uri = runCatching { URI(currentUrl) }.getOrNull()
        val safe =
            uri?.takeIf {
                it.scheme in listOf("http", "https") && it.userInfo == null &&
                    it.host?.let(::isPublicFaviconHost) == true
            }
        val addresses = safe?.host?.let { resolveAddresses(it) }.orEmpty()
        val address = addresses.takeIf { it.isNotEmpty() && it.all(::isPublicFaviconAddress) }?.firstOrNull()
        val reply =
            if (safe != null && address != null) {
                if (clientForHost != null) {
                    requestOriginalFavicon(clientForHost(safe.host), safe, address, htmlPrefix)
                } else if (client == null) {
                    pinnedOriginalFaviconClient(safe.host).use { requestOriginalFavicon(it, safe, address, htmlPrefix) }
                } else {
                    requestOriginalFavicon(client, safe, address, htmlPrefix)
                }
            } else {
                OriginalHttpReply()
            }
        if (reply.redirect == null) return reply.bytes
        currentUrl =
            runCatching {
                safe?.let { start ->
                    start.resolve(reply.redirect).takeIf { start.scheme != "https" || it.scheme == "https" }?.toString()
                }
            }.getOrNull().orEmpty()
    }
    return null
}

private class OriginalHttpReply(
    val bytes: ByteArray? = null,
    val redirect: String? = null,
)

private suspend fun requestOriginalFavicon(
    client: HttpClient,
    uri: URI,
    address: InetAddress,
    htmlPrefix: Boolean,
): OriginalHttpReply =
    // CIO sees only a numeric IP, so it cannot resolve the hostname a second time. Preserve
    // Host for virtual hosting; the production engine's SNI/certificate name is set separately.
    client
        .prepareGet(uri.toString()) {
            url.host = address.hostAddress.let { if (':' in it) "[$it]" else it }
            header(HttpHeaders.Host, uri.rawAuthority)
        }.execute { response ->
            when {
                response.status.value in 300..399 -> {
                    OriginalHttpReply(redirect = response.headers[HttpHeaders.Location])
                }

                response.status != HttpStatusCode.OK ||
                    (!htmlPrefix && (response.contentLength() ?: 0) > MAX_ORIGINAL_FAVICON_BYTES) -> {
                    OriginalHttpReply()
                }

                else -> {
                    OriginalHttpReply(readOriginalFaviconBody(response.bodyAsChannel(), htmlPrefix))
                }
            }
        }

private suspend fun readOriginalFaviconBody(
    channel: io.ktor.utils.io.ByteReadChannel,
    htmlPrefix: Boolean,
): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var oversized = false
    var count = channel.readAvailable(buffer)
    while (count >= 0) {
        val remaining = MAX_ORIGINAL_FAVICON_BYTES - output.size()
        if (count > remaining) {
            channel.cancel(null)
            oversized = !htmlPrefix
            if (htmlPrefix) output.write(buffer, 0, remaining)
            break
        }
        output.write(buffer, 0, count)
        count = channel.readAvailable(buffer)
    }
    return if (oversized) null else output.toByteArray()
}

/** One client per TLS identity for a discovery attempt, including redirect hops. */
internal class OriginalFaviconHttpSession(
    private val createClient: (String) -> HttpClient = ::pinnedOriginalFaviconClient,
    private val resolveAddresses: suspend (String) -> List<InetAddress> = ::resolveOriginalFaviconHost,
) : AutoCloseable {
    private val clients = mutableMapOf<String, HttpClient>()

    suspend fun fetch(url: String): ByteArray? {
        val uri = URI(url)
        return fetchOriginalFavicon(
            null,
            url,
            htmlPrefix = uri.rawPath == "/" && uri.rawQuery == null,
            clientForHost = { clients.getOrPut(it) { createClient(it) } },
            resolveAddresses = resolveAddresses,
        )
    }

    override fun close() {
        clients.values.forEach { it.close() }
    }
}
