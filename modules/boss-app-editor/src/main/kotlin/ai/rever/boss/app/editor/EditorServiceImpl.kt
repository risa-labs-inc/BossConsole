package ai.rever.boss.app.editor

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.language.LanguageIds
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.util.concurrent.ConcurrentHashMap

/**
 * gRPC implementation of EditorService.
 *
 * Provides real file I/O using the host filesystem:
 * - OpenFile: reads file from disk, detects language by extension
 * - SaveFile: writes content back to disk
 * - DetectMainFunctions: regex-based scan for entry points across multiple languages
 * - GetTokens / NavigateToDefinition: require PSI (in composeApp) — return empty
 */
class EditorServiceImpl(
    /**
     * The one root saves and opens are confined to (BossConsole#885): the user's home
     * directory by default. A confinement root instead of a blocklist covers every
     * platform's danger zones in one rule - Windows system paths (`C:\Windows\...`),
     * POSIX system paths (`/etc`, `/sys`, `/proc`), drive roots, and any path that is
     * simply not the user's. The old POSIX-only prefix blocklist let all Windows system
     * paths through.
     *
     * Injected so the tests can confine at a per-test directory instead of mutating the
     * process-global user.home (the same fragility the composeApp test-home rule exists
     * to avoid). Passed raw: the constructor resolves it once into
     * [confinementRoot].
     */
    root: File = File(System.getProperty("user.home")),
) : EditorServiceGrpcKt.EditorServiceCoroutineImplBase() {
    /**
     * The resolved confinement root. The root itself is symlink-resolved (not
     * just the checked paths): on macOS `/var` is a symlink into `/private/var`,
     * so an unresolved injected root would sit in a different namespace from
     * [validatePath]'s toRealPath result and refuse every legitimate path.
     */
    private val confinementRoot: File =
        runCatching { root.toPath().toRealPath().toFile() }
            // canonicalFile can itself throw IOException; an absolute path is
            // the last-resort total, so construction never fails.
            .getOrElse { runCatching { root.canonicalFile }.getOrElse { root.absoluteFile } }

    private val logger = LoggerFactory.getLogger(EditorServiceImpl::class.java)

    /** path → isDirty: tracks files opened in this session */
    private val openFiles = ConcurrentHashMap<String, Boolean>()

    /**
     * Validates [path] after canonicalization (BossConsole#885): the old check ran on
     * RAW string - `..` in the literal and a POSIX-only prefix list - so a symlink
     * inside the allowed root pointing at a blocked target sailed through, and Windows
     * system paths were never covered. Canonicalize FIRST so the checks see what the
     * filesystem will actually resolve; then confine to [confinementRoot] so escapes
     * (link or otherwise) are refused by the one rule that matters.
     */
    @Suppress("ThrowsCount") // A validating gate: each refusal is a distinct wire status.
    private fun validatePath(path: String): File {
        // toRealPath, not canonicalFile: on Windows (and for symlinks generally),
        // File.canonicalFile can return the LINK's own path without resolving the
        // link, so a symlink inside the home pointing at an outside target passed
        // the gate (BossConsole#885). toRealPath() resolves the link chain to the
        // actual filesystem location, which is what the confinement check must see.
        val absolute =
            try {
                File(path).toPath().toAbsolutePath().normalize()
            } catch (_: InvalidPathException) {
                // Illegal name characters (e.g. `C:\a<b` on Windows) must read as a
                // bad argument, not a service crash (UNKNOWN on the wire).
                throw Status.INVALID_ARGUMENT
                    .withDescription("Invalid filesystem path")
                    .asRuntimeException()
            }
        // Resolve the deepest EXISTING ancestor with toRealPath (which follows
        // every symlink in it) and re-append the missing tail - a missing tail
        // component has no link to follow. This mirrors the house
        // FileSystemPathPolicy shape and is what keeps a save for a NEW file in a
        // fresh subdirectory valid WITHOUT any caller pre-creating the parent:
        // nothing may mkdirs a path the gate has not yet approved. It also catches
        // a symlink FILE inside the home pointing at an outside target: toRealPath
        // follows the link to its actual location. The exists() probe uses
        // NOFOLLOW_LINKS so a BROKEN symlink still counts as present - walking
        // past it would resolve the anchor one level too high and judge the link's
        // own location instead of the target it names. A DANGLING symlink inside
        // the root is therefore its own anchor: toRealPath fails on it and the
        // path is refused (NOT_FOUND inside the root, PERMISSION_DENIED
        // outside) - fail-closed; a save through a link nobody can follow is
        // not a save the user can expect, and the refusal names the condition
        // (dangling symlink) rather than reading as a deleted parent. Traversal
        // is covered by the resolution itself, so the
        // old raw-string `..` ban (which also false-refused legitimate names like
        // `notes..txt`) is gone. Best-effort against concurrent mutation, like
        // FileSystemPathPolicy: a directory component swapped for a symlink
        // between the gate and the write is followed by the write itself - the
        // gate closes the static-configuration holes (links, prefixes, case),
        // not a live adversary.
        var anchor = absolute
        while (!Files.exists(anchor, LinkOption.NOFOLLOW_LINKS)) {
            anchor = anchor.parent ?: break
        }
        val resolvedAnchor =
            try {
                anchor.toRealPath().toFile()
            } catch (e: NoSuchFileException) {
                // The anchor is gone (deleted, or a dangling symlink with no
                // target to resolve to).
                throw unresolvableAnchorRefusal(
                    UnresolvableAnchor(path, absolute, anchor, e, confinementRoot.toPath(), true),
                )
            } catch (e: IOException) {
                // The anchor is inaccessible (AccessDeniedException, a
                // disconnected share, ...).
                throw unresolvableAnchorRefusal(
                    UnresolvableAnchor(path, absolute, anchor, e, confinementRoot.toPath(), false),
                )
            }
        val tail = anchor.relativize(absolute)
        // Path arithmetic, not string splicing (the shape FileSystemPathPolicy
        // uses): a string prefix check misbehaves when the root is a
        // filesystem root (root + separator yields `//`, which nothing
        // starts with - the service would silently refuse everything) and
        // compares case-sensitively on case-insensitive filesystems.
        val resolved =
            resolvedAnchor
                .toPath()
                .resolve(tail)
                .normalize()
                .toFile()
        if (!resolved.toPath().startsWith(confinementRoot.toPath())) {
            throw denyOutsideRoot(path)
        }
        return resolved
    }

    /**
     * Atomic save (BossConsole#885): content streams into a unique sibling temp file
     * which is then moved atomically over the target. A crash or disk-full mid-save
     * leaves the previous complete version - the house `atomicWriteText` shape,
     * re-implemented locally because the helper lives in composeApp and this module
     * is a standalone kernel service. `File.renameTo` is deliberately not used: its
     * behavior when the destination exists is platform-dependent in exactly the way
     * that hid the favicon-cache bug on Windows. A process killed mid-save
     * leaves the `.part` sibling in the user's source tree (visible in git
     * status) - the trade is a torn target, which is worse.
     *
     * A rename-based save also MOVES the permission requirement from the
     * file to its directory: a read-only FILE in a writable directory is
     * now saveable, but a writable file in a read-only directory is not
     * (previously a plain writeText needed only the file). And the move
     * changes the target's inode - hard links to it keep the old content,
     * and inode-based watchers lose the file - with no fsync before the
     * move, so "the previous version survives a crash" covers a process
     * crash, not power loss on a filesystem without the rename heuristic.
     * Windows ACLs and ownership are not mirrored across the move (only
     * the POSIX mode is; the house helper does not either), and a
     * Windows replace is denied while another process holds the target
     * open without `FILE_SHARE_DELETE` (antivirus, an indexer, a watcher)
     * - a shape an in-place write would have survived.
     */
    private fun atomicWrite(
        target: File,
        content: String,
    ) {
        target.parentFile?.mkdirs()
        // Unique sibling temp file, moved atomically over the target. The
        // prefix is the target's name, capped: createTempFile appends random
        // digits plus the suffix, so a ~230-char filename at the usual 255
        // NAME_MAX would overflow and fail EVERY save of that file (and a
        // one-char name is under createTempFile's 3-char minimum, hence the
        // pad).
        val prefix = target.name.take(64).takeIf { it.length >= 2 } ?: "ed."
        val tmp = File.createTempFile("$prefix.", ".part", target.parentFile)
        try {
            tmp.writeText(content, Charsets.UTF_8)
            // Preserve the target's existing POSIX mode: createTempFile takes
            // the umask-derived 0644, and the moved temp file BECOMES the
            // target - without this an executable script loses +x on Ctrl-S
            // and a 0600 file widens, the exact failure the house
            // atomicWriteText's permission pin prevents for state files. A
            // brand-new file keeps the umask default. (Windows ACLs are not
            // mirrored; the house helper does not.) AFTER the write: a 0444
            // target would otherwise make the freshly-created temp unwritable
            // by its own owner and the save would fail inside the very write
            // it is trying to make atomic.
            if (target.exists()) {
                val view = Files.getFileAttributeView(target.toPath(), PosixFileAttributeView::class.java)
                if (view != null) {
                    Files.setPosixFilePermissions(tmp.toPath(), view.readAttributes().permissions())
                }
            }
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            // No-op when the move took it away; cleans up on failure paths.
            if (tmp.exists()) tmp.delete()
        }
    }

    // Language-specific main/entry-point patterns
    private val mainPatterns =
        listOf(
            Regex("""^\s*(?:suspend\s+)?fun\s+main\s*\("""), // Kotlin
            Regex("""^\s*public\s+static\s+void\s+main\s*\(\s*String"""), // Java
            Regex("""^\s*if\s+__name__\s*==\s*['"]__main__['"]\s*:"""), // Python
            Regex("""^\s*func\s+main\s*\(\s*\)"""), // Go / Swift
            Regex("""^\s*fn\s+main\s*\(\s*\)"""), // Rust
            Regex("""^\s*int\s+main\s*\("""), // C / C++
        )

    override suspend fun openFile(request: OpenFileRequest): OpenFileResponse =
        withContext(Dispatchers.IO) {
            logger.info("openFile: path={}", request.path)
            val file =
                try {
                    validatePath(request.path)
                } catch (e: StatusRuntimeException) {
                    if (e.status.code == Status.Code.NOT_FOUND) {
                        // The path is unresolvable (a stale recent-files entry, a
                        // dangling symlink, ...): the not-found response, not an
                        // RPC error - with the gate's own description, which says
                        // WHY the path does not resolve (a deleted parent, a broken
                        // link), instead of the generic "File not found".
                        return@withContext OpenFileResponse
                            .newBuilder()
                            .setSuccess(false)
                            .setErrorMessage(e.status.description ?: "File not found: ${request.path}")
                            .build()
                    }
                    throw e
                }
            if (!file.exists() || !file.isFile) {
                return@withContext OpenFileResponse
                    .newBuilder()
                    .setSuccess(false)
                    .setErrorMessage("File not found: ${request.path}")
                    .build()
            }
            try {
                val content = file.readText(Charsets.UTF_8)
                openFiles[request.path] = false
                OpenFileResponse
                    .newBuilder()
                    .setSuccess(true)
                    .setContent(content)
                    .setLanguage(languageForFile(file))
                    .build()
            } catch (e: Exception) {
                logger.warn("openFile read failed: {}", e.message)
                OpenFileResponse
                    .newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(e.message ?: "Read failed")
                    .build()
            }
        }

    override suspend fun saveFile(request: SaveFileRequest): Empty =
        withContext(Dispatchers.IO) {
            logger.info("saveFile: path={}", request.path)
            // Validate BEFORE any mkdirs: a save may target a new file (validatePath
            // resolves the deepest existing ancestor and re-appends the missing
            // tail), and creating the parent first would let a refused outside-home
            // save still create directories outside the confinement root.
            val file = validatePath(request.path)
            // A failed save must not read as success: the client marks the buffer
            // clean on an Empty response, so a disk-full or permission-denied save
            // that returns Empty loses the edit with no signal.
            try {
                atomicWrite(file, request.content)
                openFiles[request.path] = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                logger.error("saveFile failed for {}: {}", request.path, e.message)
                throw Status.INTERNAL
                    .withDescription("Save failed for ${request.path}: ${e.message}")
                    .withCause(e)
                    .asRuntimeException()
            }
            Empty.getDefaultInstance()
        }

    override suspend fun getTokens(request: GetTokensRequest): GetTokensResponse {
        // PSI-based tokenization lives in composeApp (kotlin-compiler-embeddable).
        // Return empty — the kernel-side editor proxy uses composeApp's PSI directly.
        logger.debug("getTokens: path={} (PSI not in this process)", request.path)
        return GetTokensResponse.newBuilder().build()
    }

    override suspend fun navigateToDefinition(request: NavigateRequest): NavigateResponse {
        logger.debug("navigateToDefinition: path={} (PSI not in this process)", request.path)
        return NavigateResponse.newBuilder().setFound(false).build()
    }

    override suspend fun detectMainFunctions(request: DetectMainRequest): DetectMainResponse =
        withContext(Dispatchers.IO) {
            logger.info("detectMainFunctions: path={}", request.path)
            val file =
                try {
                    validatePath(request.path)
                } catch (e: StatusRuntimeException) {
                    if (e.status.code == Status.Code.NOT_FOUND) {
                        // Stale path whose parent raced away: the same
                        // empty-scan response the missing-file check below
                        // returns, not an RPC error.
                        return@withContext DetectMainResponse.newBuilder().build()
                    }
                    throw e
                }
            if (!file.exists() || !file.isFile) return@withContext DetectMainResponse.newBuilder().build()

            val functions = mutableListOf<MainFunctionInfo>()
            try {
                file.readLines(Charsets.UTF_8).forEachIndexed { idx, line ->
                    if (mainPatterns.any { it.containsMatchIn(line) }) {
                        functions +=
                            MainFunctionInfo
                                .newBuilder()
                                .setName("main")
                                .setLine(idx + 1)
                                .setDisplayName("main (line ${idx + 1})")
                                .setQualifiedName("${file.nameWithoutExtension}.main")
                                .build()
                    }
                }
            } catch (e: Exception) {
                logger.warn("detectMainFunctions scan error: {}", e.message)
            }

            DetectMainResponse.newBuilder().addAllFunctions(functions).build()
        }

    override suspend fun listOpenFiles(request: Empty): ListOpenFilesResponse {
        val infos =
            openFiles.entries.map { (path, dirty) ->
                OpenFileInfo
                    .newBuilder()
                    .setPath(path)
                    .setIsModified(dirty)
                    .build()
            }
        return ListOpenFilesResponse.newBuilder().addAllFiles(infos).build()
    }

    // Filename rules take precedence even when a suffix is a known extension
    // (Dockerfile.sh is a Dockerfile). Preserve this service's proto and unknown defaults.
    private fun languageForFile(file: File): String =
        LanguageIds.detect(file.name).takeUnless { it == LanguageIds.TEXT }
            ?: detectLanguage(file.extension)

    /**
     * BossConsole#75: this used to be its own hand-maintained table, independent of
     * (and disagreeing with) `composeApp`'s `EditorLanguages` - most visibly, `.sh`/
     * `.bash`/`.zsh` were `shell` here and `bash` there. Both now read
     * [LanguageIds], the module the two were consolidated into. `proto` stays a local
     * addition: `LanguageIds` is the table shared with `boss-file-types.json`'s
     * default-file-type-association list, and adding an id there means adding the
     * extension to that JSON too - out of scope for a language-id fix.
     */
    internal fun detectLanguage(ext: String): String =
        when (ext.lowercase()) {
            "proto" -> "protobuf"
            else -> LanguageIds.forExtension(ext) ?: "plaintext"
        }
}

/**
 * The anchor existed at the NOFOLLOW probe but `toRealPath` could not resolve
 * it: a DANGLING symlink (present, no target to follow) or a directory that
 * raced into deletion between the walk and the resolution, or an ancestor
 * that is inaccessible / disconnected. Deny before not-found: resolve the
 * EXISTING prefix of the path (the deepest existing ancestor above the broken
 * piece - canonicalizing the broken piece itself would fail), re-append the
 * rest, and judge THAT against the root, so a stale out-of-root path reads as
 * a refusal, not a NOT_FOUND the caller can probe with. Judging impossible =>
 * deny.
 */
private data class UnresolvableAnchor(
    val path: String,
    val absolute: Path,
    val anchor: Path,
    val cause: IOException,
    val confinementRoot: Path,
    val gone: Boolean,
)

private fun unresolvableAnchorRefusal(state: UnresolvableAnchor): StatusRuntimeException {
    val path = state.path
    val absolute = state.absolute
    val anchor = state.anchor
    val cause = state.cause
    val confinementRoot = state.confinementRoot
    val gone = state.gone

    var up: Path? = anchor.parent
    while (up != null && !Files.exists(up, LinkOption.NOFOLLOW_LINKS)) {
        up = up.parent
    }
    val base = up?.let { runCatching { it.toRealPath() }.getOrNull() }
    val candidate =
        base?.let { it.resolve(anchor.fileName).resolve(anchor.relativize(absolute)).normalize() }
    if (base == null || candidate == null || !candidate.startsWith(confinementRoot)) {
        return denyOutsideRoot(path)
    }
    // Inside the root: the cause decides the wire status, and openFile /
    // detectMainFunctions both MAP NOT_FOUND (to a File-not-found response and
    // an empty scan), so a cause that is not "the path is gone" must not read
    // as NOT_FOUND - an unreadable directory or a disconnected share would
    // otherwise be reported to the user as a missing file.
    val detail = cause.message ?: cause::class.java.simpleName
    val isLink = Files.isSymbolicLink(anchor)
    // A SYMLINK anchor decorates the cause decision rather than overriding
    // it: a link behind an inaccessible directory is an ACCESS FAULT
    // (PERMISSION_DENIED, the directory mode is what the user can fix), a
    // link with a missing target is a gone path (NOT_FOUND). Either way the
    // wording names the symlink condition so a bug report can tell it apart
    // from a plain deleted directory.
    val status =
        if (!gone) {
            Status.PERMISSION_DENIED
                .withDescription(
                    if (isLink) {
                        "Symlink target is not accessible: $path ($detail)"
                    } else {
                        "Path parent is not accessible: $path ($detail)"
                    },
                )
        } else if (isLink) {
            Status.NOT_FOUND
                .withDescription("Symlink could not be resolved: $path (target missing)")
        } else {
            Status.NOT_FOUND
                .withDescription("Path parent no longer exists: $path")
        }
    return status.withCause(cause).asRuntimeException()
}

/**
 * The refusal for a well-formed path the policy forbids: PERMISSION_DENIED,
 * not INVALID_ARGUMENT - the client must be able to tell "this path is
 * nonsense" (bad argument) from "this path is fine, policy forbids it"
 * (denied). The message does not hardcode "home": the confinement root is
 * injectable, and a project-root follow-up will pass a different one.
 * File-scope (not a member) so it does not count toward the class's
 * function budget.
 */
private fun denyOutsideRoot(path: String) =
    Status.PERMISSION_DENIED
        .withDescription("Access denied: path outside the confinement root: $path")
        .asRuntimeException()
