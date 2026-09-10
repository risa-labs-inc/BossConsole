package ai.rever.boss.plugin.browser

import com.teamdev.jxbrowser.media.MediaType
import com.teamdev.jxbrowser.menu.ContextMenuContentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Truth table for [ContextMenuTarget.toContextMenuInfo] — the mapping from what Chromium reports about a
 * right-click onto the plugin-facing info. Pure, so none of this needs a live browser.
 *
 * Page identity (url, title) is the caller's to apply and is not asserted here.
 */
class ContextMenuInfoMappingTest {
    private fun info(
        contentTypes: List<ContextMenuContentType> = emptyList(),
        mediaType: MediaType = MediaType.NONE,
        srcUrl: String = "",
        linkUrl: String = "",
        selectedText: String = "",
        isMainFrame: Boolean = true,
    ) = ContextMenuTarget(
        contentTypes = contentTypes,
        mediaType = mediaType,
        srcUrl = srcUrl,
        linkUrl = linkUrl,
        selectedText = selectedText,
        isMainFrame = isMainFrame,
    ).toContextMenuInfo(pageUrl = "https://example.com/", pageTitle = "Example")

    @Test
    fun `blank strings become null rather than empty`() {
        val result = info(linkUrl = "", selectedText = "   ")

        assertNull(result.linkUrl)
        assertNull(result.selectedText)
    }

    @Test
    fun `a link target carries its url`() {
        assertEquals("https://example.com/next", info(linkUrl = "https://example.com/next").linkUrl)
    }

    @Test
    fun `an image is reported from either the media type or the content type`() {
        val byMediaType = info(mediaType = MediaType.IMAGE, srcUrl = "https://example.com/cat.png")
        val byContentType =
            info(
                contentTypes = listOf(ContextMenuContentType.MEDIA_IMAGE),
                srcUrl = "https://example.com/cat.png",
            )

        assertTrue(byMediaType.hasImage)
        assertEquals("https://example.com/cat.png", byMediaType.imageUrl)
        assertTrue(byContentType.hasImage)
        assertEquals("https://example.com/cat.png", byContentType.imageUrl)
    }

    @Test
    fun `an image with no source url is not reported as an image`() {
        // Chromium reports MEDIA_IMAGE for canvases and CSS backgrounds, which have no
        // address — every image action needs one, so hasImage must not promise otherwise.
        val result = info(contentTypes = listOf(ContextMenuContentType.MEDIA_IMAGE), srcUrl = "")

        assertFalse(result.hasImage)
        assertNull(result.imageUrl)
    }

    @Test
    fun `a video source url is not mistaken for an image url`() {
        val result = info(mediaType = MediaType.VIDEO, srcUrl = "https://example.com/clip.mp4")

        assertTrue(result.hasVideo)
        assertFalse(result.hasImage)
        assertNull(result.imageUrl)
    }

    @Test
    fun `an editable field in the main frame is editable`() {
        assertTrue(info(contentTypes = listOf(ContextMenuContentType.EDITABLE)).isEditable)
    }

    @Test
    fun `an editable field inside an iframe is offered edit actions`() {
        // Editor commands target the focused frame, and credential filling is owned by the
        // caller that identified the clicked element, so the old main-frame gate is obsolete.
        val result =
            info(
                contentTypes = listOf(ContextMenuContentType.EDITABLE),
                isMainFrame = false,
            )

        assertTrue(result.isEditable)
    }

    @Test
    fun `a non-editable subframe click still reports its link and selection`() {
        val result =
            info(
                linkUrl = "https://example.com/in-frame",
                selectedText = "picked",
                isMainFrame = false,
            )

        assertEquals("https://example.com/in-frame", result.linkUrl)
        assertEquals("picked", result.selectedText)
    }

    @Test
    fun `a plain page click reports no target of any kind`() {
        val result = info()

        assertNull(result.linkUrl)
        assertNull(result.selectedText)
        assertFalse(result.isEditable)
        assertFalse(result.hasImage)
        assertFalse(result.hasVideo)
    }

    @Test
    fun `audio is neither image nor video`() {
        val result =
            info(
                contentTypes = listOf(ContextMenuContentType.MEDIA_AUDIO),
                mediaType = MediaType.AUDIO,
                srcUrl = "https://example.com/track.mp3",
            )

        assertFalse(result.hasImage)
        assertFalse(result.hasVideo)
        assertNull(result.imageUrl)
    }

    @Test
    fun `a source url with no media signal is not surfaced`() {
        val result = info(linkUrl = "https://example.com/next", srcUrl = "https://example.com/x.bin")

        assertFalse(result.hasImage)
        assertNull(result.imageUrl)
    }

    @Test
    fun `an inline image too large to carry counts as having no address`() {
        // A data: source is the whole encoded image; no menu action needs the bytes, and
        // this is the first path that hands srcUrl to plugins.
        val result =
            info(
                mediaType = MediaType.IMAGE,
                srcUrl = "data:image/png;base64," + "A".repeat(MAX_INLINE_IMAGE_URL_LENGTH * 2),
            )

        assertFalse(result.hasImage)
        assertNull(result.imageUrl)
    }

    @Test
    fun `an inline image right at the cap is still carried`() {
        val prefix = "data:image/png;base64,"
        val atCap = prefix + "A".repeat(MAX_INLINE_IMAGE_URL_LENGTH - prefix.length)
        val result = info(mediaType = MediaType.IMAGE, srcUrl = atCap)

        assertTrue(result.hasImage)
        assertEquals(atCap, result.imageUrl)
    }

    @Test
    fun `a long http image url is not capped`() {
        // Signed CDN addresses carry policy and signature query strings and can run long;
        // dropping them would remove the image actions invisibly.
        val signed = "https://cdn.example.com/cat.png?" + "k=v&".repeat(700)
        val result = info(mediaType = MediaType.IMAGE, srcUrl = signed)

        assertTrue(signed.length > MAX_INLINE_IMAGE_URL_LENGTH)
        assertTrue(result.hasImage)
        assertEquals(signed, result.imageUrl)
    }

    @Test
    fun `a linked image reports both`() {
        val result =
            info(
                contentTypes = listOf(ContextMenuContentType.LINK, ContextMenuContentType.MEDIA_IMAGE),
                mediaType = MediaType.IMAGE,
                srcUrl = "https://example.com/cat.png",
                linkUrl = "https://example.com/next",
            )

        assertEquals("https://example.com/next", result.linkUrl)
        assertTrue(result.hasImage)
    }
}
