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
        val stateRootLiteral = Regex(""""\.boss(?:_debug)?(?:"|[/\\])""")
        val stateRootLiterals =
            productionKotlinSources()
                .filter { source ->
                    val text = source.readText()
                    stateRootLiteral.containsMatchIn(text)
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
        val documentsPathSegment = Regex(""""Documents"\s*[,)]""")
        val documentsReferences =
            productionKotlinSources()
                .filter { source ->
                    val text = source.readText()
                    documentsPathSegment.containsMatchIn(text) || "LEGACY_WORKSPACE_DIRECTORY_NAME" in text
                }.map { source -> source.relativeTo(repoRoot()).invariantSeparatorsPath }
                .toSet()

        assertEquals(
            setOf(
                "composeApp/src/commonMain/kotlin/ai/rever/boss/components/workspaces/" +
                    "WorkspaceFileManager.kt",
                "composeApp/src/desktopMain/kotlin/ai/rever/boss/components/workspaces/" +
                    "DesktopWorkspaceFileManager.kt",
            ),
            documentsReferences,
        )
    }

    private fun productionKotlinSources() =
        kotlinSourcesUnder(repoRoot(), ".").filter { source ->
            val path = source.invariantSeparatorsPath
            "/src/commonMain/" in path || "/src/desktopMain/" in path || "/src/main/" in path
        }
}
