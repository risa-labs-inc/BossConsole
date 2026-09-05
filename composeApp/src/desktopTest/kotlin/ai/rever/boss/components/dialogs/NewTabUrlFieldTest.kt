package ai.rever.boss.components.dialogs

import ai.rever.boss.components.overlays.OverlayConfig
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.browser.UrlHistoryManager
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import ai.rever.boss.plugin.ui.LocalHeavyweightOverlays
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import com.arkivanov.decompose.ComponentContext
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the new-tab dialog's URL field does as you type into it.
 *
 * Assertions are on the URL the dialog would actually OPEN, not on the field's rendered
 * text: the completion is ghost text and deliberately not part of the field's value, so
 * "where does this take me" is the only question worth pinning - and it is the one that was
 * wrong.
 */
class NewTabUrlFieldTest {
    @get:Rule
    val rule = createComposeRule()

    private class Stub(
        ctx: ComponentContext,
        override val config: TabInfo,
        override val tabTypeInfo: TabTypeInfo,
    ) : TabComponentWithUI,
        ComponentContext by ctx {
        @Composable
        override fun Content() = Unit
    }

    private val registry =
        TabRegistry().apply {
            registerTabType(FluckTabType) { config, ctx -> Stub(ctx, config, FluckTabType) }
        }

    private val opened = mutableListOf<String>()
    private lateinit var temp: java.io.File
    private var originalFile: java.io.File? = null
    private val previousModal = OverlayConfig.heavyweightModal
    private var previousSuggestionContext: CoroutineContext = EmptyCoroutineContext
    private val previousUseHeavyweight = OverlayConfig.useHeavyweightPopups

    @Before
    fun setUp() {
        temp = java.io.File.createTempFile("new-tab-history", ".json")
        temp.writeText(
            """
            [
              {"url":"https://github.com/","title":"GitHub","domain":"github.com",
               "visitCount":13,"lastVisited":${System.currentTimeMillis()}},
              {"url":"https://github.com/risa-labs-inc/BossConsole/pulls","title":"Pull requests",
               "domain":"github.com","visitCount":37,"lastVisited":${System.currentTimeMillis()}},
              {"url":"https://news.example.com/first","title":"First","domain":"news.example.com",
               "visitCount":9,"lastVisited":${System.currentTimeMillis()}},
              {"url":"https://news.example.com/second","title":"Second","domain":"news.example.com",
               "visitCount":2,"lastVisited":${System.currentTimeMillis()}}
            ]
            """.trimIndent(),
        )
        originalFile = UrlHistoryManager.historyFile
        UrlHistoryManager.historyFile = temp
        UrlHistoryManager.loadHistory()

        // Render the modal in place instead of in a separate always-on-top window, so the
        // dialog's own content belongs to this composition. The window itself is platform
        // code with its own tests; what is under test here is the field inside it.
        OverlayConfig.useHeavyweightPopups = true
        OverlayConfig.heavyweightModal = { _, _, content -> content() }

        // Run the suggestion lookup on the composition's own dispatcher. On
        // `Dispatchers.Default` the work happens on a pool the test clock does not drive, so
        // `advanceTimeBy` + `waitForIdle` proves the debounce elapsed but not that the result
        // has landed - a race that a fast lookup wins almost every time, which is exactly the
        // shape of a CI flake.
        previousSuggestionContext = urlSuggestionContext
        urlSuggestionContext = EmptyCoroutineContext
    }

    @After
    fun tearDown() {
        urlSuggestionContext = previousSuggestionContext
        OverlayConfig.heavyweightModal = previousModal
        OverlayConfig.useHeavyweightPopups = previousUseHeavyweight
        // UrlHistoryManager is a process-global store; a scratch file left in it would leak
        // into any other test that reads history.
        originalFile?.let { UrlHistoryManager.historyFile = it }
        UrlHistoryManager.loadHistory()
        temp.delete()
    }

    private fun openDialog() {
        rule.setContent {
            CompositionLocalProvider(LocalHeavyweightOverlays provides true) {
                NewTabDialog(
                    onDismiss = {},
                    onCreateTab = { _, path -> opened += path },
                    tabRegistry = registry,
                )
            }
        }
        rule.onNodeWithText("Enter URL or search term").assertExists()
    }

    /** Past the suggestion lookup's debounce, then let the completion effect run. */
    private fun settle() {
        rule.mainClock.advanceTimeBy(URL_SUGGESTION_DEBOUNCE_MS * 5)
        rule.waitForIdle()
    }

    private fun confirm() {
        rule.onNodeWithText("Fluck it").performClick()
        rule.waitForIdle()
    }

    @Test
    fun `confirming takes the completion the ghost text is offering`() {
        openDialog()

        rule.onNode(hasSetTextAction()).performTextInput("git")
        settle()
        confirm()

        assertEquals(listOf("https://github.com"), opened)
    }

    @Test
    fun `Tab accepts the completion into the field`() {
        openDialog()

        rule.onNode(hasSetTextAction()).performTextInput("git")
        settle()
        rule.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Tab) }
        settle()
        confirm()

        assertEquals(listOf("https://github.com"), opened)
    }

    @Test
    fun `deleting suppresses the completion instead of filling it back in`() {
        openDialog()

        rule.onNode(hasSetTextAction()).performTextInput("git")
        settle()
        rule.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Backspace) }
        settle()
        confirm()

        // "gi" is not an address and nothing re-completed it, so it is searched for. A
        // completion that survived the delete would have opened github.com instead.
        assertEquals(listOf("https://www.google.com/search?q=gi"), opened)
    }

    @Test
    fun `the matching history entry is offered above the web search`() {
        // Asserted on the returned order, not on rendered y-coordinates: the rows are what
        // the reorder actually changed, and a layout comparison also relied on JVM
        // assertions being enabled to fail at all.
        val rows = UrlHistoryProvider.getSuggestions("git", limit = 10)

        assertTrue(rows.isNotEmpty())
        assertFalse(rows.first().isSearchSuggestion, "a history match must come first")
        assertTrue(rows.last().isSearchSuggestion, "the web search row belongs at the bottom")
    }

    @Test
    fun `Enter takes the completion the ghost text is offering`() {
        openDialog()

        rule.onNode(hasSetTextAction()).performTextInput("git")
        settle()
        rule.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()

        assertEquals(listOf("https://github.com"), opened)
    }

    @Test
    fun `Right at the end of the input accepts the completion`() {
        openDialog()

        rule.onNode(hasSetTextAction()).performTextInput("git")
        settle()
        rule.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.DirectionRight) }
        settle()
        confirm()

        assertEquals(listOf("https://github.com"), opened)
    }

    @Test
    fun `Tab in the middle of the input does not accept either`() {
        openDialog()

        rule.onNode(hasSetTextAction()).performTextInput("git")
        settle()
        rule.onNode(hasSetTextAction()).performKeyInput {
            pressKey(Key.MoveHome)
            pressKey(Key.Tab)
        }
        settle()
        confirm()

        // The ghost is drawn AFTER the text, so with the caret at the front it describes an
        // insertion point it does not belong to. Right already refused to accept there;
        // Tab did not, because it never consulted the caret at all.
        assertEquals(listOf("https://www.google.com/search?q=git"), opened)
    }

    @Test
    fun `Right in the middle of the input stays a cursor move`() {
        openDialog()

        rule.onNode(hasSetTextAction()).performTextInput("git")
        settle()
        rule.onNode(hasSetTextAction()).performKeyInput {
            pressKey(Key.MoveHome)
            pressKey(Key.DirectionRight)
        }
        // Probed by typing rather than by reading the field: the ghost is a visual
        // transformation and Compose reports the TRANSFORMED text as the node's editable
        // text, so neither a text matcher nor the semantics value can tell "git" with a
        // ghost apart from an accepted "github.com". Where the next character lands can:
        // after a cursor move it goes at index 1, after an accept it goes at the end.
        rule.onNode(hasSetTextAction()).performTextInput("X")
        settle()
        confirm()

        assertEquals(listOf("https://www.google.com/search?q=gXit"), opened)
    }

    @Test
    fun `typing after a delete re-arms the completion`() {
        openDialog()

        rule.onNode(hasSetTextAction()).performTextInput("git")
        settle()
        rule.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Backspace) }
        settle()
        rule.onNode(hasSetTextAction()).performTextInput("t")
        settle()
        confirm()

        // Suppression must be per-edit. Latched false, inline completion would be dead for
        // the rest of the dialog's life and every other test here would still pass.
        assertEquals(listOf("https://github.com"), opened)
    }

    @Test
    fun `Escape cancels the completion, not just the list`() {
        openDialog()

        rule.onNode(hasSetTextAction()).performTextInput("git")
        settle()
        rule.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Escape) }
        settle()
        rule.onNodeWithText(DEEP_ROW).assertDoesNotExist()
        confirm()

        // Escape rejected the proposal, so the typed text is what is left - and "git" is
        // not an address, so it is searched for.
        assertEquals(listOf("https://www.google.com/search?q=git"), opened)
    }

    @Test
    fun `accepting a completion leaves the list closed`() {
        openDialog()

        rule.onNode(hasSetTextAction()).performTextInput("git")
        settle()
        rule.onNodeWithText(DEEP_ROW).assertExists()

        rule.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Tab) }
        settle()

        // Accepting rewrites the field, which re-keys the debounced lookup - so closing the
        // list by clearing the flag alone left it to re-open one debounce later, under a
        // comment claiming the opposite.
        rule.onNodeWithText(DEEP_ROW).assertDoesNotExist()
    }

    @Test
    fun `a highlighted row wins over the ghost completion`() {
        openDialog()

        rule.onNode(hasSetTextAction()).performTextInput("git")
        settle()
        rule.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.DirectionDown) }
        settle()
        confirm()

        // Arrowing into the list makes the highlighted row the proposal; the confirm button
        // relies on the completion having been cleared for that to hold.
        assertEquals(listOf("https://github.com/"), opened)
    }

    @Test
    fun `Escape drops the highlighted row, not just the list and the ghost`() {
        openDialog()

        rule.onNode(hasSetTextAction()).performTextInput("git")
        settle()
        rule.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.DirectionDown) }
        settle()
        rule.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Escape) }
        settle()
        confirm()

        // The highlighted row outranks the ghost in `urlToOpen`, and Escape used to leave it
        // behind: nothing suggestion-shaped was on screen any more, and the confirm button
        // still opened a row the user could no longer see.
        assertEquals(listOf("https://www.google.com/search?q=git"), opened)
    }

    @Test
    fun `deleting a row drops the highlight it was addressed by`() {
        openDialog()

        // Two pages on one host, so the list still has a row under the deleted one. "git"
        // cannot show this: its remaining row is on the same host as the ghost, so the two
        // commit targets agree by accident.
        rule.onNode(hasSetTextAction()).performTextInput("news")
        settle()
        rule.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.DirectionDown) }
        settle()
        rule.onAllNodesWithContentDescription("Delete")[0].performClick()
        settle()
        confirm()

        // The list is filtered in place, so the row under the deleted one moves up into the
        // index - and the highlight, which outranks the ghost, then names a page the user
        // never pointed at. With the index dropped, the ghost's host is what commits.
        assertEquals(listOf("https://news.example.com"), opened)
    }

    @Test
    fun `a limit of zero or less returns nothing instead of throwing`() {
        // `take` rejects a negative count, and there are TWO of them on this path: the one
        // inside `rankMatches`, which the provider used to reach by passing `limit - 1`, and
        // the provider's own after it appends the search row. Zero only exercises the first,
        // which is why it passed while the second was still unguarded.
        assertEquals(emptyList(), UrlHistoryProvider.getSuggestions("git", limit = 0))
        assertEquals(emptyList(), UrlHistoryProvider.getSuggestions("git", limit = -1))
        assertEquals(emptyList(), UrlHistoryProvider.getSuggestions("git", limit = Int.MIN_VALUE))
    }

    @Test
    fun `the encoder names what is safe rather than what is not`() {
        // Asserted on the encoder rather than through a search row, because the row is only
        // offered for text with no dot in it - which would have left the unreserved case
        // below untestable, and it is the one that says the output stays readable.
        //
        // This was a chain of `replace` calls and every review round found another character
        // missing from it. An allowlist ends that: RFC 3986 unreserved characters survive, a
        // space is the `?q=` convention's `+`, everything else leaves as its UTF-8 bytes.
        assertEquals("a-b_c.d~e", encodeUrlParameter("a-b_c.d~e"))
        assertEquals("caf%C3%A9+r%C3%B6sti", encodeUrlParameter("café rösti"))
        assertEquals("%E6%97%A5%E6%9C%AC", encodeUrlParameter("日本"))
        assertEquals("%22exact+phrase%22", encodeUrlParameter("\"exact phrase\""))
        assertEquals("a%3Cb%3Ec%7Cd%5Ce", encodeUrlParameter("a<b>c|d\\e"))
        // The characters the denylist did cover still go out the same way, so the search row
        // and the confirm path did not change what they search for.
        assertEquals("foo+%26+bar", encodeUrlParameter("foo & bar"))
        assertEquals("100%25+cotton", encodeUrlParameter("100% cotton"))
        assertEquals("a+%2B+b", encodeUrlParameter("a + b"))
        assertEquals("a%2Fb%3Fc%23d%3De", encodeUrlParameter("a/b?c#d=e"))
    }

    @Test
    fun `Tab with nothing to accept does not swallow the keystroke`() {
        openDialog()

        rule.onNode(hasSetTextAction()).performTextInput("zzqq")
        settle()
        rule.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Tab) }
        settle()
        confirm()

        // Consuming Tab unconditionally trapped focus in the field, putting the dialog's
        // own buttons out of keyboard reach.
        assertEquals(listOf("https://www.google.com/search?q=zzqq"), opened)
    }

    @Test
    fun `the search row is encoded the same way the confirm path encodes`() {
        // Clicking the row and pressing Enter on the same text used to search for different
        // things: the row only replaced spaces, so `&` arrived at Google as a separator.
        val row = UrlHistoryProvider.getSuggestions("foo & bar", limit = 10).single { it.isSearchSuggestion }

        assertEquals("https://www.google.com/search?q=foo+%26+bar", row.url)
    }

    @Test
    fun `a percent and a plus in the query survive encoding`() {
        // Sharing the encoder fixed `&` and `#` but left these two: `100%` went out as a
        // truncated escape, `a%26b` reached Google as `a&b` - the user's own text read back
        // as a separator - and `a + b` became `a+++b`, which reads as `a   b`.
        assertEquals(
            "https://www.google.com/search?q=100%25+cotton",
            searchRowFor("100% cotton"),
        )
        assertEquals("https://www.google.com/search?q=a+%2B+b", searchRowFor("a + b"))
        // The `%` a replacement introduces must not itself be re-escaped, which is why `%`
        // is replaced first.
        assertEquals("https://www.google.com/search?q=a%2526b", searchRowFor("a%26b"))
    }

    private fun searchRowFor(query: String): String {
        val rows = UrlHistoryProvider.getSuggestions(query, limit = 10)
        return rows.single { it.isSearchSuggestion }.url
    }

    private companion object {
        /**
         * A dropdown row's title, and only a dropdown row's: the field itself renders the
         * URL, so this is what tells "the list is on screen" apart from "the field holds a
         * completion".
         */
        const val DEEP_ROW = "Pull requests"
    }
}
