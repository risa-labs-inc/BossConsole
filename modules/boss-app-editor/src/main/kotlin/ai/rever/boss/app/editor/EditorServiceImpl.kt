package ai.rever.boss.app.editor

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
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

    /** Paths that must not be accessed via IPC — mirrors FileSystemServiceImpl policy. */
    private val BLOCKED_PATH_PREFIXES = listOf("/etc", "/sys", "/proc")

    private fun validatePath(path: String) {
        require(!path.contains("..")) { "Path traversal sequences ('..') are not allowed: $path" }
        BLOCKED_PATH_PREFIXES.forEach { prefix ->
            require(!path.startsWith(prefix)) { "Access to system path '$prefix' is not allowed: $path" }
        }
    }

    // Language-specific main/entry-point patterns
    private val mainPatterns = listOf(
        Regex("""^\s*(?:suspend\s+)?fun\s+main\s*\("""),        // Kotlin
        Regex("""^\s*public\s+static\s+void\s+main\s*\(\s*String"""), // Java
        Regex("""^\s*if\s+__name__\s*==\s*['"]__main__['"]\s*:"""),    // Python
        Regex("""^\s*func\s+main\s*\(\s*\)"""),                 // Go / Swift
        Regex("""^\s*fn\s+main\s*\(\s*\)"""),                   // Rust
        Regex("""^\s*int\s+main\s*\("""),                       // C / C++
    )

    override suspend fun openFile(request: OpenFileRequest): OpenFileResponse =
        withContext(Dispatchers.IO) {
            logger.info("openFile: path={}", request.path)
            validatePath(request.path)
            val file = File(request.path)
            if (!file.exists() || !file.isFile) {
                return@withContext OpenFileResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage("File not found: ${request.path}")
                    .build()
            }
            try {
                val content = file.readText(Charsets.UTF_8)
                openFiles[request.path] = false
                OpenFileResponse.newBuilder()
                    .setSuccess(true)
                    .setContent(content)
                    .setLanguage(detectLanguage(file.extension))
                    .build()
            } catch (e: Exception) {
                logger.warn("openFile read failed: {}", e.message)
                OpenFileResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(e.message ?: "Read failed")
                    .build()
            }
        }

    override suspend fun saveFile(request: SaveFileRequest): Empty =
        withContext(Dispatchers.IO) {
            logger.info("saveFile: path={}", request.path)
            validatePath(request.path)
            try {
                val file = File(request.path)
                file.parentFile?.mkdirs()
                file.writeText(request.content, Charsets.UTF_8)
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
            validatePath(request.path)
            val file = File(request.path)
            if (!file.exists() || !file.isFile) return@withContext DetectMainResponse.newBuilder().build()

            val functions = mutableListOf<MainFunctionInfo>()
            try {
                file.readLines(Charsets.UTF_8).forEachIndexed { idx, line ->
                    if (mainPatterns.any { it.containsMatchIn(line) }) {
                        functions += MainFunctionInfo.newBuilder()
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
        val infos = openFiles.entries.map { (path, dirty) ->
            OpenFileInfo.newBuilder().setPath(path).setIsModified(dirty).build()
        }
        return ListOpenFilesResponse.newBuilder().addAllFiles(infos).build()
    }

    private fun detectLanguage(ext: String): String = when (ext.lowercase()) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "py", "pyw" -> "python"
        "js", "mjs" -> "javascript"
        "ts", "tsx" -> "typescript"
        "go" -> "go"
        "rs" -> "rust"
        "c", "h" -> "c"
        "cpp", "cxx", "cc", "hpp" -> "cpp"
        "cs" -> "csharp"
        "swift" -> "swift"
        "rb" -> "ruby"
        "php" -> "php"
        "sh", "bash", "zsh" -> "shell"
        "html", "htm" -> "html"
        "css", "scss", "sass" -> "css"
        "json" -> "json"
        "xml" -> "xml"
        "yaml", "yml" -> "yaml"
        "toml" -> "toml"
        "md" -> "markdown"
        "gradle" -> "groovy"
        "proto" -> "protobuf"
        "sql" -> "sql"
        else -> "plaintext"
    }
}
