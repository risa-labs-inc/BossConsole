package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicMoveFrom
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO

/** A cached icon together with the moment it was fetched, which is what its TTL is measured from. */
internal class CachedFavicon(
    val icon: TabIcon.Image,
    val fetchedAtMs: Long,
)

/**
 * The on-disk half of [HighQualityFaviconService]: one PNG per host, capped in count and aged out
 * by [FaviconFreshness].
 *
 * Every entry point takes the directory explicitly, defaulting to the real one, the same seam
 * `FaviconCache.saveFavicon` uses - the alternative is a test that writes to the developer's own
 * `~/.boss/cache`, and the behaviours worth pinning here all *remove or retain* files.
 */
internal object HqFaviconDiskCache {
    private val logger = BossLogger.forComponent("HqFaviconDiskCache")
    private const val DIR_NAME = "favicon-hq-cache"
    private const val MAX_ENTRIES = 200
    private const val EVICTION_COUNT = 50

    /** In-progress writes land here first, and the suffix keeps them out of [entriesIn]. */
    private const val PARTIAL_SUFFIX = ".part"

    private val mutex = Mutex()

    val defaultDir: File by lazy {
        BossDirectories.resolve("cache/$DIR_NAME").apply { mkdirs() }
    }

    fun keyFor(host: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(host.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * The entry under [cacheKey] and the time it was fetched, or null when there is none.
     *
     * **A read does not touch the file, and an expired entry is not deleted here.** Both used to
     * be otherwise, and both were wrong:
     *
     * - The mtime was bumped on every read to order an LRU, which made the age a TTL needs
     *   unknowable and pinned the entries shown most often - including a wrong one on a favourite
     *   tile - beyond eviction's reach. Eviction is therefore oldest-*fetched* first, which for
     *   200 icons that all expire in a fortnight anyway is the same set of files in a slightly
     *   different order.
     * - Deleting on expiry destroyed the only copy *before* a replacement existed. Offline, or
     *   with Google blocked or rate-limiting, the sequence was: delete, fetch fails, letter - and
     *   a letter on every later launch too. Freshness is the caller's question to ask (see
     *   [FaviconFreshness]); this returns the bytes and the date and lets it decide, so a stale
     *   entry can still serve as a last resort. Nothing keeps the entry alive past its welcome: a
     *   fetch that succeeds overwrites it, and eviction still ages it out.
     */
    fun load(
        cacheKey: String,
        dir: File = defaultDir,
    ): CachedFavicon? {
        val cacheFile = File(dir, "$cacheKey.png")
        if (!cacheFile.exists()) return null

        // Broad on purpose, and not enumerable: JDK image readers throw UNCHECKED on malformed
        // data - ArrayIndexOutOfBoundsException, NegativeArraySizeException - not only
        // IIOException. A torn or truncated PNG has to be a cache miss rather than take the
        // caller's icon down with it, which is why the sibling FaviconCache.loadFavicon is broad
        // too.
        return runCatching {
            ImageIO.read(cacheFile)?.let { image ->
                CachedFavicon(
                    icon = TabIcon.Image(BitmapPainter(image.toComposeImageBitmap())),
                    fetchedAtMs = cacheFile.lastModified(),
                )
            }
        }.getOrElse { e ->
            logger.debug(
                LogCategory.BROWSER,
                "Failed to read cached HQ favicon - treating as cache miss",
                mapOf("error" to e.toString()),
            )
            null
        }
    }

    /**
     * Store [image] under [cacheKey], evicting first if the cache is full.
     *
     * Written to a unique temp file and moved into place, the dance `FaviconCache.saveFavicon`
     * documents: the shelf and the dashboard can resolve the same host at the same moment, and a
     * reader must never meet a half-written PNG. The move and the eviction share one lock, so a
     * write cannot be evicted halfway through.
     */
    suspend fun save(
        cacheKey: String,
        image: BufferedImage,
        dir: File = defaultDir,
    ) {
        mutex.withLock {
            evictOldestIfFull(dir)
            var temp: File? = null
            runCatching {
                temp = File.createTempFile("hq-favicon_", PARTIAL_SUFFIX, dir)
                // write() returns FALSE rather than throwing when no writer accepts the image, and
                // the move would then promote a zero-byte PNG over a perfectly good entry - which
                // load() reads as a permanent miss while it still occupies one of the 200 slots.
                // Failing loudly here costs one uncached icon instead.
                check(ImageIO.write(image, "PNG", temp)) { "no PNG writer accepted the icon" }
                File(dir, "$cacheKey.png").atomicMoveFrom(temp)
            }.onFailure { e ->
                logger.debug(
                    LogCategory.FILE,
                    "Failed to write HQ favicon cache entry - the icon still shows, it is just not cached",
                    mapOf("error" to e.toString()),
                )
            }
            // No-op once the move took it away; cleans up every failure path.
            temp?.delete()
        }
    }

    /**
     * Forget the entry under [cacheKey].
     *
     * The one thing that removes an entry short of eviction, and it is called for exactly one
     * reason: Google answered *definitely* that the host has no favicon, so the cached copy is a
     * picture of an icon the site no longer serves. A TTL alone cannot do this - expiry only means
     * "prefer a refetch", and the refetch is what discovers the icon is gone.
     */
    suspend fun delete(
        cacheKey: String,
        dir: File = defaultDir,
    ) {
        mutex.withLock {
            val cacheFile = File(dir, "$cacheKey.png")
            // Absence is the postcondition, not the return value: delete() reports false both for
            // "was not there" and for "could not remove it", and only the second is worth a line.
            if (!cacheFile.delete() && cacheFile.exists()) {
                logger.debug(
                    LogCategory.FILE,
                    "Could not drop the entry for a host with no favicon - it stays as a last resort",
                    mapOf("file" to cacheFile.name),
                )
            }
        }
    }

    /** Caller holds [mutex]. */
    private fun evictOldestIfFull(dir: File) {
        val files = entriesIn(dir)
        if (files.size < MAX_ENTRIES) return

        files
            .sortedBy { it.lastModified() }
            .take(EVICTION_COUNT)
            .forEach { file ->
                if (!file.delete()) {
                    // Non-fatal: the entry is retried on the next eviction.
                    logger.debug(
                        LogCategory.FILE,
                        "Failed to evict HQ favicon cache entry",
                        mapOf("file" to file.name),
                    )
                }
            }
    }

    /**
     * Empty the cache, `.part` files included - a crash mid-write can leave one behind.
     *
     * Under the same lock as [save] and [delete]: without it this could land between a write's
     * temp file and its move and take one or the other with it.
     */
    suspend fun clear(dir: File = defaultDir) {
        mutex.withLock {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    /** Entry count and total bytes on disk. */
    fun stats(dir: File = defaultDir): Pair<Int, Long> {
        val files = entriesIn(dir)
        return Pair(files.size, files.sumOf { it.length() })
    }

    /** The cache's own entries, i.e. not a `.part` file some concurrent write is still filling. */
    private fun entriesIn(dir: File): List<File> {
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { it.isFile && it.name.endsWith(".png") }
    }
}
