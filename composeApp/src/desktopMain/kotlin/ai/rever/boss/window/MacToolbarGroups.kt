package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.clazz
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.send
import ai.rever.boss.window.MacToolbarRuntime.string
import com.sun.jna.Pointer

/** AppKit draws one shared glass background per group, preserving each subitem's action. */
internal object MacToolbarGroups {
    val members =
        mapOf(
            "terminal_controls" to listOf("terminal_sharing", "terminal_call", "terminal_mcp"),
            "utility_controls" to listOf("search", "tools", "toolbox"),
        )

    fun groupId(id: String): String? = members.entries.firstOrNull { id in it.value }?.key

    fun identifiers(ids: List<String>): List<String> = ids.map { groupId(it) ?: it }.distinct()

    fun presentMembers(
        id: String,
        actions: Map<String, NativeTitleBarAction>,
    ): List<String> = members.getValue(id).filter { it in actions }

    fun create(id: String): Pointer {
        val group =
            checkNotNull(
                pointer(pointer(clazz("NSToolbarItemGroup"), "alloc"), "initWithItemIdentifier:", string(id)),
            )
        send(group, "setBordered:", 1.toByte())
        send(group, "setControlRepresentation:", 1L) // Expanded: keep every action visible.
        // Status segments can be independently active; utility buttons remain momentary.
        send(group, "setSelectionMode:", if (id == "terminal_controls") 1L else 2L)
        val label = if (id == "terminal_controls") "Sharing, Call and MCP" else "Search and Tools"
        send(group, "setLabel:", string(label))
        return group
    }

    fun update(
        id: String,
        group: Pointer,
        actions: Map<String, NativeTitleBarAction>,
        previous: MutableMap<String, List<String>>,
        makeItem: (Pointer?) -> Pointer?,
    ) {
        val present = presentMembers(id, actions)
        if (previous[id] != present) {
            val array = pointer(clazz("NSMutableArray"), "array")
            present.mapNotNull { makeItem(string(it)) }.forEach { send(array, "addObject:", it) }
            send(group, "setSubitems:", array)
            previous[id] = present
        }
        present.forEachIndexed { index, actionId ->
            val selected = if (actions[actionId]?.active == true) 1.toByte() else 0.toByte()
            send(group, "setSelected:atIndex:", selected, index.toLong())
        }
    }
}
