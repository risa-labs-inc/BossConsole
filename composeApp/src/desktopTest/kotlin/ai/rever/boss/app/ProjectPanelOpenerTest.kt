package ai.rever.boss.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Selecting a project opens no panel by itself.
 *
 * The host used to open Codebase and Run Configurations whenever a project was selected -
 * everywhere except Windows, which came up bare. Picking a project is not a request for a
 * particular layout, and the two panels landed on top of whatever was already open. Every panel
 * is still one sidebar click, menu item, deep link or CLI command away; only the automatic open
 * is gone, and it is gone on every platform rather than one.
 *
 * A source guard rather than a behavioural test, because these effects only run inside a composed
 * window: nothing observable happens for a test to assert the absence of. What can be asserted is
 * that no startup effect asks the bus to open a panel, which is the only way one could come back.
 */
class ProjectPanelOpenerTest {
    @Test
    fun `startup effects open no panel on their own`() {
        val text = startupEffects.readText()

        // Code lines only: a KDoc or comment naming the call is not a call, and this guard firing
        // on prose would be a false positive nobody could fix except by rewording.
        val code =
            text
                .lines()
                .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                .joinToString("\n")

        assertEquals(
            0,
            Regex("""PanelEventBus\.openPanel\(""").findAll(code).count(),
            "selecting a project must not open a panel: every panel is a click, menu item, " +
                "deep link or CLI command away, and an automatic open lands on top of whatever " +
                "the user already has arranged",
        )
    }

    @Test
    fun `the opener seam is gone rather than merely unused`() {
        // Left behind, it is an invitation to call it again - and its name reads like something
        // the host is meant to do on startup.
        assertTrue(
            !File(repoRoot, "composeApp/src/commonMain/kotlin/ai/rever/boss/app/StartupPanelPolicy.kt").exists(),
            "StartupPanelPolicy.kt is back: the auto-open policy has no second value to choose between",
        )
        assertEquals(
            0,
            Regex("""openProjectPanels""").findAll(startupEffects.readText()).count(),
            "BossAppStartupEffects calls an opener that no longer exists as a policy",
        )
    }

    private companion object {
        /** Same repo-root walk as WindowsArm64SourceIsolationTest - the test CWD is not pinned. */
        val repoRoot: File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "composeApp/build.gradle.kts").isFile }
                ?: error("could not locate the repository root from ${File("").absolutePath}")

        val startupEffects: File
            get() =
                File(repoRoot, "composeApp/src/commonMain/kotlin/ai/rever/boss/app/BossAppStartupEffects.kt")
                    .also { assertTrue(it.isFile, "source moved: ${it.absolutePath}") }
    }
}
