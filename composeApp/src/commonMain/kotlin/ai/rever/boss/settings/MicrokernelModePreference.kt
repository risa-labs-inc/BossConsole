package ai.rever.boss.settings

import ai.rever.boss.config.envVarsKey
import ai.rever.boss.config.parseEnvVarsLines
import ai.rever.boss.plugin.pathutils.BossDirectories
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView

/**
 * The confirmation dialog's body text, shared by both entry points so Settings and the
 * application menu offer to enable the exact same thing in the exact same words. Wording is
 * BossConsole#472's own suggested copy: it names what the mode is for, states plainly that it is
 * experimental, and stops short of promising isolation or recovery guarantees this mode does not
 * actually make.
 */
const val MICROKERNEL_MODE_CONFIRMATION_MESSAGE =
    "Microkernel Mode is designed to run supported services and plugin workloads in separate " +
        "processes instead of keeping everything inside the main BOSS process. This architecture " +
        "is intended to improve fault isolation and allow those components to be managed " +
        "independently.\n\n" +
        "This mode is experimental. Some features or plugins may not work correctly, startup or " +
        "communication between processes may fail, and additional processes may use more memory. " +
        "It does not guarantee that every plugin runs separately or that all crashes are " +
        "isolated. Save your work before trying it.\n\n" +
        "The change takes effect after restarting BOSS. You can turn Microkernel Mode off and " +
        "restart again to return to normal mode."

/** Shared persistence and save outcome for Settings and every application menu. */
object MicrokernelModePreference {
    private val mutex = Mutex()
    private val _saveState = MutableStateFlow(MicrokernelModeSaveState())
    internal val saveState = _saveState.asStateFlow()

    // Latched from the FIRST refresh() this process makes, never updated after - this is "what
    // env_vars said when BOSS started", which is the comparand "restart required" actually needs.
    // ConfigLoader.getConfig("BOSS_MODE") looks like the right answer to that question and still
    // isn't one here: it resolves env var > system property > env_vars > local.properties >
    // embedded, so it answers "what mode will the NEXT launch run in" (and can reflect an
    // environment or system-property override the saved file has nothing to do with), not
    // "what mode is THIS process running in" - which was fixed at startup by the ConfigLoader
    // snapshot. A live file read is worse still: it would clear the notice the moment the save
    // lands, while the process still runs the old mode. The latched file read is the only
    // comparand that answers "does this need a restart" for the saved preference. See #472's
    // review; what a process actually runs at startup is #391's territory.
    private var startupEnabledLatched: Boolean? = null

    /**
     * [envFile] defaults to the real `env_vars` file; exists as a parameter only so a test can
     * simulate "the process starting" against an isolated file - see [isEnabled].
     */
    suspend fun refresh(envFile: File = BossDirectories.resolve("env_vars")) =
        mutex.withLock {
            val current =
                try {
                    withContext(Dispatchers.IO) { readEnabled(envFile) }
                } catch (_: IOException) {
                    // A read failure is not a save failure - nothing was written. Conflating the
                    // two (BossConsole#481 review) had the menu say "save failed - retry" and
                    // Settings say "Could not save..." for a fault that never touched a write, and
                    // on a FIRST refresh it is actively misleading: enabled stays null, which
                    // disables both controls, so the "retry" the message invites is impossible
                    // from either surface until some other window's refresh happens to succeed.
                    _saveState.value = _saveState.value.copy(readFailed = true)
                    return@withLock
                }
            if (startupEnabledLatched == null) {
                startupEnabledLatched = current
            }
            _saveState.value =
                _saveState.value.copy(
                    enabled = current,
                    startupEnabled = startupEnabledLatched,
                    // A fresh read is also the point at which a stale save/read error stops being
                    // useful - most concretely, reopening Settings after seeing one.
                    saveFailed = false,
                    readFailed = false,
                )
        }

    /**
     * Forgets the latched startup snapshot and resets published state to its construction-time
     * default. Test-only: [startupEnabledLatched] is deliberately latched once per real process
     * and has no other reset seam, which would otherwise make every test after the first
     * [refresh] call in a JVM run see a stale snapshot from whichever test happened to run first.
     */
    internal fun forgetStartupSnapshotForTest() {
        startupEnabledLatched = null
        _saveState.value = MicrokernelModeSaveState()
    }

    /** Finish publication even if the window closes while the file write is in progress. */
    internal suspend fun saveAndPublish(
        enabled: Boolean,
        write: suspend () -> Result<Unit>,
    ): Result<Unit> =
        withContext(NonCancellable) {
            mutex.withLock {
                val result = write()
                _saveState.value =
                    if (result.isSuccess) {
                        MicrokernelModeSaveState(enabled = enabled, startupEnabled = startupEnabledLatched)
                    } else {
                        _saveState.value.copy(saveFailed = true)
                    }
                result
            }
        }

    suspend fun save(enabled: Boolean): Result<Unit> = saveAndPublish(enabled) { setEnabled(enabled) }

    /**
     * Whether the persisted preference currently requests Microkernel Mode.
     *
     * [envFile] defaults to the real `env_vars` file and exists as a parameter only so a test
     * can point this at an isolated temp file - [BossDirectories.resolve] always resolves
     * against the real user home directory with no override seam of its own.
     */
    suspend fun isEnabled(envFile: File = BossDirectories.resolve("env_vars")): Boolean =
        withContext(Dispatchers.IO) {
            try {
                readEnabled(envFile)
            } catch (_: IOException) {
                false
            }
        }

    private fun readEnabled(envFile: File): Boolean {
        if (!envFile.exists()) return false
        // The same shared line semantics as ConfigLoader's env_vars tier (parseEnvVarsLines):
        // both readers of the same file must agree, so Settings and the runtime gate cannot
        // state opposite modes for one file (BossConsole#450's review).
        return parseEnvVarsLines(envFile.readLines(Charsets.UTF_8))["BOSS_MODE"] == "KERNEL"
    }

    /**
     * Persists [enabled] to `env_vars`, returning whether the write actually succeeded.
     *
     * A caller must not update its own "saved" state, or show the restart-required notice, on a
     * `false` result - BossConsole#472 asks explicitly that only a successful save do either,
     * with the failure staying visible instead.
     *
     * [envFile] defaults to the real `env_vars` file; see [isEnabled] for why a test overrides it.
     */
    suspend fun setEnabled(
        enabled: Boolean,
        envFile: File = BossDirectories.resolve("env_vars"),
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                envFile.parentFile?.mkdirs()

                if (!envFile.exists()) {
                    writeModeFile(
                        envFile,
                        if (enabled) "BOSS_MODE=KERNEL\n" else "# BOSS_MODE=KERNEL\n",
                    )
                    return@withContext Result.success(Unit)
                }

                val lines = envFile.readLines(Charsets.UTF_8).toMutableList()
                val modeLineIndices = lines.indices.filter { index -> envVarsKey(lines[index]) == "BOSS_MODE" }
                val newLine = if (enabled) "BOSS_MODE=KERNEL" else "# BOSS_MODE=KERNEL"
                if (modeLineIndices.isEmpty()) {
                    lines.add(newLine)
                } else {
                    lines[modeLineIndices.first()] = newLine
                    modeLineIndices.drop(1).reversed().forEach { lines.removeAt(it) }
                }

                // env_vars is also where the secret-manager plugin resolves API keys from - a
                // truncate-on-open write left mid-flight (disk full, killed process, a Windows
                // lock) would destroy every unrelated key in it, not just this one. Write a
                // sibling temp file and move it into place instead, the same pattern this repo
                // already uses for exactly this hazard (StoreMissingDependencyInstaller's
                // `.jar.part`).
                writeModeFile(envFile, lines.joinToString("\n") + "\n")
                Result.success(Unit)
            } catch (e: IOException) {
                Result.failure(e)
            }
        }
}

/**
 * Whether requesting [nextEnabled] for the current preference state [currentlyEnabled] needs the
 * operator to confirm first.
 *
 * BossConsole#472's acceptance criteria, restated as one predicate so both entry points ask the
 * same question the same way: confirmation is for an explicit off-to-on request only. Turning it
 * off stays a plain, un-confirmed toggle ("Disabling remains straightforward"), and a
 * no-op request (already in the requested state - the menu item's stale, restart-pinned display
 * can send one) confirms nothing because nothing would actually change.
 */
fun needsMicrokernelModeConfirmation(
    currentlyEnabled: Boolean,
    nextEnabled: Boolean,
): Boolean = nextEnabled && !currentlyEnabled

internal data class MicrokernelModeSaveState(
    val enabled: Boolean? = null,
    val saveFailed: Boolean = false,
    // Distinct from saveFailed: this is a fault in reading env_vars, and nothing was written.
    // Worth keeping separate rather than reworded onto one flag, since the two surfaces this
    // drives need to say different things - "check that BOSS can write" is actively wrong advice
    // for a read fault, and a read fault can also occur on the very first refresh, when there is
    // no prior enabled/startupEnabled to fall back on either.
    val readFailed: Boolean = false,
    // What env_vars said the FIRST time this process read it - see the KDoc on
    // MicrokernelModePreference.startupEnabledLatched for why this, and not a live
    // ConfigLoader read, is the saved-preference comparand. Runtime mode activation is tracked separately in #391.
    val startupEnabled: Boolean? = null,
) {
    val needsRestart: Boolean
        get() = enabled != null && startupEnabled != null && enabled != startupEnabled
}

internal fun microkernelModeMenuLabel(state: MicrokernelModeSaveState): String =
    when {
        state.readFailed -> "Microkernel Mode (unavailable)"
        state.saveFailed -> "Microkernel Mode (save failed - retry)"
        state.needsRestart -> "Microkernel Mode (restart required)"
        else -> "Microkernel Mode"
    }

/** Local consent belongs to the initiating surface; a dismissal never calls persistence. */
internal class MicrokernelModeConfirmation {
    var pending by mutableStateOf(false)
        private set

    fun request() {
        pending = true
    }

    fun cancel() {
        pending = false
    }

    fun confirm(save: () -> Unit) {
        if (!pending) return
        pending = false
        save()
    }
}

/**
 * Publish [text] to [file] atomically, replacing a killed-process-mid-write with a killed-process-
 * before-move - the file [file] names is either the old complete contents or the new ones, never a
 * partial write. `ATOMIC_MOVE` alone already replaces an existing target (`Files.move`'s contract:
 * when `ATOMIC_MOVE` is present every other option is ignored), so there is deliberately no
 * non-atomic fallback path here - if the filesystem cannot do it atomically, this should fail
 * rather than silently accept a torn write.
 *
 * This protects against another *process* reading a half-written file (killed BOSS, a Windows
 * lock) - it does not `fsync` and so does not protect against power loss, which for a filesystem
 * without an equivalent of ext4's `auto_da_alloc` rename heuristic can still leave a zero-length
 * `env_vars` behind. Forcing the temp file to disk before the move is cheap insurance against that,
 * given this file also holds the secret-manager plugin's API keys.
 *
 * On POSIX this **preserves** an existing target's permissions rather than tightening them - a
 * world-readable `644` file stays `644` - and where the target does not exist yet, the temp file's
 * own `Files.createTempFile` default (`600`) is used, which is already tighter than
 * `File.writeText`'s umask-derived mode. On Windows there is no POSIX permission view at all:
 * `Files.createTempFile` there inherits the *directory's* ACL, so a deliberately-restricted
 * `env_vars` ACL is not carried over by this function.
 */
internal fun writeModeFile(
    file: File,
    text: String,
    move: (Path, Path) -> Unit = { source, target ->
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    },
) {
    val target = file.canonicalFile.toPath()
    // The original check here was Files.isWritable(target) alone, which is necessary but not
    // sufficient (BossConsole#481 review): a writable target inside a read-only directory passed
    // it and then failed later at createTempFile instead, with a less specific error. The two
    // platforms disagree on whether it is also SUFFICIENT, which is why both checks stay rather
    // than replacing one with the other: POSIX rename() only cares about directory permissions, so
    // a read-only target in a writable directory is replaceable there and Files.isWritable(target)
    // alone would have wrongly refused it - but Windows' ATOMIC_MOVE (MoveFileEx with
    // MOVEFILE_REPLACE_EXISTING) does honor the target's own read-only attribute and fails to
    // replace it regardless of directory permissions, confirmed empirically
    // (`writeModeFile checks the parent directory is writable, not just the target file`).
    if (Files.exists(target) && !Files.isWritable(target)) throw IOException("Mode file is not writable")
    if (!Files.isWritable(target.parent)) throw IOException("Mode file's directory is not writable")
    val temporary = Files.createTempFile(target.parent, "env_vars-", ".tmp")
    try {
        if (Files.exists(target) && Files.getFileAttributeView(target, PosixFileAttributeView::class.java) != null) {
            Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(target))
        }
        Files.writeString(temporary, text, Charsets.UTF_8)
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
        move(temporary, target)
    } finally {
        Files.deleteIfExists(temporary)
    }
}
