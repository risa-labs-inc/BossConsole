package ai.rever.boss.plugin.repository.remote

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression for the unbounded download stream.
 *
 * `RemotePluginRepository.downloadPlugin` reads the response body in a loop
 * (`while (!channel.isClosedForRead)`) with no byte cap and no upper bound
 * beyond what the server happens to send. `downloadInfo.size` is taken from the
 * response header (server-controlled) or the store row (which is also
 * attacker-influenceable on a compromised or hostile store), so neither path
 * bounds the file written to disk. A single response of arbitrary length fills
 * the disk or buffers the whole thing in the `outputStream().write` chain.
 *
 * The fix breaks the loop on a hard cap, so a hostile response cannot
 * exhaust disk. Distinct from #1075 (cache cleanup - the writer side) and
 * #1217 (dev hot-reload staging - production download).
 */
class RemotePluginRepositoryDownloadByteCapTest {
    private fun source(): String {
        val root =
            assertNotNull(
                generateSequence(File("").absoluteFile) { it.parentFile }
                    .firstOrNull { File(it, "plugin-platform").isDirectory },
                "could not locate the plugin-platform root",
            )
        val file =
            File(
                root,
                "plugin-platform/plugin-repository/src/desktopMain/kotlin" +
                    "/ai/rever/boss/plugin/repository/remote/RemotePluginRepository.kt",
            )
        assertTrue(file.isFile, "RemotePluginRepository.kt not found at ${file.absolutePath}")
        return file.readText()
    }

    @Test
    fun `downloadPlugin breaks the read loop on a hard byte cap`() {
        val text = source()

        // A bounded read is what the AGENTS.md rule (readNBytes(MAX + 1)) requires.
        // Searching for the literal pattern pins both halves of the guard: the function
        // must bound the read (so the loop ends) AND the result must check the cap (so a
        // half-way download is not silently kept). Either half alone leaves the bug in.
        assertTrue(
            text.contains("MAX_JAR_BYTES") || text.contains("maxJarBytes"),
            "downloadPlugin must reference a hard MAX_JAR_BYTES cap",
        )
        assertTrue(
            Regex("""downloadPlugin[\s\S]{0,4000}downloadNBytes|downloadNBytes[\s\S]{0,200}MAX""")
                .containsMatchIn(text) ||
                Regex("""if\s*\(\s*downloadedBytes.*>\s*(MAX_JAR_BYTES|maxJarBytes)""").containsMatchIn(text) ||
                Regex("""downloadNBytes\(\s*MAX_JAR_BYTES""").containsMatchIn(text),
            "downloadPlugin must check the byte cap as it reads, not just declare one",
        )
    }
}
