package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.number
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.send
import ai.rever.boss.window.MacToolbarRuntime.supports
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.IntRect
import com.sun.jna.Pointer
import java.awt.Window
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class NativeToolbarMenuRequest(
    val id: String,
    val title: String,
    val bounds: IntRect,
    val entries: List<NativeTitleBarAction>,
    val select: (String) -> Unit,
)

/** Borrowed native views are usable only while the exact controller still owns its toolbar. */
internal data class MacToolbarInputTarget(
    val owner: MacToolbarInput,
    val id: String,
    val bounds: IntRect,
    val view: Pointer,
    val browserIdentity: String? = null,
    val windowButton: Long? = null,
) {
    fun isCurrent(): Boolean = owner.isCurrent(this)
}

/** AppKit publishes immutable control geometry; the EDT never searches by label or native class name. */
@Suppress("TooManyFunctions") // One controller lifetime owns the borrowed views and all native dispatch authority.
internal class MacToolbarInput(
    private val controller: MacSidebarToolbar,
    private val handle: Long,
    private val onMenu: (NativeToolbarMenuRequest) -> Unit,
) {
    private val tags = mutableMapOf<String, Long>()
    private val targets = AtomicReference(emptyList<MacToolbarInputTarget>())
    private val captureSurfaces = AtomicReference(emptyList<MacToolbarCaptureSurface>())
    private val actions = AtomicReference(emptyMap<String, NativeTitleBarAction>())
    private val lifetime = Any()
    private val pendingWindowAction = AtomicBoolean()
    private var fullscreenExitPendingUntil = 0L // AppKit queue only.

    @Volatile var fullscreenTransitioning = false
        private set

    @Volatile var fullscreenEntries = 0L
        private set

    @Volatile var enabled = false
        set(value) {
            synchronized(lifetime) {
                field = value && !retired
                if (!field) {
                    owners.remove(handle, this)
                    targets.set(emptyList())
                    captureSurfaces.set(emptyList())
                }
            }
        }

    @Volatile private var retired = false

    fun updateActions(next: List<NativeTitleBarAction>) {
        synchronized(lifetime) { if (!retired) actions.set(next.associateBy { it.id }) }
    }

    fun windowChanged(notification: Pointer?) {
        val name = pointer(pointer(notification, "name"), "UTF8String")?.getString(0, "UTF-8")
        when (name) {
            "NSWindowWillEnterFullScreenNotification", "NSWindowWillExitFullScreenNotification" -> {
                fullscreenTransitioning = true
                targets.set(emptyList())
                captureSurfaces.set(emptyList())
            }

            "NSWindowDidEnterFullScreenNotification" -> {
                fullscreenTransitioning = false
                fullscreenEntries++
            }

            "NSWindowDidExitFullScreenNotification" -> {
                fullscreenTransitioning = false
                fullscreenExitPendingUntil = 0L
            }
        }
    }

    fun tag(
        item: Pointer,
        id: String,
    ) {
        val value = tags.getOrPut(id) { nextTag.incrementAndGet() }
        send(item, "setTag:", value)
    }

    fun taggedAction(sender: Pointer?): String? =
        if (supports(sender, "tag")) tags.entries.firstOrNull { it.value == number(sender, "tag") }?.key else null

    fun publish(
        window: Pointer,
        delegate: Pointer?,
        items: Map<String, Pointer>,
        nextActions: Map<String, NativeTitleBarAction>,
    ) {
        if (retired || !enabled || fullscreenTransitioning) return
        if (number(window, "styleMask") and (1L shl 14) == 0L) fullscreenExitPendingUntil = 0L
        captureSurfaces.set(nativeToolbarCaptureSurfaces(window, items.values))
        val contents = MacToolbarInputGeometry(items, tags, nextActions)
        val found = collectNativeToolbarTargets(this, window, delegate, contents)
        val editing = controller.addressField.editing
        val field = editing.view
        val identity = editing.input?.identity
        if (field != null && identity != null && ownsNativeToolbarView(window, field)) {
            nativeAddressBounds(field, window)?.let {
                found.add(MacToolbarInputTarget(this, "browser_url", it, field, identity))
            }
        }
        // Reject ambiguous mappings rather than dispatching to a duplicate/overflow representation.
        val unique =
            found
                .groupBy { it.id }
                .values
                .filter { it.size == 1 }
                .map { it.single() }
        synchronized(lifetime) {
            if (!retired && enabled) {
                targets.set(unique)
                owners[handle] = this
            }
        }
    }

    fun hit(
        x: Int,
        y: Int,
    ): MacToolbarInputTarget? =
        targets
            .get()
            .filter { x >= it.bounds.left && x < it.bounds.right && y >= it.bounds.top && y < it.bounds.bottom }
            .singleOrNull()
            ?.takeIf { isCurrent(it) }

    fun isCurrent(target: MacToolbarInputTarget): Boolean {
        val active = !retired && enabled && owners[handle] === this
        val browserCurrent =
            target.browserIdentity == null ||
                actions.get()[target.id]?.textInput?.identity == target.browserIdentity
        return active && browserCurrent &&
            targets.get().any {
                it.id == target.id && it.view == target.view && it.browserIdentity == target.browserIdentity &&
                    it.windowButton == target.windowButton
            }
    }

    // Reject retired, disabled and local-only actions before reaching callbacks.
    // Keep native and local-only action checks at the dispatch boundary.
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun activate(
        target: MacToolbarInputTarget,
        context: Boolean = false,
        validUntilMillis: Long = Long.MAX_VALUE,
        authorized: () -> Boolean = { true },
    ): Boolean {
        if (!isCurrent(target) || !authorized() || System.currentTimeMillis() >= validUntilMillis) return false
        if (target.windowButton != null) {
            return !context && windowButtonClick(target, validUntilMillis, authorized)
        }
        if (target.id in MacSharingWindowControls.selectors) {
            return !context && sharingWindowAction(target.id, target, validUntilMillis, authorized)
        }
        val action = actions.get()[target.id]?.takeIf { it.enabled && !it.localOnly } ?: return false
        val menu = if (context) action.contextMenu else action.menu
        if (menu != null && menu.isNotEmpty()) {
            val remoteEntries = menu.map { if (it.localOnly) it.copy(enabled = false) else it }
            onMenu(
                NativeToolbarMenuRequest(action.id, action.label, target.bounds, remoteEntries) { id ->
                    if (isCurrent(target)) {
                        val current = actions.get()[target.id]?.takeIf { it.enabled && !it.localOnly }
                        val entries = if (context) current?.contextMenu else current?.menu
                        entries?.singleOrNull { it.id == id && it.enabled && !it.localOnly }?.onClick?.invoke()
                    }
                },
            )
        } else {
            action.onClick()
        }
        return true
    }

    /** Use the real button's target/action, preserving AppKit close, minimize and fullscreen behavior. */
    private fun windowButtonClick(
        target: MacToolbarInputTarget,
        validUntilMillis: Long,
        authorized: () -> Boolean,
    ): Boolean {
        val window = Pointer(handle)
        val validate = {
            val owned =
                isCurrent(target) && MacToolbarRuntime.isLiveWindow(window) &&
                    pointer(window, "toolbar") == controller.toolbar
            if (!owned) {
                false
            } else {
                val button = pointer(window, "standardWindowButton:", target.windowButton)
                button == target.view && ownsNativeToolbarView(window, button) &&
                    number(button, "isEnabled") != 0L && number(button, "isHiddenOrHasHiddenAncestor") == 0L &&
                    nativeAddressBounds(checkNotNull(button), window) == target.bounds && isCurrent(target)
            }
        }
        return queueNativeWindowAction(validUntilMillis, pendingWindowAction, authorized, validate) {
            send(target.view, "performClick:", null)
        }
    }

    /** The extra controls use window selectors even when macOS replaces the standard buttons. */
    @Suppress("CyclomaticComplexMethod") // Validate ownership, the live control and authority again on AppKit.
    fun sharingWindowAction(
        id: String,
        target: MacToolbarInputTarget? = null,
        validUntilMillis: Long = Long.MAX_VALUE,
        authorized: () -> Boolean = { true },
    ): Boolean {
        val selector = MacSharingWindowControls.selectors[id] ?: return false
        val buttonIndex = checkNotNull(MacSharingWindowControls.buttonIndex(id))
        val window = Pointer(handle)
        val validate = {
            val owned =
                !retired && enabled && actions.get().containsKey(id) &&
                    MacToolbarRuntime.isLiveWindow(window) && pointer(window, "toolbar") == controller.toolbar &&
                    pointer(window, "attachedSheet") == null
            val currentTarget =
                if (!owned) {
                    false
                } else if (target == null) {
                    true
                } else if (!isCurrent(target) || pointer(target.view, "window") != window ||
                    number(target.view, "isHiddenOrHasHiddenAncestor") != 0L
                ) {
                    false
                } else {
                    nativeAddressBounds(target.view, window) == target.bounds
                }
            val button = if (owned) pointer(window, "standardWindowButton:", buttonIndex) else null
            val operable =
                button != null && pointer(button, "window") == window && number(button, "isEnabled") != 0L
            owned && currentTarget && operable && !fullscreenTransitioning
        }
        return queueNativeWindowAction(validUntilMillis, pendingWindowAction, authorized, validate) {
            send(window, selector, null)
        }
    }

    /** Coordinate-free recovery is available even when fullscreen chrome or minimization hides buttons. */
    @Suppress("CyclomaticComplexMethod") // Ownership and idempotency remain explicit at native dispatch.
    private fun recoverWindow(
        action: String,
        validUntilMillis: Long,
        authorized: () -> Boolean,
    ): Boolean {
        val window = Pointer(handle)
        val validate = {
            val owned =
                !retired && enabled && owners[handle] === this &&
                    MacToolbarRuntime.isLiveWindow(window) && pointer(window, "toolbar") == controller.toolbar &&
                    pointer(window, "attachedSheet") == null
            val validAction = action == "restore" || (action == "exit-fullscreen" && !fullscreenTransitioning)
            owned && validAction
        }
        return queueNativeWindowAction(validUntilMillis, pendingWindowAction, authorized, validate) {
            when (action) {
                "restore" -> {
                    if (number(window, "isMiniaturized") != 0L) send(window, "deminiaturize:", null)
                }

                "exit-fullscreen" -> {
                    val now = System.currentTimeMillis()
                    val fullscreen = number(window, "styleMask") and (1L shl 14) != 0L
                    if (now >= fullscreenExitPendingUntil && fullscreen) {
                        fullscreenExitPendingUntil = now + 1500
                        send(window, "toggleFullScreen:", null)
                    }
                }
            }
        }
    }

    fun addressClick(
        target: MacToolbarInputTarget,
        validUntilMillis: Long,
    ): Boolean =
        addressOperation(target, validUntilMillis) {
            controller.addressField.focus()
            pointer(target.view, "currentEditor") != null
        }

    fun addressKey(
        target: MacToolbarInputTarget,
        event: ai.rever.boss.sharing.AppInputEvent.Key,
        validUntilMillis: Long,
    ): Boolean {
        val edit = { editNativeAddress(controller.addressField.editing, event) }
        return addressOperation(target, validUntilMillis, edit)
    }

    private fun addressOperation(
        target: MacToolbarInputTarget,
        validUntilMillis: Long,
        action: () -> Boolean,
    ): Boolean =
        scopedNativeToolbarCall(validUntilMillis) {
            val window = Pointer(handle)
            val windowOwned = MacToolbarRuntime.isLiveWindow(window) && pointer(window, "toolbar") == controller.toolbar
            val viewOwned = ownsNativeToolbarView(window, target.view)
            val browserCurrent =
                target.browserIdentity != null &&
                    controller.addressField.editing.input
                        ?.identity == target.browserIdentity
            val controlCurrent = isCurrent(target) && viewOwned
            if (controlCurrent && windowOwned && browserCurrent) action() else false
        }

    fun retire() =
        synchronized(lifetime) {
            retired = true
            owners.remove(handle, this)
            targets.set(emptyList())
            captureSurfaces.set(emptyList())
            actions.set(emptyMap())
        }

    companion object {
        private val nextTag = AtomicLong(10_000)
        private val owners = ConcurrentHashMap<Long, MacToolbarInput>()

        fun captureSurfaces(window: Window): List<MacToolbarCaptureSurface> =
            (window as? ComposeWindow)
                ?.takeIf { it.isDisplayable }
                ?.let { owners[it.windowHandle]?.captureSurfaces?.get() }
                .orEmpty()

        fun supportsRecovery(window: Window): Boolean =
            (window as? ComposeWindow)?.takeIf { it.isDisplayable }?.let { owners.containsKey(it.windowHandle) } == true

        fun recover(
            window: Window,
            action: String,
            validUntilMillis: Long,
            authorized: () -> Boolean,
        ): Boolean {
            val nativeWindow = (window as? ComposeWindow)?.takeIf { it.isDisplayable } ?: return false
            return owners[nativeWindow.windowHandle]?.recoverWindow(action, validUntilMillis, authorized) == true
        }

        fun hit(
            window: Window,
            x: Int,
            y: Int,
        ): MacToolbarInputTarget? {
            val nativeWindow = (window as? ComposeWindow)?.takeIf { it.isDisplayable }
            return nativeWindow?.let { owners[it.windowHandle]?.hit(x, y) }
        }
    }
}
