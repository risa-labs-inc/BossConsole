package ai.rever.boss.sharing

import ai.rever.boss.utils.SystemUtils
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal data class AppViewerPage(
    val url: String,
    val close: () -> Unit,
    val rawFrames: AppRawFrameMailbox = AppRawFrameMailbox(),
)

/** Host-only capabilities are signed into geometry; old viewers can ignore them. */
internal fun appSharingHostConfig(config: JsonObject): JsonObject {
    val controls =
        if (SystemUtils.isMacOS) {
            listOf("restore", "exit-fullscreen")
        } else {
            config["windowId"]
                ?.jsonPrimitive
                ?.content
                ?.let {
                    ai.rever.boss.window.OwnedWindowControls
                        .capabilities(it)
                }.orEmpty()
        }
    return buildJsonObject {
        config.forEach { (key, value) -> if (key != "windowControls") put(key, value) }
        if (controls.isNotEmpty()) put("windowControls", buildJsonArray { controls.forEach { add(it) } })
    }
}

/** Private loopback pages proxy only one admitted peer, never a general host-token API. */
internal class AppSharingAssets(
    private val now: () -> Long = System::nanoTime,
) : AutoCloseable {
    private data class Page(
        val config: JsonObject,
        val host: Boolean,
        val request: suspend (JsonObject) -> JsonObject,
        val lastAccess: AtomicLong,
        val rawFrames: AppRawFrameMailbox,
        val rawReader: AppRawFrameDelivery.Page?,
    ) : AutoCloseable {
        override fun close() {
            rawFrames.close()
            rawReader?.close()
        }
    }

    private val pages = ConcurrentHashMap<String, Page>()
    private val rawDelivery = AppRawFrameDelivery()
    private var closed = false
    private val executor =
        Executors.newFixedThreadPool(4) {
            Thread(it, "boss-app-share-assets").apply { isDaemon = true }
        }
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 16).also {
            it.executor = executor
            it.createContext("/", ::handle)
            it.start()
        }
    private val origin get() = "http://127.0.0.1:${server.address.port}"
    private val reaper =
        Executors
            .newSingleThreadScheduledExecutor {
                Thread(it, "boss-app-share-expiry").apply { isDaemon = true }
            }.also { it.scheduleWithFixedDelay(::evictIdlePages, 30, 30, TimeUnit.SECONDS) }

    private fun evictIdlePages() {
        val cutoff = now() - TimeUnit.MINUTES.toNanos(2)
        pages.entries.removeIf {
            val expired = it.value.lastAccess.get() < cutoff
            if (expired) it.value.close()
            expired
        }
    }

    @Synchronized
    fun open(
        config: JsonObject,
        host: Boolean,
        request: suspend (JsonObject) -> JsonObject,
    ): AppViewerPage {
        check(!closed) { "Viewer server is closed" }
        evictIdlePages()
        check(pages.values.count { it.host == host } < 16) { "Too many sharing windows or viewers" }
        val token = randomSecret()
        val root = "$origin/$token/"
        val rawFrames = AppRawFrameMailbox()
        pages[token] =
            Page(
                buildJsonObject {
                    config.forEach { (key, value) -> put(key, value) }
                    put("rpcUrl", root + "rpc")
                    put("rpcToken", token)
                    if (host) put("rawFrameUrl", root + "raw-frame")
                },
                host,
                request,
                AtomicLong(now()),
                rawFrames,
                if (host) rawDelivery.newPage() else null,
            )
        return AppViewerPage(
            root + if (host) "host.html" else "viewer.html",
            close = {
                pages.remove(token)?.close()
            },
            rawFrames = rawFrames,
        )
    }

    private fun handle(exchange: HttpExchange) {
        var transferred = false
        try {
            transferred = route(exchange)
        } catch (error: AssetHttpException) {
            respond(exchange, error.status, "application/json", "{\"error\":\"unauthorized\"}".toByteArray())
        } catch (error: AppSharingException) {
            val reason = error.reason.takeIf { it.matches(Regex("[a-z_]{1,80}")) } ?: "request_failed"
            val code =
                error.status?.takeIf { it in 400..599 } ?: when (reason) {
                    "unauthorized", "sign_in_required", "account_changed" -> 401
                    "approval_required" -> 403
                    "conflict", "publication_pending" -> 409
                    else -> 503
                }
            runCatching { respond(exchange, code, "application/json", "{\"error\":\"$reason\"}".toByteArray()) }
        } catch (_: java.io.IOException) {
            runCatching {
                respond(exchange, 503, "application/json", "{\"error\":\"network_unavailable\"}".toByteArray())
            }
        } catch (_: Exception) {
            runCatching { respond(exchange, 400, "application/json", "{\"error\":\"request_failed\"}".toByteArray()) }
        } finally {
            if (!transferred) exchange.close()
        }
    }

    private fun route(exchange: HttpExchange): Boolean {
        evictIdlePages()
        checkRequest(exchange.requestHeaders.getFirst("Host") == "127.0.0.1:${server.address.port}", 403)
        val parts =
            exchange.requestURI.rawPath
                .removePrefix("/")
                .split('/')
        checkRequest(parts.size == 2, 404)
        val token = parts[0]
        val page = pages[token] ?: throw AssetHttpException(404)
        if (parts[1] == "raw-frame") return serveRawFrame(exchange, token, page)
        when (parts[1]) {
            "rpc" -> serveRpc(exchange, token, page)
            else -> serveAsset(exchange, token, page, parts[1])
        }
        return false
    }

    private fun serveRawFrame(
        exchange: HttpExchange,
        token: String,
        page: Page,
    ): Boolean {
        val supplied = exchange.requestHeaders.getFirst("X-Boss-App-Token") ?: ""
        checkRequest(
            page.host && exchange.requestMethod == "POST" &&
                exchange.requestHeaders.getFirst("Origin") == origin &&
                MessageDigest.isEqual(supplied.toByteArray(), token.toByteArray()),
            403,
        )
        val rate = exchange.requestHeaders.getFirst("X-Boss-App-Frame-Rate")
        checkRequest(rate == null || rate == "30" || rate == "60", 400)
        val format = exchange.requestHeaders.getFirst("X-Boss-App-Pixel-Format") ?: "BGRA"
        checkRequest(format == "BGRA" || format == "NV12", 400)
        val wait = exchange.requestHeaders.getFirst("X-Boss-App-Wait")
        checkRequest(wait == null || wait == "true", 400)
        if (wait == "true") return serveAwaitedFrame(exchange, token, page, rate?.toInt() ?: 30, format)
        page.lastAccess.set(now())
        page.rawFrames.requestFrameRate(rate?.toInt() ?: 30)
        page.rawFrames.requestPixelFormat(format)
        val after = exchange.requestHeaders.getFirst("X-Boss-App-After")?.toLongOrNull()
        serveRawSnapshot(exchange, page.rawFrames.latest(), after)
        return false
    }

    /** Only negotiated readers leave the four HTTP workers; legacy publishers retain polling. */
    private fun serveAwaitedFrame(
        exchange: HttpExchange,
        token: String,
        page: Page,
        rate: Int,
        format: String,
    ): Boolean {
        val lengths = exchange.requestHeaders["Content-Length"]
        checkRequest(
            exchange.requestHeaders["Transfer-Encoding"] == null &&
                (lengths == null || lengths == listOf("0")),
            400,
        )
        val after = exchange.requestHeaders.getFirst("X-Boss-App-After")?.toLongOrNull()
        checkRequest(after != null && after in -1..9_007_199_254_740_991L, 400)
        val sequence = checkNotNull(after)
        checkRequest(sequence <= page.rawFrames.latest().sequence, 400)
        exchange.responseHeaders.set("X-Boss-App-Wait", "true")
        val reader = checkNotNull(page.rawReader)
        val admitted =
            reader.submit(exchange) {
                if (pages[token] === page) {
                    // Keep preferences ordered with their response, not a delayed HTTP handler.
                    page.lastAccess.set(now())
                    page.rawFrames.requestFrameRate(rate)
                    page.rawFrames.requestPixelFormat(format)
                    val latest = page.rawFrames.awaitNext(sequence)
                    if (pages[token] === page) serveRawSnapshot(exchange, latest, sequence)
                }
            }
        val error =
            when (admitted) {
                AppRawFrameDelivery.Admission.ACCEPTED -> null
                AppRawFrameDelivery.Admission.DUPLICATE -> 409
                AppRawFrameDelivery.Admission.SATURATED -> 429
                AppRawFrameDelivery.Admission.RETIRED -> 404
            }
        if (error != null) throw AssetHttpException(error)
        return true
    }

    private fun serveRpc(
        exchange: HttpExchange,
        token: String,
        page: Page,
    ) {
        val supplied = exchange.requestHeaders.getFirst("X-Boss-App-Token") ?: ""
        val sameOrigin = exchange.requestHeaders.getFirst("Origin") == origin
        val validToken = MessageDigest.isEqual(supplied.toByteArray(), token.toByteArray())
        if (exchange.requestMethod != "POST" || !sameOrigin || !validToken) throw AssetHttpException(403)
        page.lastAccess.set(now())
        val bytes = exchange.requestBody.use { it.readNBytes(1024 * 1024 + 1) }
        require(bytes.size <= 1024 * 1024)
        val request = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        val response = runBlocking { page.request(request) }
        if (!page.host && request["action"]?.jsonPrimitive?.contentOrNull == "mediaClose") {
            page.close()
            pages.remove(token, page)
        }
        respond(exchange, 200, "application/json", response.toString().toByteArray())
    }

    private fun serveAsset(
        exchange: HttpExchange,
        token: String,
        page: Page,
        name: String,
    ) {
        val validName = name.matches(Regex("[a-z][a-z0-9-]*[.](html|mjs|js|css)"))
        val expectedHtml = if (page.host) "host.html" else "viewer.html"
        checkRequest(exchange.requestMethod == "GET" && validName, 404)
        checkRequest(!name.endsWith(".html") || name == expectedHtml, 404)
        var bytes =
            javaClass.getResourceAsStream("/app-sharing/$name")?.use { it.readBytes() }
                ?: throw AssetHttpException(404)
        page.lastAccess.set(now())
        if (name.endsWith(".html")) {
            val config =
                page.config
                    .toString()
                    .replace("<", "\\u003c")
                    .replace("\u2028", "\\u2028")
                    .replace("\u2029", "\\u2029")
            val bootstrap = "<head><script nonce=\"$token\">window.__bossAppShareConfig=$config;</script>"
            bytes = bytes.decodeToString().replace("<head>", bootstrap).toByteArray()
        }
        val type =
            when (name.substringAfterLast('.')) {
                "html" -> "text/html; charset=utf-8"
                "css" -> "text/css"
                else -> "text/javascript"
            }
        exchange.responseHeaders.set(
            "Content-Security-Policy",
            "default-src 'none'; script-src 'self' 'nonce-$token'; style-src 'self' 'unsafe-inline'; " +
                "connect-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; worker-src 'self' blob:; " +
                "frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
        )
        respond(exchange, 200, type, bytes)
    }

    private class AssetHttpException(
        val status: Int,
    ) : IllegalArgumentException()

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        pages.values.forEach { it.close() }
        pages.clear()
        rawDelivery.close()
        reaper.shutdownNow()
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        private fun serveRawSnapshot(
            exchange: HttpExchange,
            latest: AppRawFrameMailbox.Snapshot,
            after: Long?,
        ) {
            exchange.responseHeaders.set("X-Boss-App-Sequence", latest.sequence.toString())
            val frame = latest.frame
            if (frame == null || after == latest.sequence) {
                exchange.responseHeaders.set("X-Boss-App-Empty", (frame == null).toString())
                exchange.responseHeaders.set("Cache-Control", "no-store")
                exchange.sendResponseHeaders(204, -1)
                return
            }
            exchange.responseHeaders.set("X-Boss-App-Pixel-Format", frame.pixels.format)
            exchange.responseHeaders.set("X-Boss-App-Width", frame.pixels.width.toString())
            exchange.responseHeaders.set("X-Boss-App-Height", frame.pixels.height.toString())
            exchange.responseHeaders.set("X-Boss-App-Revision", frame.geometryRevision.toString())
            respond(exchange, 200, "application/octet-stream", frame.pixels.bgra)
        }

        private fun respond(
            exchange: HttpExchange,
            code: Int,
            type: String,
            bytes: ByteArray,
        ) {
            exchange.responseHeaders.set("Content-Type", type)
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.responseHeaders.set("Referrer-Policy", "no-referrer")
            exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        private fun checkRequest(
            valid: Boolean,
            status: Int,
        ) {
            if (!valid) throw AssetHttpException(status)
        }

        fun randomSecret(): String =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    }
}
