package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.api.CoBrowseRtcPeer
import ai.rever.boss.plugin.api.CoBrowseRtcProvider
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.browser.callback.StartCaptureSessionCallback
import com.teamdev.jxbrowser.capture.AudioCaptureMode
import com.teamdev.jxbrowser.capture.NotificationVisibility
import com.teamdev.jxbrowser.frame.Frame
import com.teamdev.jxbrowser.js.JsObject
import com.teamdev.jxbrowser.navigation.event.LoadFinished
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress

/**
 * Host-side WebRTC peer running inside a hidden JxBrowser page.
 *
 * The page is served from http://127.0.0.1 (a *secure context*, required for
 * getDisplayMedia) by a tiny built-in HTTP server, so the same page can run the
 * data channels (input + DOM) AND capture a shared tab's live pixels as a video
 * track. Tab capture is auto-selected (no picker) via [StartCaptureSessionCallback].
 */
internal class CoBrowseRtcProviderImpl : CoBrowseRtcProvider {
    override fun createPeer(
        onAnswer: (String) -> Unit,
        onIce: (String) -> Unit,
        onInput: (String) -> Unit,
        onState: (Boolean) -> Unit,
    ): CoBrowseRtcPeer? = try {
        CoBrowseRtcPeerImpl(onAnswer, onIce, onInput, onState)
    } catch (e: Throwable) {
        logger.warn(LogCategory.BROWSER, "Failed to create WebRTC peer", error = e)
        null
    }

    companion object {
        internal val logger = BossLogger.forComponent("CoBrowseRtc")
    }
}

internal class CoBrowseRtcPeerImpl(
    onAnswer: (String) -> Unit,
    onIce: (String) -> Unit,
    onInput: (String) -> Unit,
    onState: (Boolean) -> Unit,
) : CoBrowseRtcPeer {

    private val logger = CoBrowseRtcProviderImpl.logger
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val bridge = CoBrowseRtcBridge(onAnswer, onIce, onInput, onState).also {
        it.onVideoError = { msg -> logger.warn(LogCategory.BROWSER, "WebRTC video capture error: $msg") }
        it.onVideoState = { msg -> logger.info(LogCategory.BROWSER, "WebRTC video: $msg") }
    }

    @Volatile private var browser: Browser? = null
    @Volatile private var injected = false
    @Volatile private var ready = false
    @Volatile private var closed = false
    // Ops (offer/ice/sendDom/startVideo) that arrive before the peer page has
    // loaded + initialized are queued here and flushed on init — the viewer can
    // send its offer faster than the localhost page loads.
    private val pending = ArrayList<String>()
    /** Page title of the tab to capture; read when the peer page calls getDisplayMedia. */
    @Volatile private var targetTitle: String? = null

    @Volatile private var iceServersJson: String = buildIceServersJson()

    init {
        scope.launch {
            try {
                val b = FluckEngine.engine.newBrowser()
                browser = b
                logger.info(LogCategory.BROWSER, "WebRTC peer browser created")
                // Auto-select the shared tab as the capture source (no picker UI).
                // Internal-tab capture doesn't need OS screen-recording permission.
                b.set(StartCaptureSessionCallback::class.java, StartCaptureSessionCallback { params, tell ->
                    try {
                        val want = targetTitle
                        val browsers = params.sources().browsers()
                        val chosen = (want?.let { t -> browsers.firstOrNull { it.name() == t } }) ?: browsers.firstOrNull()
                        if (chosen != null) {
                            logger.info(LogCategory.BROWSER, "WebRTC video: selecting capture source '${chosen.name()}'")
                            // HIDE suppresses Chromium's "Sharing this tab" banner on the captured tab.
                            tell.selectSource(chosen, AudioCaptureMode.IGNORE, NotificationVisibility.HIDE)
                        } else {
                            logger.warn(LogCategory.BROWSER, "WebRTC video: no internal-tab capture source available")
                            tell.cancel()
                        }
                    } catch (e: Exception) {
                        logger.warn(LogCategory.BROWSER, "WebRTC video: capture selection failed", error = e)
                        runCatching { tell.cancel() }
                    }
                })
                // Inject the bridge + init the peer once the served page (and its
                // script) has finished loading.
                b.navigation().on(LoadFinished::class.java) {
                    try {
                        if (!injected) {
                            val f = b.mainFrame().orElse(null)
                            if (f != null) {
                                injectInto(f); injected = true
                                logger.info(LogCategory.BROWSER, "WebRTC peer initialized")
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn(LogCategory.BROWSER, "WebRTC peer init failed", error = e)
                    }
                }
                b.navigation().loadUrl(RtcHostServer.baseUrl())
            } catch (e: Throwable) {
                logger.warn(LogCategory.BROWSER, "WebRTC peer browser init failed", error = e)
                runCatching { onState(false) }
            }
        }
    }

    private fun injectInto(frame: Frame) {
        val window = frame.executeJavaScript<JsObject>("window")
        window?.putProperty("__bossRtcBridge", bridge)
        frame.executeJavaScript<Any?>("window.__bossRtcInit && window.__bossRtcInit(${jsStr(iceServersJson)});")
        // The page is now initialized — flush any ops that raced ahead of it.
        val queued: List<String>
        synchronized(pending) { ready = true; queued = ArrayList(pending); pending.clear() }
        queued.forEach { runCatching { frame.executeJavaScript<Any?>(it) } }
    }

    private fun mainFrame(): Frame? = browser?.takeIf { !it.isClosed }?.mainFrame()?.orElse(null)

    private fun runJs(script: String) {
        if (closed) return
        synchronized(pending) {
            if (!ready) { pending.add(script); return } // apply once initialized
        }
        scope.launch {
            try { mainFrame()?.executeJavaScript<Any?>(script) }
            catch (e: Exception) { logger.warn(LogCategory.BROWSER, "WebRTC peer JS call failed", error = e) }
        }
    }

    override fun acceptOffer(sdp: String) {
        logger.info(LogCategory.BROWSER, "WebRTC peer accepting offer (injected=$injected, ${sdp.length} chars)")
        runJs("window.__bossRtcOffer && window.__bossRtcOffer(${jsStr(sdp)});")
    }

    override fun addRemoteIce(candidate: String) {
        runJs("window.__bossRtcAddIce && window.__bossRtcAddIce(${jsStr(candidate)});")
    }

    override fun sendDom(json: String) {
        runJs("window.__bossRtcSendDom && window.__bossRtcSendDom(${jsStr(json)});")
    }

    override fun startVideo(targetTitle: String) {
        this.targetTitle = targetTitle
        logger.info(LogCategory.BROWSER, "WebRTC video: starting capture of '$targetTitle'")
        runJs("window.__bossRtcStartVideo && window.__bossRtcStartVideo();")
    }

    override fun stopVideo() {
        runJs("window.__bossRtcStopVideo && window.__bossRtcStopVideo();")
    }

    override fun close() {
        if (closed) return
        closed = true
        bridge.onAnswer = null; bridge.onIce = null; bridge.onInput = null; bridge.onState = null; bridge.onVideoError = null
        scope.launch {
            try { browser?.takeIf { !it.isClosed }?.close() } catch (_: Throwable) {}
            browser = null
        }
    }

    private companion object {
        fun jsStr(s: String): String = Json.encodeToString(String.serializer(), s)

        // Must stay in lockstep with the plugin's BrowserShareManager.iceServers():
        // both peers read the same boss.cobrowse.turn / free default so they agree.
        // STUN alone fails across most real cross-network NATs (symmetric / mobile
        // CGNAT); TURN is what lets a remote viewer's media actually connect.
        fun buildIceServersJson(): String {
            fun cfg(prop: String, env: String) =
                System.getProperty(prop)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() }
            val sb = StringBuilder(
                """[{"urls":"stun:stun.l.google.com:19302"},{"urls":"stun:stun1.l.google.com:19302"}"""
            )
            val turn = cfg("boss.cobrowse.turn", "BOSS_COBROWSE_TURN")
            if (turn != null) {
                val p = turn.split(",").map { it.trim() }
                sb.append(""",{"urls":${jsStr(p.getOrElse(0) { turn })}""")
                p.getOrNull(1)?.let { sb.append(""","username":${jsStr(it)}""") }
                p.getOrNull(2)?.let { sb.append(""","credential":${jsStr(it)}""") }
                sb.append("}")
            } else if (cfg("boss.cobrowse.turn.free", "BOSS_COBROWSE_TURN_FREE")?.lowercase() != "false") {
                // Open Relay Project free public TURN — shared/best-effort, zero config.
                for (url in listOf(
                    "turn:openrelay.metered.ca:80",
                    "turn:openrelay.metered.ca:443",
                    "turn:openrelay.metered.ca:443?transport=tcp",
                    "turns:openrelay.metered.ca:443",
                )) {
                    sb.append(""",{"urls":${jsStr(url)},"username":"openrelayproject","credential":"openrelayproject"}""")
                }
            }
            sb.append("]")
            return sb.toString()
        }
    }
}

/**
 * Serves the WebRTC host-peer page from http://127.0.0.1:<ephemeral>/ so the
 * peer runs in a secure context (required for getDisplayMedia). One server,
 * lazily started, shared by all peers.
 */
private object RtcHostServer {
    @Volatile private var port: Int = -1

    fun baseUrl(): String { ensure(); return "http://127.0.0.1:$port/" }

    @Synchronized
    private fun ensure() {
        if (port > 0) return
        val html = (javaClass.getResourceAsStream("/webrtc/rtc-host.html")?.use { it.readBytes() })
            ?: ByteArray(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            ex.sendResponseHeaders(200, html.size.toLong())
            ex.responseBody.use { it.write(html) }
        }
        server.executor = null
        server.start()
        port = server.address.port
        CoBrowseRtcProviderImpl.logger.info(LogCategory.BROWSER, "WebRTC peer page server on 127.0.0.1:$port")
    }
}
