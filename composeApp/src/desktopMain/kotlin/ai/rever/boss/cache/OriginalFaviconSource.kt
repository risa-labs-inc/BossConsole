package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.URI
import java.util.Base64
import javax.swing.text.MutableAttributeSet
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.parser.ParserDelegator
import javax.xml.parsers.DocumentBuilderFactory

/** Original site icons retain app-specific artwork that a parent-domain favicon cannot supply. */
internal object OriginalFaviconSource {
    private const val MAX_BYTES = 256 * 1024
    private const val MAX_DIMENSION = 1024
    private const val SVG_SIZE = 128
    private val attempts = OriginalFaviconAttempts()
    private val semaphore = Semaphore(3)
    private val clientLazy =
        lazy {
            HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 2500
                    connectTimeoutMillis = 2500
                }
                expectSuccess = false
            }
        }
    private val client by clientLazy

    suspend fun sharperIcon(
        url: String?,
        page: TabIcon.Image?,
        fetch: suspend (String) -> ByteArray? = ::fetch,
    ): TabIcon.Image? =
        withContext(Dispatchers.IO) {
            val origin = originFor(url) ?: return@withContext null
            val (attempt, owner) = attempts.acquire(origin) ?: return@withContext null
            val result =
                if (owner) {
                    try {
                        semaphore
                            .withPermit {
                                withTimeoutOrNull(5000) { resolve(origin, page, fetch) }
                            }.also { attempt.result.complete(it) }
                    } finally {
                        // A cancelled or failed owner must always release the other cards.
                        if (!attempt.result.isCompleted) {
                            attempt.result.complete(null)
                            attempts.discard(origin, attempt)
                        }
                    }
                } else {
                    attempt.result.await()
                }
            val matching = result?.takeIf { page == null || sharperMatchingFavicon(page, it.icon) === it.icon }
            if (matching != null && owner) {
                val host = FaviconHost.of(url) ?: return@withContext matching.icon
                HqFaviconDiskCache.save(HqFaviconDiskCache.keyFor(host), matching.bitmap.toAwtImage())
            }
            matching?.icon
        }

    /** No page path, query, fragment or credentials are sent just to discover an icon. */
    internal fun originFor(url: String?): String? =
        runCatching {
            val uri = URI(url ?: return null)
            if (uri.scheme !in listOf("http", "https") || uri.host == null) return null
            URI(uri.scheme, null, uri.host, uri.port, "/", null, null).toString()
        }.getOrNull()

    internal class OriginalIcon(
        val bitmap: androidx.compose.ui.graphics.ImageBitmap,
    ) {
        val icon = TabIcon.Image(BitmapPainter(bitmap))
    }

    /** Sources are injected so identity, ICO/SVG decoding and URL resolution need no network tests. */
    internal suspend fun resolve(
        origin: String,
        page: TabIcon.Image?,
        fetch: suspend (String) -> ByteArray?,
    ): OriginalIcon? {
        val faviconUrl = URI(origin).resolve("favicon.ico").toString()
        var best = matchingIcon(page, fetch(faviconUrl))
        if ((
                best
                    ?.icon
                    ?.painter
                    ?.intrinsicSize
                    ?.minDimension ?: 0f
            ) >= 64f
        ) {
            return best
        }
        val html = fetch(origin)?.toString(Charsets.UTF_8)
        if (html != null) {
            for (url in iconLinks(origin, html).filterNot { it == faviconUrl }.take(4)) {
                val candidate = matchingIcon(best?.icon ?: page, fetch(url))
                if (candidate != null) best = candidate
                if ((
                        best
                            ?.icon
                            ?.painter
                            ?.intrinsicSize
                            ?.minDimension ?: 0f
                    ) >= 64f
                ) {
                    break
                }
            }
        }
        return best
    }

    private fun matchingIcon(
        page: TabIcon.Image?,
        bytes: ByteArray?,
    ): OriginalIcon? {
        val candidate = bytes?.let(::decode)
        return candidate?.takeIf { page == null || sharperMatchingFavicon(page, it.icon) === it.icon }
    }

    internal fun iconLinks(
        origin: String,
        html: String,
    ): List<String> {
        val links = mutableListOf<String>()
        ParserDelegator().parse(
            StringReader(html),
            object : HTMLEditorKit.ParserCallback() {
                override fun handleSimpleTag(
                    tag: HTML.Tag,
                    attributes: MutableAttributeSet,
                    position: Int,
                ) {
                    if (tag != HTML.Tag.LINK) return
                    val rel =
                        attributes
                            .getAttribute(HTML.Attribute.REL)
                            ?.toString()
                            .orEmpty()
                            .lowercase()
                            .split(' ')
                    val href = attributes.getAttribute(HTML.Attribute.HREF)?.toString()
                    if (href != null && rel.any { it == "icon" || it == "apple-touch-icon" }) {
                        val uri = runCatching { URI(origin).resolve(href) }.getOrNull()
                        if (uri?.scheme in listOf("http", "https") && uri?.host != null && uri.userInfo == null) {
                            links.add(uri.toString())
                        }
                    }
                }
            },
            true,
        )
        return links.distinct()
    }

    internal fun decode(bytes: ByteArray): OriginalIcon? =
        runCatching {
            require(bytes.size <= MAX_BYTES)
            Data.makeFromBytes(bytes).use { data ->
                if (bytes.toString(Charsets.UTF_8).trimStart().startsWith("<")) {
                    embeddedSvgBitmap(bytes)?.let(::decode) ?: decodeSvg(data)
                } else {
                    Codec.makeFromData(data).use { codec ->
                        require(codec.width in 1..MAX_DIMENSION && codec.height in 1..MAX_DIMENSION)
                    }
                    Image.makeFromEncoded(bytes).use { OriginalIcon(it.toComposeImageBitmap()) }
                }
            }
        }.getOrNull()

    private fun decodeSvg(data: Data): OriginalIcon =
        SVGDOM(data).use { svg ->
            requireNotNull(svg.root)
            Surface.makeRasterN32Premul(SVG_SIZE, SVG_SIZE).use { surface ->
                surface.canvas.clear(0)
                svg.setContainerSize(SVG_SIZE.toFloat(), SVG_SIZE.toFloat())
                svg.render(surface.canvas)
                surface.makeImageSnapshot().use { OriginalIcon(it.toComposeImageBitmap()) }
            }
        }

    /** Bound the bytes while reading, including chunked responses without Content-Length. */
    private suspend fun fetch(url: String): ByteArray? =
        client.prepareGet(url).execute { response ->
            if (response.status != HttpStatusCode.OK || (response.contentLength() ?: 0) > MAX_BYTES) return@execute null
            val channel = response.bodyAsChannel()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!channel.isClosedForRead) {
                val count = channel.readAvailable(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_BYTES) {
                    channel.cancel(null)
                    return@execute null
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }

    fun clearAttempts() {
        attempts.clear()
    }

    fun close() {
        if (clientLazy.isInitialized()) client.close()
    }
}

/** Skia's SVG loader has no image-resource provider; preserve a full-canvas embedded raster logo. */
private fun embeddedSvgBitmap(bytes: ByteArray): ByteArray? =
    runCatching {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        val root = factory.newDocumentBuilder().parse(bytes.inputStream()).documentElement
        require(root.localName == "svg")
        val children = (0 until root.childNodes.length).map { root.childNodes.item(it) }
        val image = children.filterIsInstance<Element>().single()
        require(image.localName == "image")
        val rootAttributes = (0 until root.attributes.length).map { root.attributes.item(it).nodeName }
        val imageAttributes = (0 until image.attributes.length).map { image.attributes.item(it).nodeName }
        require(rootAttributes.all { it.startsWith("xmlns") || it in listOf("viewBox", "width", "height") })
        require(imageAttributes.all { it in listOf("x", "y", "width", "height", "href", "xlink:href") })
        val viewBox =
            root
                .getAttribute("viewBox")
                .trim()
                .split(Regex("[,\\s]+"))
                .map(String::toFloat)
        require(viewBox.size == 4 && viewBox[0] == 0f && viewBox[1] == 0f)
        require(image.getAttribute("x").ifEmpty { "0" }.toFloat() == 0f)
        require(image.getAttribute("y").ifEmpty { "0" }.toFloat() == 0f)
        require(image.getAttribute("width").toFloat() == viewBox[2])
        require(image.getAttribute("height").toFloat() == viewBox[3])
        val href = image.getAttribute("href").ifEmpty { image.getAttribute("xlink:href") }
        require(href.startsWith("data:image/png;base64,"))
        Base64.getDecoder().decode(href.substringAfter(','))
    }.getOrNull()

/** Home may fetch original artwork when the cached image is too small for its larger cards. */
internal suspend fun resolveHighQualityCardFavicon(
    url: String?,
    standardCacheKey: String?,
): TabIcon.Image? =
    HighQualityFaviconService.resolve(
        url = url,
        standardCacheKey = standardCacheKey,
        pageIcon = { it?.let(FaviconCache::loadFavicon) },
        hostGuess = {
            OriginalFaviconSource.sharperIcon(it, null)
                ?: HighQualityFaviconService.hostIcon(it, refreshSmallIcon = true)
        },
        qualityUpgrade = { pageUrl, page ->
            val cached = upgradeCachedFavicon(pageUrl, page)
            if (cached.painter.intrinsicSize.minDimension >= 64f) {
                cached
            } else {
                val original = OriginalFaviconSource.sharperIcon(pageUrl, cached)
                original ?: sharperMatchingFavicon(
                    cached,
                    HighQualityFaviconService.hostIcon(pageUrl, refreshSmallIcon = true),
                )
            }
        },
    )

/** Cards for multiple pages on one site await the same result instead of keeping a small fallback. */
internal class OriginalFaviconAttempts {
    class Attempt(
        val startedAt: Long,
    ) {
        val result = CompletableDeferred<OriginalFaviconSource.OriginalIcon?>()
    }

    private val entries = mutableMapOf<String, Attempt>()

    fun acquire(
        origin: String,
        now: Long = System.currentTimeMillis(),
    ): Pair<Attempt, Boolean>? =
        synchronized(entries) {
            entries.entries.removeAll { it.value.result.isCompleted && now - it.value.startedAt >= RETRY_MS }
            entries[origin]?.let { return@synchronized it to false }
            if (entries.size >= MAX_ATTEMPTS) {
                val oldest = entries.entries.filter { it.value.result.isCompleted }.minByOrNull { it.value.startedAt }
                if (oldest == null) return@synchronized null
                entries.remove(oldest.key)
            }
            val attempt = Attempt(now)
            entries[origin] = attempt
            attempt to true
        }

    fun discard(
        origin: String,
        attempt: Attempt,
    ) {
        synchronized(entries) { if (entries[origin] === attempt) entries.remove(origin) }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    private companion object {
        const val RETRY_MS = 10 * 60 * 1000L
        const val MAX_ATTEMPTS = 200
    }
}
