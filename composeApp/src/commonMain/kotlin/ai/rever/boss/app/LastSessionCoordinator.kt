package ai.rever.boss.app

import ai.rever.boss.components.workspaces.LastSessionSet
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the single app-level "Last Session" workspace.
 *
 * Two problems live here, both from #19:
 *
 * 1. **Who writes.** Every window's dispose used to save *its own* layout into
 *    the one "Last Session" workspace, so closing a secondary window overwrote
 *    the primary's session. A window disposing now only writes when it is the
 *    last live one.
 *
 * 2. **Whether anyone writes at all.** A window dispose only happens when Compose
 *    tears the window composition down. That covers closing a window and the
 *    File menu's "Quit BOSS" (`exitApplication()` drops the content from the
 *    composition), but *not* the paths that end the JVM directly:
 *    - macOS app-menu Quit / Cmd+Q — the JDK's default `QuitStrategy` is
 *      `NORMAL_EXIT`, i.e. `System.exit(0)` (`com.apple.eawt._AppEventHandler`),
 *      and nothing here opts into `CLOSE_ALL_WINDOWS`
 *    - `ApplicationRestarter.quitForUpdate()` / `restart()` — `exitProcess(0)`
 *    - SIGTERM (logout, restart, `kill`)
 *
 *    So [saveOnProcessExit] runs from the JVM shutdown hook, which every one of
 *    those paths does execute. On that path the window compositions are still
 *    alive, so their layouts are still extractable.
 *
 * [writtenThisSession] keeps the two paths from writing twice, and is reset when
 * a window registers, because the app can outlive all its windows on macOS and a
 * later shutdown must be able to save again.
 */
class LastSessionCoordinator internal constructor(
    private val save: (LayoutWorkspace) -> Boolean,
    /**
     * Write the multi-Space record, or DELETE it when handed null.
     *
     * A second write under the SAME claim rather than a second writer: the whole point of this
     * class is that one window produces the app-level session record, and two records describing
     * one session must be produced together or they can disagree about it. Defaulted so a test
     * that only cares about who writes does not have to say anything about the set.
     */
    private val saveSet: (LastSessionSet?) -> Boolean = { true },
) {
    private val logger = BossLogger.forComponent("LastSessionCoordinator")

    private class LiveWindow(
        val isPrimary: Boolean,
        val extractLayout: () -> LayoutWorkspace,
        val extractSet: () -> LastSessionSet?,
    )

    private val liveWindows = ConcurrentHashMap<String, LiveWindow>()
    private val writtenThisSession = AtomicBoolean(false)

    /** Number of windows currently registered. */
    val liveWindowCount: Int
        get() = liveWindows.size

    /**
     * Register a live window and how to extract its current layout.
     *
     * [extractLayout] is invoked at teardown, possibly from the shutdown-hook
     * thread, so it must read live state rather than close over a snapshot.
     */
    fun register(
        windowId: String,
        isFirstWindow: Boolean,
        /**
         * Every Space this window is running, and which was showing, or null for a session that
         * needs no set - fewer than two Spaces, which `Last_Session.json` already records on its
         * own. Invoked at teardown alongside [extractLayout], so it must read live state too.
         */
        extractSet: () -> LastSessionSet? = { null },
        extractLayout: () -> LayoutWorkspace,
    ) {
        liveWindows[windowId] = LiveWindow(isFirstWindow, extractLayout, extractSet)
        // A new window means a new session to persist later.
        writtenThisSession.set(false)
    }

    /**
     * A window's composition was disposed. Writes "Last Session" only if this was
     * the last live window.
     *
     * @return true when this call performed the write.
     */
    fun onWindowDisposed(windowId: String): Boolean {
        // A window we don't know (or a double dispose) never writes on its behalf,
        // and neither does one closing while others are still open - that was the
        // bug.
        val window = liveWindows.remove(windowId)
        return if (window == null || liveWindows.isNotEmpty()) {
            logger.debug(
                LogCategory.WORKSPACE,
                "Skipping Last Session save",
                mapOf(
                    "windowId" to windowId,
                    "known" to (window != null).toString(),
                    "liveWindows" to liveWindows.size.toString(),
                ),
            )
            false
        } else {
            writeLastSession(windowId, window, trigger = "window-dispose")
        }
    }

    /**
     * Process is exiting. Writes "Last Session" unless a window dispose already
     * did for this session.
     *
     * Prefers the primary window's layout: new windows deliberately start fresh
     * (#129), so on a multi-window quit persisting a secondary window's mostly
     * empty layout would reproduce #19's symptom by another route.
     *
     * @return true when this call performed the write.
     */
    fun saveOnProcessExit(): Boolean {
        val entry =
            if (writtenThisSession.get()) {
                null
            } else {
                liveWindows.entries.firstOrNull { it.value.isPrimary } ?: liveWindows.entries.firstOrNull()
            }
        return entry != null && writeLastSession(entry.key, entry.value, trigger = "process-exit")
    }

    private fun writeLastSession(
        windowId: String,
        window: LiveWindow,
        trigger: String,
    ): Boolean {
        // Claim the write before doing it: the dispose path and the shutdown hook
        // can run concurrently (a hook fires while Compose is still tearing down).
        if (!writtenThisSession.compareAndSet(false, true)) return false
        return try {
            val saved = save(window.extractLayout())
            // Both files, one claim. The set is written (or deleted) even when the single-Space
            // write failed: they describe the same session, and leaving a stale set beside a
            // half-written single record is the one state that restores something nobody had.
            val set = window.extractSet()
            val setSaved = saveSet(set)
            logger.debug(
                LogCategory.WORKSPACE,
                "Last Session save",
                mapOf(
                    "windowId" to windowId,
                    "trigger" to trigger,
                    "saved" to saved.toString(),
                    "spaces" to (set?.spaces?.size ?: 0).toString(),
                    "setSaved" to setSaved.toString(),
                ),
            )
            saved
        } catch (e: Exception) {
            // Shutdown path: never block teardown, but leave a breadcrumb — a
            // silently lost "Last Session" is exactly what users report as
            // "my layout disappeared".
            writtenThisSession.set(false)
            logger.warn(
                LogCategory.WORKSPACE,
                "Last Session save failed",
                mapOf("windowId" to windowId, "trigger" to trigger),
                error = e,
            )
            false
        }
    }

    companion object {
        val instance =
            LastSessionCoordinator(
                save = { layout -> workspaceManager.saveLastSessionBlocking(layout) },
                saveSet = { set -> workspaceManager.saveLastSessionSetBlocking(set) },
            )
    }
}
