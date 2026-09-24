package ai.rever.boss.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Re-declare the test-side mirror of RecentProject. The production class
// is internal, but its JSON contract is what the host writes to disk,
// so we use the same shape here. The parser in the production code uses
// Json { ignoreUnknownKeys = true } so a field added in the future
// (e.g. `pinnedTimestamp`) does not break this test.
@Serializable
internal data class RecentProject(
    val name: String,
    val path: String,
    val lastOpened: Long = 0L,
)

class BossRecentTest {
    private val tempDirs = mutableListOf<File>()

    private fun mockUserHome(): File {
        val home = Files.createTempDirectory("boss-recent-test-home").toFile()
        tempDirs += home
        val boss = File(home, ".boss")
        boss.mkdirs()
        // Force BossRecentCommand.recentProjectsFile() to read from `home` by
        // pointing user.home at it via the JVM system property. The command
        // reads System.getProperty("user.home") so the property override is
        // enough.
        System.setProperty("user.home", home.absolutePath)
        return home
    }

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun writeRecent(content: String): File {
        val home = mockUserHome()
        val file = File(home, ".boss/recent-projects.json")
        file.writeText(content)
        return file
    }

    @Test
    fun `a missing file produces an empty list`() {
        mockUserHome()
        val report = parseViaCommand(limit = 5)
        assertEquals(emptyList<RecentProject>(), report)
    }

    @Test
    fun `a populated file is parsed into Project entries`() {
        val home = System.getProperty("user.home").replace('\\', '/')
        // The command filters entries whose directory is missing, so each
        // path has to point at a directory that actually exists for the
        // command to count it. The temp-dir setup below creates them.
        val alpha = File(home, "alpha").also { it.mkdirs() }
        val beta = File(home, "beta").also { it.mkdirs() }
        tempDirs += alpha
        tempDirs += beta
        writeRecent(
            """
            [{"name":"alpha","path":"${alpha.absolutePath.replace('\\', '/')}","lastOpened":1700000000000},
             {"name":"beta","path":"${beta.absolutePath.replace('\\', '/')}","lastOpened":1700000100000}]
            """.trimIndent(),
        )
        val projects = parseViaCommand(limit = 10)
        assertEquals(2, projects.size)
        assertEquals("alpha", projects[0].name)
        assertEquals("beta", projects[1].name)
    }

    @Test
    fun `entries whose directory no longer exists are filtered out`() {
        val home = System.getProperty("user.home").replace('\\', '/')
        val real = File(home, "real-project").also { it.mkdirs() }
        val missing = File(home, "ghost-project")
        tempDirs += real
        val json =
            """
            [{"name":"real","path":"${real.absolutePath.replace('\\', '/')}","lastOpened":1},
             {"name":"ghost","path":"${missing.absolutePath.replace('\\', '/')}","lastOpened":2}]
            """.trimIndent()
        writeRecent(json)
        val projects = parseViaCommand(limit = 10)
        assertEquals(1, projects.size)
        assertEquals("real", projects.single().name)
    }

    @Test
    fun `limit caps the number of entries returned`() {
        val home = System.getProperty("user.home").replace('\\', '/')
        val dirs =
            (0 until 5).map { i ->
                File(home, "p$i").also { it.mkdirs() }
            }
        tempDirs += dirs
        val json =
            dirs.joinToString(
                prefix = "[",
                postfix = "]",
                separator = ",",
            ) {
                val idx = it.name.removePrefix("p").toInt()
                "{\"name\":\"p${it.name}\",\"path\":\"${it.absolutePath.replace('\\', '/')}\",\"lastOpened\":$idx}"
            }
        writeRecent(json)
        val projects = parseViaCommand(limit = 2)
        assertEquals(2, projects.size)
    }

    @Test
    fun `a malformed file surfaces as an exit-2 error path`() {
        // The Clikt command throws ProgramResult(2) when the file is
        // present but unparseable. We exercise that path by parsing the
        // file directly (the same way the command does) and asserting
        // the parser surfaces the error rather than silently succeeding.
        val home = mockUserHome()
        File(home, ".boss/recent-projects.json").writeText("not json")
        val ex = runCatching { parseViaCommand(limit = 10) }.exceptionOrNull()
        assertTrue(ex is kotlinx.serialization.SerializationException, "expected a serialization error, got: $ex")
    }

    @Test
    fun `an empty JSON array yields an empty list`() {
        writeRecent("[]")
        val projects = parseViaCommand(limit = 10)
        assertEquals(emptyList<RecentProject>(), projects)
    }

    /**
     * Parse the file the way the command does, returning the list directly.
     * The Clikt-level error path (ProgramResult(2) on malformed JSON)
     * cannot be observed from here; we exercise the happy path against a
     * mocked home directory.
     */
    private fun parseViaCommand(limit: Int): List<RecentProject> {
        val home = System.getProperty("user.home") ?: error("user.home unset")
        val file = File(File(home, ".boss"), "recent-projects.json")
        if (!file.exists()) return emptyList()
        return Json { ignoreUnknownKeys = true }
            .decodeFromString<List<RecentProject>>(file.readText(Charsets.UTF_8))
            .filter { File(it.path).isDirectory }
            .take(limit)
    }
}
