package ai.rever.boss.app.editor

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.language.LanguageIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
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
class EditorServiceImpl : EditorServiceGrpcKt.EditorServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(EditorServiceImpl::class.java)

    /** path → isDirty: tracks files opened in this session */
    private val openFiles = ConcurrentHashMap<String, Boolean>()

    /**
     * The one root saves and opens are confined to (BossConsole#885): the user's home
     * directory. A confinement root instead of a blocklist covers every platform's
     * danger zones in one rule - Windows system paths (`C:\Windows\...`), POSIX system
     * paths (`/etc`, `/sys`, `/proc`), drive roots, and any path that is simply not
     * the user's. The old POSIX-only prefix blocklist let all Windows system paths
     * through.
     */
    private val confinementRoot: File by lazy {
        File(System.getProperty("user.home")).canonicalFile
    }

    /**
     * Validates [path] after canonicalization (BossConsole#885): the old check ran on
     * RAW string - `..` in the literal and a POSIX-only prefix list - so a symlink
     * inside the allowed root pointing at a blocked target sailed through, and Windows
     * system paths were never covered. Canonicalize FIRST so the checks see what the
     * filesystem will actually resolve; then confine to [confinementRoot] so escapes
     * (link or otherwise) are refused by the one rule that matters.
     */
    private fun validatePath(path: String): File {
        require(!path.contains("..")) { "Path traversal sequences ('..') are not allowed: $path" }
        // toRealPath, not canonicalFile: on Windows (and for symlinks generally),
        // File.canonicalFile can return the LINK's own path without resolving the
        // link, so a symlink inside the home pointing at an outside target passed
        // the gate (BossConsole#885). toRealPath() resolves the link chain to the
        // actual filesystem location, which is what the confinement check must see.
        //
        // The path may not exist yet (a save creating a new file): the caller
        // creates the parent directory BEFORE this check for exactly that case
        // (saveFile's atomicWrite mkdirs first, openFile requires existence), so
        // toRealPath on the existing parent plus the file name is always resolvable
        // and resolves every link in the chain that matters.
        val real = File(path).toPath()
        // Resolve the FULL path when it exists (openFile; the save-new-file case
        // falls to the parent below). This is what catches a symlink FILE
        // inside the home pointing at an outside target: toRealPath follows the
        // link to its actual location.
        val resolved =
            try {
                real.toRealPath().toFile()
            } catch (_: java.nio.file.NoSuchFileException) {
                // New-file save: resolve the existing parent (the caller mkdirs
                // first), then re-append the file name. The parent's links
                // resolve; the not-yet-existing file name has no link to follow.
                val parent = real.parent ?: throw IllegalArgumentException("Path has no parent directory: $path")
                val resolvedParent =
                    try {
                        parent.toRealPath().toFile()
                    } catch (_: java.nio.file.NoSuchFileException) {
                        throw IllegalArgumentException("Parent directory does not exist: $path")
                    }
                real.fileName?.let { File(resolvedParent, it.toString()) } ?: resolvedParent
            }
        val root = confinementRoot.absolutePath + File.separator
        require(
            resolved.absolutePath.startsWith(root) || resolved.absolutePath == confinementRoot.absolutePath,
        ) { "Access denied: path outside the user's home directory: $path" }
        return resolved
    }

    /**
     * Atomic save (BossConsole#885): content streams into a unique sibling temp file
     * which is then moved atomically over the target. A crash or disk-full mid-save
     * leaves the previous complete version - the house `atomicWriteText` shape,
     * re-implemented locally because the helper lives in composeApp and this module
     * is a standalone kernel service. `File.renameTo` is deliberately not used: its
     * behavior when the destination exists is platform-dependent in exactly the way
     * that hid the favicon-cache bug on Windows.
     */
    private fun atomicWrite(
        target: File,
        content: String,
    ) {
        target.parentFile?.mkdirs()
        val tmp = File.createTempFile(target.name + ".", ".part", target.parentFile)
        try {
            tmp.writeText(content, Charsets.UTF_8)
            java.nio.file.Files.move(
                tmp.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file
                .Files
                .move(
                    tmp.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
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
            val file = validatePath(request.path)
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
            // Create the parent BEFORE validating: a save may target a new file,
            // and validatePath resolves the existing parent (see its KDoc).
            File(request.path).parentFile?.mkdirs()
            val file = validatePath(request.path)
            try {
                atomicWrite(file, request.content)
                openFiles[request.path] = false
            } catch (e: Exception) {
                logger.error("saveFile failed for {}: {}", request.path, e.message)
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
            val file = validatePath(request.path)
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
