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
     * Get the currently selected text in the browser.
     *
     * Uses window.getSelection() API to retrieve selected text.
     * Returns trimmed text or null if no selection exists.
     *
     * **Usage**: `frame.executeJavaScript<String?>(BrowserJavaScripts.getSelectedText)`
     *
     * @return Selected text (trimmed) or null
     */
    val getSelectedText =
        """
        (function() {
            const sel = window.getSelection();
            return sel ? sel.toString().trim() : null;
        })();
        """.trimIndent()

    /**
     * Get the URL of a right-clicked link.
     *
     * Uses window._rightClickedLinkUrl which is set by the context menu
     * event listener in JxBrowserCompose.
     *
     * **Usage**: `frame.executeJavaScript<String?>(BrowserJavaScripts.getRightClickedLinkUrl)`
     *
     * @return Link URL or null
     */
    val getRightClickedLinkUrl =
        """
        (function() {
            return window._rightClickedLinkUrl || null;
        })();
        """.trimIndent()

    /**
     * Check if there are any video elements on the current page.
     *
     * Checks for:
     * - Standard HTML5 <video> elements
     * - YouTube-specific video selectors (html5-main-video, video-stream)
     *
     * **Usage**: `frame.executeJavaScript<Boolean>(BrowserJavaScripts.hasVideoElements)`
     *
     * @return true if video elements exist, false otherwise
     */
    val hasVideoElements =
        """
        (function() {
            const videos = document.querySelectorAll('video');
            const ytVideo = document.querySelector('video.html5-main-video, video.video-stream');
            return videos.length > 0 || ytVideo !== null;
        })();
        """.trimIndent()

    /**
     * Check if the right-clicked element is a video or inside a video container.
     *
     * This stores the result when contextmenu event fires and retrieves it.
     * More accurate than hasVideoElements for showing PiP option only on video right-click.
     *
     * **Usage**: `frame.executeJavaScript<Boolean>(BrowserJavaScripts.isClickedOnVideo)`
     *
     * @return true if clicked on/inside video element, false otherwise
     */
    val isClickedOnVideo =
        """
        (function() {
            return window._rightClickedOnVideo || false;
        })();
        """.trimIndent()

    /**
     * JavaScript to inject for tracking right-click on video elements.
     * Should be injected once after page load.
     *
     * Sets window._rightClickedOnVideo to true/false based on whether
     * the contextmenu event target is a video that supports Picture-in-Picture.
     *
     * Uses native PiP API checks (works across all sites):
     * - document.pictureInPictureEnabled: browser supports PiP
     * - !video.disablePictureInPicture: video element allows PiP
     * - video.readyState >= 1: video has loaded metadata (filters unloaded previews)
     * - video.videoWidth > 0: video actually has a video track
     */
    val injectVideoClickTracker =
        """
        (function() {
            if (!window._videoClickTrackerAdded) {
                document.addEventListener('contextmenu', function(event) {
                    var el = event.target;
                    window._rightClickedOnVideo = false;
                    while (el) {
                        if (el.tagName === 'VIDEO') {
                            // Use native PiP API checks (general, works on all sites)
                            var pipSupported = document.pictureInPictureEnabled === true;
                            var pipAllowed = el.disablePictureInPicture !== true;
                            var hasMetadata = el.readyState >= 1;  // HAVE_METADATA or higher
                            var hasVideoTrack = el.videoWidth > 0 && el.videoHeight > 0;

                            if (pipSupported && pipAllowed && hasMetadata && hasVideoTrack) {
                                window._rightClickedOnVideo = true;
                            }
                            break;
                        }
                        el = el.parentElement;
                    }
                }, true);
                window._videoClickTrackerAdded = true;
            }
        })();
        """.trimIndent()

    /**
     * JavaScript to inject for tracking right-click on link elements.
     * Should be injected once after page load.
     *
     * Sets window._rightClickedLinkUrl to the href of the clicked link,
     * or null if not clicking on a link.
     *
     * This enables the context menu to show "Copy Link URL" and "Open Link in New Tab"
     * options when right-clicking on a link.
     */
    val injectLinkClickTracker =
        """
        (function() {
            if (!window._linkClickTrackerAdded) {
                document.addEventListener('contextmenu', function(event) {
                    const link = event.target.closest('a');
                    if (link && link.href) {
                        window._rightClickedLinkUrl = link.href;
                    } else {
                        window._rightClickedLinkUrl = null;
                    }
                }, true);
                window._linkClickTrackerAdded = true;
            }
        })();
        """.trimIndent()

    /**
     * JavaScript to inject for Cmd+Click (Mac) / Ctrl+Click (Windows/Linux) to open links in new tabs.
     * Should be injected once after page load.
     *
     * When the user holds Cmd/Ctrl and clicks on a link, this intercepts the click,
     * prevents the default navigation, and calls window.open() with _blank target.
     * JxBrowser's OpenPopupCallback then routes this to open as a new tab.
     *
     * Uses capture phase (true) to intercept before normal click handlers.
     */
    val injectCmdClickHandler =
        """
        (function() {
            if (!window._cmdClickHandlerAdded) {
                document.addEventListener('click', function(event) {
                    const link = event.target.closest('a');
                    if (link && link.href) {
                        if (event.metaKey || event.ctrlKey) {
                            event.preventDefault();
                            event.stopPropagation();
                            window.open(link.href, '_blank');
                        }
                    }
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
