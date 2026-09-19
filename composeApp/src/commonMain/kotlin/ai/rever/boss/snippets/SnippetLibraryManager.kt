package ai.rever.boss.snippets

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Stores the operator's reusable prompt/command snippets in `~/.boss/snippets.json`.
 *
 * The persistence contract is the one every small BOSS state file follows, and the reasons are
 * the same here:
 * - **Writes are atomic** ([atomicWriteText]): a crash mid-write leaves at most a stray temp
 *   file, never a truncated `snippets.json` that fails to decode on the next launch.
 * - **Writes are serialized under [mutex]**: two overlapping saves cannot interleave a
 *   read-modify-write and silently drop one edit, the class of bug #1031 records for the other
 *   settings managers.
 * - **Reads coerce forward** ([json] with `ignoreUnknownKeys`): the file is migrated ahead of
 *   installed builds and is hand-editable, so an unknown key is ignored rather than aborting the
 *   whole load.
 *
 * The manager is a process-wide singleton like `RunConfigurationManager`; [storageFile] is an
 * overridable test hook so the read/write path can be exercised in a temp directory without
 * touching `~/.boss`.
 */
object SnippetLibraryManager {
    private val logger = BossLogger.forComponent("SnippetLibraryManager")

    private val defaultStorageFile = BossDirectories.resolve("snippets.json")

    /**
     * Overridable so hermetic tests exercise the real read/write path without touching `~/.boss`.
     * Production code never reassigns it; tests restore [defaultStorageFile] via
     * [resetForTesting] when they finish.
     */
    @Volatile
    internal var storageFile: File = defaultStorageFile

    /**
     * The clock used for [Snippet.createdAt] / [Snippet.updatedAt]. Overridable so tests can
     * assert timestamp behaviour deterministically.
     */
    @Volatile
    internal var clock: () -> Long = { System.currentTimeMillis() }

    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    private val mutex = Mutex()

    private val _snippets = MutableStateFlow<List<Snippet>>(emptyList())
    val snippets: StateFlow<List<Snippet>> = _snippets.asStateFlow()

    init {
        storageFile.parentFile?.mkdirs()
        loadSync()
    }

    /**
     * Load the library synchronously on startup or test reset. A missing file is not an error and
     * leaves the library empty; a corrupt file is logged and also leaves it empty rather than
     * throwing out of the initializer.
     */
    internal fun loadSync() {
        try {
            if (storageFile.exists()) {
                val library = json.decodeFromString(SnippetLibrary.serializer(), storageFile.readText())
                _snippets.value = library.snippets
                logger.debug(
                    LogCategory.SYSTEM,
                    "Loaded snippets",
                    mapOf("count" to library.snippets.size, "path" to storageFile.absolutePath),
                )
            } else {
                _snippets.value = emptyList()
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.warn(LogCategory.SYSTEM, "Failed to load snippets", error = e)
            _snippets.value = emptyList()
        }
    }

    /**
     * Reset manager state and optionally redirect [storageFile] to [testFile]; with no argument,
     * restore [defaultStorageFile]. Call only between tests, when no mutation is in flight, and
     * always call with no argument before finishing so the singleton is left where the app and
     * other tests expect it.
     */
    internal fun resetForTesting(testFile: File? = null) {
        storageFile = testFile ?: defaultStorageFile
        clock = { System.currentTimeMillis() }
        loadSync()
    }

    /** All snippets whose [Snippet.tags] contain [tag] (case-insensitive). */
    fun byTag(tag: String): List<Snippet> = _snippets.value.filter { s -> s.tags.any { it.equals(tag, true) } }

    /** The snippet with [id], or null. */
    fun get(id: String): Snippet? = _snippets.value.firstOrNull { it.id == id }

    /**
     * Create a new snippet and persist it. Returns the stored snippet, whose [Snippet.id] and
     * timestamps are assigned here. A blank [title] is rejected so a snippet is always
     * addressable by something a human recognises in a picker.
     */
    suspend fun add(
        title: String,
        body: String,
        tags: List<String> = emptyList(),
    ): Snippet {
        require(title.isNotBlank()) { "Snippet title must not be blank" }
        return mutex.withLock {
            val now = clock()
            val snippet =
                Snippet(
                    id = generateId(),
                    title = title,
                    body = body,
                    tags = normalizeTags(tags),
                    createdAt = now,
                    updatedAt = now,
                )
            val updated = _snippets.value + snippet
            _snippets.value = updated
            persist(updated)
            snippet
        }
    }

    /**
     * Update the title/body/tags of an existing snippet, preserving its [Snippet.createdAt] and
     * bumping [Snippet.updatedAt]. Returns the updated snippet, or null when [id] is unknown.
     */
    suspend fun update(
        id: String,
        title: String,
        body: String,
        tags: List<String> = emptyList(),
    ): Snippet? {
        require(title.isNotBlank()) { "Snippet title must not be blank" }
        return mutex.withLock {
            val current = _snippets.value
            val existing = current.firstOrNull { it.id == id } ?: return@withLock null
            val edited =
                existing.copy(
                    title = title,
                    body = body,
                    tags = normalizeTags(tags),
                    updatedAt = clock(),
                )
            val updated = current.map { if (it.id == id) edited else it }
            _snippets.value = updated
            persist(updated)
            edited
        }
    }

    /** Remove the snippet with [id]. Returns true when something was removed. */
    suspend fun remove(id: String): Boolean =
        mutex.withLock {
            val current = _snippets.value
            val updated = current.filterNot { it.id == id }
            if (updated.size == current.size) {
                false
            } else {
                _snippets.value = updated
                persist(updated)
                true
            }
        }

    private fun normalizeTags(t: List<String>): List<String> = t.mapNotNull { it.trim().ifEmpty { null } }.distinct()

    private fun generateId(): String = "snippet-${clock()}-${(0..9999).random()}"

    private suspend fun persist(snippets: List<Snippet>) =
        withContext(Dispatchers.IO) {
            try {
                val content = json.encodeToString(SnippetLibrary.serializer(), SnippetLibrary(snippets))
                storageFile.atomicWriteText(content)
                logger.debug(LogCategory.SYSTEM, "Saved snippets", mapOf("path" to storageFile.absolutePath))
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                logger.warn(LogCategory.SYSTEM, "Failed to save snippets", error = e)
            }
        }
}
