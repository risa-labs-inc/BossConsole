package ai.rever.boss.plugin

import ai.rever.boss.utils.atomicWriteText
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `installed.json` is the plugin registry: which jars are installed, which are switched off, where
 * each came from. A file that does not parse must not be left where the next save will overwrite it.
 *
 * The manager falls back to an empty list in memory, and the very next install, enable or disable
 * writes that list over the file. One truncated write (a crash, a kill, a full disk) therefore used
 * to end as "every plugin's enabled flag and source URL is gone", with no copy of what was there.
 */
class InstalledPluginsRecoveryTest {
    private val directory = createTempDirectory("installed-plugins-recovery").toFile()
    private val file = File(directory, "installed.json")
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun cleanup() {
        directory.deleteRecursively()
    }

    private fun quarantined(): List<File> = directory.listFiles().orEmpty().filter { it.name.contains(".corrupt-") }

    @Test
    fun `a valid file loads, including keys this build does not know`() {
        file.writeText(
            """{"plugins":[{"pluginId":"a","jarPath":"/x/a.jar","enabled":false,"futureField":1}],"alsoNew":true}""",
        )
        val read = readInstalledPluginsFile(file, json)
        assertIs<InstalledPluginsFileRead.Loaded>(read)
        assertEquals(listOf("a"), read.config.plugins.map { it.pluginId })
        val only = read.config.plugins.single()
        assertEquals(false, only.enabled)
        assertTrue(quarantined().isEmpty())
    }

    @Test
    fun `a missing file is missing and nothing is created`() {
        assertEquals(InstalledPluginsFileRead.Missing, readInstalledPluginsFile(file, json))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a truncated file is set aside with its bytes intact`() {
        val truncated = """{"plugins":[{"pluginId":"a","jarPath":"/x/a.jar","enabled":fal"""
        file.writeText(truncated)

        val read = readInstalledPluginsFile(file, json)

        assertIs<InstalledPluginsFileRead.Corrupt>(read)
        assertTrue(!file.exists(), "the corrupt file must not stay where the next save will overwrite it")
        val kept = assertNotNull(read.quarantinedTo)
        assertEquals(truncated, kept.readText())
        assertEquals(listOf(kept), quarantined())
    }

    @Test
    fun `an empty file and a wrong-shaped file are both corrupt`() {
        for (content in listOf("", "[]", "\"plugins\"", "{\"plugins\":\"none\"}")) {
            file.writeText(content)
            assertIs<InstalledPluginsFileRead.Corrupt>(readInstalledPluginsFile(file, json), "content: $content")
            assertTrue(!file.exists(), "content: $content")
        }
    }

    @Test
    fun `saving after a corrupt read does not touch the set-aside copy`() {
        val original = """{"plugins":[{"pluginId":"precious","jarPath":"/x.jar"""
        file.writeText(original)
        val corrupt = readInstalledPluginsFile(file, json) as InstalledPluginsFileRead.Corrupt
        val kept = assertNotNull(corrupt.quarantinedTo)

        file.atomicWriteText("""{"plugins":[]}""")

        assertEquals(original, kept.readText())
        assertIs<InstalledPluginsFileRead.Loaded>(readInstalledPluginsFile(file, json))
    }

    @Test
    fun `a file that cannot be read is left exactly where it is`() {
        // A directory in the file's place: it exists, and reading it throws an IOException. That says
        // nothing about the contents, so nothing may be moved.
        assertTrue(file.mkdirs())
        assertIs<InstalledPluginsFileRead.Unreadable>(readInstalledPluginsFile(file, json))
        assertTrue(file.isDirectory)
        assertTrue(quarantined().isEmpty())
    }
}
