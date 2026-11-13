package ai.rever.boss.downloadopen

import ai.rever.boss.components.plugin.panels.left_top.DownloadDataProviderImpl
import ai.rever.boss.components.plugin.panels.left_top.DownloadEngineController
import ai.rever.boss.components.plugin.tab_types.fluck.DownloadItem
import ai.rever.boss.components.plugin.tab_types.fluck.DownloadManager
import ai.rever.boss.components.plugin.tab_types.fluck.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `DownloadDataProvider.openFile` is a "launch this file with the OS" call, and `DownloadServiceBridge`
 * already confines it to a path the provider is tracking as a download. The in-process provider it wraps did
 * not, so every installed plugin held `openFile(anyPath)` and `revealInFolder(anyPath)`: for an executable,
 * open and double-click are the same action, and this path has no executable-consent step. The confinement is
 * now in the provider, where both callers meet it.
 *
 * The check reads [DownloadManager.allDownloads], the live map, not the provider's own [downloads] flow,
 * which mirrors [DownloadManager.downloads] and is sampled every 150ms for the UI. Reading the sampled flow
 * would refuse a download that was just added, and keep authorizing one that was just removed, for up to a
 * sampling window either way - the opposite of "currently tracked". `a download is trusted the instant it is
 * added` and `a download is refused the instant it stops being tracked` below call the provider without
 * waiting for that flow to catch up, which is what would have caught it.
 */
class DownloadOpenConfinementTest {
    private val dir = createTempDirectory("download-confinement").toFile()
    private val opened = mutableListOf<String>()
    private val revealed = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun tracked(name: String = "report.pdf") = File(dir, name).apply { writeText("x") }

    private fun providerOver(manager: DownloadManager): DownloadDataProviderImpl =
        DownloadDataProviderImpl(
            downloadManager = manager,
            engine = NoEngine,
            collectorContext = Dispatchers.Unconfined,
            opener = { opened += it },
            revealer = { revealed += it },
        )

    private fun providerTracking(vararg files: File): DownloadDataProviderImpl =
        runBlocking {
            val manager = DownloadManager()
            files.forEachIndexed { i, f -> manager.addDownload(item("d$i", f.path)) }
            providerOver(manager).also {
                // The provider's own downloads flow mirrors the manager's throttled one; wait for it to
                // arrive so a test that reads `it.downloads.value` sees a settled list. The confinement
                // check itself does not need this - see the two tests below that deliberately skip it.
                val deadline = System.currentTimeMillis() + 5_000
                while (it.downloads.value.size < files.size && System.currentTimeMillis() < deadline) Thread.sleep(20)
            }
        }

    private fun item(
        id: String,
        path: String,
        status: DownloadStatus = DownloadStatus.COMPLETED,
    ) = DownloadItem(
        id = id,
        fileName = File(path).name,
        destinationPath = path,
        url = "https://example.test/$id",
        mimeType = null,
        status = status,
        receivedBytes = 1,
        totalBytes = 1,
        speed = 0.0,
        startedAt = 1L,
        finishedAt = 2L,
        canPause = false,
        canResume = false,
        errorReason = null,
    )

    private object NoEngine : DownloadEngineController {
        override fun pause(id: String) = false

        override fun resume(id: String) = false

        override fun cancel(id: String) = false
    }

    @Test
    fun `a tracked download opens and reveals as before`() {
        val file = tracked()
        val provider = providerTracking(file)
        provider.openFile(file.path)
        provider.revealInFolder(file.path)
        assertEquals(listOf(file.path), opened)
        assertEquals(listOf(file.path), revealed)
    }

    @Test
    fun `a path that is not a tracked download is refused`() {
        val provider = providerTracking(tracked())
        val onWindows = System.getProperty("os.name").lowercase().contains("windows")
        val system = File(System.getenv("SystemRoot") ?: "/bin", if (onWindows) "System32/calc.exe" else "sh")
        provider.openFile(system.path)
        provider.openFile(File(dir, "other.exe").apply { writeText("x") }.path)
        provider.revealInFolder(system.path)
        assertEquals(emptyList(), opened, "nothing untracked may be launched")
        assertEquals(emptyList(), revealed)
    }

    @Test
    fun `dot-dot segments are resolved before the comparison`() {
        val file = tracked()
        val other = File(dir, "elsewhere.exe").apply { writeText("x") }
        val provider = providerTracking(file)

        provider.openFile(File(dir, "sub/../${file.name}").path) // resolves to the tracked file
        provider.openFile(File(dir, "sub/../${other.name}").path) // resolves to an untracked one

        assertEquals(1, opened.size, "$opened")
        assertTrue(opened.single().endsWith(file.name))
    }

    @Test
    fun `blank and empty paths are refused even when a tracked download has a blank destination`() {
        val provider =
            runBlocking {
                val manager = DownloadManager()
                manager.addDownload(item("blank", "")) // an in-flight download before its path is known
                manager.addDownload(item("real", tracked().path))
                providerOver(manager)
            }
        provider.openFile("")
        provider.openFile("   ")
        provider.revealInFolder("")
        assertEquals(emptyList(), opened)
        assertEquals(emptyList(), revealed)
    }

    @Test
    fun `the launcher gets the path as given by the tracked entry`() {
        val file = tracked("a b (1).pdf")
        val provider = providerTracking(file)
        provider.openFile(File(dir, "x/../a b (1).pdf").path)
        assertEquals(1, opened.size)
        assertEquals(file.canonicalFile, File(opened.single()).canonicalFile)
    }

    @Test
    fun `a download is trusted the instant it is added, without waiting for the throttled flow`() =
        runBlocking {
            val manager = DownloadManager()
            val provider = providerOver(manager)
            val file = tracked()

            manager.addDownload(item("fresh", file.path))
            // No wait on provider.downloads here: the manager's `downloads` flow samples every 150ms,
            // so a naive check against it would still show this download as untracked at this point.
            provider.openFile(file.path)

            assertEquals(listOf(file.path), opened, "a just-added download must be recognized immediately")
        }

    @Test
    fun `a download is refused the instant it stops being tracked, without waiting for the throttled flow`() =
        runBlocking {
            val manager = DownloadManager()
            val file = tracked()
            manager.addDownload(item("gone", file.path))
            val provider = providerOver(manager)
            // Wait until the throttled `downloads` flow has actually caught up and shows the entry, so the
            // removal below is a real race against a snapshot that still says "tracked" - not a case where
            // the stale snapshot happened to be empty either way.
            awaitTracked(provider, file.path)

            manager.removeDownload("gone")
            // No further wait: the 150ms sample can still hold the entry it just caught up on, which would
            // let this call keep authorizing a path that is no longer tracked.
            provider.openFile(file.path)
            provider.revealInFolder(file.path)

            assertEquals(emptyList(), opened, "a just-removed download must be refused immediately")
            assertEquals(emptyList(), revealed)
        }

    @Test
    fun `a completed download cleared from the manager is refused immediately`() =
        runBlocking {
            val manager = DownloadManager()
            val file = tracked()
            manager.addDownload(item("done", file.path, status = DownloadStatus.COMPLETED))
            val provider = providerOver(manager)
            awaitTracked(provider, file.path)

            manager.clearCompleted()
            provider.openFile(file.path)

            assertEquals(emptyList(), opened, "clearCompleted must take effect before the throttled flow catches up")
        }

    /** Blocks until [provider]'s own (throttled) `downloads` flow reports [path] as tracked. */
    private fun awaitTracked(
        provider: DownloadDataProviderImpl,
        path: String,
    ) {
        val deadline = System.currentTimeMillis() + 5_000
        while (provider.downloads.value.none { it.destinationPath == path } && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
    }
}
