package ai.rever.boss.sharing

import ai.rever.boss.config.JxBrowserConfig
import ai.rever.boss.plugin.browser.ChromiumToolkitPreload
import com.teamdev.jxbrowser.engine.Engine
import com.teamdev.jxbrowser.engine.EngineOptions
import com.teamdev.jxbrowser.engine.RenderingMode
import com.teamdev.jxbrowser.frame.Frame
import com.teamdev.jxbrowser.net.callback.BeforeUrlRequestCallback
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real production HTTP/client/VP8 integration; synthetic pixels only, isolated profile, no capture or SFU. */
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_APP_RAW_BROWSER", matches = "1")
class AppRawFrameBrowserSmokeTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    @Timeout(value = 150, unit = TimeUnit.SECONDS)
    fun `real Chromium negotiates raw waits decodes colors and retires pixels`() {
        val chromium = Path.of(requireNotNull(System.getenv("BOSS_TEST_APP_MEDIA_CHROMIUM_DIR")))
        require(Files.isDirectory(chromium)) { "Use existing matching Chromium binaries" }
        require(JxBrowserConfig.licenseKey.isNotBlank()) { "Use the existing local.properties JxBrowser license" }
        ChromiumToolkitPreload.preload(chromium)
        val engine = createEngine(chromium)
        try {
            AppSharingAssets().use { assets ->
                listOf("BGRA", "NV12").forEach { format -> verifyFormat(engine, assets, format) }
            }
        } finally {
            engine.close()
        }
    }

    private fun createEngine(chromium: Path): Engine =
        Engine.newInstance(
            EngineOptions
                .newBuilder(RenderingMode.OFF_SCREEN)
                .licenseKey(JxBrowserConfig.licenseKey)
                .chromiumDir(chromium)
                .userDataDir(Files.createDirectory(temporary.resolve("chromium-profile")))
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

    // One scope owns the capability, browser, stage sequencing and retirement assertions.
    @Suppress("LongMethod")
    private fun verifyFormat(
        engine: Engine,
        assets: AppSharingAssets,
        format: String,
    ) {
        val page = assets.open(buildJsonObject {}, true) { error("This smoke must not call host RPC") }
        val uri = URI(page.url)
        val origin = "${uri.scheme}://${uri.authority}/"
        engine.network().set(
            BeforeUrlRequestCallback::class.java,
            BeforeUrlRequestCallback { params ->
                if (params.urlRequest().url().startsWith(origin)) {
                    BeforeUrlRequestCallback.Response.proceed()
                } else {
                    BeforeUrlRequestCallback.Response.cancel()
                }
            },
        )
        val browser = engine.newBrowser()
        try {
            browser.resize(640, 480)
            // A harmless real asset establishes the protected origin without starting host.html/SFU.
            browser.navigation().loadUrlAndWait(uri.resolve("viewer.css").toString(), Duration.ofSeconds(20))
            val frame = browser.mainFrame().orElseThrow()
            val harness =
                checkNotNull(javaClass.getResourceAsStream("/app-sharing-smoke/raw-assets.js"))
                    .use { it.readBytes().decodeToString() }
            frame.executeJavaScript<Any?>(harness)
            frame.executeJavaScript<Any?>("window.startRawAssetsSmoke('$format');undefined")
            val pixels = syntheticFrame(format)
            awaitStage(frame, "colored") { page.rawFrames.offer(pixels) }
            assertEquals(format, page.rawFrames.pixelFormat)
            page.rawFrames.offer(null)
            awaitStage(frame, "cleared")
            frame.executeJavaScript<Any?>("window.rawAssetsSmoke.retiring=true;undefined")
            page.close()
            page.rawFrames.offer(pixels)
            assertNull(page.rawFrames.latest().frame, "Retired mailbox must reject late pixels")
            awaitStage(frame, "retired")
            val report =
                Json
                    .parseToJsonElement(
                        checkNotNull(
                            frame.executeJavaScript<String>(
                                "JSON.stringify(window.rawAssetsSmoke)",
                            ),
                        ),
                    ).jsonObject
            assertEquals(true, report.getValue("passed").jsonPrimitive.boolean)
            val counters = report.getValue("report").jsonObject
            assertTrue(counters.getValue("framesDecoded").jsonPrimitive.int > 0)
            assertTrue(counters.getValue("acknowledgedFrames").jsonPrimitive.int > 0)
            assertTrue(counters.getValue("acknowledgedEmpty").jsonPrimitive.int > 0)
            assertEquals(1, counters.getValue("maximumFetches").jsonPrimitive.int)
            assertEquals(true, counters.getValue("cleared").jsonPrimitive.boolean)
            assertEquals(true, counters.getValue("retired").jsonPrimitive.boolean)
        } finally {
            browser.close()
            page.close()
        }
    }

    private fun awaitStage(
        frame: Frame,
        expected: String,
        publish: () -> Unit = {},
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35)
        while (System.nanoTime() < deadline) {
            val stage = frame.executeJavaScript<String>("window.rawAssetsSmoke?.stage")
            if (stage == expected) return
            if (stage == "failed") {
                val safe = frame.executeJavaScript<String>("JSON.stringify(window.rawAssetsSmoke)")
                error("Synthetic browser smoke failed while awaiting $expected: $safe")
            }
            publish()
            Thread.sleep(30)
        }
        error("Synthetic browser smoke timed out awaiting $expected")
    }

    private fun syntheticFrame(format: String): AppRawCapturedFrame {
        val bytes = ByteArray(if (format == "BGRA") 320 * 160 * 4 else 320 * 160 * 3 / 2)
        for (y in 0 until 160) {
            for (x in 0 until 320) {
                val rgb =
                    when {
                        y < 80 && x < 160 -> intArrayOf(230, 40, 48)
                        y < 80 -> intArrayOf(32, 204, 80)
                        x < 160 -> intArrayOf(35, 70, 220)
                        else -> intArrayOf(220, 200, 30)
                    }
                putPixel(bytes, format, x, y, rgb)
            }
        }
        return AppRawCapturedFrame(
            AppRawWindowFrame(bytes, 320, 160, format),
            1,
            AppSurfaceSnapshot(emptyList(), 0, 0, 320, 160, 320, 160),
        )
    }

    private fun putPixel(
        bytes: ByteArray,
        format: String,
        x: Int,
        y: Int,
        rgb: IntArray,
    ) {
        val (red, green, blue) = rgb
        if (format == "BGRA") {
            val offset = (y * 320 + x) * 4
            bytes[offset] = blue.toByte()
            bytes[offset + 1] = green.toByte()
            bytes[offset + 2] = red.toByte()
            bytes[offset + 3] = -1
        } else {
            fun component(value: Double) = value.roundToInt().coerceIn(0, 255).toByte()
            bytes[y * 320 + x] = component(16 + 0.182586 * red + 0.614231 * green + 0.062007 * blue)
            if (x % 2 == 0 && y % 2 == 0) {
                val uv = 320 * 160 + y / 2 * 320 + x
                bytes[uv] = component(128 - 0.100644 * red - 0.338572 * green + 0.439216 * blue)
                bytes[uv + 1] = component(128 + 0.439216 * red - 0.398942 * green - 0.040274 * blue)
            }
        }
    }
}
