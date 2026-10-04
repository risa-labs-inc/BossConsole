package ai.rever.boss.sharing

import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.window.MacToolbarInput
import ai.rever.boss.window.MacToolbarInputTarget
import ai.rever.boss.window.OwnedWindowControls
import ai.rever.boss.window.WindowInputModalBoundary
import com.teamdev.jxbrowser.ui.KeyCode
import com.teamdev.jxbrowser.ui.KeyModifiers
import com.teamdev.jxbrowser.ui.MouseButton
import com.teamdev.jxbrowser.ui.Point
import com.teamdev.jxbrowser.ui.ScrollType
import com.teamdev.jxbrowser.ui.event.KeyPressed
import com.teamdev.jxbrowser.ui.event.KeyReleased
import com.teamdev.jxbrowser.ui.event.KeyTyped
import com.teamdev.jxbrowser.ui.event.MouseDragged
import com.teamdev.jxbrowser.ui.event.MouseMoved
import com.teamdev.jxbrowser.ui.event.MousePressed
import com.teamdev.jxbrowser.ui.event.MouseReleased
import com.teamdev.jxbrowser.ui.event.MouseWheel
import com.teamdev.jxbrowser.view.swing.BrowserView
import java.awt.Component
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

/** Routes only to descendants of the consented window. Never synthesizes global OS input. */
// Separate event translations keep AWT and browser authority checks explicit.
@Suppress("TooManyFunctions")
internal class AwtAppInputSink(
    private val window: Window,
    private val privateSurfaceAllowed: () -> Boolean = { true },
    private val requireForeground: Boolean = true,
    private val onCursor: (String) -> Unit = {},
    private val browserSurfaceAt: (Window, Int, Int) -> AppBrowserInputSurface? = ::appBrowserInputSurfaceAt,
) : AppScopedInputSink {
    private data class HeldPointer(
        val component: Component,
        val x: Int,
        val y: Int,
        val browserSurface: AppBrowserInputSurface? = null,
        val nativeTarget: MacToolbarInputTarget? = null,
    )

    private data class HeldKey(
        val component: Component,
        val event: AppInputEvent.Key,
        val browserSurface: AppBrowserInputSurface?,
    )

    private val authorityEpoch = AtomicLong()
    private val buttons = mutableMapOf<Int, HeldPointer>()
    private val keys = mutableMapOf<String, HeldKey>()

    private var surfaceSnapshot: AppSurfaceSnapshot? = null
    private var capturePaused = false
    private var keyboardTarget: Component? = null
    private var keyboardBrowser: AppBrowserInputSurface? = null
    private var keyboardNative: MacToolbarInputTarget? = null
    private val nativeKeys = mutableSetOf<String>()

    fun updateSurfaces(snapshot: AppSurfaceSnapshot) =
        onEdt {
            require(snapshot.surfaces.firstOrNull()?.window === window)
            require(snapshot.surfaces.all { isOwnedBy(it.window, window) })
            if (surfaceSnapshot != snapshot) releaseAll()
            surfaceSnapshot = snapshot
            capturePaused = false
        }

    /** Pixels may disappear before AWT reports native geometry changes. Recovery alone remains usable. */
    fun pauseCapture() =
        onEdt {
            if (!capturePaused) {
                capturePaused = true
                releaseAll()
            }
        }

    override fun isAvailable(): Boolean {
        if (!visibleInputWindow()) return false
        val snapshot = surfaceSnapshot
        val snapshotCurrent = snapshot == null || runCatching { captureSurfaceSnapshot(window) }.getOrNull() == snapshot
        val owned = snapshot?.surfaces?.map { it.window } ?: listOf(window)
        return snapshotCurrent && (!requireForeground || owned.any { it.isFocused }) &&
            (snapshot != null || window.ownedWindows.none { it.isShowing })
    }

    // Reject unverified surfaces and modal focus before admitting coordinate-free keys.
    @Suppress("ReturnCount")
    override fun isAvailableFor(event: AppInputEvent): Boolean {
        if (event is AppInputEvent.Window) return windowActionAvailable(event)
        if (event is AppInputEvent.Key && !selectedBrowserIsCurrent()) return false
        if (event !is AppInputEvent.Key || surfaceSnapshot == null) return isAvailable()
        val current = currentInputSurfaces() ?: return false
        val target = scoped(focusedComponent()) ?: return false
        val targetWindow = SwingUtilities.getWindowAncestor(target) ?: return false
        val modal = current.surfaces.lastOrNull { it.modal }?.window
        return current.surfaces.any { it.window === targetWindow } && target.isShowing &&
            (modal == null || isOwnedBy(targetWindow, modal)) &&
            (!requireForeground || current.surfaces.any { it.window.isFocused })
    }

    private fun minimized(): Boolean = window is Frame && window.extendedState and Frame.ICONIFIED != 0

    private fun visibleInputWindow(): Boolean {
        val visible = !capturePaused && window.isDisplayable && window.isShowing
        return visible && !minimized() && privateSurfaceAllowed()
    }

    private fun hasModalOwnedWindow(owner: Window): Boolean =
        owner.ownedWindows.any { child ->
            (child.isShowing && WindowInputModalBoundary.isModal(child)) || hasModalOwnedWindow(child)
        }

    private fun windowActionAvailable(event: AppInputEvent.Window): Boolean {
        val liveRoot = window.isDisplayable && window.isVisible && privateSurfaceAllowed()
        val localPolicy = (!requireForeground || window.isFocused) && !hasModalOwnedWindow(window)
        if (!liveRoot || !localPolicy) return false
        val recovery = event.action == "restore" || event.action == "exit-fullscreen"
        val registered = OwnedWindowControls.supports(window, event.action)
        return if (recovery) registered || MacToolbarInput.supportsRecovery(window) else registered && isAvailable()
    }

    private fun selectedBrowserIsCurrent(): Boolean {
        val nativeCurrent = keyboardNative?.isCurrent() != false
        return nativeCurrent && keyboardBrowser?.isCurrent() != false
    }

    private fun currentInputSurfaces(): AppSurfaceSnapshot? =
        if (visibleInputWindow()) {
            runCatching { captureSurfaceSnapshot(window) }.getOrNull()
        } else {
            null
        }

    private fun allowedWindow(candidate: Window?): Boolean =
        candidate != null &&
            (surfaceSnapshot?.surfaces?.any { it.window === candidate } ?: (candidate === window))

    private fun focusedComponent(): Component? {
        val local =
            surfaceSnapshot
                ?.surfaces
                ?.lastOrNull { it.window.isFocused }
                ?.window
                ?.focusOwner
                ?: window.focusOwner
        val remote = keyboardTarget?.takeIf { it.isDisplayable && allowedWindow(SwingUtilities.getWindowAncestor(it)) }
        val candidate = if (!requireForeground) remote ?: local else local ?: remote
        val modal = surfaceSnapshot?.surfaces?.lastOrNull { it.modal }?.window
        return candidate?.takeIf {
            val owner = SwingUtilities.getWindowAncestor(it)
            modal == null || (owner != null && isOwnedBy(owner, modal))
        }
    }

    override fun apply(event: AppInputEvent): Boolean = apply(event, Long.MAX_VALUE)

    override fun apply(
        event: AppInputEvent,
        validUntilMillis: Long,
    ): Boolean {
        check(SwingUtilities.isEventDispatchThread())
        if (!isAvailableFor(event)) {
            releaseAll()
            return false
        }
        return when (event) {
            is AppInputEvent.Window -> {
                // A repeated command from the same authority must not cancel its pending native action.
                releaseHeldInput()
                val epoch = authorityEpoch.get()
                val modalAuthority = WindowInputModalBoundary.captureAuthority(window)
                val authorized = { authorityEpoch.get() == epoch && windowActionAvailable(event) }
                if (OwnedWindowControls.supports(window, event.action)) {
                    OwnedWindowControls.perform(window, event.action, validUntilMillis, authorized)
                } else {
                    MacToolbarInput.recover(window, event.action, validUntilMillis) {
                        authorityEpoch.get() == epoch && modalAuthority()
                    }
                }
            }

            is AppInputEvent.Pointer -> {
                applyPointer(event, validUntilMillis)
            }

            is AppInputEvent.Wheel -> {
                applyWheel(event)
            }

            is AppInputEvent.Key -> {
                applyKey(event, validUntilMillis)
            }
        }
    }

    private fun keyboardFocusChanged(target: HeldPointer): Boolean =
        keyboardBrowser?.browser !== target.browserSurface?.browser ||
            keyboardNative?.owner !== target.nativeTarget?.owner || keyboardNative?.id != target.nativeTarget?.id

    // Explicit early rejection keeps held-target and duplicate-press checks auditable.
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun applyPointer(
        event: AppInputEvent.Pointer,
        validUntilMillis: Long,
    ): Boolean {
        val hit = hit(event.x, event.y) ?: return false
        val target =
            if (event.action == "up") {
                buttons.remove(event.button) ?: return false
            } else {
                buttons.values.firstOrNull() ?: hit
            }
        val position = SwingUtilities.convertPoint(hit.component, hit.x, hit.y, target.component)
        if (event.action == "down") {
            if (buttons.containsKey(event.button)) return false
            if (keyboardFocusChanged(target)) {
                releaseAll()
                keyboardBrowser?.takeIf { it.isCurrent() }?.browser?.unfocus()
            }
            if (target.nativeTarget?.id == "browser_url" &&
                !target.nativeTarget.owner.addressClick(target.nativeTarget, validUntilMillis)
            ) {
                return false
            }
            if (target.nativeTarget == null) target.component.requestFocusInWindow()
            target.browserSurface?.activatePanel()
            keyboardTarget = (target.component as? javax.swing.RootPaneContainer)?.contentPane ?: target.component
            keyboardBrowser = target.browserSurface
            keyboardNative = target.nativeTarget?.takeIf { it.id == "browser_url" }
            buttons[event.button] = target.copy(x = position.x, y = position.y)
        }
        buttons.replaceAll { _, held ->
            if (held.component === target.component) held.copy(x = position.x, y = position.y) else held
        }
        pointer(
            target.copy(x = position.x, y = position.y),
            event.action,
            if (event.action ==
                "move"
            ) {
                buttons.keys.firstOrNull() ?: event.button
            } else {
                event.button
            },
            validUntilMillis,
        )
        publishCursor(target)
        return true
    }

    /** Compose updates hover icons asynchronously. Fence feedback against retired input authority. */
    private fun publishCursor(target: HeldPointer) {
        val epoch = authorityEpoch.get()
        SwingUtilities.invokeLater {
            if (authorityEpoch.get() != epoch || !visibleInputWindow()) return@invokeLater
            if (target.component.isShowing &&
                allowedWindow(SwingUtilities.getWindowAncestor(target.component)) &&
                target.browserSurface?.isCurrent() != false
            ) {
                val cursor =
                    when {
                        target.nativeTarget?.id == "browser_url" -> "text"
                        target.nativeTarget != null || target.browserSurface != null -> "default"
                        else -> appCursorCss(target.component.cursor)
                    }
                onCursor(cursor)
            }
        }
    }

    private fun applyWheel(event: AppInputEvent.Wheel): Boolean {
        val hit = hit(event.x, event.y) ?: return false
        val browser = hit.browserSurface?.takeIf { it.isCurrent() }?.browser ?: (hit.component as? BrowserView)?.browser
        if (browser != null) {
            val point = hit.browserSurface?.point(hit.component, hit.x, hit.y) ?: Point.of(hit.x, hit.y)
            // The sharing protocol uses DOM wheel signs (positive = down/right).
            // JxBrowser's native wheel API uses the opposite signs on both axes.
            browser.dispatch(
                MouseWheel
                    .newBuilder(point)
                    .keyModifiers(
                        browserModifiers(),
                    ).deltaX(-event.deltaX.toFloat())
                    .deltaY(-event.deltaY.toFloat())
                    .scrollType(ScrollType.UNIT_SCROLL)
                    .build(),
            )
        } else {
            hit.component.dispatchEvent(
                MouseWheelEvent(
                    hit.component,
                    MouseEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(),
                    modifiers(),
                    hit.x,
                    hit.y,
                    0,
                    false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL,
                    3,
                    event.deltaY.toInt().coerceIn(-1000, 1000),
                ),
            )
        }
        return true
    }

    // Releases must retain their original component instead of using the current focus owner.
    @Suppress("ReturnCount")
    private fun applyKey(
        event: AppInputEvent.Key,
        validUntilMillis: Long,
    ): Boolean {
        keyboardNative?.let { target ->
            if (event.action == "up") return nativeKeys.remove(event.code)
            if (!target.owner.addressKey(target, event, validUntilMillis)) return false
            nativeKeys.add(event.code)
            return true
        }
        val target =
            if (event.action == "up") {
                keys.remove(event.code) ?: return false
            } else {
                keys[event.code]
                    ?: scoped(focusedComponent())?.let { HeldKey(it, event, keyboardBrowser) }
                    ?: return false
            }
        if (event.action == "down") keys[event.code] = target
        if (event.action == "down" && target.browserSurface == null && target.component !is BrowserView) {
            if (traverseFocus(event)) return true
        }
        key(target.component, event, target.browserSurface)
        return true
    }

    /** redispatchEvent bypasses AWT traversal; move only the viewer's scoped target, never OS focus. */
    private fun traverseFocus(event: AppInputEvent.Key): Boolean {
        val target = scoped(focusedComponent())
        val destination = target?.let { scoped(appTraversalTarget(it, event)) } ?: return false
        keyboardTarget = destination
        keyboardBrowser = null
        if (requireForeground) destination.requestFocusInWindow()
        return true
    }

    override fun releaseAll() {
        check(SwingUtilities.isEventDispatchThread())
        authorityEpoch.incrementAndGet()
        onCursor("default")
        releaseHeldInput()
    }

    private fun releaseHeldInput() {
        val heldButtons = buttons.toMap()
        val heldKeys = keys.toMap()
        buttons.clear()
        keys.clear()
        nativeKeys.clear()
        // Releasing held input on a geometry change does not change the viewer-selected focus.
        // focusedComponent rechecks visibility, ownership and modal boundaries before reuse.
        heldButtons.forEach { (button, target) ->
            if (target.nativeTarget == null) runCatching { pointer(target, "up", button, popupAllowed = false) }
        }
        heldKeys.values.forEach { held ->
            runCatching {
                key(
                    held.component,
                    held.event.copy(action = "up", alt = false, ctrl = false, meta = false, shift = false),
                    held.browserSurface,
                )
            }
        }
    }

    // Reject stale/foreign/modal-parent coordinates before dispatch; keep the guards explicit.
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun hit(
        x: Double,
        y: Double,
    ): HeldPointer? {
        val snapshot = surfaceSnapshot
        val px = (x * ((snapshot?.logicalWidth ?: window.width) - 1)).toInt() + (snapshot?.x ?: window.x)
        val py = (y * ((snapshot?.logicalHeight ?: window.height) - 1)).toInt() + (snapshot?.y ?: window.y)
        val targetWindow =
            snapshot
                ?.surfaces
                ?.asReversed()
                ?.firstOrNull {
                    val g = it.geometry
                    px in g.x until g.x + g.logicalWidth && py in g.y until g.y + g.logicalHeight
                }?.window ?: window.takeIf { snapshot == null } ?: return null
        val modal = snapshot?.surfaces?.lastOrNull { it.modal }?.window
        if (modal != null && !isOwnedBy(targetWindow, modal)) return null
        val localX = px - targetWindow.x
        val localY = py - targetWindow.y
        MacToolbarInput.hit(targetWindow, localX, localY)?.let { native ->
            return HeldPointer(targetWindow, localX, localY, nativeTarget = native)
        }
        val insets = targetWindow.insets
        val inHorizontalBounds = localX >= insets.left && localX < targetWindow.width - insets.right
        val inVerticalBounds = localY >= insets.top && localY < targetWindow.height - insets.bottom
        if (!inHorizontalBounds || !inVerticalBounds) return null
        return SwingUtilities.getDeepestComponentAt(targetWindow, localX, localY)?.let(::scoped)?.let { target ->
            val local = SwingUtilities.convertPoint(targetWindow, localX, localY, target)
            HeldPointer(target, local.x, local.y, browserSurfaceAt(targetWindow, localX, localY))
        }
    }

    private fun scoped(component: Component?): Component? {
        if (component == null || component === window ||
            !allowedWindow(SwingUtilities.getWindowAncestor(component))
        ) {
            return null
        }
        return generateSequence(component) { it.parent }
            .takeWhile { it !== window }
            .filterIsInstance<BrowserView>()
            .firstOrNull() ?: component
    }

    // Parallel AWT/JxBrowser translations deliberately retain every explicit button/action branch.
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private fun pointer(
        held: HeldPointer,
        action: String,
        button: Int,
        validUntilMillis: Long = Long.MAX_VALUE,
        popupAllowed: Boolean = true,
    ) {
        val target = held.component
        val x = held.x
        val y = held.y
        val browserSurface = held.browserSurface
        held.nativeTarget?.let { native ->
            val current = (target as? Window)?.let { MacToolbarInput.hit(it, x, y) }
            val releasedOnTarget = current?.owner === native.owner && current.id == native.id
            val activation = action == "up" && native.id != "browser_url"
            if (activation && releasedOnTarget && button != 1) {
                val epoch = authorityEpoch.get()
                val modalAuthority = WindowInputModalBoundary.captureAuthority(window)
                native.owner.activate(native, context = button == 2, validUntilMillis = validUntilMillis) {
                    authorityEpoch.get() == epoch && modalAuthority()
                }
            }
            return
        }
        // Detached/replaced components must not acquire authority in another window.
        if (!allowedWindow(SwingUtilities.getWindowAncestor(target))) return
        if (browserSurface?.isCurrent() == false) return
        val browser = browserSurface?.browser ?: (target as? BrowserView)?.browser
        if (browser != null) {
            val point = browserSurface?.point(target, x, y) ?: Point.of(x, y)
            if (action != "move" && button == 2) {
                val epoch = authorityEpoch.get()
                AppBrowserMenuDispatch.record(
                    browser,
                    MouseEvent(
                        target,
                        if (action == "down") MouseEvent.MOUSE_PRESSED else MouseEvent.MOUSE_RELEASED,
                        System.currentTimeMillis(),
                        modifiers(),
                        x,
                        y,
                        1,
                        true,
                        MouseEvent.BUTTON3,
                    ),
                    point,
                ) {
                    popupAllowed && epoch == authorityEpoch.get() &&
                        allowedWindow(SwingUtilities.getWindowAncestor(target)) &&
                        privateSurfaceAllowed() && browserSurface?.isCurrent() != false
                }
            }
            if (action == "down") browser.focus()
            val browserButton =
                when (button) {
                    1 -> MouseButton.MIDDLE
                    2 -> MouseButton.SECONDARY
                    else -> MouseButton.PRIMARY
                }
            when (action) {
                "down" -> {
                    browser.dispatch(
                        MousePressed
                            .newBuilder(point)
                            .button(browserButton)
                            .keyModifiers(browserModifiers())
                            .clickCount(1)
                            .build(),
                    )
                }

                "up" -> {
                    browser.dispatch(
                        MouseReleased
                            .newBuilder(point)
                            .button(browserButton)
                            .keyModifiers(browserModifiers())
                            .clickCount(1)
                            .build(),
                    )
                }

                else -> {
                    if (buttons.isEmpty()) {
                        browser.dispatch(MouseMoved.newBuilder(point).keyModifiers(browserModifiers()).build())
                    } else {
                        browser.dispatch(
                            MouseDragged
                                .newBuilder(point)
                                .button(browserButton)
                                .keyModifiers(browserModifiers())
                                .build(),
                        )
                    }
                }
            }
        } else {
            val awtButton =
                when (button) {
                    1 -> MouseEvent.BUTTON2
                    2 -> MouseEvent.BUTTON3
                    else -> MouseEvent.BUTTON1
                }
            val id =
                when (action) {
                    "down" -> MouseEvent.MOUSE_PRESSED
                    "up" -> MouseEvent.MOUSE_RELEASED
                    else -> if (buttons.isEmpty()) MouseEvent.MOUSE_MOVED else MouseEvent.MOUSE_DRAGGED
                }
            target.dispatchEvent(
                MouseEvent(
                    target,
                    id,
                    System.currentTimeMillis(),
                    modifiers(),
                    x,
                    y,
                    if (action == "move") 0 else 1,
                    popupAllowed && button == 2 && action == (if (SystemUtils.isMacOS) "down" else "up"),
                    if (action == "move") MouseEvent.NOBUTTON else awtButton,
                ),
            )
        }
    }

    // Key release and printable typing differ across AWT and JxBrowser; preserve explicit guards.
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "LongMethod")
    private fun key(
        target: Component,
        event: AppInputEvent.Key,
        browserSurface: AppBrowserInputSurface? = null,
    ) {
        if (!allowedWindow(SwingUtilities.getWindowAncestor(target))) return
        val code = awtKeyCode(event.code) ?: return
        val char = event.key.singleOrNull()?.takeUnless { it.isISOControl() } ?: KeyEvent.CHAR_UNDEFINED
        val flags =
            (if (event.alt) InputEvent.ALT_DOWN_MASK else 0) or (if (event.ctrl) InputEvent.CTRL_DOWN_MASK else 0) or
                (if (event.meta) InputEvent.META_DOWN_MASK else 0) or
                (if (event.shift) InputEvent.SHIFT_DOWN_MASK else 0)
        val printable = char != KeyEvent.CHAR_UNDEFINED && !event.ctrl && !event.meta && !event.alt
        if (browserSurface?.isCurrent() == false) return
        val browser = browserSurface?.browser ?: (target as? BrowserView)?.browser
        if (browser != null) {
            val browserCode = browserKeyCode(event.code) ?: return
            val mods =
                KeyModifiers
                    .newBuilder()
                    .altDown(
                        event.alt,
                    ).controlDown(event.ctrl)
                    .metaDown(event.meta)
                    .shiftDown(event.shift)
                    .build()
            if (event.action == "down") {
                browser.focus()
                AppBrowserKeyDispatch.press(
                    browser,
                    window,
                    KeyPressed
                        .newBuilder(browserCode)
                        .keyChar(browserTypedChar(event))
                        .keyModifiers(mods)
                        .build(),
                ) {
                    allowedWindow(SwingUtilities.getWindowAncestor(target)) &&
                        privateSurfaceAllowed() && (browserSurface?.isCurrent() != false)
                }
                run {
                    val browserChar = browserTypedChar(event)
                    AppBrowserKeyDispatch.type(
                        browser,
                        window,
                        KeyTyped
                            .newBuilder(browserCode)
                            .keyChar(browserChar)
                            .keyModifiers(mods)
                            .build(),
                    ) {
                        allowedWindow(SwingUtilities.getWindowAncestor(target)) &&
                            privateSurfaceAllowed() && (browserSurface?.isCurrent() != false)
                    }
                }
            } else {
                browser.dispatch(KeyReleased.newBuilder(browserCode).keyModifiers(mods).build())
            }
        } else {
            // dispatchEvent(KeyEvent) normally re-enters global focus retargeting. Explicit
            // redispatch bypasses that path, so cleanup releases cannot reach a newly focused app window.
            val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            focusManager.redispatchEvent(
                target,
                KeyEvent(
                    target,
                    if (event.action ==
                        "down"
                    ) {
                        KeyEvent.KEY_PRESSED
                    } else {
                        KeyEvent.KEY_RELEASED
                    },
                    System.currentTimeMillis(),
                    flags,
                    code,
                    char,
                ),
            )
            if (event.action == "down" && printable) {
                focusManager.redispatchEvent(
                    target,
                    KeyEvent(
                        target,
                        KeyEvent.KEY_TYPED,
                        System.currentTimeMillis(),
                        flags,
                        KeyEvent.VK_UNDEFINED,
                        char,
                    ),
                )
            }
        }
    }

    private fun browserModifiers(): KeyModifiers =
        KeyModifiers
            .newBuilder()
            .shiftDown(keys.keys.any { it.startsWith("Shift") })
            .controlDown(keys.keys.any { it.startsWith("Control") })
            .altDown(keys.keys.any { it.startsWith("Alt") })
            .metaDown(keys.keys.any { it.startsWith("Meta") })
            .build()

    private fun modifiers(): Int =
        keys.keys.fold(0) { mask, code ->
            mask or
                when {
                    code.startsWith("Shift") -> InputEvent.SHIFT_DOWN_MASK
                    code.startsWith("Control") -> InputEvent.CTRL_DOWN_MASK
                    code.startsWith("Alt") -> InputEvent.ALT_DOWN_MASK
                    code.startsWith("Meta") -> InputEvent.META_DOWN_MASK
                    else -> 0
                }
        } or
            buttons.keys.fold(0) { mask, button ->
                mask or
                    when (button) {
                        1 -> InputEvent.BUTTON2_DOWN_MASK
                        2 -> InputEvent.BUTTON3_DOWN_MASK
                        else -> InputEvent.BUTTON1_DOWN_MASK
                    }
            }
}

private fun browserTypedChar(event: AppInputEvent.Key): Char =
    when (event.code) {
        "Backspace" -> '\b'
        "Enter" -> '\r'
        "Tab" -> '\t'
        "Escape" -> '\u001b'
        "Delete" -> '\u007f'
        else -> event.key.singleOrNull()?.takeUnless { it.isISOControl() } ?: '\u0000'
    }

private fun awtKeyCode(code: String): Int? =
    when {
        code.startsWith("Key") && code.length == 4 -> {
            KeyEvent.getExtendedKeyCodeForChar(code[3].code)
        }

        code.startsWith("Digit") && code.length == 6 -> {
            KeyEvent.getExtendedKeyCodeForChar(code[5].code)
        }

        else -> {
            mapOf(
                "Enter" to KeyEvent.VK_ENTER,
                "Escape" to KeyEvent.VK_ESCAPE,
                "Backspace" to KeyEvent.VK_BACK_SPACE,
                "Tab" to KeyEvent.VK_TAB,
                "Space" to KeyEvent.VK_SPACE,
                "ArrowLeft" to KeyEvent.VK_LEFT,
                "ArrowRight" to KeyEvent.VK_RIGHT,
                "ArrowUp" to KeyEvent.VK_UP,
                "ArrowDown" to KeyEvent.VK_DOWN,
                "Delete" to KeyEvent.VK_DELETE,
                "Home" to KeyEvent.VK_HOME,
                "End" to KeyEvent.VK_END,
                "PageUp" to KeyEvent.VK_PAGE_UP,
                "PageDown" to KeyEvent.VK_PAGE_DOWN,
                "ShiftLeft" to KeyEvent.VK_SHIFT,
                "ShiftRight" to KeyEvent.VK_SHIFT,
                "ControlLeft" to KeyEvent.VK_CONTROL,
                "ControlRight" to KeyEvent.VK_CONTROL,
                "AltLeft" to KeyEvent.VK_ALT,
                "AltRight" to KeyEvent.VK_ALT,
                "MetaLeft" to KeyEvent.VK_META,
                "MetaRight" to KeyEvent.VK_META,
                "Minus" to KeyEvent.VK_MINUS,
                "Equal" to KeyEvent.VK_EQUALS,
                "BracketLeft" to KeyEvent.VK_OPEN_BRACKET,
                "BracketRight" to KeyEvent.VK_CLOSE_BRACKET,
                "Backslash" to KeyEvent.VK_BACK_SLASH,
                "Semicolon" to KeyEvent.VK_SEMICOLON,
                "Quote" to KeyEvent.VK_QUOTE,
                "Comma" to KeyEvent.VK_COMMA,
                "Period" to KeyEvent.VK_PERIOD,
                "Slash" to KeyEvent.VK_SLASH,
                "Backquote" to KeyEvent.VK_BACK_QUOTE,
            )[code]
        }
    }

private fun browserKeyCode(code: String): KeyCode? {
    val suffix =
        when {
            code.startsWith("Key") && code.length == 4 -> {
                code.substring(3)
            }

            code.startsWith("Digit") && code.length == 6 -> {
                code.substring(5)
            }

            else -> {
                mapOf(
                    "Enter" to "RETURN",
                    "Escape" to "ESCAPE",
                    "Backspace" to "BACK",
                    "Tab" to "TAB",
                    "Space" to "SPACE",
                    "ArrowLeft" to "LEFT",
                    "ArrowRight" to "RIGHT",
                    "ArrowUp" to "UP",
                    "ArrowDown" to "DOWN",
                    "Delete" to "DELETE",
                    "Home" to "HOME",
                    "End" to "END",
                    "PageUp" to "PRIOR",
                    "PageDown" to "NEXT",
                    "ShiftLeft" to "SHIFT",
                    "ShiftRight" to "SHIFT",
                    "ControlLeft" to "CONTROL",
                    "ControlRight" to "CONTROL",
                    "AltLeft" to "MENU",
                    "AltRight" to "MENU",
                    "MetaLeft" to "LWIN",
                    "MetaRight" to "RWIN",
                    "Minus" to "OEM_MINUS",
                    "Equal" to "OEM_PLUS",
                    "BracketLeft" to "OEM_4",
                    "BracketRight" to "OEM_6",
                    "Backslash" to "OEM_5",
                    "Semicolon" to "OEM_1",
                    "Quote" to "OEM_7",
                    "Comma" to "OEM_COMMA",
                    "Period" to "OEM_PERIOD",
                    "Slash" to "OEM_2",
                    "Backquote" to "OEM_3",
                )[code]
            }
        } ?: return null
    return runCatching { KeyCode.valueOf("KEY_CODE_$suffix") }.getOrNull()
}

private fun isOwnedBy(
    candidate: Window,
    owner: Window,
): Boolean = generateSequence(candidate) { it.owner }.any { it === owner }
