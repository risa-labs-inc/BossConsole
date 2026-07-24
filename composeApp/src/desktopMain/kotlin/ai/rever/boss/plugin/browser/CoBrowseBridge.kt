package ai.rever.boss.plugin.browser

import com.teamdev.jxbrowser.js.JsAccessible

/**
 * Page→host bridge for the co-browse (tab-sharing) feature.
 *
 * An instance is injected onto each shared page's `window.__bossCoBrowse`
 * (see [BrowserHandleImpl.startCoBrowseCapture]). The injected rrweb recorder
 * calls [emit] with each serialized event (full snapshot / incremental mutation /
 * scroll / input). [onEvent] forwards the JSON to the host-side co-browse sink,
 * which fans it out to connected viewers.
 *
 * IMPORTANT: [emit] is invoked on a JxBrowser thread and can fire at high
 * frequency on mutation-heavy pages, so [onEvent] MUST be non-blocking (it should
 * do nothing more than a non-blocking enqueue per viewer). Exceptions are
 * swallowed so a misbehaving sink can never crash the page's JS thread.
 */
internal class CoBrowseBridge(
    @Volatile var onEvent: ((String) -> Unit)? = null,
) {
    @JsAccessible
    fun emit(json: String) {
        try {
            onEvent?.invoke(json)
        } catch (_: Throwable) {
            // Never propagate into the page's JS thread.
        }
    }
}
