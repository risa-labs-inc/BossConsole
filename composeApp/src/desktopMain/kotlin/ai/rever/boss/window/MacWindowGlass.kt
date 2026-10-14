package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.clazz
import ai.rever.boss.window.MacToolbarRuntime.number
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.send
import ai.rever.boss.window.MacToolbarRuntime.string
import com.sun.jna.Pointer
import com.sun.jna.Structure
import javax.swing.SwingUtilities

/**
 * BossTerm's native-backdrop approach, sharing BossConsole's existing AppKit dispatcher.
 * Never replace or reparent AWT's contentView: AWT dispatches private mouse selectors to it.
 * The view sits behind that content, uses logical points, and owns no layout constraints.
 */
internal class MacWindowGlass(
    handle: Long,
    private val onInstalled: (Boolean) -> Unit,
) : AutoCloseable {
    private val window = Pointer(handle)
    private val fullscreenBackdrop = MacFullscreenBackdrop(window)

    @Volatile private var closed = false
    private var view: Pointer? = null
    private var configured = false

    @Suppress("TooGenericExceptionCaught")
    fun update(request: GlassRequest) {
        if (closed) return
        MacToolbarRuntime.dispatch {
            if (closed || !MacToolbarRuntime.isLiveWindow(window)) return@dispatch
            var installed = false
            try {
                configureFrame()
                if (request.enabled && !reduceTransparency()) {
                    if (view == null) view = install()
                    view?.let {
                        refresh(it, request)
                        installed = true
                    }
                } else {
                    remove()
                }
            } catch (error: Exception) {
                remove()
                org.slf4j.LoggerFactory
                    .getLogger(MacWindowGlass::class.java)
                    .warn("Native glass unavailable; keeping opaque surfaces", error)
            }
            SwingUtilities.invokeLater { if (!closed) onInstalled(installed) }
        }
    }

    private fun configureFrame() {
        if (configured) return
        // Keep Java undecorated for alpha; AppKit supplies native frame and traffic lights.
        send(window, "setStyleMask:", number(window, "styleMask") or 1L or 2L or 4L or 8L or 32768L)
        send(window, "setTitlebarAppearsTransparent:", 1.toByte())
        send(window, "setTitleVisibility:", 1L)
        send(window, "setHasShadow:", 1.toByte())
        configured = true
    }

    private fun reduceTransparency(): Boolean {
        val workspace = pointer(clazz("NSWorkspace"), "sharedWorkspace")
        return MacToolbarRuntime.supports(workspace, "accessibilityDisplayShouldReduceTransparency") &&
            number(workspace, "accessibilityDisplayShouldReduceTransparency") and 0xffL != 0L
    }

    private fun refresh(
        effect: Pointer,
        request: GlassRequest,
    ) {
        val content = checkNotNull(pointer(window, "contentView"))
        val parent = checkNotNull(pointer(content, "superview"))
        if (pointer(effect, "superview") != parent) {
            send(effect, "removeFromSuperview")
            send(parent, "addSubview:positioned:relativeTo:", effect, -1L, content)
        }
        send(effect, "setFrameSize:", GlassSize(request.size.width.toDouble(), request.size.height.toDouble()))
        fullscreenBackdrop.refresh(effect, request)
        if (MacToolbarRuntime.supports(effect, "setBlendingMode:")) {
            // Fullscreen glass samples our wallpaper sibling; windowed glass samples the desktop.
            send(effect, "setBlendingMode:", if (request.fullscreen) 1L else 0L)
        }
        val name = if (request.dark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua"
        val appearance = pointer(clazz("NSAppearance"), "appearanceNamed:", string(name))
        send(effect, "setAppearance:", appearance)
        send(window, "setAppearance:", appearance)
        if (MacToolbarRuntime.supports(effect, "setStyle:")) {
            send(effect, "setStyle:", if (request.clear) 1L else 0L)
            // BossTerm lets the native frame shape the backdrop; a second rounded lens
            // changes the edge refraction and appearance of the sidebar and toolbar.
            send(effect, "setCornerRadius:", 0.0)
        }
    }

    private fun install(): Pointer? {
        val glass = clazz("NSGlassEffectView")
        val type = glass ?: clazz("NSVisualEffectView") ?: return null
        val content = checkNotNull(pointer(window, "contentView"))
        val parent = checkNotNull(pointer(content, "superview"))
        val effect = checkNotNull(pointer(pointer(type, "alloc"), "init"))
        // Record ownership before any subsequent call can fail.
        view = effect
        if (glass == null) {
            send(effect, "setMaterial:", 7L)
            send(effect, "setBlendingMode:", 0L)
            send(effect, "setState:", 0L)
        }
        send(effect, "setAutoresizingMask:", 18L)
        send(parent, "addSubview:positioned:relativeTo:", effect, -1L, content)
        return effect
    }

    private fun remove() {
        fullscreenBackdrop.close()
        view?.let {
            send(it, "removeFromSuperview")
            send(it, "release")
        }
        view = null
    }

    override fun close() {
        closed = true
        MacToolbarRuntime.dispatch { remove() }
    }
}

/** Objective-C structure argument by value; no architecture-dependent structure return. */
@Structure.FieldOrder("width", "height")
internal class GlassSize(
    @JvmField var width: Double = 0.0,
    @JvmField var height: Double = 0.0,
) : Structure(),
    Structure.ByValue

internal data class GlassRequest(
    val enabled: Boolean,
    val size: androidx.compose.ui.unit.IntSize,
    val dark: Boolean,
    val clear: Boolean,
    val fullscreen: Boolean,
)
