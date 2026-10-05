package ai.rever.boss.cache

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

internal const val MAX_ORIGINAL_FAVICON_BYTES = 256 * 1024

/** Reject public-looking domains that currently resolve to a local/private service as well. */
internal fun originalFaviconAddressAllowed(host: String): Boolean =
    try {
        isPublicFaviconHost(host) && InetAddress.getAllByName(host).all(::isPublicFaviconAddress)
    } catch (_: UnknownHostException) {
        false
    }

internal fun isPublicFaviconAddress(address: InetAddress): Boolean {
    val bytes = address.address
    val first = bytes[0].toInt() and 255
    val second = bytes[1].toInt() and 255
    val reserved =
        if (bytes.size == 4) {
            first == 0 || first >= 224 || (first == 100 && second in 64..127) || (first == 198 && second in 18..19)
        } else {
            first and 254 == 252 // IPv6 unique-local fc00::/7.
        }
    return !reserved && !address.isAnyLocalAddress && !address.isLoopbackAddress &&
        !address.isLinkLocalAddress && !address.isSiteLocalAddress && !address.isMulticastAddress
}

internal fun HttpClientConfig<*>.configureOriginalFaviconClient() {
    // Automatic redirects bypass destination checks. Handle at most three redirects explicitly
    // so legitimate public CDNs work without allowing a favicon to redirect to a private service.
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = 2500
        connectTimeoutMillis = 2500
    }
}

/** Image bodies must fit in full; for HTML, the bounded prefix is enough to discover head links. */
internal suspend fun fetchOriginalFavicon(
    client: HttpClient,
    url: String,
    htmlPrefix: Boolean,
    addressAllowed: (String) -> Boolean = ::originalFaviconAddressAllowed,
): ByteArray? {
    var currentUrl = url
    repeat(4) {
        val uri = runCatching { URI(currentUrl) }.getOrNull()
        val safe =
            uri?.takeIf {
                it.scheme in listOf("http", "https") && it.userInfo == null &&
                    it.host?.let { host -> isPublicFaviconHost(host) && addressAllowed(host) } == true
            }
        val reply = safe?.let { requestOriginalFavicon(client, it.toString(), htmlPrefix) } ?: OriginalHttpReply()
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
    url: String,
    htmlPrefix: Boolean,
): OriginalHttpReply =
    client.prepareGet(url).execute { response ->
        when {
            response.status.value in 300..399 -> OriginalHttpReply(redirect = response.headers[HttpHeaders.Location])

            response.status != HttpStatusCode.OK ||
                (!htmlPrefix && (response.contentLength() ?: 0) > MAX_ORIGINAL_FAVICON_BYTES) -> OriginalHttpReply()

            else -> OriginalHttpReply(readOriginalFaviconBody(response.bodyAsChannel(), htmlPrefix))
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
