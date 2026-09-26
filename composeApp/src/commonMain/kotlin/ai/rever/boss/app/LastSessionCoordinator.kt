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
    /**
     * Write the layout watcher's record as it is, for [writeInSession]. Not [save]: that one stamps
     * the record and makes it the manager's current workspace, which mid-session would rename the
     * Space the user is working in (see `WorkspaceManager.writeLastSessionRecordBlocking`).
     */
    private val saveRecord: (LayoutWorkspace) -> Boolean = { true },
) {
    private val logger = BossLogger.forComponent("LastSessionCoordinator")

    private class LiveWindow(
        val isPrimary: Boolean,
        val extractLayout: () -> LayoutWorkspace,
        val extractSet: () -> LastSessionSet?,
        val canSave: () -> Boolean,
    )

    private val liveWindows = ConcurrentHashMap<String, LiveWindow>()
    private val writtenThisSession = AtomicBoolean(false)

    // A refused startup restore protects the recovery files for this process, even if the
    // primary window closes before a secondary one. Opening another window cannot clear it.
    private val recoveryProtected = AtomicBoolean(false)

    // Held by the shutdown write and the owner's in-session write, so neither can land a file in
    // the middle of the other's pair. See [writeInSession].
    private val writeLock = Any()

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
        canSave: () -> Boolean = { true },
        extractLayout: () -> LayoutWorkspace,
    ) {
        liveWindows[windowId] = LiveWindow(isFirstWindow, extractLayout, extractSet, canSave)
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
        val pending = liveWindows[windowId]
        if (pending != null && !pending.canSave()) recoveryProtected.set(true)
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
        val entry = if (writtenThisSession.get()) null else recordOwner()
        return entry != null && writeLastSession(entry.key, entry.value, trigger = "process-exit")
    }

    /**
     * Whether [windowId] may keep the recovery files current during the session: it is the window
     * a shutdown at this moment would write for, and no live window is protecting a refused
     * restore.
     *
     * The layout watcher asks before every in-session write, so the files have ONE writer role
     * during the session as well as at its end. Every window's watcher used to write
     * `Last_Session.json`, so a secondary window's layout replaced the primary's crash-recovery
     * copy - the #19 symptom by the in-session route. And the multi-Space set was written only
     * here, at shutdown, so after a hard kill it described the clean shutdown BEFORE the session
     * that crashed, and restore reads it first. The owner now writes the record and the set
     * together, which is what this class does at shutdown too.
     *
     * "Protecting a refused restore" reaches a little further than the words: a window that
     * closes before its own restore has finished latches the same protection, because `canSave`
     * cannot tell "refused" from "not finished yet". From then on no window writes either file
     * in-session for the rest of the process, exactly as the shutdown write already refuses to.
     */
    fun ownsSessionRecord(windowId: String): Boolean {
        if (recoveryProtected.get() || liveWindows.values.any { !it.canSave() }) return false
        return recordOwner()?.key == windowId
    }

    /**
     * The record owner's in-session write: [set] and then [record], and whether the record landed.
     * False without writing anything when [windowId] no longer owns the record or the shutdown
     * write has already happened.
     *
     * The set goes first. Restore reads it in preference to the record, so if the pair is ever cut
     * short, by a kill between the two writes, the file that landed is the one restore reads.
     *
     * Under [writeLock], which [writeLastSession] holds too. The shutdown hook runs on its own
     * thread while every window's watcher is still alive, so without the lock a watcher part-way
     * through its pair could land its second file after the hook had written both: a set from one
     * moment beside a record from another, and, when the user had just closed a Space and quit, the
     * set the hook deleted written back. Asking again inside the lock means a watcher that arrives
     * after the shutdown write adds nothing, and one that arrives first finishes before the hook
     * writes over it.
     *
     * Never throws, like [claimAndWrite]: a failure is logged and answered false. The caller is a
     * window's layout watcher, and an exception escaping into it would end the watcher for the rest
     * of that window's life - no unsaved marks, no recovery files - with nothing in the log.
     */
    // Any failure, the set's serialization included, must be logged rather than end the watcher.
    @Suppress("TooGenericExceptionCaught")
    fun writeInSession(
        windowId: String,
        record: LayoutWorkspace,
        set: LastSessionSet?,
    ): Boolean =
        synchronized(writeLock) {
            if (writtenThisSession.get() || !ownsSessionRecord(windowId)) {
                false
            } else {
                try {
                    saveSet(set)
                    saveRecord(record)
                } catch (e: Exception) {
                    logger.warn(
                        LogCategory.WORKSPACE,
                        "In-session recovery write failed",
                        mapOf("windowId" to windowId),
                        error = e,
                    )
                    false
                }
            }
        }

    /** The primary window if it is still open, else any live window - the one a shutdown writes for. */
    private fun recordOwner(): Map.Entry<String, LiveWindow>? =
        liveWindows.entries.firstOrNull { it.value.isPrimary } ?: liveWindows.entries.firstOrNull()

    private fun writeLastSession(
        windowId: String,
        window: LiveWindow,
        trigger: String,
    ): Boolean {
        // Check every live window before selecting a writer: the primary may have refused
        // restoration while a secondary is the last disposer or the shutdown-hook candidate.
        if (!window.canSave() || liveWindows.values.any { !it.canSave() }) recoveryProtected.set(true)
        if (recoveryProtected.get()) return false
        // The owner's in-session write takes the same lock; see [writeInSession].
        return synchronized(writeLock) { claimAndWrite(windowId, window, trigger) }
    }

    private fun claimAndWrite(
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
                saveRecord = { record -> workspaceManager.writeLastSessionRecordBlocking(record) },
            )
    }
}
