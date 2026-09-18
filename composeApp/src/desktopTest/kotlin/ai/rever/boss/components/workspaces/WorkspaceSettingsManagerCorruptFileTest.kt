package ai.rever.boss.components.workspaces

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for issue #925's `WorkspaceSettingsManager` finding: a corrupt
 * `workspace-settings.json` used to be silently re-read (and re-failed) on every future launch
 * instead of self-healing, and saves used truncate-on-open `writeText`.
 *
 * `WorkspaceSettingsManager` is a singleton that loads once at class-init time, so this test
 * drives it through [WorkspaceSettingsManager.reloadForTesting] rather than relying on init
 * timing.
 */
class WorkspaceSettingsManagerCorruptFileTest {
    @AfterTest
    fun cleanUp() {
        WorkspaceSettingsManager.settingsFile.parentFile
            ?.listFiles { f -> f.name.startsWith("${WorkspaceSettingsManager.settingsFile.name}.corrupt-") }
            ?.forEach { it.delete() }
        // Leave the manager holding a valid file again for any other test in this process.
        WorkspaceSettingsManager.reloadForTesting()
    }

    @Test
    fun `a corrupt workspace settings file is moved aside and replaced with a fresh default on reload`() {
        WorkspaceSettingsManager.settingsFile.parentFile?.mkdirs()
        WorkspaceSettingsManager.settingsFile.writeText("{ not valid json at all")

        WorkspaceSettingsManager.reloadForTesting()

        assertEquals(
            WorkspaceSettings(settingsVersion = WorkspaceSettings.CURRENT_SETTINGS_VERSION),
            WorkspaceSettingsManager.currentSettings.value,
        )

        // The original path now holds a fresh, valid file, not the corrupt bytes.
        assertTrue(
            !WorkspaceSettingsManager.settingsFile.readText().contains("not valid json"),
            "the corrupt content must not still be at the original path",
        )

        // The corrupt bytes themselves survive, renamed aside for inspection.
        val corruptSiblings =
            WorkspaceSettingsManager.settingsFile.parentFile
                ?.listFiles { f -> f.name.startsWith("${WorkspaceSettingsManager.settingsFile.name}.corrupt-") }
                .orEmpty()
        assertEquals(1, corruptSiblings.size)
        assertEquals("{ not valid json at all", corruptSiblings.single().readText())
    }
}
