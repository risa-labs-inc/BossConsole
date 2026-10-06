package ai.rever.boss.app

import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.platform.pickedFileSelection
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class NewTabFileInfoTest {
    @Test
    fun `PDF selection opens a browser even above the text import limit`() {
        val directory = createTempDirectory("new-tab-pdf").toFile()
        try {
            val pdf = File(directory, "Budget & notes #1 + 100% '東京'.PDF")
            RandomAccessFile(pdf, "rw").use { it.setLength(17L * 1024 * 1024) }

            val selection = pickedFileSelection(pdf, readContent = false)
            assertFalse(selection.tooLarge)
            assertNull(selection.content)
            val tab = assertIs<FluckTabInfo>(newTabFileInfo(requireNotNull(selection.path)))

            assertEquals(FluckTabType.typeId, tab.typeId)
            assertEquals(pdf.name, tab.title)
            val uri = URI(tab.url)
            assertEquals("file", uri.scheme)
            assertNull(uri.rawQuery)
            assertNull(uri.rawFragment)
            assertEquals(pdf.absoluteFile, File(uri))
            assertFalse(tab.url.contains(' '))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `browser media use the same routing as files opened elsewhere`() {
        for (name in listOf("photo.PNG", "image.svg", "recording.mp4", "audio.mp3")) {
            val file = File(System.getProperty("java.io.tmpdir"), name)
            val tab = assertIs<FluckTabInfo>(newTabFileInfo(file.absolutePath))
            assertEquals(file.absoluteFile, File(URI(tab.url)))
        }
    }

    @Test
    fun `source files retain their editor path and filename`() {
        val file = File(System.getProperty("java.io.tmpdir"), "notes about report.pdf.kt")
        val tab = assertIs<EditorTabInfo>(newTabFileInfo(file.absolutePath))
        assertEquals(file.absolutePath, tab.filePath)
        assertEquals(file.name, tab.title)
    }
}
