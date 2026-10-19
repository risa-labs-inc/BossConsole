package ai.rever.boss.health

import java.util.concurrent.ConcurrentHashMap

/**
 * Which health sources are currently failing, so each failure is logged once rather than on every
 * read.
 *
 * The report used to be read only by `boss status` and `boss doctor`, one query at a time. The
 * status bar reads it every few seconds in every window, so a source that keeps throwing would
 * otherwise write the same warning to the log several times a minute for as long as it stays broken.
 * A source that recovers is forgotten, so failing again is logged again.
 *
 * Shared across windows and threads: each window's status item reads the report on its own
 * background coroutine.
 */
internal object HealthSourceWarnings {
    private val failing = ConcurrentHashMap.newKeySet<String>()

    /** Records that [key] failed. True only the first time since it last succeeded: log then. */
    fun failed(key: String): Boolean = failing.add(key)

    /** Records that [key] was read successfully, so its next failure is logged again. */
    fun recovered(key: String) {
        failing.remove(key)
    }

    /** For tests. */
    fun clear() {
        failing.clear()
    }
}
