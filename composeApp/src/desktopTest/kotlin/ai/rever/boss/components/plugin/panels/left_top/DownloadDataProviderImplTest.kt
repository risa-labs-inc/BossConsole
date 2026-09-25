package ai.rever.boss.components.plugin.panels.left_top

import ai.rever.boss.components.plugin.tab_types.fluck.DownloadItem
import ai.rever.boss.components.plugin.tab_types.fluck.DownloadManager
import ai.rever.boss.components.plugin.tab_types.fluck.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the provider's engine routing. A real JxBrowser engine cannot be
 * started in a unit test (no license, no Chromium binary, no window), so the
 * engine boundary is faked at [DownloadEngineController]; that boundary is one
 * delegation away from the live `Download.pause()/resume()/cancel()` calls in
 * FluckEngine.
 */
class DownloadDataProviderImplTest {
    @Test
    fun `removing an unfinished download cancels in the engine before dropping tracking`() {
        runBlocking {
            val manager = DownloadManager()
            val partialFile = tempPartialFile()
            manager.addDownload(downloadItem("d1", DownloadStatus.DOWNLOADING, partialFile.absolutePath))
            val engine =
                FakeDownloadEngine(
                    liveIds = setOf("d1"),
                    stillTracked = { manager.getDownload(it) != null },
                )
            val provider = provider(manager, engine)

            val result = provider.removeDownload("d1")

            assertTrue(result.isSuccess)
            assertEquals(listOf("cancel:d1"), engine.commands)
            // Ordering: the engine was told to stop while the download was still
            // tracked, never after the entry had been dropped.
            assertEquals(true, engine.trackedWhenCancelled)
            assertNull(manager.getDownload("d1"))
            assertFalse(partialFile.exists())
        }
    }

    @Test
    fun `removing a paused download also cancels in the engine`() {
        runBlocking {
            val manager = DownloadManager()
            val partialFile = tempPartialFile()
            manager.addDownload(downloadItem("d2", DownloadStatus.PAUSED, partialFile.absolutePath))
            val engine = FakeDownloadEngine(liveIds = setOf("d2"))
            val provider = provider(manager, engine)

            assertTrue(provider.removeDownload("d2").isSuccess)

            assertEquals(listOf("cancel:d2"), engine.commands)
            assertNull(manager.getDownload("d2"))
            assertFalse(partialFile.exists())
        }
    }

    @Test
    fun `removing a queued download cancels in the engine`() {
        runBlocking {
            val manager = DownloadManager()
            val partialFile = tempPartialFile()
            manager.addDownload(downloadItem("d3", DownloadStatus.QUEUED, partialFile.absolutePath))
            val engine = FakeDownloadEngine(liveIds = setOf("d3"))
            val provider = provider(manager, engine)

            assertTrue(provider.removeDownload("d3").isSuccess)

            assertEquals(listOf("cancel:d3"), engine.commands)
            assertFalse(partialFile.exists())
        }
    }

    @Test
    fun `rejected cancellation keeps the active download and its bytes for retry`() {
        runBlocking {
            val manager = DownloadManager()
            val partialFile = tempPartialFile()
            manager.addDownload(downloadItem("d4", DownloadStatus.DOWNLOADING, partialFile.absolutePath))
            val engine = FakeDownloadEngine(liveIds = emptySet())
            val provider = provider(manager, engine)

            assertTrue(provider.removeDownload("d4").isFailure)

            assertEquals(listOf("cancel:d4"), engine.commands)
            assertNotNull(manager.getDownload("d4"))
            assertTrue(partialFile.exists())
        }
    }

    @Test
    fun `removing a completed download deletes the file without an engine command`() {
        runBlocking {
            val manager = DownloadManager()
            val downloadedFile = tempPartialFile()
            manager.addDownload(downloadItem("d5", DownloadStatus.COMPLETED, downloadedFile.absolutePath))
            val engine = FakeDownloadEngine(liveIds = emptySet())
            val provider = provider(manager, engine)

            assertTrue(provider.removeDownload("d5").isSuccess)

            assertTrue(engine.commands.isEmpty())
            assertNull(manager.getDownload("d5"))
            assertFalse(downloadedFile.exists())
        }
    }

    @Test
    fun `removing an already terminal download issues no engine command`() {
        runBlocking {
            val manager = DownloadManager()
            manager.addDownload(downloadItem("d6", DownloadStatus.FAILED, "/does/not/exist/boss.part"))
            manager.addDownload(downloadItem("d7", DownloadStatus.CANCELLED, "/does/not/exist/boss2.part"))
            val engine = FakeDownloadEngine(liveIds = emptySet())
            val provider = provider(manager, engine)

            assertTrue(provider.removeDownload("d6").isSuccess)
            assertTrue(provider.removeDownload("d7").isSuccess)

            assertTrue(engine.commands.isEmpty())
            assertNull(manager.getDownload("d6"))
            assertNull(manager.getDownload("d7"))
        }
    }

    @Test
    fun `removing an unknown download succeeds without touching the engine`() {
        runBlocking {
            val manager = DownloadManager()
            val engine = FakeDownloadEngine(liveIds = emptySet())

            assertTrue(provider(manager, engine).removeDownload("missing").isSuccess)

            assertTrue(engine.commands.isEmpty())
        }
    }

    @Test
    fun `pause resume and cancel reach the engine`() {
        runBlocking {
            val manager = DownloadManager()
            manager.addDownload(downloadItem("d8", DownloadStatus.DOWNLOADING, "/tmp/boss-d8.part"))
            val engine = FakeDownloadEngine(liveIds = setOf("d8"))
            val provider = provider(manager, engine)

            assertTrue(provider.pauseDownload("d8").isSuccess)
            assertTrue(provider.resumeDownload("d8").isSuccess)
            assertTrue(provider.cancelDownload("d8").isSuccess)

            assertEquals(listOf("pause:d8", "resume:d8", "cancel:d8"), engine.commands)
        }
    }

    @Test
    fun `a command the engine does not own is reported as a failure`() {
        runBlocking {
            val manager = DownloadManager()
            manager.addDownload(downloadItem("d9", DownloadStatus.COMPLETED, "/tmp/boss-d9.bin"))
            val engine = FakeDownloadEngine(liveIds = emptySet())
            val provider = provider(manager, engine)

            // No lying: the panel and the download_* MCP tools must not report
            // PAUSED for a download Chromium is no longer running.
            assertTrue(provider.pauseDownload("d9").isFailure)
            assertTrue(provider.resumeDownload("d9").isFailure)
            assertTrue(provider.cancelDownload("d9").isFailure)
            assertEquals(listOf("pause:d9", "resume:d9", "cancel:d9"), engine.commands)
        }
    }

    @Test
    fun `an engine failure is propagated instead of a false success`() {
        runBlocking {
            val manager = DownloadManager()
            val engine = FakeDownloadEngine(liveIds = setOf("d10"), failWith = IllegalStateException("engine gone"))
            val provider = provider(manager, engine)

            val result = provider.pauseDownload("d10")

            assertTrue(result.isFailure)
            assertEquals("engine gone", result.exceptionOrNull()?.message)
        }
    }

    @Test
    fun `clearing completed downloads keeps unfinished ones`() {
        runBlocking {
            val manager = DownloadManager()
            manager.addDownload(downloadItem("done", DownloadStatus.COMPLETED, "/tmp/boss-done.bin"))
            manager.addDownload(downloadItem("live", DownloadStatus.DOWNLOADING, "/tmp/boss-live.part"))
            val engine = FakeDownloadEngine(liveIds = setOf("live"))
            val provider = provider(manager, engine)

            assertTrue(provider.clearCompleted().isSuccess)

            assertNull(manager.getDownload("done"))
            assertNotNull(manager.getDownload("live"))
            assertTrue(engine.commands.isEmpty())
        }
    }

    @Test
    fun `failed file removal retains tracking and can be retried`() =
        runBlocking {
            val manager = DownloadManager()
            // Nonempty directories reject delete on every platform, including root.
            val directory = Files.createTempDirectory("boss-download-refused").toFile()
            val child = File(directory, "keep.txt").apply { writeText("fixture") }
            try {
                manager.addDownload(downloadItem("refused", DownloadStatus.COMPLETED, directory.absolutePath))
                val provider = provider(manager, FakeDownloadEngine(emptySet()))

                assertTrue(provider.removeDownload("refused").isFailure)
                assertNotNull(manager.getDownload("refused"))
                assertTrue(child.exists())

                assertTrue(child.delete())
                assertTrue(provider.removeDownload("refused").isSuccess)
                assertNull(manager.getDownload("refused"))
                assertFalse(directory.exists())
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `already missing completed file can be removed repeatedly`() =
        runBlocking {
            val manager = DownloadManager()
            val file = tempPartialFile().apply { delete() }
            manager.addDownload(downloadItem("missing-file", DownloadStatus.COMPLETED, file.absolutePath))
            val provider = provider(manager, FakeDownloadEngine(emptySet()))

            assertTrue(provider.removeDownload("missing-file").isSuccess)
            assertTrue(provider.removeDownload("missing-file").isSuccess)
            assertNull(manager.getDownload("missing-file"))
        }

    @Test
    fun `completion racing cancellation still retains entry on deletion failure`() =
        runBlocking {
            val manager = DownloadManager()
            val directory = Files.createTempDirectory("boss-download-race").toFile()
            val child = File(directory, "keep.txt").apply { writeText("fixture") }
            try {
                manager.addDownload(downloadItem("race", DownloadStatus.DOWNLOADING, directory.absolutePath))
                val engine =
                    FakeDownloadEngine(emptySet(), onCancel = {
                        runBlocking { manager.updateStatus("race", DownloadStatus.COMPLETED) }
                    })
                assertTrue(provider(manager, engine).removeDownload("race").isFailure)
                assertEquals(DownloadStatus.COMPLETED, manager.getDownload("race")?.status)
                assertTrue(child.exists())
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `settled cancellation can remove tracking even after engine releases handle`() =
        runBlocking {
            val manager = DownloadManager()
            val file = tempPartialFile()
            manager.addDownload(downloadItem("settled", DownloadStatus.DOWNLOADING, file.absolutePath))
            val engine =
                FakeDownloadEngine(emptySet(), onCancel = {
                    runBlocking { manager.updateStatus("settled", DownloadStatus.CANCELLED) }
                })
            assertTrue(provider(manager, engine).removeDownload("settled").isSuccess)
            assertNull(manager.getDownload("settled"))
            assertFalse(file.exists())
        }

    private fun provider(
        manager: DownloadManager,
        engine: DownloadEngineController,
    ) = DownloadDataProviderImpl(
        downloadManager = manager,
        engine = engine,
        collectorContext = Dispatchers.Unconfined,
    )

    private fun tempPartialFile(): File {
        val file = File.createTempFile("boss-download-test", ".part")
        file.deleteOnExit()
        file.writeText("partial bytes")
        return file
    }

    private fun downloadItem(
        id: String,
        status: DownloadStatus,
        destinationPath: String,
    ) = DownloadItem(
        id = id,
        fileName = File(destinationPath).name,
        destinationPath = destinationPath,
        url = "https://example.com/$id",
        mimeType = "application/octet-stream",
        status = status,
        receivedBytes = 10,
        totalBytes = 100,
        speed = 1.0,
        startedAt = 1L,
        finishedAt = null,
        canPause = true,
        canResume = true,
        errorReason = null,
    )
}

/**
 * Stands in for the JxBrowser downloads FluckEngine keeps handles to. [liveIds]
 * are the downloads the engine still owns; anything else reports rejection, the
 * way FluckEngine does once a download has finished, failed or been cancelled.
 */
private class FakeDownloadEngine(
    private val liveIds: Set<String>,
    private val stillTracked: (String) -> Boolean = { false },
    private val failWith: Exception? = null,
    private val onCancel: () -> Unit = {},
) : DownloadEngineController {
    val commands = mutableListOf<String>()
    var trackedWhenCancelled: Boolean? = null

    override fun pause(id: String): Boolean = record("pause", id)

    override fun resume(id: String): Boolean = record("resume", id)

    override fun cancel(id: String): Boolean {
        trackedWhenCancelled = stillTracked(id)
        onCancel()
        return record("cancel", id)
    }

    private fun record(
        command: String,
        id: String,
    ): Boolean {
        commands += "$command:$id"
        failWith?.let { throw it }
        return id in liveIds
    }
}
