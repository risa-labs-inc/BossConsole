package ai.rever.boss.components.plugin.tabtypes.fluck

import ai.rever.boss.components.plugin.tab_types.fluck.DownloadItem
import ai.rever.boss.components.plugin.tab_types.fluck.DownloadManager
import ai.rever.boss.components.plugin.tab_types.fluck.DownloadStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the b21 regression: a progress tick must mutate one map entry, not
 * copy the whole map, and the sorted UI snapshot must be produced at the
 * sampled cadence, not once per emission. The assertion leans on
 * [DownloadManager.sortedSnapshotCount], which increments only inside the
 * snapshot path that used to run per upstream emission.
 */
class DownloadManagerTest {
    @Test
    fun `rapid progress ticks do not re-sort the download list per tick`() =
        runBlocking {
            val manager = DownloadManager()
            val downloadCount = 50
            for (i in 0 until downloadCount) {
                manager.addDownload(downloadItem("d$i", startedAt = i.toLong()))
            }
            val baseline = manager.sortedSnapshotCount

            // One per-chunk tick per download, many rounds - far faster than the
            // 150ms sampling cadence the UI flow throttles to.
            val ticks = 1000
            repeat(ticks) { tick ->
                manager.updateProgress(
                    id = "d${tick % downloadCount}",
                    receivedBytes = tick.toLong(),
                    totalBytes = 10_000L,
                    instantSpeed = 1.0,
                )
            }
            // Let a few sampling periods elapse so any pending emissions flush.
            delay(400)

            val sorts = manager.sortedSnapshotCount - baseline
            assertTrue(
                sorts < ticks / 10,
                "sort must run per sampled emission, not per tick: $sorts sorts for $ticks ticks",
            )

            // Semantics preserved: newest-first ordering and latest progress.
            assertEquals(downloadCount, manager.downloads.value.size)
            assertEquals(
                "d${downloadCount - 1}",
                manager.downloads.value
                    .first()
                    .id,
            )
            assertEquals((ticks - 1).toLong(), manager.getDownload("d${(ticks - 1) % downloadCount}")?.receivedBytes)
        }

    private fun downloadItem(
        id: String,
        startedAt: Long,
    ) = DownloadItem(
        id = id,
        fileName = "$id.bin",
        destinationPath = "/tmp/$id.bin",
        url = "https://example.com/$id",
        mimeType = "application/octet-stream",
        status = DownloadStatus.DOWNLOADING,
        receivedBytes = 0,
        totalBytes = 10_000L,
        speed = 0.0,
        startedAt = startedAt,
        finishedAt = null,
        canPause = true,
        canResume = true,
        errorReason = null,
    )
}
