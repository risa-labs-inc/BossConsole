package ai.rever.boss.git

import ai.rever.boss.components.events.FileEventBus
import ai.rever.boss.components.plugin.providers.DisposableProvider
import ai.rever.boss.plugin.api.GitBranchRefData
import ai.rever.boss.plugin.api.GitCommitInfoData
import ai.rever.boss.plugin.api.GitCommitNodeData
import ai.rever.boss.plugin.api.GitDataProvider
import ai.rever.boss.plugin.api.GitDiffData
import ai.rever.boss.plugin.api.GitFileStatusData
import ai.rever.boss.plugin.api.GitFileStatusTypeData
import ai.rever.boss.plugin.api.GitOperationResultData
import ai.rever.boss.plugin.git.GitCommitInfo
import ai.rever.boss.plugin.git.GitFileStatus
import ai.rever.boss.plugin.git.GitFileStatusType
import ai.rever.boss.plugin.git.GitOperationResult
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.WindowGitState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Implementation of GitDataProvider that wraps GitService and WindowGitState.
 *
 * This adapter allows the Git panels to be extracted to separate modules
 * while keeping the Git infrastructure in composeApp.
 *
 * @param windowGitState The window-specific git state (nullable for flexibility)
 * @param windowIdProvider Provider for the current window ID
 * @param projectPathProvider Provider for the window's selected project path. The
 * window-scoped reads (status, log, graph) need [WindowGitState.projectPath],
 * which the top bar sets when a project is picked there; panels that select a
 * project on their own (the codebase picker) never go through that path, so
 * this fills the gap.
 */
class GitDataProviderImpl(
    private val windowGitState: WindowGitState?,
    private val windowIdProvider: () -> String?,
    private val projectPathProvider: () -> String? = { null },
) : GitDataProvider,
    DisposableProvider {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val logger = BossLogger.forComponent("GitDataProvider")

    // Last path [ensureRepoState] asked [GitService.refreshForWindow] about.
    // `isGitRepository` cannot carry this: after the first probe a non-repo
    // is also false, which is "known not a repository", not "unknown".
    private var lastProbedPath: String? = null

    // [ensureRepoState] is called from every suspend member, so two of them
    // can run it at once after a project switch. The check-and-probe is not
    // atomic without this: both callers would see `lastProbedPath != path`
    // and both would run the four-command refresh (eight subprocesses behind
    // the global git lock instead of four, and two racing state writes).
    private val ensureMutex = Mutex()

    // State flows mapped from WindowGitState - initialized with current values
    private val _fileStatus =
        MutableStateFlow(
            windowGitState?.fileStatus?.value?.map { it.toData() } ?: emptyList(),
        )
    override val fileStatus: StateFlow<List<GitFileStatusData>> = _fileStatus

    private val _commitLog =
        MutableStateFlow(
            windowGitState?.commitLog?.value?.map { it.toData() } ?: emptyList(),
        )
    override val commitLog: StateFlow<List<GitCommitInfoData>> = _commitLog

    private val _isGitRepository =
        MutableStateFlow(
            windowGitState?.isGitRepository?.value ?: false,
        )
    override val isGitRepository: StateFlow<Boolean> = _isGitRepository

    private val _isLoading =
        MutableStateFlow(
            windowGitState?.isLoading?.value ?: false,
        )
    override val isLoading: StateFlow<Boolean> = _isLoading

    /**
     * Cancels the four collectors launched in init. DefaultPlugin is per window and
     * builds this provider lazily; without this a closed window leaked all four,
     * each holding its [WindowGitState] alive.
     */
    override fun dispose() {
        scope.cancel()
    }

    init {
        // Collect from WindowGitState and map to plugin API types
        windowGitState?.let { state ->
            scope.launch {
                state.fileStatus.collect { statuses ->
                    _fileStatus.value = statuses.map { it.toData() }
                }
            }
            scope.launch {
                state.commitLog.collect { commits ->
                    _commitLog.value = commits.map { it.toData() }
                }
            }
            scope.launch {
                state.isGitRepository.collect { isRepo ->
                    _isGitRepository.value = isRepo
                }
            }
            scope.launch {
                state.isLoading.collect { loading ->
                    _isLoading.value = loading
                }
            }
        }
    }

    override suspend fun refreshStatus() {
        ensureRepoState()
        GitService.getStatusForWindow(windowGitState)
    }

    override suspend fun refreshLog(limit: Int) {
        ensureRepoState()
        GitService.getLogForWindow(windowGitState, limit)
    }

    /**
     * Bring [WindowGitState] in line with the window's selected project before
     * every window-scoped read: the right project path, and - once per project -
     * the repository facts.
     *
     * Two separate problems live here.
     *
     * The path: window-scoped reads short-circuit to empty without one, so a
     * project picked outside the top bar (the codebase panel's own picker)
     * showed an empty git view forever. Filling it only when *unset* was not
     * enough either, because [GitService.refreshForWindow] also writes it - a
     * window bootstrapped on a directory that is not a repository kept that
     * path after the user switched projects. The selected project is the single
     * authority, so it syncs on change; a blank provider result means "not
     * resolved yet", not "no project", and is never written.
     *
     * The repository facts: `isGitRepository`, the branch name and the branch
     * lists are only ever written by [GitService.refreshForWindow], and nothing
     * on the status/log path called it. `getStatusForWindow` updates the file
     * list alone, so a panel that gates its UI on `isGitRepository` reported
     * "no repository" for a perfectly good checkout - dirty files and all.
     *
     * [GitService.refreshForWindow] costs four extra git invocations (repo
     * check, branch, local branches, remote branches), so it runs when the
     * project changed or this path has never been probed - not on every tick
     * of a panel's status poll. A non-repository leaves `isGitRepository`
     * false after that first probe; treating the flag as "unknown" would
     * re-run the four commands on every poll.
     */
    private suspend fun ensureRepoState() {
        val state = windowGitState ?: return
        ensureMutex.withLock {
            ensureRepoStateLocked(state)
        }
    }

    private suspend fun ensureRepoStateLocked(state: WindowGitState) {
        val path = projectPathProvider()
        if (path.isNullOrBlank()) {
            if (state.projectPath.value == null) {
                logger.debug(
                    LogCategory.SYSTEM,
                    "Git window refresh skipped - no project path resolved",
                    mapOf("windowId" to (windowIdProvider() ?: "none")),
                )
            }
            return
        }
        val changed = state.projectPath.value != path
        if (changed) {
            logger.debug(
                LogCategory.SYSTEM,
                "Git window project path synced to the selected project",
                mapOf(
                    "windowId" to (windowIdProvider() ?: "none"),
                    "from" to (state.projectPath.value ?: "none"),
                    "to" to path,
                ),
            )
        }
        // Align the global project path unconditionally: it is still read by the
        // verbs that carry no window override (openFile's fallback,
        // getCurrentProjectPath's fallback), and it is a bare assignment -
        // the four-command probe below stays gated.
        GitService.alignCurrentProjectPath(path)

        // `changed` covers a project switch. `lastProbedPath != path` covers
        // the case where something wrote [WindowGitState.projectPath] without
        // ever calling refreshForWindow (the top bar used to be the only
        // writer; tests still do this). `!state.isGitRepository.value` covers
        // a cached "not a repository" specifically: that fact can change
        // underneath an open panel (`git init`, or the first probe losing the
        // race against [DesktopGitService]'s async `_isGitAvailable` check at
        // app startup) and, unlike a confirmed repo, costs only the one
        // `isGitRepo` command to keep re-checking - `refreshForWindow` returns
        // before the four-command branch bundle when it is not a repo. A
        // confirmed repo still caches (that fact does not spontaneously
        // reverse), so the steady-state cost this gate exists for is unchanged.
        if (changed || lastProbedPath != path || !state.isGitRepository.value) {
            GitService.refreshForWindow(path, state)
            lastProbedPath = path
        }
    }

    // ===== Writes =====
    //
    // Every write resolves the repo first, exactly as the reads do. Without it
    // a write issued before the first read - staging from a freshly opened
    // panel, say - reached GitService with no project path and came back
    // "No project selected", so the button did nothing and said nothing useful.
    // Ordering between a panel's first read and its first write is not
    // something the panel should have to guarantee.
    //
    // And every write passes THIS window's path, like the diff reads: aligning
    // the global in ensureRepoState and then reading it back is two steps, and
    // another window's align (its status poll runs ensureRepoState too) landing
    // in between redirected the write - with two worktrees of one repo open, a
    // Discard ran `git restore` in the other window's tree. The override travels
    // with the call, so no interleaving can redirect it; null (a provider with
    // no window state) falls back to the global as before.

    override suspend fun commit(message: String): GitOperationResultData {
        ensureRepoState()
        return GitService
            .commit(message, windowId = windowIdProvider(), projectPathOverride = windowProjectPath())
            .toData()
    }

    override suspend fun stage(filePath: String): GitOperationResultData {
        ensureRepoState()
        return GitService.stage(filePath, windowIdProvider(), windowProjectPath()).toData()
    }

    override suspend fun unstage(filePath: String): GitOperationResultData {
        ensureRepoState()
        return GitService.unstage(filePath, windowIdProvider(), windowProjectPath()).toData()
    }

    override suspend fun stageAll(): GitOperationResultData {
        ensureRepoState()
        return GitService.stageAll(windowIdProvider(), windowProjectPath()).toData()
    }

    override suspend fun unstageAll(): GitOperationResultData {
        ensureRepoState()
        return GitService.unstageAll(windowIdProvider(), windowProjectPath()).toData()
    }

    override suspend fun discardChanges(filePath: String): GitOperationResultData {
        ensureRepoState()
        return GitService.discardChanges(filePath, windowIdProvider(), windowProjectPath()).toData()
    }

    override suspend fun cherryPick(commitHash: String): GitOperationResultData {
        ensureRepoState()
        return GitService.cherryPick(commitHash, windowIdProvider(), windowProjectPath()).toData()
    }

    override suspend fun revert(commitHash: String): GitOperationResultData {
        ensureRepoState()
        return GitService.revert(commitHash, windowIdProvider(), windowProjectPath()).toData()
    }

    override suspend fun checkout(ref: String): GitOperationResultData {
        ensureRepoState()
        return GitService.checkout(ref, windowIdProvider(), windowProjectPath()).toData()
    }

    // This window's path FIRST, the global as fallback: the global belongs to
    // whichever window aligned it last, and with two windows on different
    // projects a plugin resolving a relative path (the one this method
    // exists for) would get the other window's repo. A provider with no
    // window state has no window path, so the global IS the right answer there.
    override fun getCurrentProjectPath(): String? = windowProjectPath() ?: GitService.getCurrentProjectPath()

    override fun openFile(
        filePath: String,
        windowId: String,
    ) {
        scope.launch {
            // Resolve the repo first, like every other member: this read the
            // GLOBAL project path, so "Edit" on a changed file did nothing at
            // all whenever that path had not been seeded yet.
            ensureRepoState()
            // This window's path FIRST, global as fallback - the same ordering every diff
            // read uses, and for the same reason: the global belongs to whichever window
            // refreshed last, so once two windows on different projects have settled, this
            // built the other window's absolute path. A relative path that exists in both
            // repos opened the wrong file; one that does not opened a dead tab.
            val projectPath = windowProjectPath() ?: getCurrentProjectPath() ?: projectPathProvider()
            if (projectPath.isNullOrBlank()) {
                logger.debug(
                    LogCategory.SYSTEM,
                    "Git openFile skipped - no project path resolved",
                    mapOf("path" to filePath),
                )
                return@launch
            }
            // Trim the separator rather than concatenating blindly: a project
            // path ending in "/" produced "…//file", which then failed to match
            // an already-open tab and opened a duplicate.
            // [File.isAbsolute] rather than startsWith("/"): on Windows an
            // absolute path is `C:\…` and the POSIX test would have glued the
            // project path onto it.
            val fullPath =
                if (File(filePath).isAbsolute) filePath else "${projectPath.trimEnd('/')}/$filePath"
            FileEventBus.openFile(fullPath, sourceWindowId = windowId)
        }
    }

    /**
     * This window's project path, for the reads AND writes that must not use the
     * global: the global belongs to whichever window aligned it last, and even with
     * unconditional alignment another window's align can land between this window's
     * align and its command. Null falls back to the global, which is the right
     * answer for a provider with no window.
     */
    private fun windowProjectPath(): String? = windowGitState?.projectPath?.value

    // ===== Diff (boss-plugin-api 1.0.87) =====

    override suspend fun diffFile(
        path: String,
        staged: Boolean,
    ): List<GitDiffData> {
        // Like every other member: these read git's GLOBAL project path, so a
        // caller that has not triggered a status read first - the diff tab now
        // loads its own content - got an empty diff and rendered as blank.
        ensureRepoState()
        return GitService.getFileDiff(path, staged, windowProjectPath())
    }

    override suspend fun diffRef(
        ref: String,
        path: String?,
    ): List<GitDiffData> {
        ensureRepoState()
        return GitService.getCommitDiff(ref, path, windowProjectPath())
    }

    override suspend fun diffBetween(
        from: String,
        to: String,
        path: String?,
    ): List<GitDiffData> {
        ensureRepoState()
        return GitService.getRefDiff(from, to, path, windowProjectPath())
    }

    override suspend fun diffNames(staged: Boolean): List<GitFileStatusData> {
        ensureRepoState()
        return GitService.getDiffFileNames(staged, windowProjectPath())
    }

    override fun openDiff(
        filePath: String,
        windowId: String,
        staged: Boolean,
        fromRef: String?,
        toRef: String?,
    ) {
        val target = windowId.ifBlank { windowIdProvider().orEmpty() }
        if (target.isBlank()) {
            // Consumers filter on exact window-id equality (see BossAppEventBusEffects),
            // so a blank id matches no window and the click does nothing. Log it rather
            // than emitting an event nobody can receive - the symptom is otherwise an
            // invisible dead button.
            logger.warn(
                LogCategory.SYSTEM,
                "openDiff has no window to target; the diff tab cannot be routed",
                mapOf("filePath" to filePath),
            )
            return
        }
        scope.launch {
            // The diff tab resolves its repo from git's project path, so make
            // sure that is pointing at this window's project before the tab
            // opens and asks.
            ensureRepoState()
            FileEventBus.openDiffTab(filePath, staged, fromRef, toRef, target)
        }
    }

    // ===== Graph (boss-plugin-api 1.0.87) =====

    // The log fetch already parses %P (parents) and %D (ref decorations), so
    // this is a pure mapping - no new git command.
    override suspend fun logGraph(limit: Int): List<GitCommitNodeData> {
        ensureRepoState()
        return GitService.getLogForWindow(windowGitState, limit).map { it.toNodeData() }
    }

    // ===== Remote + branch-scoped graph (boss-plugin-api 1.0.87) =====
    //
    // ensureRepoState() first, like every other member - and then the
    // WINDOW-scoped GitService entry points rather than pull()/push(), which
    // resolve their repository from the global `currentProjectPath`. That
    // global belongs to whichever window refreshed last; pushing the wrong
    // repository is not a bug worth the convenience.

    override suspend fun fetch(prune: Boolean): GitOperationResultData {
        ensureRepoState()
        return GitService.fetchForWindow(windowGitState, prune).toData()
    }

    override suspend fun pull(): GitOperationResultData {
        ensureRepoState()
        return GitService.pullForWindow(windowGitState).toData()
    }

    override suspend fun push(): GitOperationResultData {
        ensureRepoState()
        return GitService.pushForWindow(windowGitState).toData()
    }

    override suspend fun branches(): List<GitBranchRefData> {
        ensureRepoState()
        return GitService.listBranchesForWindow(windowGitState).map {
            GitBranchRefData(name = it.name, isCurrent = it.isCurrent, isRemote = it.isRemote)
        }
    }

    override suspend fun logGraphFor(
        ref: String?,
        limit: Int,
    ): List<GitCommitNodeData> {
        ensureRepoState()
        return GitService.getLogForRef(windowGitState, ref, limit).map { it.toNodeData() }
    }

    // ===== Type Conversion Extensions =====

    private fun GitCommitInfo.toNodeData(): GitCommitNodeData =
        GitCommitNodeData(
            hash = hash,
            shortHash = shortHash,
            subject = subject,
            author = author,
            authorEmail = authorEmail,
            date = date,
            refs = refs,
            parents = parentHashes,
        )

    private fun GitFileStatus.toData(): GitFileStatusData =
        GitFileStatusData(
            path = path,
            indexStatus = indexStatus?.toData(),
            workTreeStatus = workTreeStatus?.toData(),
            isStaged = isStaged,
            isUnstaged = isUnstaged,
        )

    private fun GitFileStatusType.toData(): GitFileStatusTypeData =
        when (this) {
            GitFileStatusType.MODIFIED -> GitFileStatusTypeData.MODIFIED
            GitFileStatusType.ADDED -> GitFileStatusTypeData.ADDED
            GitFileStatusType.DELETED -> GitFileStatusTypeData.DELETED
            GitFileStatusType.RENAMED -> GitFileStatusTypeData.RENAMED
            GitFileStatusType.COPIED -> GitFileStatusTypeData.COPIED
            GitFileStatusType.UNTRACKED -> GitFileStatusTypeData.UNTRACKED
            GitFileStatusType.IGNORED -> GitFileStatusTypeData.IGNORED
            GitFileStatusType.UNMERGED -> GitFileStatusTypeData.UNMERGED
        }

    private fun GitCommitInfo.toData(): GitCommitInfoData =
        GitCommitInfoData(
            hash = hash,
            shortHash = shortHash,
            subject = subject,
            author = author,
            authorEmail = authorEmail,
            date = date,
            refs = refs,
        )

    private fun GitOperationResult.toData(): GitOperationResultData =
        when (this) {
            is GitOperationResult.Success -> GitOperationResultData.Success(message)
            is GitOperationResult.Error -> GitOperationResultData.Error(message)
        }
}
