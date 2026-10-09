package ai.rever.boss.state

import ai.rever.boss.testsupport.kotlinSourcesUnder
import ai.rever.boss.testsupport.repoRoot
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Prevents host and in-repository plugin code from adding a second durable state root.
 *
 * The three allowlisted microkernel/library defaults cannot depend on plugin-path-utils without
 * introducing a published dependency or a module cycle. They spell the same `~/.boss` contract
 * directly. User-selected files and operating-system integration paths do not match these durable
 * state signatures.
 */
class DurableStatePathConventionTest {
    @Test
    fun `durable home paths are centralized or explicitly isolated`() {
        val allowedDirectDefaults =
            setOf(
                "modules/boss-service-settings/src/main/kotlin/ai/rever/boss/service/settings/SettingsServiceImpl.kt",
                "modules/boss-service-workspace/src/main/kotlin/ai/rever/boss/service/workspace/" +
                    "WorkspaceServiceImpl.kt",
                "plugin-platform/plugin-logging/src/desktopMain/kotlin/ai/rever/boss/plugin/logging/BossLogger.kt",
            )
        val roots = listOf("composeApp/src", "modules", "plugin-platform")
        val directDefaults =
            roots
                .flatMap { root -> kotlinSourcesUnder(repoRoot(), root).toList() }
                .filter { source ->
                    val text = source.readText()
                    "System.getProperty(\"user.home\"), \".boss" in text
                }.map { source -> source.relativeTo(repoRoot()).invariantSeparatorsPath }
                .toSet()

        assertEquals(allowedDirectDefaults, directDefaults)
    }

    @Test
    fun `documents is legacy input and never the active workspace root`() {
        val productionSources =
            listOf("composeApp/src/commonMain", "composeApp/src/desktopMain")
                .flatMap { root -> kotlinSourcesUnder(repoRoot(), root).toList() }
        val documentsReferences =
            productionSources
                .filter { source ->
                    val text = source.readText()
                    "WorkspaceFileManagerCommon.LEGACY_WORKSPACE_DIRECTORY_NAME" in text
                }.map { source -> source.relativeTo(repoRoot()).invariantSeparatorsPath }

        assertEquals(
            listOf(
                "composeApp/src/desktopMain/kotlin/ai/rever/boss/components/workspaces/" +
                    "DesktopWorkspaceFileManager.kt",
            ),
            documentsReferences,
        )
    }
}
