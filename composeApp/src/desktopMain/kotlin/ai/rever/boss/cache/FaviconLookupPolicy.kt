package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon
import java.io.File
import java.net.IDN

internal const val FAVICON_TARGET_SIZE = 128

private val privateFaviconSuffixes =
    setOf(
        "localhost",
        "localdomain",
        "local",
        "internal",
        "lan",
        "home",
        "corp",
        "intranet",
        "test",
        "invalid",
        "example",
        "onion",
        "i2p",
        "arpa",
    )

/** No DNS resolution: this only decides whether a hostname may be disclosed to Google. */
internal fun isPublicFaviconHost(host: String): Boolean {
    val ascii =
        try {
            IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase()
        } catch (_: IllegalArgumentException) {
            return false
        }
    val labels = ascii.split('.')
    return ascii.length <= 253 && labels.size >= 2 &&
        labels.all { it.isNotEmpty() && it.length <= 63 } &&
        labels.last().any { it in 'a'..'z' } && labels.last() !in privateFaviconSuffixes
}

/** Called under a bounded per-host stripe lock; waiters re-read the cache and retry memory. */
internal suspend fun resolveHostFavicon(
    host: String,
    nowMs: Long,
    dir: File,
    refreshSmallIcon: Boolean,
    fetch: suspend (String, String) -> FaviconFetch,
): TabIcon.Image? {
    val cacheKey = HqFaviconDiskCache.keyFor(host)
    val cached = HqFaviconDiskCache.load(cacheKey, dir)
    return when {
        !isPublicFaviconHost(host) -> {
            cached?.icon
        }

        cached != null && !FaviconFreshness.isEntryExpired(cached.fetchedAtMs, nowMs) &&
            (!refreshSmallIcon || !qualityRefreshDue(cached, nowMs)) -> {
            cached.icon
        }

        // If deletion failed, a remembered definite miss can still use the copy left on disk.
        FaviconMissMemory.remembers(host, nowMs) || FaviconRetryMemory.coolingDown(host, nowMs) -> {
            cached?.icon
        }

        else -> {
            when (val outcome = fetch(host, cacheKey)) {
                is FaviconFetch.Icon -> {
                    FaviconRetryMemory.forget(host)
                    outcome.icon
                }

                FaviconFetch.NoIcon -> {
                    FaviconRetryMemory.forget(host)
                    HqFaviconDiskCache.delete(cacheKey, dir)
                    null
                }

                FaviconFetch.NoAnswer -> {
                    FaviconRetryMemory.record(host, nowMs)
                    cached?.icon
                }
            }
        }
    }
}

/** Transient failures pause briefly without changing the file's real fetch timestamp. */
internal object FaviconRetryMemory {
    const val RETRY_DELAY_MS = 60_000L
    const val MAX_REMEMBERED = 500
    private val attempts = mutableMapOf<String, Long>()

    @Synchronized
    fun record(
        host: String,
        nowMs: Long,
    ) {
        if (host !in attempts && attempts.size >= MAX_REMEMBERED) {
            attempts.entries.removeIf { nowMs - it.value !in 0 until RETRY_DELAY_MS }
            if (attempts.size >= MAX_REMEMBERED) attempts.remove(attempts.minBy { it.value }.key)
        }
        attempts[host] = nowMs
    }

    @Synchronized
    fun coolingDown(
        host: String,
        nowMs: Long,
    ): Boolean = attempts[host]?.let { nowMs - it in 0 until RETRY_DELAY_MS } == true

    @Synchronized
    fun forget(host: String) {
        attempts.remove(host)
    }

    @Synchronized
    fun clear() {
        attempts.clear()
    }
}
