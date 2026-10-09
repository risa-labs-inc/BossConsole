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
        val roots =
            listOf(
                "composeApp/src/commonMain",
                "composeApp/src/desktopMain",
                "modules",
                "plugin-platform",
            )
        val stateRootLiterals =
            roots
                .flatMap { root -> kotlinSourcesUnder(repoRoot(), root).toList() }
                .filter { source ->
                    val path = source.invariantSeparatorsPath
                    val text = source.readText()
                    val productionSource =
                        "/src/commonMain/" in path || "/src/desktopMain/" in path || "/src/main/" in path
                    productionSource && ("\".boss\"" in text || "\".boss_debug\"" in text)
                }.map { source -> source.relativeTo(repoRoot()).invariantSeparatorsPath }
                .toSet()

        assertEquals(
            setOf(
                "plugin-platform/plugin-logging/src/desktopMain/kotlin/ai/rever/boss/plugin/logging/BossLogger.kt",
                "plugin-platform/plugin-path-utils/src/commonMain/kotlin/ai/rever/boss/plugin/pathutils/" +
                    "BossDirectories.kt",
            ),
            stateRootLiterals,
        )
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
                    "\"Documents\"" in text || "LEGACY_WORKSPACE_DIRECTORY_NAME" in text
                }.map { source -> source.relativeTo(repoRoot()).invariantSeparatorsPath }

        assertEquals(
            listOf(
                "composeApp/src/commonMain/kotlin/ai/rever/boss/components/workspaces/" +
                    "WorkspaceFileManager.kt",
                "composeApp/src/desktopMain/kotlin/ai/rever/boss/components/workspaces/" +
                    "DesktopWorkspaceFileManager.kt",
            ),
            documentsReferences,
        )
    }
}
