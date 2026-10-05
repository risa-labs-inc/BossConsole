package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
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
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.util.Base64
import javax.swing.text.MutableAttributeSet
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.parser.ParserDelegator
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Original site icons retain app-specific artwork that a parent-domain favicon cannot supply. */
internal object OriginalFaviconSource {
    private const val MAX_DIMENSION = 1024
    private val attempts = OriginalFaviconAttempts()
    private val semaphore = Semaphore(3)

    suspend fun sharperIcon(
        url: String?,
        page: TabIcon.Image?,
        fetch: (suspend (String) -> ByteArray?)? = null,
    ): TabIcon.Image? =
        withContext(Dispatchers.IO) {
            val origin = originFor(url) ?: return@withContext null
            if (FaviconHost.of(origin)?.let(::isPublicFaviconHost) != true) return@withContext null
            val (attempt, owner) = attempts.acquire(origin) ?: return@withContext null
            val result =
                if (owner) {
                    try {
                        semaphore
                            .withPermit {
                                withTimeoutOrNull(5000) {
                                    if (fetch != null) {
                                        resolve(origin, page, fetch)
                                    } else {
                                        OriginalFaviconHttpSession().use { resolve(origin, page, it::fetch) }
                                    }
                                }
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
                HqFaviconDiskCache.save(HqFaviconDiskCache.originalKeyFor(origin), matching.bitmap.toAwtImage())
            }
            matching?.icon
        }

    /** No page path, query, fragment or credentials are sent just to discover an icon. */
    internal fun originFor(url: String?): String? =
        runCatching {
            val uri = URI(url ?: return null)
            val scheme = uri.scheme?.lowercase()
            if (scheme !in listOf("http", "https") || uri.host == null) return null
            val port = uri.port.takeUnless { it == if (scheme == "https") 443 else 80 } ?: -1
            URI(scheme, null, uri.host.lowercase(), port, "/", null, null).toString()
        }.getOrNull()

    internal class OriginalIcon(
        bitmap: ImageBitmap,
    ) {
        // At most 256px is retained in the attempt map or saved in the HQ cache (~16MB at its
        // 64-origin bound), even when the source asset is a 1024px apple-touch icon.
        val bitmap = boundedOriginalFavicon(bitmap)
        val icon = TabIcon.Image(BitmapPainter(this.bitmap))
    }

    /** Sources are injected so identity, ICO/SVG decoding and URL resolution need no network tests. */
    internal suspend fun resolve(
        origin: String,
        page: TabIcon.Image?,
        fetch: suspend (String) -> ByteArray?,
    ): OriginalIcon? {
        val faviconUrl = URI(origin).resolve("favicon.ico").toString()
        var best = matchingIcon(page, fetch(faviconUrl))
        if (hasSharpCardFavicon(best?.icon)) {
            return best
        }
        val html = fetch(origin)?.toString(Charsets.UTF_8)
        if (html != null) {
            for (url in iconLinks(origin, html).filterNot { it == faviconUrl }.take(4)) {
                val candidate = matchingIcon(page, fetch(url))
                if (candidate != null && (best == null || sharperMatchingFavicon(best.icon, candidate.icon) === candidate.icon)) {
                    best = candidate
                }
                if (hasSharpCardFavicon(best?.icon)) {
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

    // Swing's parser can throw unchecked exceptions on malformed remote HTML. Discovery is
    // optional: keep any links/icon already found, while cancellation must still propagate.
    @Suppress("TooGenericExceptionCaught")
    internal fun iconLinks(
        origin: String,
        html: String,
        parse: (String, HTMLEditorKit.ParserCallback) -> Unit = { source, callback ->
            ParserDelegator().parse(StringReader(source), callback, true)
        },
    ): List<String> {
        val links = mutableListOf<String>()
        val callback =
            object : HTMLEditorKit.ParserCallback() {
                override fun handleStartTag(
                    tag: HTML.Tag,
                    attributes: MutableAttributeSet,
                    position: Int,
                ) {
                    handleSimpleTag(tag, attributes, position)
                }

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
                        if (uri != null && sameFaviconOrigin(URI(origin), uri)) {
                            links.add(uri.toString())
                        }
                    }
                }
            }
        try {
            parse(html, callback)
        } catch (e: IOException) {
            logOriginalFaviconParseFailure(e)
        } catch (e: RuntimeException) {
            logOriginalFaviconParseFailure(e)
        }
        return links.distinct()
    }

    internal fun decode(bytes: ByteArray): OriginalIcon? =
        runCatching {
            require(bytes.size <= MAX_ORIGINAL_FAVICON_BYTES)
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
            Surface.makeRasterN32Premul(FAVICON_TARGET_SIZE, FAVICON_TARGET_SIZE).use { surface ->
                surface.canvas.clear(0)
                svg.setContainerSize(FAVICON_TARGET_SIZE.toFloat(), FAVICON_TARGET_SIZE.toFloat())
                svg.render(surface.canvas)
                surface.makeImageSnapshot().use { OriginalIcon(it.toComposeImageBitmap()) }
            }
        }

    fun clearAttempts() {
        attempts.clear()
    }

    fun close() {
        closeOriginalFaviconDns()
    }
}

private fun logOriginalFaviconParseFailure(error: Exception) {
    if (error is kotlinx.coroutines.CancellationException) throw error
    BossLogger.forComponent("OriginalFaviconSource").debug(
        LogCategory.BROWSER,
        "Favicon HTML parsing stopped; retaining discovered artwork",
        mapOf("errorType" to error.javaClass.simpleName),
    )
}

/** HTML must not make Home fetch another service, including one on another port or a local IP. */
private fun sameFaviconOrigin(
    origin: URI,
    candidate: URI,
): Boolean =
    candidate.userInfo == null && candidate.scheme.equals(origin.scheme, true) &&
        candidate.host?.equals(origin.host, true) == true && faviconPort(candidate) == faviconPort(origin)

private fun faviconPort(uri: URI): Int =
    if (uri.port >= 0) {
        uri.port
    } else if (uri.scheme == "https") {
        443
    } else {
        80
    }

private fun boundedOriginalFavicon(bitmap: ImageBitmap): ImageBitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= 256) return bitmap
    val scale = 256.0 / longest
    val reduced =
        BufferedImage(
            maxOf(1, (bitmap.width * scale).toInt()),
            maxOf(1, (bitmap.height * scale).toInt()),
            BufferedImage.TYPE_INT_ARGB,
        )
    reduced.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        drawImage(bitmap.toAwtImage(), 0, 0, reduced.width, reduced.height, null)
        dispose()
    }
    return reduced.toComposeImageBitmap()
}

/** Skia's SVG loader has no image-resource provider; preserve a full-canvas embedded raster logo. */
private fun embeddedSvgBitmap(bytes: ByteArray): ByteArray? =
    runCatching {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
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

internal class CardFaviconSources(
    val pageIcon: (String?) -> TabIcon.Image? = { it?.let(FaviconCache::loadFavicon) },
    val hostIcon: suspend (String?) -> TabIcon.Image? = {
        HighQualityFaviconService.hostIcon(it, refreshSmallIcon = true)
    },
    val qualityUpgrade: suspend (String?, TabIcon.Image) -> TabIcon.Image = { pageUrl, page ->
        upgradeCachedFavicon(pageUrl, page)
    },
    val originalIcon: suspend (String?, TabIcon.Image?) -> TabIcon.Image? = { pageUrl, page ->
        OriginalFaviconSource.sharperIcon(pageUrl, page)
    },
)

/** Home may fetch original artwork when the cached image is too small for its larger cards. */
internal suspend fun resolveHighQualityCardFavicon(
    url: String?,
    standardCacheKey: String?,
    sources: CardFaviconSources = CardFaviconSources(),
): TabIcon.Image? =
    HighQualityFaviconService.resolve(
        url = url,
        standardCacheKey = standardCacheKey,
        pageIcon = sources.pageIcon,
        hostGuess = {
            sources.originalIcon(it, null) ?: sources.hostIcon(it)
        },
        qualityUpgrade = { pageUrl, page ->
            val cached = sources.qualityUpgrade(pageUrl, page)
            if (hasSharpCardFavicon(cached)) {
                cached
            } else {
                sources.originalIcon(pageUrl, cached) ?: sharperMatchingFavicon(cached, sources.hostIcon(pageUrl))
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
        const val MAX_ATTEMPTS = 64
    }
}
