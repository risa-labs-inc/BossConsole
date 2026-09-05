package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.api.CoBrowseRtcPeer
import ai.rever.boss.plugin.api.CoBrowseRtcProvider
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.sun.net.httpserver.HttpServer
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.browser.callback.StartCaptureSessionCallback
import com.teamdev.jxbrowser.capture.AudioCaptureMode
import com.teamdev.jxbrowser.capture.NotificationVisibility
import com.teamdev.jxbrowser.frame.Frame
import com.teamdev.jxbrowser.js.JsObject
import com.teamdev.jxbrowser.navigation.event.LoadFinished
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

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
    ): CoBrowseRtcPeer? =
        try {
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

    // Every blocking JxBrowser round trip this peer makes, off the EDT. NOT on a deadline: this
    // class takes only [BoundedBrowserCall.dispatcher] and calls `executeJavaScript` / `putProperty`
    // directly, so DEFAULT_TIMEOUT_MS never applies here. That is deliberate - nothing awaits these
    // calls, so there is no wait to bound, and a wedged peer page costs one parked daemon thread and
    // nothing more. Routing them through `call()` would be worse, not better: this scope runs ON that
    // dispatcher, and awaiting a call from its own dispatcher is the hang [BoundedBrowserCall] warns
    // about.
    //
    // One instance per peer, so a share whose page stops answering costs its own thread and nobody
    // else's - and the thread carries an id, because the WARN's thread name is the only thing that
    // says WHICH share wedged when several are live.
    //
    // This moves `Engine.newBrowser()` and `browser.close()` off the EDT too, which is safe for the
    // reason that is easy to lose: JxBrowser's `Browser` API is thread-safe, and this peer is
    // HEADLESS - no `BrowserView`, no AWT anywhere in the class. A peer that ever acquires a view
    // has to put the view's lifecycle back on the EDT; the rest can stay here.
    private val browserCall = BoundedBrowserCall("boss-rtc-peer-call-${System.identityHashCode(this)}")
    private val scope = CoroutineScope(SupervisorJob() + browserCall.dispatcher)
    private val bridge =
        CoBrowseRtcBridge(onAnswer, onIce, onInput, onState).also {
            it.onVideoError = { msg -> logger.warn(LogCategory.BROWSER, "WebRTC video capture error: $msg") }
            it.onVideoState = { msg -> logger.info(LogCategory.BROWSER, "WebRTC video: $msg") }
        }

    @Volatile private var browser: Browser? = null

    // Two flags, not one, now that injection is posted off the callback thread.
    //
    // [injected] is the DONE state and is only ever set on success. [injectQueued] keeps a second
    // LoadFinished from queueing a duplicate while one is in flight. Collapsing them into a single
    // claim-before-launch was wrong in the other direction: a failed attempt released the claim only
    // after the events that would have retried it had already been dropped, and a page that loads
    // once never fires a third.
    //
    // Two things the pair does NOT promise. [injectQueued]'s finally does not run if the dispatch
    // itself is rejected - after close() shuts the executor down, kotlinx cancels the child before
    // its body starts - so the flag can stay latched; harmless only because `closed` short-circuits
    // every path that would look at it again. And [injected] is never reset, so a *reload* of the
    // peer page leaves the new document without the bridge. Both were true before this change; they
    // are written down here because this comment is where the next reader will look.
    private val injected = AtomicBoolean(false)
    private val injectQueued = AtomicBoolean(false)

    /** One warning per stall, not one per dropped frame. Cleared only on a drained queue. */
    private val domDropWarned = AtomicBoolean(false)

    @Volatile private var ready = false

    @Volatile private var closed = false

    // Ops (offer/ice/sendDom/startVideo) that arrive before the peer page has
    // loaded + initialized are queued here and flushed on init — the viewer can
    // send its offer faster than the localhost page loads.
    private val pending = ArrayList<String>()

    /**
     * How many of [pending] are DOM frames. Guarded by `pending`'s own monitor.
     *
     * Counted separately rather than gating on `pending.size`, because [pending] also holds the
     * offer and every ICE candidate, and a peer that is slow to come up can easily produce more of
     * those than [MAX_DOM_BACKLOG] - which would then drop the whole DOM stream for a reason that
     * has nothing to do with the stream. Read outside the lock only for the warning watermark, where
     * a stale count costs at most one extra WARN.
     */
    @Volatile private var pendingDomFrames = 0

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
                b.set(
                    StartCaptureSessionCallback::class.java,
                    StartCaptureSessionCallback { params, tell ->
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
                    },
                )
                // Inject the bridge + init the peer once the served page (and its
                // script) has finished loading.
                b.navigation().on(LoadFinished::class.java) { injectWhenReady(b) }
                b.navigation().loadUrl(RtcHostServer.baseUrl())
            } catch (e: Throwable) {
                logger.warn(LogCategory.BROWSER, "WebRTC peer browser init failed", error = e)
                runCatching { onState(false) }
            }
        }
    }

    /**
     * Inject on the peer's own thread, once, when a load lands.
     *
     * Posted rather than run on the caller's thread: `LoadFinished` is delivered on JxBrowser's RPC
     * thread, and [injectInto]'s blocking `executeJavaScript` made from there re-enters
     * `RpcThreadCallExecutor` and parks on a queue only the thread it is blocking could drain, so
     * the reply can never arrive. That is the deadlock PR #268 fixed for the page helpers, at a call
     * site it missed.
     */
    private fun injectWhenReady(b: Browser) {
        if (injected.get()) return
        if (!injectQueued.compareAndSet(false, true)) return
        scope.launch {
            try {
                val frame = b.mainFrame().orElse(null) ?: return@launch
                injectInto(frame)
                // Only here. A frame that was not there yet, or a throw, leaves this false so the
                // next LoadFinished tries again rather than the peer sitting dead.
                injected.set(true)
                logger.info(LogCategory.BROWSER, "WebRTC peer initialized")
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "WebRTC peer init failed", error = e)
            } finally {
                injectQueued.set(false)
            }
        }
    }

    private fun injectInto(frame: Frame) {
        val window = frame.executeJavaScript<JsObject>("window")
        window?.putProperty("__bossRtcBridge", bridge)
        frame.executeJavaScript<Any?>("window.__bossRtcInit && window.__bossRtcInit(${jsStr(iceServersJson)});")
        // The page is now initialized — flush any ops that raced ahead of it.
        val queued: List<String>
        synchronized(pending) {
            ready = true
            queued = ArrayList(pending)
            pending.clear()
            pendingDomFrames = 0
        }
        // This loop is the burst [sendDom]'s KDoc says it is avoiding, so the DOM frames in here are
        // capped on the queueing side - see [runJs]'s domFrame. The signalling ops (offer, ICE) are
        // not capped and do not need to be: they are one-per-negotiation, not a stream.
        queued.forEach { runCatching { frame.executeJavaScript<Any?>(it) } }
    }

    private fun mainFrame(): Frame? = browser?.takeIf { !it.isClosed }?.mainFrame()?.orElse(null)

    /**
     * Queue [script] for the peer page, or dispatch it now if the page is already up.
     *
     * @param domFrame marks the rrweb stream, the one caller that drops rather than enqueues. The cap
     *   is applied to whichever queue the script would land on, because the two are alternatives and
     *   not stages: until the page answers, nothing here reaches the executor at all, so a cap read
     *   off [BoundedBrowserCall.backlog] alone is bypassed in exactly the state it was written for.
     * @return false when the frame was dropped, so the caller can warn once per stall.
     */
    // Five returns rather than two: they are the three outcomes (closed, dropped, accepted) across
    // the two queues, and collapsing them means carrying the decision out of the synchronized block
    // that has to make it - where `ready` can already have flipped.
    @Suppress("ReturnCount")
    private fun runJs(
        script: String,
        domFrame: Boolean = false,
    ): Boolean {
        if (closed) return false
        synchronized(pending) {
            if (!ready) {
                if (domFrame && pendingDomFrames >= MAX_DOM_BACKLOG) return false
                pending.add(script)
                if (domFrame) pendingDomFrames++
                return true
            } // apply once initialized
        }
        if (domFrame && browserCall.backlog >= MAX_DOM_BACKLOG) return false
        scope.launch {
            try {
                mainFrame()?.executeJavaScript<Any?>(script)
            } catch (
                e: Exception,
            ) {
                logger.warn(LogCategory.BROWSER, "WebRTC peer JS call failed", error = e)
            }
        }
        return true
    }

    override fun acceptOffer(sdp: String) {
        logger.info(LogCategory.BROWSER, "WebRTC peer accepting offer (injected=$injected, ${sdp.length} chars)")
        runJs("window.__bossRtcOffer && window.__bossRtcOffer(${jsStr(sdp)});")
    }

    override fun addRemoteIce(candidate: String) {
        runJs("window.__bossRtcAddIce && window.__bossRtcAddIce(${jsStr(candidate)});")
    }

    /**
     * The one caller that drops rather than enqueues.
     *
     * rrweb emits continuously and each payload is a DOM mutation batch, so against a peer page that
     * has stopped answering this would grow without limit, holding every frame's JSON - and then run
     * them all in a burst if the page ever recovered. That is exactly the hazard cited for leaving
     * `dispatchCoBrowseInput` on Main in BrowserHandleImpl, and the argument has to point the same
     * way here: same shape, bigger payloads.
     *
     * **Both queues, not just the executor's.** The first version gated on
     * [BoundedBrowserCall.backlog] alone, which a peer page that has not answered never reaches:
     * [runJs] appends to [pending] and returns while `ready` is false, so the guard was bypassed in
     * precisely the state it describes - an unreachable `RtcHostServer` or a load that never
     * finishes grew [pending] for the whole life of the share, silently. The cap now lives in [runJs]
     * and applies to whichever queue the frame would land on.
     *
     * Dropping is safe in the way that matters. A peer page that is not answering is not mirroring
     * anything to a viewer either, so these frames have nowhere to be displayed; the share is already
     * dead and this only decides whether it also costs memory. Newest-dropped rather than
     * oldest-dropped because rrweb is incremental - the frames already queued are the ones the
     * viewer's mirror needs first.
     */
    override fun sendDom(json: String) {
        if (closed) return
        val accepted = runJs("window.__bossRtcSendDom && window.__bossRtcSendDom(${jsStr(json)});", domFrame = true)
        if (!accepted) {
            if (domDropWarned.compareAndSet(false, true)) {
                logger.warn(
                    LogCategory.BROWSER,
                    "WebRTC peer is not draining DOM frames - dropping until it catches up",
                    mapOf("backlog" to browserCall.backlog.toString(), "pending" to pendingDomFrames.toString()),
                )
            }
            return
        }
        // A drained queue, not merely an accepted frame. [MAX_DOM_BACKLOG] is small, so a peer
        // draining just fast enough to sit at the cap crosses it constantly, and clearing on every
        // accept turns "one warning per stall" into one per crossing.
        if (browserCall.backlog == 0 && pendingDomFrames == 0) domDropWarned.set(false)
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
        bridge.onAnswer = null
        bridge.onIce = null
        bridge.onInput = null
        bridge.onState = null
        bridge.onVideoError = null
        bridge.onVideoState = null
        // A peer that closed before its page ever answered still holds every op that raced ahead of
        // it, DOM payloads included, until the object itself is collected. `closed` already stops
        // anything from being flushed, so these are dead weight.
        synchronized(pending) {
            pending.clear()
            pendingDomFrames = 0
        }
        scope.launch {
            try {
                browser?.takeIf { !it.isClosed }?.close()
            } catch (_: Throwable) {
            }
            browser = null
        }
        // Deliberately no scope.cancel(): the browser close above is already queued on this thread
        // and cancelling would drop it before it starts.
        //
        // Known cost: if a wedged JS call is holding the thread, that queued close never runs and
        // this peer's hidden browser leaks until process exit. One browser per failed share, against
        // a frozen application - the trade this whole change is making, and bounded because the
        // thread is daemon.
        browserCall.shutdown()
    }

    private companion object {
        /**
         * How many un-started DOM frames may be waiting - on the peer's thread, or in [pending]
         * before the page has answered - before new ones are dropped.
         *
         * Small on purpose: anything past a handful means the peer page is not draining, and the
         * frames behind it are already too stale to be worth the memory.
         */
        const val MAX_DOM_BACKLOG = 8

        fun jsStr(s: String): String = Json.encodeToString(String.serializer(), s)

        // Must stay in lockstep with the plugin's BrowserShareManager.iceServers():
        // both peers read the same boss.cobrowse.turn / free default so they agree.
        // STUN alone fails across most real cross-network NATs (symmetric / mobile
        // CGNAT); TURN is what lets a remote viewer's media actually connect.
        fun buildIceServersJson(): String {
            fun cfg(
                prop: String,
                env: String,
            ) = System.getProperty(prop)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() }
            val sb =
                StringBuilder(
                    """[{"urls":"stun:stun.l.google.com:19302"},{"urls":"stun:stun1.l.google.com:19302"}""",
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

    fun baseUrl(): String {
        ensure()
        return "http://127.0.0.1:$port/"
    }

    @Synchronized
    private fun ensure() {
        if (port > 0) return
        val html =
            (javaClass.getResourceAsStream("/webrtc/rtc-host.html")?.use { it.readBytes() })
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
