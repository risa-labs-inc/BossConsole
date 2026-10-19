package ai.rever.boss.sharing

import ai.rever.boss.config.JxBrowserConfig
import ai.rever.boss.plugin.browser.ChromiumToolkitPreload
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.teamdev.jxbrowser.engine.Engine
import com.teamdev.jxbrowser.engine.EngineOptions
import com.teamdev.jxbrowser.engine.RenderingMode
import com.teamdev.jxbrowser.net.callback.BeforeUrlRequestCallback
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Explicit opt-in: two WebRTC peers inside a fresh off-screen Chromium process.
 * It renders only synthetic canvas pixels; never opens/captures a user's window,
 * uses the application's browser profile, injects input, or contacts an SFU.
 * BOSS_TEST_APP_MEDIA_CHROMIUM_DIR points to already installed matching binaries
 * so this test does not invoke the host's automatic browser downloader.
 */
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_APP_MEDIA", matches = "1")
class AppSharingMediaSmokeTest {
    @TempDir
    lateinit var temporary: Path

    private val logger = BossLogger.forComponent("AppSharingMediaSmoke")

    @Test
    @Timeout(value = 150, unit = TimeUnit.SECONDS)
    fun `encrypted VP8 decodes synthetic pixels and rejects real frame tampering and replay`() {
        val chromium = chromiumDirectory()
        val profile = Files.createDirectory(temporary.resolve("chromium-profile"))
        MediaSmokeServer().use { fixture ->
            val engine = createEngine(chromium, profile)
            try {
                engine.network().set(
                    BeforeUrlRequestCallback::class.java,
                    BeforeUrlRequestCallback { params ->
                        if (params.urlRequest().url().startsWith(fixture.baseUrl)) {
                            BeforeUrlRequestCallback.Response.proceed()
                        } else {
                            BeforeUrlRequestCallback.Response.cancel()
                        }
                    },
                )
                val browser = engine.newBrowser()
                browser.resize(640, 480)
                browser.navigation().loadUrlAndWait(fixture.baseUrl + "smoke.html", Duration.ofSeconds(20))
                verifyReport(fixture.result.get(90, TimeUnit.SECONDS))
            } finally {
                // Cleanup must not hide a more useful media assertion or timeout.
                runCatching { engine.close() }.onFailure {
                    logger.warn(LogCategory.BROWSER, "Synthetic media engine cleanup failed", error = it)
                }
            }
        }
    }

    private fun chromiumDirectory(): Path {
        val chromium =
            Path.of(
                requireNotNull(System.getenv("BOSS_TEST_APP_MEDIA_CHROMIUM_DIR")) {
                    "Set BOSS_TEST_APP_MEDIA_CHROMIUM_DIR to existing matching Chromium binaries"
                },
            )
        require(Files.isDirectory(chromium)) { "Configured Chromium directory is missing" }
        require(JxBrowserConfig.licenseKey.isNotBlank()) {
            "Configure the existing JxBrowser license through local.properties"
        }
        return chromium
    }

    private fun createEngine(
        chromium: Path,
        profile: Path,
    ): Engine {
        // Match host startup: toolkit loading briefly swaps macOS malloc zones.
        // Loading before engine threads start narrows that native SIGTRAP race.
        ChromiumToolkitPreload.preload(chromium)
        return Engine.newInstance(
            EngineOptions
                .newBuilder(RenderingMode.OFF_SCREEN)
                .licenseKey(JxBrowserConfig.licenseKey)
                .chromiumDir(chromium)
                .userDataDir(profile)
                .enableIncognito()
                .enableAutoplay()
                .disableDnsOverHttps()
                .addSwitch("--disable-background-networking")
                .addSwitch("--disable-component-update")
                .addSwitch("--disable-sync")
                .addSwitch("--disable-renderer-backgrounding")
                .addSwitch("--disable-background-timer-throttling")
                .build(),
        )
    }

    private fun verifyReport(report: JsonObject) {
        assertEquals(true, report["passed"]?.jsonPrimitive?.boolean, "Synthetic encrypted media smoke failed: $report")
        val production = report.getValue("production").jsonObject
        val audited = report.getValue("audited").jsonObject
        assertTrue(production.getValue("framesDecoded").jsonPrimitive.int > 0)
        assertTrue(audited.getValue("framesDecoded").jsonPrimitive.int > 0)
        val raw = report.getValue("raw").jsonObject
        assertTrue(raw.getValue("framesDecoded").jsonPrimitive.int > 0)
        assertEquals(true, raw.getValue("cleared").jsonPrimitive.boolean)
        val direct = report.getValue("direct").jsonObject
        assertTrue(direct.getValue("framesDecoded").jsonPrimitive.int > 0)
        assertEquals("videoframe", direct.getValue("renderer").jsonPrimitive.content)
        assertEquals(true, direct.getValue("cleared").jsonPrimitive.boolean)
        val nv12 = report.getValue("nv12").jsonObject
        assertTrue(nv12.getValue("framesDecoded").jsonPrimitive.int > 0)
        assertEquals(true, nv12.getValue("cleared").jsonPrimitive.boolean)
        listOf("encrypted", "decrypted", "tamperRejected", "replayRejected").forEach { field ->
            assertEquals(true, audited.getValue(field).jsonPrimitive.boolean, field)
        }
        // Only synthetic pixels/counters and capabilities are reported, never config keys.
        logger.info(LogCategory.BROWSER, "Application media loopback", mapOf("report" to report.toString()))
    }
}

private class MediaSmokeResource(
    val bytes: ByteArray,
    val type: String,
)

private class MediaSmokeServer : AutoCloseable {
    val result = CompletableFuture<JsonObject>()
    private val prefix = "/${UUID.randomUUID()}/"
    private val executor =
        Executors.newFixedThreadPool(3) { task ->
            Thread(task, "app-media-smoke-http").apply { isDaemon = true }
        }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 8)
    private val origin = "http://127.0.0.1:${server.address.port}"
    val baseUrl = origin + prefix
    private val testAssets = setOf("smoke.html", "smoke.mjs", "smoke-worker.mjs")
    private val productionAssets = setOf("media.mjs", "crypto.mjs", "encoded-worker.mjs", "stats.mjs", "recovery.mjs")

    init {
        server.executor = executor
        server.createContext(prefix, ::handle)
        server.start()
    }

    /** Boundary for HTTP parsing, resource IO and browser disconnects; malformed requests receive no resource. */
    @Suppress("TooGenericExceptionCaught")
    private fun handle(exchange: HttpExchange) {
        try {
            require(exchange.requestHeaders.getFirst("Host") == "127.0.0.1:${server.address.port}")
            val name = exchange.requestURI.rawPath.removePrefix(prefix)
            val resource =
                if (name == "result" && exchange.requestMethod == "POST") {
                    acceptResult(exchange)
                } else {
                    require(exchange.requestMethod == "GET")
                    asset(name)
                }
            respond(exchange, resource)
        } catch (_: Exception) {
            runCatching { exchange.sendResponseHeaders(400, -1) }
        } finally {
            exchange.close()
        }
    }

    private fun acceptResult(exchange: HttpExchange): MediaSmokeResource {
        require(exchange.requestHeaders.getFirst("Origin") == origin)
        val body = exchange.requestBody.use { it.readNBytes(8193) }
        require(body.size <= 8192)
        result.complete(Json.parseToJsonElement(body.decodeToString()).jsonObject)
        return MediaSmokeResource("{}".toByteArray(), "application/json")
    }

    private fun asset(name: String): MediaSmokeResource {
        val path =
            when (name) {
                in testAssets -> "/app-sharing-smoke/$name"
                in productionAssets -> "/app-sharing/$name"
                else -> error("Unknown test resource")
            }
        val bytes =
            checkNotNull(javaClass.getResourceAsStream(path)) {
                "Missing test resource $name"
            }.use { it.readBytes() }
        val type = if (name.endsWith(".html")) "text/html; charset=utf-8" else "text/javascript; charset=utf-8"
        return MediaSmokeResource(bytes, type)
    }

    private fun respond(
        exchange: HttpExchange,
        resource: MediaSmokeResource,
    ) {
        exchange.responseHeaders.set("Content-Type", resource.type)
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
        exchange.responseHeaders.set(
            "Content-Security-Policy",
            "default-src 'none'; script-src 'self'; worker-src 'self'; connect-src 'self'; " +
                "media-src 'self' blob:; base-uri 'none'; frame-ancestors 'none'",
        )
        exchange.sendResponseHeaders(200, resource.bytes.size.toLong())
        exchange.responseBody.use { it.write(resource.bytes) }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}
