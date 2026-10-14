package ai.rever.boss.components.overlays

import ai.rever.boss.testsupport.kotlinSourcesUnder
import ai.rever.boss.testsupport.repoRoot
import kotlin.test.Test
import kotlin.test.fail

/**
 * Asserts no host source imports `androidx.compose.ui.window.Dialog` directly.
 *
 * Under HARDWARE_ACCELERATED the JxBrowser view is a native child window composited ABOVE the
 * Compose scene, so a plain `Dialog` opened over a browser tab renders behind the page. `BossDialog`
 * routes into its own always-on-top window instead.
 *
 * A convention test rather than review vigilance because the failure is invisible in the diff and on
 * any machine that is not looking at a browser tab at the time: the dialog composes, lays out and
 * logs exactly as it should, and is simply not on screen. The same PR that added this test found
 * `MemoryPressureNoticeDialog` still calling `Dialog` directly and a whole span of dialogs that
 * *looked* converted but sat outside the CompositionLocal that routes them - two different ways for
 * "it uses the right API" and "it actually routes" to come apart.
 *
 * Deliberately an import check. It cannot catch a fully-qualified call, but it catches the shape
 * every real call site takes, and it costs nothing.
 */
class NoRawDialogConventionTest {
    private val allowed =
        setOf(
            // The routing primitive itself: its lightweight branch IS the plain Dialog.
            "plugin-platform/plugin-ui-core/src/commonMain/kotlin/ai/rever/boss/plugin/ui/BossDialog.kt",
        )

    @Test
    fun `no host source imports the raw Compose Dialog`() {
        val root = repoRoot()
        val offenders =
            kotlinSourcesUnder(root, "composeApp/src", "plugin-platform")
                .filter { it.relativeTo(root).invariantSeparatorsPath !in allowed }
                .filter { file ->
                    file.readLines().any { it.trim() == "import androidx.compose.ui.window.Dialog" }
                }.map { it.relativeTo(root).path }
                .sorted()

        if (offenders.isNotEmpty()) {
            fail(
                "These files import androidx.compose.ui.window.Dialog directly, so their dialogs " +
                    "render BEHIND the browser surface under HARDWARE_ACCELERATED. Use BossDialog " +
                    "(ai.rever.boss.plugin.ui.BossDialog) instead:\n  " + offenders.joinToString("\n  "),
            )
        }
    }
}
