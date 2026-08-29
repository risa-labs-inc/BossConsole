package ai.rever.boss.plugin.browser

/**
 * A URL is usable as a tab destination only when it names something to load. A popup starts
 * life on the empty document, so `about:blank` here means "not resolved yet", never "go there".
 */
internal fun usablePopupUrl(url: String?): String? = url?.takeIf { it.isNotEmpty() && it != "about:blank" }

/**
 * Decide where an adopted popup's new tab should go, and whether a POST body rides along.
 *
 * The navigation is the authority, with the URL Chromium reported at popup-creation time as the
 * fallback for a popup that never got as far as starting one.
 *
 * A capture may only ever contribute the *body*. It used to be able to override the destination,
 * which is how a tab ended up on `internal-analytics.odoo.com/api/event` and
 * `collector.github.com/github/collect`: the engine-wide upload callback claimed whichever POST
 * the popup's page fired first, and a page's own analytics beacon beats its main-frame request
 * whenever the navigation URL is slow to resolve. The body is attached only when the capture
 * describes the same request we are actually navigating to - a mismatched body would be stashed
 * under the wrong key and never consumed anyway, since `stashPopupPost`/`consumePopupPost` key on
 * the exact URL string.
 *
 * @return the tab to open, or null when no destination could be resolved - in which case the
 *   popup is dropped rather than sent somewhere arbitrary.
 */
internal fun popupDestination(
    navigationUrl: String?,
    createTargetUrl: String?,
    capture: PopupCapture?,
): PopupNavigation? {
    val url = usablePopupUrl(navigationUrl) ?: usablePopupUrl(createTargetUrl) ?: return null
    // Fragments are compared away because Chromium does not send one to the network: a form
    // posting to `/print#page2` reaches the upload callback as `/print`, and demanding an exact
    // match would silently downgrade that submission to a GET.
    val body = capture?.takeIf { it.url.substringBefore('#') == url.substringBefore('#') }
    return PopupNavigation(
        url = url,
        postData = body?.body,
        contentType = body?.contentType,
    )
}
