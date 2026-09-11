package ai.rever.boss.services.importer

import ai.rever.boss.plugin.api.BookmarkDataProvider
import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.workspace.TabConfig
import ai.rever.boss.services.supabase.SecretService
import ai.rever.boss.services.supabase.models.CreateSecretRequest
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Reads password and bookmark exports and writes them into BOSS.
 *
 * Everything credential-shaped passes through here, so the logging rule is
 * absolute: counts and reasons only, never a password, username or full URL.
 */
object ImportService {
    private val logger = BossLogger.forComponent("ImportService")

    /** Page size when reading existing secrets for de-duplication. */
    private const val SECRET_PAGE_SIZE = 100

    /** Upper bound on the de-duplication scan, so a huge vault can't hang the dialog. */
    private const val SECRET_SCAN_CAP = 5_000

    /**
     * Pause between per-item bookmark inserts on the fallback path.
     *
     * Only used when the installed bookmarks plugin has no bulk implementation,
     * where each insert triggers its own full rewrite of collections.json.
     */
    private const val FALLBACK_INSERT_DELAY_MS = 5L

    /** Above this, the fallback path is slow enough to be worth warning about. */
    const val FALLBACK_WARNING_THRESHOLD = 50

    // ==================== Passwords ====================

    /**
     * Create a vault entry per credential, skipping ones already present.
     *
     * There is no bulk secret endpoint, so this is one RPC per password and can
     * take a while; it checks for cancellation between items and reports
     * progress as it goes.
     */
    suspend fun importPasswords(
        passwords: List<ImportedPassword>,
        onProgress: (done: Int, total: Int, soFar: ImportResult) -> Unit = { _, _, _ -> },
    ): ImportResult =
        withContext(Dispatchers.IO) {
            val existing = loadExistingSecretKeys()
            val skipped = mutableListOf<SkippedRow>()
            val failures = mutableListOf<String>()
            var imported = 0

            for ((index, entry) in passwords.withIndex()) {
                // A cancelled import must stop rather than race through the rest.
                if (!currentCoroutineContext().isActive) break

                val website = normaliseWebsite(entry.website)
                val key = website.lowercase() to entry.username.lowercase()

                val blocked =
                    when {
                        website.isEmpty() || isNonWebEntry(entry.website) -> SkipReason.MISSING_URL

                        // Chrome stores logins with an empty username_value.
                        // The CSV path pre-skips these; without the same check
                        // the browser path sends them to the RPC, which rejects
                        // them, and they render as red failures.
                        entry.username.isBlank() -> SkipReason.MISSING_USERNAME

                        entry.password.isEmpty() -> SkipReason.MISSING_PASSWORD

                        key in existing -> SkipReason.ALREADY_EXISTS

                        else -> null
                    }

                if (blocked != null) {
                    skipped.add(SkippedRow(index + 1, blocked, displayLabel(website, entry.username)))
                } else {
                    SecretService
                        .createSecret(
                            CreateSecretRequest(
                                website = website,
                                username = entry.username,
                                password = entry.password,
                                notes = entry.notes,
                            ),
                        ).fold(
                            onSuccess = {
                                imported++
                                // Guards against duplicates inside the same file.
                                existing.add(key)
                            },
                            onFailure = { error ->
                                // The message is ours, never the credential's.
                                failures.add("${displayLabel(website, entry.username)}: ${error.message ?: "failed"}")
                            },
                        )
                }

                // Reported outward, not just returned: withContext discards the
                // block's value when the job is cancelled, so a cancelled import
                // would otherwise lose the tally and tell the user nothing was
                // written after hundreds of rows had been.
                onProgress(
                    index + 1,
                    passwords.size,
                    ImportResult(imported = imported, skipped = skipped.toList(), failures = failures.toList()),
                )
            }

            logger.info(
                LogCategory.AUTH,
                "Password import finished",
                mapOf("imported" to imported, "skipped" to skipped.size, "failed" to failures.size),
            )
            ImportResult(imported = imported, skipped = skipped, failures = failures)
        }

    /** Every (website, username) already in the vault, lowercased. */
    private suspend fun loadExistingSecretKeys(): MutableSet<Pair<String, String>> {
        val keys = mutableSetOf<Pair<String, String>>()
        var offset = 0

        while (offset < SECRET_SCAN_CAP) {
            val page =
                SecretService
                    .getUserSecrets(limit = SECRET_PAGE_SIZE, offset = offset)
                    .getOrElse { error ->
                        // A failed scan only costs de-duplication, so carry on and
                        // let the RPC reject genuine duplicates.
                        logger.warn(
                            LogCategory.AUTH,
                            "Could not read existing secrets for de-duplication",
                            mapOf("reason" to (error.message ?: "unknown")),
                        )
                        return keys
                    }

            page.data.forEach { secret ->
                keys.add(normaliseWebsite(secret.website).lowercase() to secret.username.lowercase())
            }

            if (!page.hasMore || page.data.isEmpty()) break
            offset += SECRET_PAGE_SIZE
        }
        return keys
    }

    /**
     * The value stored as a secret's website, and half of the de-duplication key.
     *
     * Preserve the full host so `jira.example.com` and `wiki.example.com` remain distinct
     * de-duplication keys. Collapsing them could discard a real password as "already saved".
     * The matching utility also preserves full hosts, but storage keeps a separate parser:
     * it uses URI parsing and retains the trimmed raw value when no host can be parsed,
     * rather than returning null. An imported website must not disappear on a parse failure.
     */
    private fun normaliseWebsite(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        val host =
            runCatching {
                val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
                java.net.URI(withScheme).host
            }.getOrNull()

        return host?.lowercase()?.removePrefix("www.") ?: trimmed
    }

    /**
     * Entries a browser stores for native apps rather than web pages.
     *
     * Chrome exports rows like `android://<hash>@com.example`; they have no host
     * and could never be autofilled, so importing them adds noise only.
     */
    private fun isNonWebEntry(raw: String): Boolean = raw.trim().startsWith("android://", ignoreCase = true)

    // ==================== Bookmarks ====================

    /**
     * True when [provider]'s plugin implements bulk insert itself.
     *
     * `addBookmarks` has a default body, so calling it always succeeds — an
     * older plugin silently inherits the shim, which loops the single-item path
     * and rewrites collections.json once per bookmark. The provider declares
     * which it is; inferring it from the declaring class would misreport an
     * override that delegates to `super`, `by` delegation, or an IPC proxy.
     */
    fun supportsBulkBookmarkInsert(provider: BookmarkDataProvider): Boolean = provider.supportsBulkAdd

    /**
     * Write [bookmarks] into collections, one collection per source folder.
     *
     * @param provider null when the bookmarks plugin failed to load
     */
    suspend fun importBookmarks(
        bookmarks: List<ImportedBookmark>,
        provider: BookmarkDataProvider?,
        onProgress: (done: Int, total: Int, soFar: ImportResult) -> Unit = { _, _, _ -> },
    ): ImportResult =
        withContext(Dispatchers.IO) {
            if (provider == null) {
                return@withContext ImportResult(
                    failures = listOf("The Bookmarks tool isn't available, so bookmarks couldn't be imported."),
                )
            }

            val bulk = supportsBulkBookmarkInsert(provider)
            // Ids must be unique per run, not just within a batch: a deterministic
            // id means re-importing the same export reuses every id from last time,
            // and removeBookmark filters by id — so deleting one would delete its
            // twin from the earlier run too.
            val runId =
                java.util.UUID
                    .randomUUID()
                    .toString()
                    .take(8)
            val byFolder = bookmarks.groupBy { it.folder?.takeIf { name -> name.isNotBlank() } ?: DEFAULT_COLLECTION }
            val failures = mutableListOf<String>()
            var imported = 0
            var done = 0
            // Counts across the whole run. mapIndexed restarted at 0 per folder,
            // so the same URL sitting first in two folders produced identical
            // ids — and the comment below claims global uniqueness.
            var idSequence = 0

            for ((collectionName, entries) in byFolder) {
                if (!currentCoroutineContext().isActive) break

                val models = entries.map { entry -> entry.toBookmark(runId, idSequence++) }

                runCatching {
                    // Ensure the collection for BOTH branches. The bulk path
                    // previously assumed the plugin does get-or-create; if it
                    // ever behaves like addBookmark — a silent no-op on a
                    // missing collection — that is a "successful" import of
                    // nothing, on the path that actually runs.
                    ensureCollection(provider, collectionName)
                    if (bulk) {
                        provider.addBookmarks(collectionName, models)
                    } else {
                        insertOneByOne(provider, collectionName, models)
                    }
                }.fold(
                    onSuccess = {
                        // Only credit what a full pass actually wrote; a cancelled
                        // per-item insert returns early.
                        imported += if (currentCoroutineContext().isActive) models.size else 0
                    },
                    onFailure = { error ->
                        // A cancelled import must unwind, not be reported as a
                        // per-collection failure the user then sees in the results.
                        if (error is CancellationException) throw error
                        failures.add("$collectionName: ${error.message ?: "failed"}")
                    },
                )

                done += models.size
                onProgress(done, bookmarks.size, ImportResult(imported = imported, failures = failures.toList()))
            }

            logger.info(
                LogCategory.GENERAL,
                "Bookmark import finished",
                mapOf(
                    "imported" to imported,
                    "collections" to byFolder.size,
                    "failed" to failures.size,
                    "bulkPath" to bulk,
                ),
            )
            ImportResult(imported = imported, failures = failures)
        }

    /**
     * Fallback for plugins with no bulk implementation.
     *
     * `addBookmark` no-ops when the named collection is absent, so the
     * collection has to exist first — and `createCollection` appends
     * unconditionally rather than get-or-create, so check before creating or a
     * duplicate empty collection is left behind.
     *
     * The pause between inserts spaces out the per-item file rewrites this path
     * triggers.
     */
    private fun ensureCollection(
        provider: BookmarkDataProvider,
        collectionName: String,
    ) {
        // createCollection appends unconditionally rather than get-or-create, so
        // creating blindly would leave a duplicate empty collection behind.
        if (provider.collections.value.none { it.name == collectionName }) {
            provider.createCollection(collectionName)
        }
    }

    private suspend fun insertOneByOne(
        provider: BookmarkDataProvider,
        collectionName: String,
        models: List<Bookmark>,
    ) {
        models.forEach { bookmark ->
            if (!currentCoroutineContext().isActive) return
            provider.addBookmark(collectionName, bookmark)
            delay(FALLBACK_INSERT_DELAY_MS)
        }
    }

    /** Collection used for bookmarks the export had at the top level. */
    const val DEFAULT_COLLECTION = "Imported"

    private fun ImportedBookmark.toBookmark(
        importRunId: String,
        index: Int,
    ): Bookmark =
        Bookmark(
            // Bookmark.generateId() is a bare millisecond timestamp, so a bulk
            // insert would hand hundreds of entries the same id — and
            // removeBookmark filters by id, so deleting one would delete them
            // all. Unique per entry AND per run: a deterministic id would make
            // re-importing the same export collide with the previous run.
            id = "imported-$importRunId-$index-${url.hashCode()}",
            tabConfig = TabConfig(type = "browser", title = title, url = url),
            workspaceName = "",
        )
}
