package ai.rever.boss.platform

import java.io.File
import java.io.RandomAccessFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PickedFileSelectionTest {
    @Test
    fun `text imports retain their content and oversize refusal`() {
        val directory = createTempDirectory("picked-file").toFile()
        try {
            val file = File(directory, "data.json")
            file.writeText("{\"name\":\"東京\"}")
            val selected = pickedFileSelection(file, readContent = true)
            assertEquals(file.absolutePath, selected.path)
            assertEquals(file.readText(), selected.content)
            assertFalse(selected.tooLarge)

            RandomAccessFile(file, "rw").use { it.setLength(17L * 1024 * 1024) }
            val refused = pickedFileSelection(file, readContent = true)
            assertTrue(refused.tooLarge)
            assertNull(refused.path)
            assertNull(refused.content)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `path selection never probes size or reads file bytes`() {
        val file =
            object : File(System.getProperty("java.io.tmpdir"), "binary.pdf") {
                override fun length(): Long = error("A path-only selection must not inspect content size")
            }
        val selected = pickedFileSelection(file, readContent = false)
        assertEquals(file.absolutePath, selected.path)
        assertNull(selected.content)
        assertFalse(selected.tooLarge)
    }
}
