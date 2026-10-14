package ai.rever.boss.plugin.ui.menu

import androidx.compose.ui.graphics.ImageBitmap

/**
 * A menu described in terms an operating-system menu can actually render.
 *
 * Deliberately narrower than the app's own `ContextMenuItem`: a native menu item is a label, an
 * enabled flag, an optional shortcut, and nothing else. No icon, no inline trailing buttons, no
 * per-item colour. Anything richer has to be expressed as structure (a submenu) rather than as
 * decoration, which is why callers convert into this model instead of it mirroring theirs.
 */
sealed interface NativeMenuNode {
    /**
     * [shortcut] is display-only. An AWT `MenuShortcut` renders as the platform's menu-key glyph
     * but does NOT register a live key equivalent, so it cannot double-fire with the app's own
     * accelerator handling. It must be an uppercase letter or a digit, whose char code IS the
     * matching `KeyEvent.VK_*` constant; lowercase 'c' is 99, which is not a VK constant at all.
     */
    data class Item(
        val label: String,
        val enabled: Boolean = true,
        val shortcut: Char? = null,
        /**
         * Already rasterised, because only the caller is in a composition and can resolve density.
         * Applied through the macOS menu peer, which does support images even though the public
         * `java.awt.MenuItem` API does not - see the desktop implementation.
         *
         * Must be square and rasterised at [NATIVE_MENU_ICON_SCALE] times
         * [NATIVE_MENU_ICON_POINTS]. AppKit reads a plain bitmap's **pixel** dimensions as its
         * **point** size, so handing it a Retina-sized bitmap renders an icon twice too large;
         * the desktop side turns this into a multi-resolution image to state the point size.
         */
        val icon: ImageBitmap? = null,
        val action: () -> Unit = {},
    ) : NativeMenuNode {
        init {
            require(shortcut == null || shortcut in 'A'..'Z' || shortcut in '0'..'9') {
                "shortcut must be an uppercase letter or digit, was '$shortcut'"
            }
        }
    }

    data object Separator : NativeMenuNode

    data class Submenu(
        val label: String,
        val children: List<NativeMenuNode>,
    ) : NativeMenuNode
}

/**
 * The point size a native menu icon should occupy.
 *
 * macOS's own menu-item checkmark is 14x13pt, and 16pt is the common convention for custom menu
 * icons, so this sits at the top of the system's own range rather than inventing a size.
 */
const val NATIVE_MENU_ICON_POINTS: Int = 16

/** Rasterise at 2x so the icon stays crisp on Retina; the base variant states the point size. */
const val NATIVE_MENU_ICON_SCALE: Int = 2

/** Where the menu should appear. */
sealed interface NativeMenuAnchor {
    /**
     * At the mouse cursor, read from the OS at show time.
     *
     * This is the right default for a right-click menu and it sidesteps a real problem: Compose
     * pointer coordinates are node-relative and in Compose pixels, while a native menu needs
     * screen coordinates in AWT units. Reading the cursor avoids the conversion entirely.
     */
    data object Cursor : NativeMenuAnchor

    /** Absolute screen coordinates, for menus anchored to a widget rather than the pointer. */
    data class Screen(
        val x: Int,
        val y: Int,
    ) : NativeMenuAnchor
}

/**
 * Native menus are enabled on macOS only.
 *
 * macOS is the platform whose behaviour was actually measured (`java.awt.PopupMenu` is peered
 * onto a real `NSMenu` there): `show()` does not block, nothing cancels an open menu, there is no
 * dismissal event but an open menu holds the input grab, and `MenuShortcut` is display-only. The
 * other two are excluded for different reasons:
 *
 * - **Windows**: unverified and plausibly worse. `WPopupMenuPeer` appears to reach
 *   `::TrackPopupMenu` through `AwtToolkit::SyncCall`, which would block the EDT for as long as
 *   the menu is open, freezing the UI. `TrackPopupMenu` also renders the classic Win32 menu,
 *   which does not follow system dark mode without `uxtheme` work AWT does not do.
 * - **Linux**: the AWT peer is the Motif-era XAWT menu, which ignores GTK.
 *
 * The engine is otherwise platform-neutral. Widening it is this one predicate plus a measurement
 * on that platform with the same harness.
 *
 * A live remote view also turns them off: an NSMenu is an OS-owned window, so a viewer painting
 * BOSS's own windows never receives it, cannot click it, and it opens at the host's cursor.
 *
 * [remotelyViewed] defaults to the live [RemoteViewState] so a caller that omits it still fails
 * closed. `@JvmOverloads` stays only to keep the published two-argument JVM signature
 * `(Boolean, Boolean)` that already-built plugins link against.
 */
@JvmOverloads
fun shouldUseNativeMenus(
    settingEnabled: Boolean,
    isMacOs: Boolean,
    remotelyViewed: Boolean = RemoteViewState.count.value > 0,
): Boolean = settingEnabled && isMacOs && !remotelyViewed

/**
 * Whether a native menu can be produced at all.
 *
 * Lifted out of [NativeContextMenus.show] because `isSupported()` short-circuits everything on a
 * non-macOS machine, so a test that goes through `show()` on CI only ever re-tests the platform
 * gate - it can never reach the EDT or empty-plan guards it claims to cover. The whole design
 * leans on this answer being honest, so it gets coverage that actually runs everywhere.
 */
fun canShowNatively(
    isSupported: Boolean,
    isEventDispatchThread: Boolean,
    plannedSize: Int,
): Boolean = isSupported && isEventDispatchThread && plannedSize > 0

/**
 * Normalise a menu for native rendering.
 *
 * Two jobs, both of which exist because callers build menus conditionally:
 *
 * - **Separator hygiene.** Leading, trailing and consecutive separators are dropped, and an empty
 *   submenu is removed entirely. A menu assembled with `if` guards routinely produces those, and
 *   a native menu renders a dangling separator as a stray line rather than ignoring it.
 * - **Mnemonic escaping.** On Windows, `AppendMenu`/`SetMenuItemInfo` treat `&` in an `MFT_STRING`
 *   as a mnemonic prefix, so a label like `feat/a&b` would render as `feat/ab` with an underlined
 *   `b`. Labels here are user data (branch names, file names, workspace names), so this is
 *   reachable in ordinary use. Unreachable while the gate is macOS-only, but kept and tested so
 *   widening the gate cannot silently mangle text.
 */
fun planNativeMenu(
    nodes: List<NativeMenuNode>,
    isWindows: Boolean = false,
): List<NativeMenuNode> {
    val planned = ArrayList<NativeMenuNode>(nodes.size)
    for (node in nodes) {
        when (node) {
            is NativeMenuNode.Separator -> {
                // Drop leading and consecutive separators; a trailing one is trimmed below.
                if (planned.isNotEmpty() && planned.last() !is NativeMenuNode.Separator) {
                    planned += NativeMenuNode.Separator
                }
            }

            is NativeMenuNode.Item -> {
                planned += node.copy(label = escapeNativeLabel(node.label, isWindows))
            }

            is NativeMenuNode.Submenu -> {
                val children = planNativeMenu(node.children, isWindows)
                // A submenu with nothing in it is an unopenable dead row.
                if (children.isNotEmpty()) {
                    planned +=
                        NativeMenuNode.Submenu(
                            label = escapeNativeLabel(node.label, isWindows),
                            children = children,
                        )
                }
            }
        }
    }
    while (planned.lastOrNull() is NativeMenuNode.Separator) planned.removeAt(planned.lastIndex)
    return planned
}

/** See the mnemonic note on [planNativeMenu]. */
fun escapeNativeLabel(
    label: String,
    isWindows: Boolean,
): String = if (isWindows) label.replace("&", "&&") else label

/**
 * Shows real operating-system context menus.
 *
 * Lives here rather than in the app so that plugins, which resolve `ai.rever.boss.plugin.ui.*`
 * parent-first from the host, get the same menus as host UI does.
 */
expect object NativeContextMenus {
    /** True when this platform is one whose native menu behaviour has been verified. */
    fun isSupported(): Boolean

    /**
     * Show [nodes] as a native menu.
     *
     * Returns false when nothing was shown, in which case the caller must fall back to its own
     * menu. [onDismiss] fires exactly once for a menu that was shown, whether it was dismissed by
     * selecting an item, clicking away, pressing Escape or switching applications.
     */
    fun show(
        nodes: List<NativeMenuNode>,
        anchor: NativeMenuAnchor = NativeMenuAnchor.Cursor,
        onDismiss: () -> Unit = {},
    ): Boolean

    /**
     * Give up ownership of the menu currently on screen.
     *
     * Advisory: an open OS menu cannot be dismissed programmatically. This makes its items inert
     * and greys them out, so a menu that outlives the UI that opened it cannot act on state that
     * has already gone away.
     */
    fun hide()
}
