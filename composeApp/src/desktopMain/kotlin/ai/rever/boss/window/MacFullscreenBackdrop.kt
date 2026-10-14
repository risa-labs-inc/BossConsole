package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.clazz
import ai.rever.boss.window.MacToolbarRuntime.number
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.send
import ai.rever.boss.window.MacToolbarRuntime.string
import com.sun.jna.Pointer

/** Owned, in-window wallpaper for fullscreen Spaces, which have no desktop behind the glass. */
internal class MacFullscreenBackdrop(
    private val window: Pointer,
) : AutoCloseable {
    private var view: Pointer? = null
    private var image: Pointer? = null
    private var imageUrl: String? = null
    private var screenId: Long? = null

    /** Called only on AppKit's main queue. Cache the desktop image before entering fullscreen. */
    fun refresh(
        effect: Pointer,
        request: GlassRequest,
    ) {
        cacheWallpaper(request.fullscreen)
        if (!request.fullscreen) {
            detach()
            return
        }
        val parent = checkNotNull(pointer(effect, "superview"))
        val backdrop = view ?: create().also { view = it }
        if (pointer(backdrop, "superview") != parent) {
            send(backdrop, "removeFromSuperview")
            send(parent, "addSubview:positioned:relativeTo:", backdrop, -1L, effect)
        }
        send(backdrop, "setFrameSize:", GlassSize(request.size.width.toDouble(), request.size.height.toDouble()))
        val layer = checkNotNull(pointer(backdrop, "layer"))
        val color = pointer(clazz("NSColor"), "colorWithCalibratedWhite:alpha:", if (request.dark) 0.12 else 0.92, 1.0)
        send(layer, "setBackgroundColor:", pointer(color, "CGColor"))
        // CALayer retains this CGImage. Aspect-fill avoids stretching or black letterboxing.
        val contents = image?.let { pointer(it, "CGImageForProposedRect:context:hints:", null, null, null) }
        send(layer, "setContents:", contents)
    }

    private fun create(): Pointer {
        val backdrop = checkNotNull(pointer(pointer(clazz("NSView"), "alloc"), "init"))
        // Record ownership before configuration, so failure cleanup can release it.
        view = backdrop
        send(backdrop, "setIdentifier:", string("boss.fullscreen.wallpaper"))
        send(backdrop, "setAutoresizingMask:", 18L)
        send(backdrop, "setWantsLayer:", 1.toByte())
        val layer = checkNotNull(pointer(backdrop, "layer"))
        send(layer, "setContentsGravity:", string("resizeAspectFill"))
        send(layer, "setMasksToBounds:", 1.toByte())
        return backdrop
    }

    private fun cacheWallpaper(fullscreen: Boolean) {
        val screen = pointer(window, "screen") ?: return
        val description = pointer(screen, "deviceDescription")
        val display = pointer(description, "objectForKey:", string("NSScreenNumber"))
        val currentScreen = number(display, "longLongValue")
        if (screenId != currentScreen) {
            clearImage()
            screenId = currentScreen
        }
        val workspace = pointer(clazz("NSWorkspace"), "sharedWorkspace")
        val url = pointer(workspace, "desktopImageURLForScreen:", screen)
        // A fullscreen Space may not report its wallpaper; retain this display's windowed image.
        if (url == null && fullscreen) return
        val key = pointer(pointer(url, "absoluteString"), "UTF8String")?.getString(0)
        if (key != imageUrl) {
            clearImage()
            imageUrl = key
            if (url != null && number(url, "isFileURL") and 0xffL != 0L) {
                // Dynamic/video wallpapers without a readable still image use the theme fallback.
                image = pointer(pointer(clazz("NSImage"), "alloc"), "initWithContentsOfURL:", url)
            }
        }
    }

    private fun detach() {
        view?.let {
            send(it, "removeFromSuperview")
            send(it, "release")
        }
        view = null
    }

    private fun clearImage() {
        image?.let { send(it, "release") }
        image = null
        imageUrl = null
    }

    override fun close() {
        detach()
        clearImage()
        screenId = null
    }
}
