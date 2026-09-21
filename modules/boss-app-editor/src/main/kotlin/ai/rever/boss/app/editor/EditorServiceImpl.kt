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
@Suppress("TooManyFunctions")
class EditorServiceImpl : EditorServiceGrpcKt.EditorServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(EditorServiceImpl::class.java)

    /** path → isDirty: tracks files opened in this session */
    private val openFiles = ConcurrentHashMap<String, Boolean>()

    private val blockedPrefixes: List<String> by lazy {
        val list =
            mutableListOf(
                "/etc",
                "/sys",
                "/proc",
                "/dev",
                "/boot",
                "/root",
                "C:\\Windows",
                "C:\\Program Files",
                "C:\\Program Files (x86)",
                "C:\\System Volume Information",
            )
        System.getenv("SystemRoot")?.let { list.add(it) }
        System.getenv("WINDIR")?.let { list.add(it) }
        list.map { runCatching { File(it).canonicalPath }.getOrDefault(it) }
    }

    private fun isSubpathOf(
        path: String,
        root: String,
    ): Boolean {
        val normPath = runCatching { File(path).canonicalPath }.getOrDefault(path)
        val normRoot = runCatching { File(root).canonicalPath }.getOrDefault(root)
        return normPath.equals(normRoot, ignoreCase = true) ||
            normPath.lowercase().startsWith(normRoot.lowercase() + File.separator.lowercase())
    }

    private fun validatePath(rawPath: String): File {
        require(!rawPath.contains("..")) { "Path traversal sequences ('..') are not allowed: $rawPath" }

        val canonicalFile =
            try {
                File(rawPath).canonicalFile
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid or unresolvable path: $rawPath", e)
            }

        val canonicalPath = canonicalFile.absolutePath
        require(!canonicalPath.contains("..")) { "Canonical path traversal sequences ('..') are not allowed: $rawPath" }

        val userHome = System.getProperty("user.home") ?: ""
        val tempDir = System.getProperty("java.io.tmpdir") ?: ""

        val isUnderHome = userHome.isNotEmpty() && isSubpathOf(canonicalPath, userHome)
        val isUnderTemp = tempDir.isNotEmpty() && isSubpathOf(canonicalPath, tempDir)

        require(isUnderHome || isUnderTemp) {
            "Access denied: path '$rawPath' (canonical: '$canonicalPath') is outside allowed roots"
        }

        blockedPrefixes.forEach { prefix ->
            require(!isSubpathOf(canonicalPath, prefix)) {
                "Access to system path '$prefix' is not allowed: $rawPath"
            }
        }

        return canonicalFile
    }

    private fun atomicWriteText(
        file: File,
        content: String,
    ) {
        file.parentFile?.mkdirs()
        val tempFile = File.createTempFile("${file.name}.", ".tmp", file.parentFile)
        try {
            tempFile.writeText(content, Charsets.UTF_8)
            atomicMoveFrom(file, tempFile)
        } finally {
            tempFile.delete()
        }
    }

    @Suppress("SwallowedException")
    private fun atomicMoveFrom(
        target: File,
        temp: File,
    ) {
        try {
            java.nio.file.Files.move(
                temp.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                temp.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
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
                } catch (e: Exception) {
                    return@withContext OpenFileResponse
                        .newBuilder()
                        .setSuccess(false)
                        .setErrorMessage(e.message ?: "Invalid path")
                        .build()
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
                openFiles[file.absolutePath] = false
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
            try {
                val file = validatePath(request.path)
                atomicWriteText(file, request.content)
                openFiles[file.absolutePath] = false
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
            val file =
                try {
                    validatePath(request.path)
                } catch (e: Exception) {
                    logger.warn("detectMainFunctions path validation failed: {}", e.message)
                    return@withContext DetectMainResponse.newBuilder().build()
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
