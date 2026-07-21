package ai.rever.boss.plugin.browser

import com.teamdev.jxbrowser.js.JsAccessible

/**
 * JS→host bridge for the WebRTC co-browse peer that runs inside a hidden
 * JxBrowser page (see [CoBrowseRtcPeerImpl]). Injected onto the page's
 * `window.__bossRtcBridge`. The peer's JS calls these as SDP/ICE/data-channel
 * events occur; each forwards to a non-blocking callback.
 *
 * All methods are invoked on a JxBrowser thread and swallow exceptions so a
 * misbehaving callback can never crash the page's JS thread.
 */
internal class CoBrowseRtcBridge(
    @Volatile var onAnswer: ((String) -> Unit)? = null,
    @Volatile var onIce: ((String) -> Unit)? = null,
    @Volatile var onInput: ((String) -> Unit)? = null,
    @Volatile var onState: ((Boolean) -> Unit)? = null,
) {
    @JsAccessible
    fun answer(sdp: String) { runCatching { onAnswer?.invoke(sdp) } }

    @JsAccessible
    fun ice(candidateJson: String) { runCatching { onIce?.invoke(candidateJson) } }

    @JsAccessible
    fun input(json: String) { runCatching { onInput?.invoke(json) } }

    @JsAccessible
    fun state(connected: Boolean) { runCatching { onState?.invoke(connected) } }

    @JsAccessible
    fun videoError(msg: String) { runCatching { onVideoError?.invoke(msg) } }

    @JsAccessible
    fun videoState(msg: String) { runCatching { onVideoState?.invoke(msg) } }

    @Volatile var onVideoError: ((String) -> Unit)? = null
    @Volatile var onVideoState: ((String) -> Unit)? = null
}
