package ai.rever.boss.sharing

import ai.rever.boss.plugin.browser.BoundedBrowserCall
import ai.rever.boss.plugin.browser.FluckEngine
import ai.rever.boss.plugin.browser.installBrowserChromeOrClose
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.js.JsAccessible
import com.teamdev.jxbrowser.js.JsObject
import com.teamdev.jxbrowser.navigation.event.LoadFinished
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean

/** A single publisher page per shared window. Frame submission retains only the latest image. */
internal class AppSharingMediaHost(
    private val page: AppViewerPage,
    private val onState: (JsonObject) -> Unit,
    private val onInput: (JsonObject) -> Unit,
) : AutoCloseable {
    private val logger = BossLogger.forComponent("AppSharingMediaHost")
    private val calls = BoundedBrowserCall("boss-app-share-media")
    private val closed = AtomicBoolean()
    private val initialized = AtomicBoolean()
    private val receivedFrame = AtomicBoolean()

    @Volatile private var browser: Browser? = null
    private val bridge =
        AppSharingMediaBridge(
            { value ->
                if (!closed.get()) {
                    // State payloads may carry control authority; log only protocol diagnostics.
                    val state = value["state"]?.jsonPrimitive?.contentOrNull
                    if (state != "lease") {
                        val reason =
                            value["reason"]
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?.takeIf { it.matches(Regex("[a-z_]{1,64}")) }
                        logger.info(
                            LogCategory.BROWSER,
                            "Publisher state",
                            mapOf("state" to state, "reason" to reason),
                        )
                    }
                    onState(value)
                }
            },
            { value -> if (!closed.get()) onInput(value) },
        )

    // Browser and native bridge failures must report a bounded publication state.
    @Suppress("TooGenericExceptionCaught")
    fun start() {
        calls.post {
            if (closed.get()) return@post
            try {
                val next = FluckEngine.engine.newBrowser().also { installBrowserChromeOrClose(it) }
                browser = next
                logger.info(LogCategory.BROWSER, "Publisher browser created")
                next.navigation().on(LoadFinished::class.java) {
                    logger.info(LogCategory.BROWSER, "Publisher page load finished")
                    calls.post {
                        if (closed.get() || !initialized.compareAndSet(false, true)) return@post
                        try {
                            check(next.url() == page.url) { "Unexpected media page navigation" }
                            val frame = next.mainFrame().orElseThrow()
                            frame.executeJavaScript<JsObject>("window")?.putProperty("__bossAppShareBridge", bridge)
                            val readiness =
                                frame.executeJavaScript<String>(
                                    "JSON.stringify({host:typeof window.BossAppShareHost," +
                                        "config:typeof window.__bossAppShareConfig," +
                                        "bridge:typeof window.__bossAppShareBridge,secure:isSecureContext})",
                                )
                            logger.info(LogCategory.BROWSER, "Publisher readiness", mapOf("readiness" to readiness))
                            val startPublisher = "void window.BossAppShareHost.start(window.__bossAppShareConfig);"
                            frame.executeJavaScript<Any?>(startPublisher)
                            // JxBrowser/JNA failures terminate publication through the same bounded state callback.
                        } catch (error: Exception) {
                            logger.warn(
                                LogCategory.BROWSER,
                                "Publisher initialization failed",
                                mapOf("errorType" to error.javaClass.simpleName),
                            )
                            onState(
                                buildJsonObject {
                                    put("state", "error")
                                    put("message", "Media initialization failed")
                                },
                            )
                        }
                    }
                }
                next.navigation().loadUrl(page.url)
            } catch (_: Exception) {
                onState(
                    buildJsonObject {
                        put("state", "error")
                        put("message", "Browser media runtime unavailable")
                    },
                )
            }
        }
    }

    fun captureFrameRate(): Int = page.rawFrames.frameRate

    fun capturePixelFormat(): String = page.rawFrames.pixelFormat

    fun frame(frame: AppRawCapturedFrame?) {
        if (closed.get()) return
        if (frame != null && receivedFrame.compareAndSet(false, true)) {
            logger.info(
                LogCategory.BROWSER,
                "First continuous publisher frame",
                mapOf("width" to frame.pixels.width, "height" to frame.pixels.height),
            )
        }
        page.rawFrames.offer(frame)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        page.rawFrames.offer(null)
        page.close()
        calls.post {
            runCatching {
                browser?.mainFrame()?.orElse(null)?.executeJavaScript<Any?>("window.BossAppShareHost?.stop();")
            }
            runCatching { browser?.close() }
            browser = null
        }
        calls.shutdown()
    }
}

internal class AppSharingMediaBridge(
    private val receiveState: (JsonObject) -> Unit,
    private val receiveInput: (JsonObject) -> Unit,
) {
    @JsAccessible
    fun state(json: String) {
        if (json.length > 8192) return
        runCatching { receiveState(Json.parseToJsonElement(json).jsonObject) }
    }

    @JsAccessible
    fun input(json: String) {
        if (json.length > 8192) return
        runCatching { receiveInput(Json.parseToJsonElement(json).jsonObject) }
    }
}
