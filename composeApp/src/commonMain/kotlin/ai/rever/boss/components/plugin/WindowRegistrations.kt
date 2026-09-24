package ai.rever.boss.components.plugin

import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps a plugin's process-wide registrations alive for as long as any window still holds them.
 *
 * Every BOSS window owns a [DefaultPlugin] and a `DynamicPluginManager`, and loads its own copy of
 * every plugin. Seven kinds of registration are not per window: MCP tool providers, search providers,
 * panel menus, settings pages, deep-link actions, shortcut providers and status-bar items all go to
 * `object` registries keyed only by id. So a second window's copy replaced the first window's entry,
 * and closing either window - `disposeWindow` force-uninstalls every plugin, and each teardown
 * unregisters by id - removed the id for the whole app while the other window still had the plugin
 * loaded. Nothing registered it again. In the released app an attached agent lost every plugin MCP
 * tool (29 tools, then 16) the moment a second window was closed.
 *
 * Each (registry, id) keeps the windows that registered it, most recent last:
 *
 * - **register** records this window's value on top and publishes it, which is what the registry
 *   did before: the newest registration serves.
 * - **unregister** removes only this window's entry. When that entry was the one being served and
 *   another window still has one, that one is published again (a replace, so a registry observer
 *   never sees the id missing); when none is left, the id is withdrawn as before. A window with no
 *   entry changes nothing, so it can no longer remove another window's registration.
 *
 * **Locked per (registry, id), never globally.** A target can [Target.prepare] a value once, after
 * the owner is admitted but before it is published. MCP and shortcut targets use that boundary to
 * snapshot `tools()` / `shortcuts()`: restoring an older window republishes its prepared value and
 * never re-enters surviving plugin code on the closing thread. A per-id lock can only make the same
 * id in another window wait. Release fences future registrations and visits only slots admitted by
 * that owner.
 *
 * Not addressed here: while two windows are open, the most recent window's copy serves every window,
 * exactly as before. A provider whose action looks a window up in its own plugin state (a shortcut's
 * `onAction(actionId, windowId)`) still sees only its own copy's windows.
 */
internal class WindowRegistrations {
    /** A window lifetime token; no process-wide set retains closed windows. */
    class Owner {
        @Volatile
        var released: Boolean = false
            private set

        private val admitted = mutableSetOf<Slot<*>>()

        internal fun admit(slot: Slot<*>): Boolean =
            synchronized(this) {
                if (released) return@synchronized false
                admitted += slot
                true
            }

        internal fun forget(slot: Slot<*>) {
            synchronized(this) { admitted.remove(slot) }
        }

        internal fun close(): List<Slot<*>> =
            synchronized(this) {
                released = true
                admitted.toList().also { admitted.clear() }
            }
    }

    /** One process-wide registry, as [DefaultPlugin] reaches it. */
    class Target<V : Any>(
        val name: String,
        val publish: (V) -> Unit,
        val withdraw: (id: String) -> Unit,
        /** Runs once per admitted registration; restored entries reuse its returned value. */
        val prepare: (V) -> V = { it },
    )

    /** What an [unregister] did to the registry, for logging and tests. */
    enum class Outcome {
        /** The window had no entry for the id: the registry is untouched. */
        NOT_REGISTERED_BY_WINDOW,

        /** The window's entry was not the one being served: the registry is untouched. */
        REMOVED_UNSERVED,

        /** Another window's entry is served again. */
        RESTORED_OTHER_WINDOW,

        /** No window holds the id any more: it was removed from the registry. */
        WITHDRAWN,
    }

    internal class Slot<V : Any>(
        private val target: Target<V>,
        private val id: String,
    ) {
        /** (window, prepared value), the served one last. Guarded by this slot's monitor. */
        private val entries = ArrayList<Pair<Owner, V>>()

        fun register(
            owner: Owner,
            value: V,
        ) = synchronized(this) {
            if (!owner.admit(this)) return@synchronized
            // Prepare BEFORE dropping the owner's previous entry: a prepare that throws must
            // leave the live registration serving, not strand it with nothing published.
            val prepared = target.prepare(value)
            entries.removeAll { it.first === owner }
            entries += owner to prepared
            target.publish(prepared)
        }

        fun unregister(owner: Owner): Outcome =
            synchronized(this) {
                val index = entries.indexOfFirst { it.first === owner }
                if (index >= 0) owner.forget(this)
                val served = index == entries.lastIndex
                when {
                    index < 0 -> {
                        Outcome.NOT_REGISTERED_BY_WINDOW
                    }

                    !served -> {
                        entries.removeAt(index)
                        Outcome.REMOVED_UNSERVED
                    }

                    else -> {
                        entries.removeAt(index)
                        val next = entries.lastOrNull()
                        if (next == null) {
                            target.withdraw(id)
                            Outcome.WITHDRAWN
                        } else {
                            target.publish(next.second)
                            Outcome.RESTORED_OTHER_WINDOW
                        }
                    }
                }
            }
    }

    private val slots = ConcurrentHashMap<Pair<Target<*>, String>, Slot<*>>()

    @Suppress("UNCHECKED_CAST")
    private fun <V : Any> slot(
        target: Target<V>,
        id: String,
    ): Slot<V> = slots.computeIfAbsent(target to id) { Slot(target, id) } as Slot<V>

    /** Records [value] as [owner]'s registration of [id] in [target] and publishes it. */
    fun <V : Any> register(
        target: Target<V>,
        id: String,
        owner: Owner,
        value: V,
    ) = slot(target, id).register(owner, value)

    /** Removes [owner]'s registration of [id] from [target]; see the class KDoc for what is published. */
    fun <V : Any> unregister(
        target: Target<V>,
        id: String,
        owner: Owner,
    ): Outcome = slot(target, id).unregister(owner)

    /**
     * Removes everything [owner] still holds, as if it had unregistered each one.
     *
     * For a window that is gone: a registration its plugins' teardown did not remove must not be
     * served again later, when a remaining window lets go of the same id.
     */
    fun release(owner: Owner) {
        owner.close().forEach { it.unregister(owner) }
    }
}
