package ai.rever.boss.downloads

import ai.rever.boss.plugin.api.TransferInfo
import ai.rever.boss.plugin.api.TransferKind
import ai.rever.boss.plugin.api.TransferPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadToastNotificationTest {
    @Test
    fun `formatDownloadToastData formats active downloading transfer correctly`() {
        val transfer =
            Transfer(
                info =
                    TransferInfo(
                        id = "test-plugin-1",
                        title = "Test Plugin",
                        kind = TransferKind.PLUGIN_INSTALL,
                        phase = TransferPhase.DOWNLOADING,
                        progress = 0.65f,
                    ),
            )

        val data = formatDownloadToastData(transfer)

        assertEquals("test-plugin-1", data.id)
        assertEquals("Test Plugin", data.title)
        assertEquals("Downloading 65%", data.statusText)
        assertEquals(0.65f, data.progress)
        assertFalse(data.isComplete)
    }

    @Test
    fun `formatDownloadToastData formats ready to install transfer as complete`() {
        val transfer =
            Transfer(
                info =
                    TransferInfo(
                        id = "boss-update-1",
                        title = "BOSS Console Update",
                        kind = TransferKind.APP_UPDATE,
                        phase = TransferPhase.READY_TO_INSTALL,
                        progress = 1.0f,
                    ),
            )

        val data = formatDownloadToastData(transfer)

        assertEquals("boss-update-1", data.id)
        assertEquals("BOSS Console Update", data.title)
        assertEquals("Ready to install", data.statusText)
        assertTrue(data.isComplete)
    }
}
