package ai.rever.boss.kernel

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins how self-healing finds a key in the legacy `llm_settings.json`.
 *
 * This lookup is the quietest thing in the file. Provider configuration moved to the
 * secret-manager plugin, and that plugin renames the legacy file to `.migrated` once it
 * has imported the keys — so reading only the original name would make self-healing stop
 * working the moment a user accepted that import, on upgraded installs only, with no
 * error anywhere. The fallback order and the fall-through below were previously correct
 * only by inspection.
 *
 * Self-healing cannot read the plugin's store at all (it resolves keys before the window
 * opens and long before plugins register), so an environment variable is the supported
 * path and this file is a compatibility shim — see [SelfHealingProvider].
 */
class LegacyLlmKeyLookupTest {
    private val tempDir: File = Files.createTempDirectory("legacy-llm-keys").toFile()

    private fun write(
        name: String,
        contents: String,
    ): File = File(tempDir, name).apply { writeText(contents) }

    private fun keysJson(vararg entries: Pair<String, String>): String =
        entries.joinToString(prefix = """{"apiKeys":{""", postfix = "}}") { (k, v) -> """"$k":"$v"""" }

    private fun lookup(
        files: List<File>,
        provider: SelfHealingProvider = SelfHealingProvider.ANTHROPIC,
    ): String? = SelfHealingSettingsManager.legacyKeyFrom(files, provider.name)

    @Test
    fun `reads the key from the live file`() {
        val live = write("live.json", keysJson("ANTHROPIC" to "sk-ant-live"))
        assertEquals("sk-ant-live", lookup(listOf(live)))
    }

    @Test
    fun `reads the key from the migrated file when the live one is gone`() {
        // The case the fallback exists for: the plugin imported the keys and renamed the file.
        val migrated = write("migrated.json", keysJson("ANTHROPIC" to "sk-ant-migrated"))
        assertEquals("sk-ant-migrated", lookup(listOf(File(tempDir, "absent.json"), migrated)))
    }

    @Test
    fun `live file wins when both are present`() {
        val live = write("live.json", keysJson("ANTHROPIC" to "sk-ant-live"))
        val migrated = write("migrated.json", keysJson("ANTHROPIC" to "sk-ant-stale"))
        assertEquals("sk-ant-live", lookup(listOf(live, migrated)))
    }

    @Test
    fun `a file without this provider falls through instead of short-circuiting`() {
        // The subtle one: an existing file that simply lacks this provider must not end
        // the search, or a key present only in the migrated copy becomes unreachable.
        val live = write("live.json", keysJson("OPENAI" to "sk-openai"))
        val migrated = write("migrated.json", keysJson("ANTHROPIC" to "sk-ant-migrated"))
        assertEquals("sk-ant-migrated", lookup(listOf(live, migrated)))
    }

    @Test
    fun `a corrupt file is skipped rather than aborting the search`() {
        val corrupt = write("live.json", "{ this is not json")
        val migrated = write("migrated.json", keysJson("ANTHROPIC" to "sk-ant-migrated"))
        assertEquals("sk-ant-migrated", lookup(listOf(corrupt, migrated)))
    }

    @Test
    fun `a blank stored value is treated as absent`() {
        val live = write("live.json", keysJson("ANTHROPIC" to "   "))
        val migrated = write("migrated.json", keysJson("ANTHROPIC" to "sk-ant-migrated"))
        assertEquals("sk-ant-migrated", lookup(listOf(live, migrated)))
    }

    @Test
    fun `returns null when no file exists`() {
        assertNull(lookup(listOf(File(tempDir, "a.json"), File(tempDir, "b.json"))))
    }

    @Test
    fun `returns null when no file names this provider`() {
        val live = write("live.json", keysJson("OPENAI" to "sk-openai"))
        assertNull(lookup(listOf(live), provider = SelfHealingProvider.ANTHROPIC))
    }

    @Test
    fun `keys are looked up per provider`() {
        val live = write("live.json", keysJson("ANTHROPIC" to "sk-ant", "OPENAI" to "sk-openai"))
        assertEquals("sk-ant", lookup(listOf(live), SelfHealingProvider.ANTHROPIC))
        assertEquals("sk-openai", lookup(listOf(live), SelfHealingProvider.OPENAI))
    }
}
