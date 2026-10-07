package ai.rever.boss.sharing

import ai.rever.boss.config.JxBrowserConfig
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.teamdev.jxbrowser.engine.Engine
import com.teamdev.jxbrowser.engine.EngineOptions
import com.teamdev.jxbrowser.engine.RenderingMode
import com.teamdev.jxbrowser.net.callback.BeforeUrlRequestCallback
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real provider test: synthetic canvas and callback-only input in a fresh private browser profile. */
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_APP_SFU", matches = "1")
class AppSharingSfuSmokeTest {
    @TempDir
    lateinit var temporary: Path

    private val logger = BossLogger.forComponent("AppSharingSfuSmoke")

    @Test
    @Timeout(value = 12, unit = TimeUnit.MINUTES)
    fun `one encrypted publication fans out to ten viewers with lease control and revocation`() {
        val credentials = AppSharingSfuCredentials.load()
        AppSharingSfuFixture(credentials).use { fixture ->
            val engine = createEngine()
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
                browser.resize(1280, 900)
                browser.navigation().loadUrlAndWait(fixture.baseUrl + "sfu-smoke.html", Duration.ofSeconds(20))
                verifyReport(fixture.result.get(10, TimeUnit.MINUTES))
            } finally {
                runCatching { engine.close() }.onFailure {
                    logger.warn(LogCategory.BROWSER, "Synthetic SFU engine cleanup failed", error = it)
                }
            }
        }
    }

    private fun createEngine(): Engine {
        val chromium =
            Path.of(
                requireNotNull(System.getenv("BOSS_TEST_APP_MEDIA_CHROMIUM_DIR")) {
                    "Set BOSS_TEST_APP_MEDIA_CHROMIUM_DIR to existing matching Chromium binaries"
                },
            )
        require(Files.isDirectory(chromium)) { "Configured Chromium directory is missing" }
        require(JxBrowserConfig.licenseKey.isNotBlank()) { "Configure the JxBrowser license through local.properties" }
        return Engine.newInstance(
            EngineOptions
                .newBuilder(RenderingMode.OFF_SCREEN)
                .licenseKey(JxBrowserConfig.licenseKey)
                .chromiumDir(chromium)
                .userDataDir(Files.createDirectory(temporary.resolve("private-sfu-profile")))
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
        assertEquals(true, report["passed"]?.jsonPrimitive?.boolean, "Synthetic SFU smoke failed: $report")
        val stages = report.getValue("stages").jsonArray.map { it.jsonObject }
        assertEquals(listOf(1, 3, 10), stages.map { it.getValue("viewers").jsonPrimitive.int })
        stages.forEach {
            assertEquals(1, it.getValue("publisherStreams").jsonPrimitive.int)
            assertEquals(1, it.getValue("publicationRequests").jsonPrimitive.int)
            assertTrue(it.getValue("minimumDecodedFramesDuringSample").jsonPrimitive.int > 0)
        }
        val control = report.getValue("control").jsonObject
        assertEquals(10, control.getValue("samples").jsonPrimitive.int)
        listOf("tamperRejected", "replayRejected", "releaseObserved").forEach {
            assertEquals(true, control.getValue(it).jsonPrimitive.boolean)
        }
        assertEquals(
            true,
            report
                .getValue("disconnect")
                .jsonObject
                .getValue("otherViewersProgressing")
                .jsonPrimitive.boolean,
        )
        assertEquals(
            true,
            report
                .getValue("revocation")
                .jsonObject
                .getValue("staleAdmissionRejected")
                .jsonPrimitive.boolean,
        )
        // Report contains synthetic counters/timings only, never a registry descriptor, SDP or credentials.
        logger.info(LogCategory.BROWSER, "Application SFU integration", mapOf("report" to report.toString()))
    }
}
