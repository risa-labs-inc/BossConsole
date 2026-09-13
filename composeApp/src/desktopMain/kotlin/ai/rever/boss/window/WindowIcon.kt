package ai.rever.boss.window

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.AWTEvent
import java.awt.Frame
import java.awt.Image
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * The BOSS icon, as every window BOSS opens should wear it.
 *
 * `jpackage` puts `boss_icon.ico` on the launcher `.exe` (see `composeApp/build.gradle.kts`), which
 * is why the Start-menu entry and the executable have always looked right - but that icon never
 * reaches a live AWT window. A Compose `Window` given no `icon` leaves `Frame.iconImages` empty, and
 * Windows then draws the JDK's default Java icon in the title bar, the taskbar button and the
 * Alt-Tab card. macOS takes its icon from the `.app` bundle and ignores per-window icons entirely,
 * and Linux takes it from the `.desktop` file - which is why this only ever showed up on Windows,
 * and why it went unnoticed: every window except the main one was affected.
 *
 * Every window ends up with [images], the multi-resolution list, but by two routes:
 *
 * - A Compose `Window` / `DialogWindow` passes [painter] as its `icon` (applied before the window is
 *   shown, so nothing flashes) and then calls [ApplyBossWindowIcon], which upgrades it to the list.
 * - A raw AWT [Frame] assigns [images] directly.
 *
 * Both read one decode.
 *
 * The five `Heavyweight*` overlays are branded too, which is worth stating because it looks
 * pointless: they are `undecorated` and `transparent`, so they have no title bar to put an icon in.
 * They are still plain `Frame`s with no `Window.Type` set, so Windows gives them taskbar buttons and
 * Alt-Tab cards of their own - which is where an unbranded one shows a coffee cup.
 *
 * The find bar used to be the one exemption, as a `JDialog` with `Window.Type.UTILITY` and so no
 * icon surface at all. It is Compose content now (see `BrowserFindBar`), hosted by
 * `HeavyweightCorner`'s FOCUSABLE branch as an owned `ComposeDialog` that keeps the same window
 * type - so it still gets no taskbar button and no Alt-Tab card, and its owner stays active while it
 * holds focus. It is branded anyway, and is no longer an exemption: the type is set through a
 * `runCatching`, so a platform that refuses it leaves an ordinary dialog that does have an icon
 * surface.
 */
object BossWindowIcon {
    private val logger = BossLogger.forComponent("BossWindowIcon")

    /** Lives in `composeApp/src/desktopMain/resources`, so it lands at the jar root. */
    private const val ICON_RESOURCE = "/boss_icon.png"

    /**
     * Sizes Windows actually asks for: 16 for the title bar, 32 for the taskbar button and
     * Alt-Tab, the rest for high-DPI variants of those two. AWT picks the nearest and scales, so
     * handing it pre-scaled variants beats letting it squeeze one 256px bitmap into 16px.
     */
    private val derivedSizes = intArrayOf(16, 20, 24, 32, 40, 48, 64, 128)

    /** The 256x256 source, decoded once. Null only if the resource cannot be read. */
    private val source: BufferedImage? by lazy { decodeSource() }

    /**
     * Multi-resolution icon list for `Window.iconImages`.
     *
     * Empty rather than throwing when the resource cannot be read. Callers include
     * [ai.rever.boss.crash.CrashHandler], which is already handling a crash - a missing icon must
     * not become the reason the crash report never gets shown. `iconImages = emptyList()` is
     * exactly the state every window was in before this existed, so the fallback is the old
     * behaviour rather than a new failure.
     */
    val images: List<BufferedImage> by lazy {
        val original = source ?: return@lazy emptyList()
        buildList {
            derivedSizes.forEach { size -> add(original.scaledTo(size)) }
            add(original)
        }
    }

    /**
     * The `icon` for a Compose `Window` or `DialogWindow`.
     *
     * A plain shared value, not a `@Composable` returning `remember { … }`: it is a process-wide
     * singleton that never changes, so a remember slot per call site would buy nothing and would
     * make "one decode, one bitmap" less obvious than it is.
     *
     * Null when the resource could not be read, which is what `Window(icon = null)` already means.
     *
     * This is only half of what a Compose window needs. Compose rasterises the painter at a fixed
     * 192dp and calls the single-image `Window.setIconImage`, so on its own it leaves Windows to
     * squeeze one bitmap down to a 16px title bar - the exact downscale [derivedSizes] exists to
     * avoid. [ApplyBossWindowIcon] supplies the list; this supplies an icon early enough that the
     * window never appears unbranded first.
     */
    val painter: Painter? by lazy {
        source?.toComposeImageBitmap()?.let(::BitmapPainter)
    }

    private fun decodeSource(): BufferedImage? {
        // `/boss_icon.png` rather than the Compose resource: this has to be readable from plain AWT
        // code and from a crash handler, neither of which can call into a composition, and one
        // decode shared by both routes beats two loaders.
        val decoded =
            runCatching {
                javaClass.getResourceAsStream(ICON_RESOURCE)?.use(ImageIO::read)
            }.getOrElse { e ->
                logger.warn(LogCategory.UI, "Could not read $ICON_RESOURCE", error = e)
                null
            }
        if (decoded == null) {
            logger.warn(LogCategory.UI, missingIconReason())
        }
        return decoded
    }

    /**
     * Why there is no icon, resolved only when there isn't one.
     *
     * "Present but undecodable" and "absent" are different bugs in different places - a truncated PNG
     * versus a packaging mistake - and reporting the first as the second would send whoever debugs it
     * looking in the wrong place.
     */
    private fun missingIconReason(): String =
        if (javaClass.getResource(ICON_RESOURCE) == null) {
            "$ICON_RESOURCE is not on the classpath - windows will be unbranded"
        } else {
            "$ICON_RESOURCE is present but ImageIO could not decode it - windows will be unbranded"
        }

    /**
     * Downscales by repeated halving rather than one jump to the target.
     *
     * A single bicubic `drawImage` from 256 to 16 samples far too few source pixels and aliases -
     * roughly the same result as letting AWT squeeze the 256px bitmap itself, which would give back
     * most of the quality [derivedSizes] is here to buy. Halving keeps every source pixel
     * contributing. Runs once per process for eight small images, so the extra passes are free.
     */
    private fun BufferedImage.scaledTo(size: Int): BufferedImage {
        var current = this
        // Halve while the result would still be at or above the target, so the final step is always
        // a reduction of less than 2x - the range bicubic handles well.
        var next = current.width / 2
        while (next > size) {
            current = current.drawnInto(next)
            next /= 2
        }
        return current.drawnInto(size)
    }

    private fun BufferedImage.drawnInto(size: Int): BufferedImage {
        val scaled = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = scaled.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(this, 0, 0, size, size, null)
        } finally {
            g.dispose()
        }
        return scaled
    }
}

/**
 * Gives the enclosing Compose window the multi-resolution icon.
 *
 * Call inside a `Window { }` / `DialogWindow { }` body, passing the scope's `window`. Works for both
 * because `java.awt.Window.setIconImages` is defined on `Window`, not only on `Frame`.
 *
 * Keyed on `window` via [DisposableEffect], not a bare `SideEffect`: the latter would re-assign and
 * re-push the native icon on every recomposition of the window content, which for the overlay
 * windows is constantly.
 *
 * Safe alongside `icon = BossWindowIcon.painter`. Compose applies `icon` from its own update pass
 * only when the value changes, and that value is a stable singleton, so it lands once at window
 * creation and never fights this. The ordering is deliberate: `icon` gets a correct - if
 * single-resolution - icon onto the window before it is shown, and this upgrades it to the list.
 */
@Composable
fun ApplyBossWindowIcon(window: java.awt.Window) {
    DisposableEffect(window) {
        val icons = BossWindowIcon.images
        if (icons.isNotEmpty()) {
            runCatching { window.iconImages = icons }
        }
        onDispose {}
    }
}

/**
 * Brands windows BOSS does not create itself.
 *
 * JxBrowser raises Swing dialogs of its own, `JFileChooser` makes its own frames, a plugin can open
 * one, and the next window added to this app can forget its icon. This catches all of them.
 *
 * A net, not the fix. `WINDOW_OPENED` is delivered after `setVisible(true)`, so a window caught here
 * shows the Java icon for one frame first - invisible for a foreign dialog, but not good enough for
 * ours, which is why every window in this repo brands itself and is held to it by
 * `WindowIconConventionTest`.
 *
 * Only [Frame] is touched: `java.awt.Dialog` has no icon of its own and takes its owner frame's, so
 * branding the frames brands the dialogs over them.
 */
object DefaultWindowIcon {
    private val logger = BossLogger.forComponent("DefaultWindowIcon")

    /**
     * Registers the listener. Call once, from `main`, after the paths that exit without a window and
     * before the first window - see the call site for why both ends of that are fenced.
     */
    fun install() {
        runCatching {
            Toolkit.getDefaultToolkit().addAWTEventListener({ event ->
                if (event.id == WindowEvent.WINDOW_OPENED) {
                    ((event as? WindowEvent)?.window as? Frame)?.let(::brandIfUnbranded)
                }
            }, AWTEvent.WINDOW_EVENT_MASK)
        }.onFailure { e ->
            // A SecurityManager can refuse the listener. Losing the net is survivable; every window
            // this app opens brands itself.
            logger.warn(LogCategory.UI, "Could not install the default window icon listener", error = e)
        }
    }

    private fun brandIfUnbranded(frame: Frame) {
        iconsToApply(frame.iconImages)?.let { icons -> runCatching { frame.iconImages = icons } }
    }

    /**
     * The listener's actual decision: what to assign to a frame that already carries [existing],
     * or null to leave it alone.
     *
     * Pure, and separated out for two reasons. It is the interesting half, and testing it through a
     * real [Frame] would not work anyway - constructing one throws `HeadlessException`, so any such
     * test would pass here and fail on every CI runner.
     *
     * Reads [BossWindowIcon.images] here rather than in [install]: forcing the decode at registration
     * time would put a PNG read and eight rescales on the startup critical path, for a list only
     * needed if an unbranded foreign window ever actually opens.
     */
    internal fun iconsToApply(existing: List<Image>): List<BufferedImage>? {
        // Only when nothing set one: an explicit icon - including a deliberately different one - is
        // never overwritten here.
        if (existing.isNotEmpty()) return null
        return BossWindowIcon.images.ifEmpty { null }
    }
}
