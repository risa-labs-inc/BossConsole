package ai.rever.boss.state

import ai.rever.boss.testsupport.kotlinSourcesUnder
import ai.rever.boss.testsupport.repoRoot
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Prevents host and in-repository plugin code from adding a second durable state root.
 *
 * User-selected files and operating-system integration paths do not match these durable state
 * signatures.
 */
class DurableStatePathConventionTest {
    @Test
    fun `durable home paths are centralized or explicitly isolated`() {
        val roots = listOf("composeApp/src", "modules", "plugin-platform")
        val directDefaults =
            roots
                .flatMap { root -> kotlinSourcesUnder(repoRoot(), root).toList() }
                .filter { source ->
                    val text = source.readText()
                    "System.getProperty(\"user.home\"), \".boss" in text
                }.map { source -> source.relativeTo(repoRoot()).invariantSeparatorsPath }
                .toSet()

        assertEquals(emptySet(), directDefaults)
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
