package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.number
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.supports
import com.sun.jna.Pointer

/** Only controls identified by this controller's public item view or propagated item tag are admitted. */
internal data class MacToolbarInputGeometry(
    val items: Map<String, Pointer>,
    val tags: Map<String, Long>,
    val actions: Map<String, NativeTitleBarAction>,
)

internal fun collectNativeToolbarTargets(
    owner: MacToolbarInput,
    window: Pointer,
    delegate: Pointer?,
    contents: MacToolbarInputGeometry,
): MutableList<MacToolbarInputTarget> = NativeToolbarInputGeometry(owner, window, delegate, contents).collect()

private class NativeToolbarInputGeometry(
    private val owner: MacToolbarInput,
    private val window: Pointer,
    private val delegate: Pointer?,
    contents: MacToolbarInputGeometry,
) {
    private val views = contents.items.mapNotNull { (id, item) -> pointer(item, "view")?.let { it to id } }.toMap()
    private val ids = contents.tags.entries.associate { it.value to it.key }
    private val actions = contents.actions
    private val groupItems =
        contents.items
            .filterKeys { it in MacToolbarGroups.members }
            .entries
            .associate { it.value to it.key }
    private val groupViews = mutableMapOf<Pointer, String>()
    private val found = mutableListOf<MacToolbarInputTarget>()
    private var visited = 0

    fun collect(): MutableList<MacToolbarInputTarget> {
        val root = pointer(window, "contentView") ?: return found
        val buttons = (0L..2L).mapNotNull { pointer(window, "standardWindowButton:", it) }
        // Follow only views borrowed from this toolbar or this window's standard controls.
        // No global-window scan or private fullscreen-window class-name matching.
        (listOf(root) + views.keys + buttons)
            .filter { ownsNativeToolbarView(window, it) }
            .map { view ->
                var top = view
                while (pointer(top, "superview") != null) top = checkNotNull(pointer(top, "superview"))
                top
            }.distinct()
            .forEach { visit(it, 0) }
        // Instance lookup proves these are this exact window's native controls, not similarly
        // labelled views or a process-wide responder. Never replace their local target/actions.
        listOf("window_close", "window_minimize", "window_zoom").forEachIndexed { index, id ->
            val button = pointer(window, "standardWindowButton:", index.toLong())
            if (button != null && ownsNativeToolbarView(window, button) &&
                number(button, "isHiddenOrHasHiddenAncestor") == 0L
            ) {
                nativeAddressBounds(button, window)?.takeIf { it.width > 0 && it.height > 0 }?.let {
                    found.add(MacToolbarInputTarget(owner, id, it, button, windowButton = index.toLong()))
                }
            }
        }
        return found
    }

    private fun visit(
        view: Pointer,
        depth: Int,
    ) {
        val outside = ++visited > 1024 || depth > 16 || !ownsNativeToolbarView(window, view)
        val hidden = supports(view, "isHidden") && number(view, "isHidden") != 0L
        if (!outside && !hidden) {
            add(view)
            val children = pointer(view, "subviews")
            for (index in 0 until number(children, "count")) {
                pointer(children, "objectAtIndex:", index)?.let { visit(it, depth + 1) }
            }
        }
    }

    @Suppress("ReturnCount") // Only positively identified controls with valid geometry are published.
    private fun add(view: Pointer) {
        val target = if (supports(view, "target")) pointer(view, "target") else null
        groupItems[target]?.let { groupViews[view] = it }
        val group = groupViews[target] ?: views[view]?.takeIf { it in MacToolbarGroups.members }
        if (group != null && supports(view, "segmentCount")) {
            val members = MacToolbarGroups.presentMembers(group, actions)
            nativeToolbarSegmentBounds(view, window, members.size)?.forEachIndexed { index, bounds ->
                found.add(MacToolbarInputTarget(owner, members[index], bounds, view))
            }
            return
        }
        val id = views[view] ?: taggedId(view) ?: return
        val action = actions[id]?.takeIf { it.textInput == null } ?: return
        val bounds = nativeAddressBounds(view, window) ?: return
        if (bounds.width > 0 && bounds.height > 0) found.add(MacToolbarInputTarget(owner, action.id, bounds, view))
    }

    private fun taggedId(view: Pointer): String? =
        if (supports(view, "target") && pointer(view, "target") == delegate && supports(view, "tag")) {
            ids[number(view, "tag")]
        } else {
            null
        }
}
