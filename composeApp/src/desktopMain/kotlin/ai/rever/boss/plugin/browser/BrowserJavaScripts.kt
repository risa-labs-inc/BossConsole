package ai.rever.boss.plugin.browser

/**
 * Centralized repository of JavaScript code snippets used in JxBrowser.
 *
 * This object contains all JavaScript code executed in the browser context,
 * making it easier to maintain, test, and reuse across the codebase.
 *
 * Benefits:
 * - Keeps JxBrowserCompose.kt cleaner and more focused on UI logic
 * - Provides a single source of truth for browser JavaScript
 * - Makes JavaScript code easier to find, update, and document
 * - Enables future testing of JavaScript snippets if needed
 */
object BrowserJavaScripts {
    /**
     * JavaScript to inject for Cmd+Click (Mac) / Ctrl+Click (Windows/Linux) to open links in new tabs.
     * Should be injected once after page load.
     *
     * When the user holds Cmd/Ctrl and clicks on a link, this intercepts the click,
     * prevents the default navigation, and calls window.open() with _blank target.
     * JxBrowser's OpenPopupCallback then routes this to open as a new tab.
     *
     * Uses capture phase (true) to intercept before normal click handlers.
     *
     * The guards all exist because this hijacks the click before the page sees it, so anything
     * it claims wrongly is a link the page can no longer handle itself:
     * - only the primary button, and only when nothing has already cancelled the click;
     * - never a `download` anchor, whose whole point is not to navigate;
     * - only http(s), so `javascript:`, `mailto:`, `blob:` and `data:` stay with the page.
     */
    val injectCmdClickHandler =
        """
        (function() {
            if (!window._cmdClickHandlerAdded) {
                document.addEventListener('click', function(event) {
                    if (!(event.metaKey || event.ctrlKey)) return;
                    if (event.button !== 0 || event.defaultPrevented) return;
                    const link = event.target.closest('a');
                    if (!link || !link.href) return;
                    if (link.hasAttribute('download')) return;
                    const protocol = link.protocol;
                    if (protocol !== 'http:' && protocol !== 'https:') return;
                    event.preventDefault();
                    event.stopPropagation();
                    window.open(link.href, '_blank');
                }, true);
                window._cmdClickHandlerAdded = true;
            }
        })();
        """.trimIndent()

    /**
     * Generate JavaScript to find a link element at given screen coordinates.
     *
     * Uses document.elementFromPoint() to find the element, then traverses up
     * the DOM tree to find the nearest anchor tag with an href.
     *
     * **Usage**: `frame.executeJavaScript<String?>(BrowserJavaScripts.getLinkAtPoint(x, y))`
     *
     * @param x The x coordinate in the viewport
     * @param y The y coordinate in the viewport
     * @return JavaScript code that returns the link URL or null
     */
    fun getLinkAtPoint(
        x: Int,
        y: Int,
    ): String =
        """
        (function() {
            var el = document.elementFromPoint($x, $y);
            while (el) {
                if (el.tagName === 'A' && el.href) return el.href;
                el = el.parentElement;
            }
            return null;
        })()
        """.trimIndent()

    /**
     * Enable Picture-in-Picture mode for videos on the page.
     *
     * Attempts to find and activate PiP on:
     * 1. YouTube's main video player
     * 2. The only video on the page
     * 3. The largest visible video (if multiple)
     *
     * Toggles PiP off if already active.
     *
     * **Usage**: `frame.executeJavaScript<Unit>(BrowserJavaScripts.enablePictureInPicture)`
     */
    val enablePictureInPicture =
        """
        (function() {
            // Find all video elements on the page
            const videos = document.querySelectorAll('video');

            // For YouTube and similar sites, find the main video player
            let targetVideo = null;

            // Check for YouTube specific video
            const ytVideo = document.querySelector('video.html5-main-video, video.video-stream');
            if (ytVideo) {
                targetVideo = ytVideo;
            } else if (videos.length === 1) {
                // If there's only one video, use it
                targetVideo = videos[0];
            } else if (videos.length > 1) {
                // If multiple videos, try to find the visible one
                for (let video of videos) {
                    const rect = video.getBoundingClientRect();
                    if (rect.width > 100 && rect.height > 100 &&
                        video.readyState >= 2) { // HAVE_CURRENT_DATA
                        targetVideo = video;
                        break;
                    }
                }
            }

            if (targetVideo) {
                if (document.pictureInPictureElement) {
                    document.exitPictureInPicture();
                } else if (targetVideo.requestPictureInPicture) {
                    targetVideo.requestPictureInPicture().catch(err => {
                        console.error('PiP failed:', err);
                    });
                }
            }
        })();
        """.trimIndent()
}
