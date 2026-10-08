package ai.rever.boss.plugin.browser

import java.util.WeakHashMap

/**
 * Remembers which (engine, profile) pairs a one-time setup already ran on.
 *
 * Keyed on the engine OBJECT, held weakly, never on a hash of it: FluckEngine rebuilds its engine
 * to recover a wedged renderer, and the replacement must get the setup again. An identity hash is
 * neither unique nor retired with its object, so a replacement could match a dead engine's key and
 * skip the setup for the rest of the session; a weak key cannot match anything but itself, and an
 * engine that is gone takes its entries with it.
 */
internal class EnginePerProfileOnce {
    private val claimed = WeakHashMap<Any, MutableSet<String>>()

    /** True the first time [profileName] is claimed on [engine]; false after that. */
    @Synchronized
    fun claim(
        engine: Any,
        profileName: String,
    ): Boolean = claimed.getOrPut(engine) { mutableSetOf() }.add(profileName)

    /** Undoes a [claim] whose setup failed, so the next attempt runs it again. */
    @Synchronized
    fun release(
        engine: Any,
        profileName: String,
    ) {
        claimed[engine]?.remove(profileName)
    }
}
