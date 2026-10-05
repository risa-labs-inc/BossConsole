package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.clazz
import ai.rever.boss.window.MacToolbarRuntime.number
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.selector
import ai.rever.boss.window.MacToolbarRuntime.send
import ai.rever.boss.window.MacToolbarRuntime.string
import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Pointer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

/** Native sidebar item, ported from BossTerm. AppKit owns its view and traffic-light geometry. */
@Suppress("TooManyFunctions") // One native toolbar lifetime owns updates, capture controls and disposal.
internal class MacSidebarToolbar(
    handle: Long,
    private val onHeight: (Double?) -> Unit,
    private val onAction: (String) -> Unit,
    private val onRemoteMenu: (NativeToolbarMenuRequest) -> Unit = {},
) : AutoCloseable {
    private val window = Pointer(handle)
    internal val remoteInput = MacToolbarInput(this, handle, onRemoteMenu)

    // AppKit synchronizes insertions/removals across all toolbars with the same identifier.
    // Each window has different active-tab actions, so its delegate cannot serve another's items.
    private val toolbarIdentifier = "ai.rever.boss.sidebar.${UUID.randomUUID()}"
    internal var toolbar: Pointer? = null
        private set
    private var delegate: Pointer? = null
    private val items = mutableMapOf<String, Pointer>()
    private val groupedItems = mutableMapOf<String, List<String>>()
    private val customIcons = MacToolbarIcons()
    internal val addressField = MacToolbarAddressField()
    private var baseActions = emptyList<NativeTitleBarAction>()
    private var sharingControlsVisible = false
    private var refreshingSharingControls = false
    private var actions = emptyMap<String, NativeTitleBarAction>()
    private var displayed = emptyList<String>()
    private var appearance: MacToolbarAppearance? = null
    private var previousToolbar: Pointer? = null
    private var previousStyle = 0L
    private var previousTitleVisibility = 0L
    private var previousTitle: Pointer? = null
    private var previousSubtitle: Pointer? = null
    private var measuredHeight: Double? = null

    @Volatile private var closed = false

    fun update(
        title: String,
        newActions: List<NativeTitleBarAction>,
        dark: Boolean,
        background: Int,
        icons: Map<String, ByteArray>,
    ) {
        remoteInput.updateActions(newActions)
        dispatchSafely {
            customIcons.update(icons)
            baseActions = newActions
            actions = newActions.associateBy { it.id }
            if (toolbar == null) install()
            appearance?.update(dark, background)
            val space = actions["space"]
            val windowTitle = actions["terminal_title"]?.label ?: space?.label ?: title
            send(window, "setTitle:", string(windowTitle))
            send(window, "setSubtitle:", string(space?.subtitle.orEmpty()))
            send(window, "setTitleVisibility:", if (space != null || windowTitle.isEmpty()) 1L else 0L)
            refreshSharingControls(force = true)
            updateIdentifiers()
            listOf("sidebar_leading", "sidebar_boundary").forEach { id ->
                items[id]?.let { MacSidebarBoundary.update(it, actions["sidebar"], id == "sidebar_leading") }
            }
            actions.forEach { (id, action) -> items[id]?.let { updateItem(it, action) } }
            MacToolbarGroups.members.keys.forEach { id ->
                items[id]?.let { MacToolbarGroups.update(id, it, actions, groupedItems, ::makeItem) }
            }
            measure()
        }
    }

    fun identifiers(): List<String> =
        buildList {
            // Even a zero-width toolbar item adds AppKit's inter-item spacing.
            // Remove unused spacers so collapsed controls retain native spacing.
            if (actions.keys.any { it in MacSharingWindowControls.selectors }) add(MacSharingWindowControls.GROUP)
            val sidebar = actions["sidebar"]
            if (MacSidebarBoundary.leadingWidth(sidebar) > 0f) add("sidebar_leading")
            if (sidebar != null) add("sidebar")
            if (MacSidebarBoundary.trailingWidth(sidebar) > 0f) add("sidebar_boundary")
            if (actions.containsKey("space")) add("space")
            if (actions.containsKey("terminal_title")) add("terminal_title")
            add("NSToolbarFlexibleSpaceItem")
            appendBrowserItems(actions.keys.filter { it.startsWith("browser_") })
            if (actions.containsKey("browser_url")) add("NSToolbarFlexibleSpaceItem")
            val trailing =
                actions.keys.filterNot {
                    it.startsWith("browser_") || it in MacSharingWindowControls.selectors ||
                        it in setOf("sidebar", "space", "terminal_title")
                }
            MacToolbarGroups.identifiers(trailing).forEachIndexed { index, id ->
                if (index > 0 && id != "split_horizontal") add("NSToolbarSpaceItem")
                add(id)
            }
        }

    private fun MutableList<String>.appendBrowserItems(ids: List<String>) {
        ids.forEach { id ->
            // Keep main's grouped navigation and the URL field's separate glass.
            if (id == "browser_url") add("NSToolbarSpaceItem")
            add(id)
            if (id == "browser_url") add("NSToolbarSpaceItem")
        }
    }

    private fun install() {
        check(MacToolbarRuntime.supports(window, "setToolbarStyle:")) { "Unified toolbar unavailable" }
        appearance = MacToolbarAppearance(window)
        previousToolbar = pointer(window, "toolbar")?.also { send(it, "retain") }
        previousTitle = pointer(window, "title")?.also { send(it, "retain") }
        previousSubtitle = pointer(window, "subtitle")?.also { send(it, "retain") }
        previousStyle = number(window, "toolbarStyle")
        previousTitleVisibility = number(window, "titleVisibility")
        delegate = pointer(MacSidebarToolbarBridge.bridgeClass, "new")
        MacSidebarToolbarBridge.owners[Pointer.nativeValue(delegate)] = this
        MacAddressKeyboardMonitor.install()
        toolbar =
            checkNotNull(
                pointer(pointer(clazz("NSToolbar"), "alloc"), "initWithIdentifier:", string(toolbarIdentifier)),
            )
        send(toolbar, "setDelegate:", delegate)
        send(toolbar, "setDisplayMode:", 2L)
        send(toolbar, "setAllowsUserCustomization:", 0.toByte())
        send(toolbar, "setShowsBaselineSeparator:", 0.toByte())
        send(window, "setToolbar:", toolbar)
        send(window, "setToolbarStyle:", 4L) // NSWindowToolbarStyleUnifiedCompact, as in BossTerm.
        val notifications = pointer(clazz("NSNotificationCenter"), "defaultCenter")
        // Updates include fullscreen toolbar reveal/hide; publish only changed measurements.
        listOf(
            "NSWindowDidResizeNotification",
            "NSWindowDidUpdateNotification",
            "NSWindowWillEnterFullScreenNotification",
            "NSWindowWillExitFullScreenNotification",
            "NSWindowDidEnterFullScreenNotification",
            "NSWindowDidExitFullScreenNotification",
        ).forEach {
            send(
                notifications,
                "addObserver:selector:name:object:",
                delegate,
                selector("windowChanged:"),
                string(it),
                window,
            )
        }
    }

    fun makeItem(identifier: Pointer?): Pointer? {
        val id = pointer(identifier, "UTF8String")?.getString(0)
        val action = id?.let(actions::get)
        if (action == null) {
            return if (id == MacSharingWindowControls.GROUP) {
                items.getOrPut(id) {
                    MacSharingWindowControls.create(identifier, window, delegate, remoteInput::tag)
                }
            } else if (id in MacToolbarGroups.members) {
                items.getOrPut(checkNotNull(id)) { MacToolbarGroups.create(id) }.also {
                    remoteInput.tag(it, id)
                    MacToolbarGroups.update(id, it, actions, groupedItems, ::makeItem)
                }
            } else if (id == "sidebar_boundary" || id == "sidebar_leading") {
                items.getOrPut(id) { MacSidebarBoundary.create(identifier) }.also {
                    MacSidebarBoundary.update(it, actions["sidebar"], id == "sidebar_leading")
                }
            } else {
                null
            }
        }
        val item =
            items.getOrPut(action.id) {
                val iconMenu =
                    action.menu != null && (action.symbol != null || action.icon != null) &&
                        MacToolbarGroups.groupId(action.id) == null
                val nativeClass = if (iconMenu) "NSMenuToolbarItem" else "NSToolbarItem"
                checkNotNull(pointer(pointer(clazz(nativeClass), "alloc"), "initWithItemIdentifier:", identifier))
            }
        remoteInput.tag(item, action.id)
        updateItem(item, action)
        return item
    }

    private fun updateItem(
        item: Pointer,
        action: NativeTitleBarAction,
    ) {
        if (MacToolbarTitle.updateTextItem(item, action, addressField, delegate, customIcons[action.id])) return
        val grouped = MacToolbarGroups.groupId(action.id) != null
        if (!grouped && updateMenuItem(item, action)) return
        val image =
            customIcons[action.id] ?: action.symbol?.let { symbol ->
                pointer(
                    clazz("NSImage"),
                    "imageWithSystemSymbolName:accessibilityDescription:",
                    string(symbol),
                    string(action.label),
                )
            }
        send(item, "setImage:", image)
        send(item, "setLabel:", string(action.label))
        send(item, "setToolTip:", string(action.label))
        send(item, "setBordered:", 1.toByte())
        send(item, "setNavigational:", if (action.id == "sidebar") 1.toByte() else 0.toByte())
        send(item, "setVisibilityPriority:", if (action.id in setOf("sidebar", "new")) 1000L else 0L)
        send(item, "setTarget:", delegate)
        send(item, "setAction:", if (grouped || action.menu == null) selector("activate:") else null)
        send(item, "setAutovalidates:", 0.toByte())
        send(item, "setEnabled:", if (action.enabled) 1.toByte() else 0.toByte())
        if (MacToolbarRuntime.supports(item, "setStyle:")) send(item, "setStyle:", if (action.active) 1L else 0L)
    }

    private fun updateMenuItem(
        item: Pointer,
        action: NativeTitleBarAction,
    ): Boolean {
        if (action.menu == null && action.id != "space") return false
        val textMenu = action.symbol == null && action.icon == null
        if (textMenu) {
            MacToolbarMenu.update(item, action, delegate)
        } else {
            MacToolbarActionMenu.update(item, action, delegate)
        }
        return textMenu
    }

    val focusAddress: () -> Unit = { dispatchSafely { addressField.focus() } }

    internal fun ownsAddressEventWindow(eventWindow: Pointer): Boolean =
        !closed && actions["browser_url"]?.textInput != null &&
            MacToolbarRuntime.isLiveWindow(window) && pointer(window, "toolbar") == toolbar &&
            pointer(window, "attachedSheet") == null && ownsNativeToolbarWindow(window, eventWindow) &&
            ownsNativeToolbarView(window, addressField.editing.view)

    fun submitAddress() {
        addressField.editing.submit()
    }

    fun activate(sender: Pointer?) {
        val identifier =
            if (MacToolbarRuntime.supports(sender, "representedObject")) {
                pointer(sender, "representedObject")
            } else if (MacToolbarRuntime.supports(sender, "itemIdentifier")) {
                pointer(sender, "itemIdentifier")
            } else {
                null
            }
        // NSButton forwards representedObject to its cell, where the context menu lives.
        // Only NSString identifiers may receive UTF8String; controls use their action tag.
        val id =
            remoteInput.taggedAction(sender) ?: identifier
                ?.takeIf { number(it, "isKindOfClass:", clazz("NSString")) != 0L }
                ?.let { pointer(it, "UTF8String")?.getString(0) } ?: return
        if (openGroupedMenu(id)) return
        SwingUtilities.invokeLater {
            if (!closed) {
                // Opening a status menu is not a service toggle. Restore AppKit's click selection
                // from the live service state, even when the action does not recompose the host.
                dispatchSafely {
                    MacToolbarGroups.members.keys.forEach { groupId ->
                        items[groupId]?.let { MacToolbarGroups.update(groupId, it, actions, groupedItems, ::makeItem) }
                    }
                }
                if (id in MacSharingWindowControls.selectors) {
                    remoteInput.sharingWindowAction(id)
                } else {
                    onAction(id)
                }
            }
        }
    }

    private fun openGroupedMenu(id: String): Boolean {
        val action = actions[id]?.takeIf { !closed && it.enabled && it.menu != null }
        val groupId = MacToolbarGroups.groupId(id)
        val group = items[groupId]
        if (action == null || group == null) return false
        MacToolbarGroups.update(checkNotNull(groupId), group, actions, groupedItems, ::makeItem)
        groupedMenuView(id)?.let { MacToolbarActionMenu.show(it, action, delegate) }
        return true
    }

    internal fun groupedMenuView(id: String): Pointer? =
        collectNativeToolbarTargets(
            remoteInput,
            window,
            delegate,
            MacToolbarInputGeometry(items, emptyMap(), actions),
        ).singleOrNull { it.id == id }?.view

    fun refreshSharingWindowControls() = dispatchSafely { measure() }

    fun measure() {
        if (closed || toolbar == null || !MacToolbarRuntime.isLiveWindow(window)) return
        refreshSharingControls()
        appearance?.refresh()
        addressField.updateBounds(window)
        remoteInput.publish(window, delegate, items, actions)
        val height = MacToolbarRuntime.headerHeight(window) ?: return
        if (height != measuredHeight) {
            if (measuredHeight == null) {
                org.slf4j.LoggerFactory
                    .getLogger(
                        MacSidebarToolbar::class.java,
                    ).info("Native sidebar toolbar installed; content inset {} points", height)
            }
            measuredHeight = height
            SwingUtilities.invokeLater { if (!closed) onHeight(height) }
        }
    }

    private fun updateIdentifiers() {
        val identifiers = identifiers()
        if (identifiers == displayed) return
        // Publish first: inserting/removing native items may synchronously notify window updates.
        displayed = identifiers
        val count = number(pointer(toolbar, "items"), "count")
        for (index in count - 1 downTo 0) send(toolbar, "removeItemAtIndex:", index)
        identifiers.forEachIndexed { index, id ->
            send(toolbar, "insertItemWithItemIdentifier:atIndex:", string(id), index.toLong())
        }
    }

    private fun refreshSharingControls(force: Boolean = false) {
        if (refreshingSharingControls) return
        val visible =
            MacSharingWindowControls.shouldShow(window, remoteInput.enabled, remoteInput.fullscreenTransitioning)
        if (!force && visible == sharingControlsVisible) return
        refreshingSharingControls = true
        try {
            sharingControlsVisible = visible
            actions = (MacSharingWindowControls.actions(visible) + baseActions).associateBy { it.id }
            remoteInput.updateActions(actions.values.toList())
            updateIdentifiers()
        } finally {
            refreshingSharingControls = false
        }
    }

    // Installing native chrome is optional; any Java/JNA failure restores the Compose fallback.
    @Suppress("TooGenericExceptionCaught")
    private fun dispatchSafely(action: () -> Unit) {
        MacToolbarRuntime.dispatch {
            if (!closed && MacToolbarRuntime.isLiveWindow(window)) {
                try {
                    action()
                } catch (error: Exception) {
                    org.slf4j.LoggerFactory
                        .getLogger(MacSidebarToolbar::class.java)
                        .warn("Native sidebar toolbar unavailable", error)
                    close()
                    SwingUtilities.invokeLater { onHeight(null) }
                }
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        remoteInput.retire()
        MacToolbarRuntime.dispatch {
            send(pointer(clazz("NSNotificationCenter"), "defaultCenter"), "removeObserver:", delegate)
            val ownsWindow =
                toolbar != null && MacToolbarRuntime.isLiveWindow(window) && pointer(window, "toolbar") == toolbar
            appearance?.close(restore = ownsWindow)
            if (ownsWindow) {
                send(window, "setToolbar:", previousToolbar)
                send(window, "setToolbarStyle:", previousStyle)
                send(window, "setTitleVisibility:", previousTitleVisibility)
                send(window, "setTitle:", previousTitle)
                send(window, "setSubtitle:", previousSubtitle)
            }
            send(toolbar, "setDelegate:", null)
            MacSidebarToolbarBridge.owners.remove(Pointer.nativeValue(delegate))
            MacAddressKeyboardMonitor.removeIfUnused()
            items.values.forEach { send(it, "release") }
            items.clear()
            addressField.editing.closed = true
            customIcons.close()
            listOf(toolbar, delegate, previousToolbar, previousTitle, previousSubtitle).forEach { send(it, "release") }
            toolbar = null
            delegate = null
            previousToolbar = null
            previousTitle = null
            previousSubtitle = null
        }
    }
}

/** Callbacks stay strongly reachable for as long as Objective-C can invoke their function pointers. */
internal object MacSidebarToolbarBridge {
    val owners = ConcurrentHashMap<Long, MacSidebarToolbar>()

    private interface ItemCallback : Callback {
        fun invoke(
            self: Pointer?,
            cmd: Pointer?,
            toolbar: Pointer?,
            identifier: Pointer?,
            inserted: Byte,
        ): Pointer?
    }

    private interface ListCallback : Callback {
        fun invoke(
            self: Pointer?,
            cmd: Pointer?,
            toolbar: Pointer?,
        ): Pointer?
    }

    private interface ActionCallback : Callback {
        fun invoke(
            self: Pointer?,
            cmd: Pointer?,
            sender: Pointer?,
        )
    }

    private val itemCallback =
        object : ItemCallback {
            override fun invoke(
                self: Pointer?,
                cmd: Pointer?,
                toolbar: Pointer?,
                identifier: Pointer?,
                inserted: Byte,
            ): Pointer? = owners[Pointer.nativeValue(self)]?.makeItem(identifier)
        }
    private val listCallback =
        object : ListCallback {
            override fun invoke(
                self: Pointer?,
                cmd: Pointer?,
                toolbar: Pointer?,
            ): Pointer? {
                val array = pointer(clazz("NSMutableArray"), "array")
                owners[Pointer.nativeValue(self)]?.identifiers()?.forEach { id ->
                    send(array, "addObject:", string(id))
                }
                return array
            }
        }
    private val actionCallback =
        object : ActionCallback {
            override fun invoke(
                self: Pointer?,
                cmd: Pointer?,
                sender: Pointer?,
            ) {
                owners[Pointer.nativeValue(self)]?.activate(sender)
            }
        }
    private val addressCallback =
        object : ActionCallback {
            override fun invoke(
                self: Pointer?,
                cmd: Pointer?,
                sender: Pointer?,
            ) {
                owners[Pointer.nativeValue(self)]?.submitAddress()
            }
        }
    private val windowCallback =
        object : ActionCallback {
            override fun invoke(
                self: Pointer?,
                cmd: Pointer?,
                sender: Pointer?,
            ) {
                owners[Pointer.nativeValue(self)]?.let {
                    it.remoteInput.windowChanged(sender)
                    it.measure()
                }
            }
        }
    val bridgeClass: Pointer by lazy {
        val objc = MacToolbarRuntime.objc
        val cls =
            checkNotNull(
                objc
                    .getFunction(
                        "objc_allocateClassPair",
                    ).invokePointer(arrayOf(clazz("NSObject"), "BossConsoleSidebarToolbarDelegate", 0L)),
            )

        fun method(
            name: String,
            callback: Callback,
            types: String,
        ) {
            check(
                objc
                    .getFunction(
                        "class_addMethod",
                    ).invokeInt(arrayOf(cls, selector(name), CallbackReference.getFunctionPointer(callback), types)) !=
                    0,
            )
        }
        method("toolbar:itemForItemIdentifier:willBeInsertedIntoToolbar:", itemCallback, "@@:@@c")
        method("toolbarAllowedItemIdentifiers:", listCallback, "@@:@")
        method("toolbarDefaultItemIdentifiers:", listCallback, "@@:@")
        method("activate:", actionCallback, "v@:@")
        method("submitAddress:", addressCallback, "v@:@")
        MacAddressDelegate.install(cls)
        method("windowChanged:", windowCallback, "v@:@")
        val protocol = objc.getFunction("objc_getProtocol").invokePointer(arrayOf("NSToolbarDelegate"))
        if (protocol != null) objc.getFunction("class_addProtocol").invokeInt(arrayOf(cls, protocol))
        objc.getFunction("objc_registerClassPair").invokeVoid(arrayOf(cls))
        cls
    }
}
