package ai.rever.boss.dashboard

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.extractFileName
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.decodeFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File

private val recentFilesLogger = BossLogger.forComponent("RecentFilesManager")

/**
 * Data class representing a recently opened file.
 */
@Serializable
data class RecentFile(
    val path: String,
    val name: String,
    val lastOpened: Long,
    val projectPath: String? = null,
)

/**
 * Container for recent files data with serialization support.
 */
@Serializable
data class RecentFilesData(
    val files: List<RecentFile> = emptyList(),
)

/**
 * Manages recently opened files for the Dashboard.
 * Persists to ~/.boss/recent-files.json
 *
 * Thread-safe: all file I/O runs on [Dispatchers.IO], every mutation of the recorded list is
 * serialised by [mutationLock], and the file is replaced atomically. Uses StateFlow for reactive
 * UI updates.
 *
 * The sibling [RecentBrowserPagesManager] had the same three defects and was fixed first; this
 * class is kept deliberately parallel to it so the two do not drift again.
 */
@Suppress("TooManyFunctions")
object RecentFilesManager {
    private const val MAX_FILES = 20
    private const val SAVE_DEBOUNCE_MS = 5000L // Debounce saves to max once per 5 seconds

    /**
     * Redirected by [resetForTesting] for hermetic unit tests; production code never reassigns
     * it, the same way the sibling [RecentBrowserPagesManager] does.
     */
    internal var settingsFile: File = BossDirectories.resolve("recent-files.json")
    private val json =
        Json {
            prettyPrint = false
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Serialises read-modify-write over [_allFiles].
     *
     * Every public mutator used to read `_allFiles.value`, edit a copy, and write it back from a
     * coroutine on the multi-threaded IO dispatcher. Two opens landing together - which is the
     * normal case when a project restores several editors at startup - would both read the same
     * list and the second write would silently discard the first file. A `MutableStateFlow.update`
     * CAS loop is not usable here because a mutation has to leave *two* flows consistent and the
     * CAS lambda can be retried; a lock is the form that keeps the recorded list single-writer.
     */
    private val mutationLock = Mutex()

    /**
     * Serialises the derive of [_recentFiles] from [_allFiles], and is deliberately *not*
     * [mutationLock].
     *
     * The derive calls `File.exists()` once per entry - up to 20 - and on an unmounted or
     * disconnected share, the exact case [_allFiles] exists for, each can block for seconds.
     * Holding [mutationLock] across that queued every `recordFileOpen`, `removeFile` and
     * `clearAll` behind one slow mount. The recorded list is read inside this lock, so the
     * derive that finishes last is the one that read the freshest recorded list.
     */
    private val visibilityLock = Mutex()

    private val saveJobLock = Any()
    private var saveJob: Job? = null

    /**
     * Every recorded file, including ones not currently on disk. **This is what is persisted.**
     *
     * Split from [recentFiles] because the displayed list is filtered by `File.exists()`, and that
     * is false for an unmounted volume or a disconnected share - normal at login, which is when
     * the prune runs. Persisting the filtered list would turn "not here right now" into permanent
     * loss: the first `recordFileOpen` after launch calls `scheduleSave()`, which serialises
     * whatever this manager holds, so hiding and saving cannot be the same list.
     */
    private val _allFiles = MutableStateFlow<List<RecentFile>>(emptyList())

    private val _recentFiles = MutableStateFlow<List<RecentFile>>(emptyList())

    /** The displayed list: [_allFiles] minus anything not on disk right now. */
    val recentFiles: StateFlow<List<RecentFile>> = _recentFiles.asStateFlow()

    /**
     * Re-derive the displayed list from the recorded one.
     *
     * Split out of the recorded write so the `File.exists()` calls happen outside [mutationLock];
     * see [visibilityLock]. The displayed list therefore lags the recorded one for the length of
     * the derive. That is not new - nothing re-derives between mutations either, so a file
     * deleted outside BOSS already stays visible until the next mutation - the window is just
     * wider. What must not happen is the reverse: the *recorded* list is what gets persisted
     * (see [_allFiles]), and no caller may write [_recentFiles] into it.
     */
    private suspend fun refreshVisible() {
        visibilityLock.withLock {
            val recorded = _allFiles.value
            _recentFiles.value = visibleFiles(recorded) { fileExists(it) }
        }
    }

    /**
     * Apply [transform] to the recorded list under [mutationLock] and schedule a save if the
     * list changed.
     *
     * The single entry point for user-driven mutation, so no caller can reintroduce the
     * read-then-write race by writing [_allFiles] directly. The startup merge is the one other
     * path that holds [mutationLock]: its no-op baseline is the decoded file rather than the
     * pre-merge list, so it decides its own save (see [loadAsync]).
     */
    private suspend fun mutate(transform: (List<RecentFile>) -> List<RecentFile>) {
        val changed =
            mutationLock.withLock {
                val before = _allFiles.value
                val after = transform(before)
                if (after == before) {
                    false
                } else {
                    _allFiles.value = after
                    true
                }
            }
        // A transform that changed nothing schedules nothing. RecentFile is a data class, so
        // this is a value comparison.
        if (!changed) return
        // Save first: it only arms a timer, while the derive can block on a slow mount.
        scheduleSave()
        refreshVisible()
    }

    /**
     * Retained so [resetForTesting] can cancel it: the init load reads [settingsFile] at
     * execution time, and a test that re-points the file must not have the first load merge
     * the real user's list into its hermetic state.
     */
    private var initialLoadJob: Job? = null

    init {
        initialLoadJob =
            scope.launch {
                loadAsync()
            }
    }

    /**
     * Load recent files from disk asynchronously.
     *
     * Returns whether the startup merge changed what is on disk, i.e. whether a save was
     * scheduled. The no-op baseline is the decoded contents, not the pre-merge in-memory list:
     * that list is empty until this point, so comparing the merge against it would call the
     * ordinary launch "changed" and rewrite recent-files.json with its own contents on every
     * start.
     *
     * Known limitation: the read and decode run before [mutationLock] is taken, so a
     * `removeFile` or `clearAll` landing inside that window (milliseconds, at launch) is
     * applied first, and the merge then reapplies the stale decoded contents until the next
     * real mutation. Re-reading the file under the lock would close the window, but a slow
     * disk would then hold the mutation lock for the whole read, which is the worse trade.
     */
    internal suspend fun loadAsync(): Boolean =
        withContext(Dispatchers.IO) {
            var persist = false
            try {
                if (!settingsFile.exists()) return@withContext false

                val content = settingsFile.readText()
                val data = json.decodeFromString<RecentFilesData>(content)

                // Merged, not assigned: the load is launched from `init` and races the first
                // `recordFileOpen`, which the UI can issue as soon as a restored editor opens.
                // Overwriting here dropped that file from the recorded list *and* from the save
                // scheduled for it, so a file opened during startup was never remembered.
                //
                // Hidden, not pruned: a file that is not on disk stops being offered (it would
                // open an empty editor - fileExists existed for exactly this and had no callers)
                // but stays in the recorded list, so an unmounted volume coming back brings its
                // entries with it. See _allFiles.
                val (recordedChanged, saveDue) =
                    mutationLock.withLock {
                        val after = mergeRecorded(loaded = data.files, recorded = _allFiles.value, max = MAX_FILES)
                        val changed = after != _allFiles.value
                        if (changed) _allFiles.value = after
                        changed to (after != data.files)
                    }
                persist = saveDue
                if (persist) scheduleSave()
                if (recordedChanged) refreshVisible()

                val present = _recentFiles.value
                recentFilesLogger.debug(
                    LogCategory.FILE,
                    "Loaded recent files",
                    mapOf("count" to present.size, "hidden" to (_allFiles.value.size - present.size)),
                )
            } catch (e: SerializationException) {
                // Recorded paths routinely carry user and project names; log where it failed only.
                recentFilesLogger.warn(LogCategory.FILE, "Error loading recent files", decodeFailure(e))
            } catch (e: Exception) {
                recentFilesLogger.warn(LogCategory.FILE, "Error loading recent files", error = e)
            }
            persist
        }

    /**
     * Reset manager state for hermetic unit testing and redirect [settingsFile] to [testFile].
     * Cancels the init load (it reads [settingsFile] at execution time) and any pending debounced
     * save, then clears both flows. When [reload] is true the load is re-run so the state matches
     * [testFile], and [recorded] is seeded after it, so a test can observe the startup merge
     * against a non-empty "recorded while the load was in flight" state.
     *
     * Tests must point this back at [BossDirectories] before finishing, so the singleton is left
     * where later tests expect it, and pass `reload = false` when they do. Test tasks redirect
     * `user.home` to a fresh build directory, so this is not the developer's real file; avoiding
     * the reload still prevents teardown from scheduling work that can outlive the test.
     */
    internal suspend fun resetForTesting(
        testFile: File,
        recorded: List<RecentFile>? = null,
        reload: Boolean = true,
    ) {
        initialLoadJob?.cancelAndJoin()
        initialLoadJob = null
        // Finished before the swap, not merely cancelled, so reset cannot clear the shared list
        // while an old save is serializing it. The destination itself is captured by scheduleSave.
        val pendingSave =
            synchronized(saveJobLock) {
                saveJob.also { saveJob = null }
            }
        pendingSave?.cancelAndJoin()
        mutationLock.withLock {
            settingsFile = testFile
            _allFiles.value = emptyList()
        }
        // Keep refreshVisible as the only writer of the displayed flow. This orders the reset
        // against a derive already holding visibilityLock without nesting the two locks.
        refreshVisible()
        if (!reload) return
        loadAsync()
        if (recorded != null) {
            mutationLock.withLock { _allFiles.value = recorded }
            refreshVisible()
        }
    }

    /**
     * Save recent files to disk with debouncing.
     * Cancels any pending save and schedules a new one after SAVE_DEBOUNCE_MS.
     */
    private fun scheduleSave() {
        // Swap the debounce job under a lock: callers arrive from concurrent coroutines (an open
        // and a removal racing), and an unsynchronised cancel-then-assign can overwrite the
        // reference to a job that is still pending, leaving a timer nothing will ever cancel.
        val target = settingsFile
        synchronized(saveJobLock) {
            saveJob?.cancel()
            saveJob =
                scope.launch {
                    delay(SAVE_DEBOUNCE_MS)
                    saveImmediately(target)
                }
        }
    }

    /**
     * Immediately save recent files to disk (bypasses debounce).
     *
     * @param target resolved by the caller, never read here: a debounced save that picked
     *   its destination at execution time would follow [settingsFile] if it changed in
     *   between, and write one test/profile's files into another's file.
     */
    private suspend fun saveImmediately(target: File = settingsFile) =
        withContext(Dispatchers.IO) {
            try {
                // The recorded list, never the filtered view; see _allFiles.
                val data = RecentFilesData(files = _allFiles.value)
                val content = json.encodeToString(RecentFilesData.serializer(), data)
                // Atomic: `writeText` truncates the target and then streams into it, so a crash or
                // a second writer arriving mid-write leaves JSON that fails to parse - and the
                // load path swallows that as "no recent files", losing all twenty entries rather
                // than one. atomicWriteText writes a unique sibling temp and moves it into place.
                target.atomicWriteText(content)
            } catch (e: Exception) {
                recentFilesLogger.warn(LogCategory.FILE, "Error saving recent files", error = e)
            }
        }

    /**
     * Flush a debounced save that is still pending, writing the recorded list immediately.
     *
     * The exit-path half of the debounce: quitting inside [SAVE_DEBOUNCE_MS] of the last
     * mutation used to drop it, because the pending job died with the process - the window
     * #795's own body called out as a separate bug. The timer is cancelled under
     * [saveJobLock] (the same swap [scheduleSave] uses, so a save landing during the flush
     * cannot install a job around it) and the write goes through [saveImmediately], which is
     * fail-closed: a write error is logged, never thrown, so one unwritable file cannot take
     * the rest of the exit steps down with it.
     *
     * A no-op when nothing is pending: a debounce that already fired has persisted the
     * list, and rewriting recent-files.json regardless would churn the file on every quit
     * for no reason.
     */
    suspend fun flushPendingSaves() {
        val pending =
            synchronized(saveJobLock) {
                val active = saveJob?.isActive == true
                saveJob?.cancel()
                saveJob = null
                active
            }
        if (pending) saveImmediately()
    }

    /**
     * Record a file open event.
     * Moves the file to the top if already present, otherwise adds it.
     * Maintains max file limit.
     *
     * @param filePath Absolute path to the file
     * @param projectPath Optional project path the file belongs to
     */
    fun recordFileOpen(
        filePath: String,
        projectPath: String? = null,
    ) {
        scope.launch {
            val newFile =
                RecentFile(
                    path = filePath,
                    name = filePath.extractFileName(),
                    lastOpened = System.currentTimeMillis(),
                    projectPath = projectPath,
                )

            // Remove existing entry for this path and add to front, over the recorded list rather
            // than the displayed one, so opening a file does not drop entries that are merely on
            // an absent volume.
            mutate { current ->
                (listOf(newFile) + current.filterNot { it.path == filePath }).take(MAX_FILES)
            }
        }
    }

    /**
     * Remove a specific file from recent history.
     */
    fun removeFile(filePath: String) {
        scope.launch {
            mutate { current -> current.filterNot { it.path == filePath } }
        }
    }

    /**
     * Clear all recent files.
     */
    fun clearAll() {
        scope.launch {
            mutate { emptyList() }
        }
    }

    /**
     * Check if a file still exists on disk.
     */
    fun fileExists(filePath: String): Boolean = File(filePath).exists()
}

/**
 * The displayed subset of [all]: entries whose file is present according to [exists].
 *
 * Pure and separate so the hide-versus-prune rule is testable without driving the singleton's file
 * I/O. The other half of that rule - that the **recorded** list is what gets persisted - is
 * structural rather than tested: `saveImmediately` serialises `_allFiles`, and `refreshVisible`
 * is the only writer of `_recentFiles`. If a future change makes `saveImmediately` read
 * `_recentFiles`, an absent volume becomes permanent deletion again and nothing here will catch
 * it.
 */
internal fun visibleFiles(
    all: List<RecentFile>,
    exists: (path: String) -> Boolean,
): List<RecentFile> = all.filter { exists(it.path) }

/**
 * Combine the list read from disk with whatever was recorded in memory while that read was in
 * flight, newest first, capped at [max].
 *
 * The startup load is not an assignment because it is not the only writer: `init` launches it, and
 * `recordFileOpen` can land first when a project restores editors. A path present on both sides
 * keeps the entry with the later `lastOpened`, which is the in-memory one in that race - the
 * loaded copy is by definition the older open of the same file.
 *
 * Ordering is by `lastOpened` descending rather than by position, because the two inputs have no
 * common order to preserve. That matches what the list means everywhere else: `recordFileOpen`
 * stamps `currentTimeMillis` and inserts at the front, so recency order and timestamp order are
 * the same thing.
 */
internal fun mergeRecorded(
    loaded: List<RecentFile>,
    recorded: List<RecentFile>,
    max: Int,
): List<RecentFile> =
    (recorded + loaded)
        .groupBy { it.path }
        .map { (_, entries) -> entries.maxBy { it.lastOpened } }
        .sortedByDescending { it.lastOpened }
        .take(max)
