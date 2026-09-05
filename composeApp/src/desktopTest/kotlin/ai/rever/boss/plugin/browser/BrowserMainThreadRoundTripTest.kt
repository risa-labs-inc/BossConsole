package ai.rever.boss.plugin.browser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * No blocking renderer round trip may be made from the EDT.
 *
 * `Frame.executeJavaScript` and `JsObject.putProperty` block until the *renderer* answers, and a
 * renderer has every right not to: one parked on a modal `window.prompt` cannot run script until the
 * dialog is answered, and one being swapped out mid-redirect never answers at all. Nothing can
 * interrupt the wait - `executeJavaScript` has no suspension point, so a `withTimeoutOrNull` placed
 * around it is not a bound.
 *
 * Made from the EDT that is not a slow call, it is a dead application: the EDT parks forever, AppKit's
 * main thread parks behind it, and the macOS menu bar goes with the window. Force quit is the only
 * exit. It happened twice in one morning on 9.5.7, and the user-visible symptom is indistinguishable
 * from a hung machine.
 *
 * The fix is [BoundedBrowserCall]: the blocking call on a dedicated single daemon thread, and the
 * wait bounded from a different one.
 *
 * A source check rather than a behaviour one, for the reason [InjectJsCallbackOwnershipTest] gives:
 * the failure mode is a *call site*, and observing it at runtime needs a real Chromium and a page
 * that genuinely stops answering.
 *
 * **Scope: every Kotlin source in the repo.** The first draft walked only `.../plugin/browser`, and
 * review promptly found a fourth live site one directory over - `DesktopBrowserAccessor`, reached by
 * plugins through a shipped API - which the guard reported as clean. "The failure mode is a new call
 * site" is exactly as true of an existing one the walk could not see.
 */
class BrowserMainThreadRoundTripTest {
    /**
     * Blocking calls into the renderer, matched in call position.
     *
     * The leading `.` keeps a declaration (`override suspend fun executeJavaScript(`) or an
     * interface's signature from reading as a call - there are several, and none of them block.
     *
     * The `<` is what separates JxBrowser's blocking `Frame.executeJavaScript` from *our own*
     * suspend wrapper of the same name. JxBrowser's is generic (`<T> T executeJavaScript(String)`),
     * so every call to it in this repo spells the type argument out; the suspend wrapper takes none.
     * Without that distinction this guard flagged `DefaultPlugin`'s adapter, which only delegates to
     * the wrapper and is exactly what a caller is *supposed* to do.
     *
     * Known gaps, every one of which is how this check goes blind, so every one belongs in writing:
     *  - a JxBrowser call whose type argument was inferred from an expected type reads as the
     *    wrapper and is missed. There are none today, and it is not the natural style here.
     *  - indirection more than **one** call deep. A Main block calling a local helper that blocks is
     *    caught (see [roundTripFunctions]) - that is the exact shape of the bug this change fixes -
     *    but a helper calling a second helper is not.
     *  - a round trip written *inside* a `${…}` string template. [endOfString] discards an
     *    interpolation with the literal that holds it, so a call in there is not seen at all. The
     *    alternative - lexing template expressions back into the code stream - buys a shape nothing
     *    in this repo writes, at the cost of the recursion being load-bearing rather than defensive.
     *  - a round trip reached only from a **non-suspend** function whose caller supplies the EDT.
     *    There is no Main marker at such a call site, so nothing here can see it; that is how
     *    `requestPictureInPicture` survived this guard, and why it is posted rather than inline.
     */
    private val rendererRoundTrips = listOf(".executeJavaScript<", ".putProperty(")

    /**
     * Anything that puts the following lambda on the EDT.
     *
     * A token list rather than a list of opener forms, because the opener is the part that keeps
     * changing: `withContext(Dispatchers.Main)`, `launch(Dispatchers.Main)`, `async(...)`,
     * `Dispatchers.Main.immediate`, `SwingUtilities.invokeLater`, and this package's own `onEdt`
     * helper are all the same hazard wearing different syntax. `invokeAndWait` is the worst of them -
     * it parks the caller *and* the EDT - so it is here even though nothing in the tree does it today.
     */
    private val mainThreadMarkers =
        listOf("Dispatchers.Main", "Dispatchers.Swing", "invokeLater", "invokeAndWait", "onEdt")

    /**
     * Scope constructions that put every launch into them on the EDT, allowed only where reviewed.
     *
     * A window of surrounding source has to contain one of these verbatim. Whole-file allowlisting
     * would have let the very scopes this change moved - `coBrowseScope`, `pageEventScope` - back in
     * unnoticed, since they lived in a file that has legitimate Main use as well.
     */
    private val allowedMainScopes =
        setOf(
            // Compose view state for the browser widget. Belongs to the EDT, and makes no renderer
            // round trip of its own - it drives the AWT component, not the page.
            "BrowserViewState(browser, MainScope(), awtWindow)",
        )

    /** How far a `{` may sit from its marker before it plainly belongs to something else. */
    private val markerToBrace = 60

    /** How long a function header may run before its `{` stops plausibly being its body. */
    private val funHeaderLimit = 400

    private fun repoRoot(): File? =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "composeApp/build.gradle.kts").isFile }

    private fun kotlinSources(root: File): List<File> =
        sequenceOf("composeApp/src", "plugin-platform", "modules", "server/src")
            .map { File(root, it) }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            .map { it.path.replace(File.separatorChar, '/') to it }
            // Build output holds generated copies of the same sources; scanning them doubles the
            // walk and reports names that are not source files anyone can fix.
            .filterNot { (path, _) -> path.contains("/build/") }
            // Test sources are excluded on purpose. This guards the product, and a test that
            // deliberately drives JxBrowser from the EDT to prove something about it should fail on
            // its own merits, not here - BrowserClipboardCommandsTest already calls
            // `.executeJavaScript<` in code position.
            .filterNot { (path, _) -> path.contains("Test/kotlin/") || path.contains("/test/") }
            .map { (_, file) -> file }
            .toList()

    // ---- lexing -------------------------------------------------------------------------------

    /**
     * The file's code with comments and string literals removed, then whitespace collapsed.
     *
     * One pass, handling all four of line comment / block comment / string / raw string together.
     * Doing it in two passes - comments first, then strings - was wrong in a way that would have
     * gone unnoticed: a `//` inside a raw string truncated that physical line, and a JS `//` comment
     * on a line that also closed its `\"\"\"` would send the string scan to end-of-file and silently
     * switch this whole guard off. Comments go because this file names both a dispatcher and a
     * round trip in prose; strings go because an injected script's braces are not block structure.
     */
    private fun codeOf(file: File): String {
        val src = file.readText()
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            i =
                when {
                    src.startsWith("//", i) -> skipLineComment(src, i, out)
                    src.startsWith("/*", i) -> skipBlockComment(src, i, out)
                    src.startsWith("\"\"\"", i) -> skipRawString(src, i, out)
                    src[i] == '"' -> skipString(src, i, out)
                    src[i] == '\'' -> skipCharLiteral(src, i, out)
                    else -> copyChar(src, i, out)
                }
        }
        return out.toString().replace(Regex("\\s+"), " ")
    }

    private fun skipLineComment(
        src: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        out.append(' ')
        val end = src.indexOf('\n', start)
        return if (end < 0) src.length else end
    }

    private fun skipBlockComment(
        src: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        out.append(' ')
        val end = src.indexOf("*/", start + 2)
        return if (end < 0) src.length else end + 2
    }

    private fun skipRawString(
        src: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        out.append("\"\"")
        val end = src.indexOf("\"\"\"", start + 3)
        return if (end < 0) src.length else end + 3
    }

    private fun skipString(
        src: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        out.append("\"\"")
        return endOfString(src, start)
    }

    /**
     * Just past the closing quote of the string literal at [start], or its line end.
     *
     * Interpolations are skipped as a unit, which is the whole point: stopping at the first `"` it
     * met made a template like `"…${f("x")}…"` re-pair every quote for the rest of that physical
     * line, so what is code read as string and vice versa. Newline-bounded, so it could not switch
     * the file off - but it could swallow the tail of a line, and if that tail carried an unmatched
     * brace then [blockAt]'s depth count is wrong and a block either truncates (a missed detection)
     * or runs to EOF (a false positive). Not hypothetical: `"Co-browse control ${if (granted)
     * "granted" else "revoked"}"` is in one of the files this guard is pointed at.
     */
    private fun endOfString(
        src: String,
        start: Int,
    ): Int {
        var i = start + 1
        while (i < src.length && src[i] != '\n') {
            i =
                when {
                    src[i] == '\\' -> i + 2
                    src.startsWith("\${", i) -> endOfTemplateExpression(src, i)
                    src[i] == '"' -> return i + 1
                    else -> i + 1
                }
        }
        return minOf(i, src.length)
    }

    /**
     * Just past the `}` that closes the `${…}` at [start].
     *
     * Brace-counted, with nested string and char literals skipped whole - either can hold a brace of
     * its own (`wrap("}")`), and counting one of those would desync the caller's depth just as
     * surely as mis-pairing a quote.
     */
    private fun endOfTemplateExpression(
        src: String,
        start: Int,
    ): Int {
        var i = start + 2
        var depth = 1
        while (i < src.length && depth > 0 && src[i] != '\n') {
            if (src[i] == '{') depth++
            if (src[i] == '}') depth--
            i =
                when (src[i]) {
                    '\\' -> i + 2
                    '"' -> endOfString(src, i)
                    '\'' -> endOfCharLiteral(src, i)
                    else -> i + 1
                }
        }
        return minOf(i, src.length)
    }

    private fun endOfCharLiteral(
        src: String,
        start: Int,
    ): Int {
        var i = start + 1
        while (i < src.length && src[i] != '\'' && src[i] != '\n') i += if (src[i] == '\\') 2 else 1
        return if (i >= src.length) src.length else i + 1
    }

    private fun skipCharLiteral(
        src: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        out.append("''")
        return endOfCharLiteral(src, start)
    }

    private fun copyChar(
        src: String,
        at: Int,
        out: StringBuilder,
    ): Int {
        out.append(src[at])
        return at + 1
    }

    // ---- block finding ------------------------------------------------------------------------

    /**
     * Names of functions declared in this file whose own body makes a renderer round trip.
     *
     * This is the one hop that matters. Guard 1 used to match round-trip *literals* inside the
     * dispatched block only, so `withContext(Dispatchers.Main) { injectPageEventScript(frame) }`
     * read as clean - and that is precisely the shape of the freeze this change fixes, one frame
     * down. Collecting the local blockers and treating a call to one as a round trip closes it for
     * every site in the diff.
     */
    private fun roundTripFunctions(code: String): Set<String> =
        // `(?:<[^>]*>\s*)?` is what lets a GENERIC helper be seen. Requiring the identifier straight
        // after `fun ` meant `private fun <T> helper(` never matched, so a generic blocking helper was
        // invisible to the one hop - and this very file declares `fun <T> syncCall(` and `fun <T> call(`.
        Regex("""fun (?:<[^>]*>\s*)?([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
            .findAll(code)
            .mapNotNull { match ->
                val open = code.indexOf('{', match.range.last)
                val body = if (open < 0 || open - match.range.last > funHeaderLimit) null else blockAt(code, open)
                match.groupValues[1].takeIf { body != null && rendererRoundTrips.any { body.contains(it) } }
            }.toSet()

    /** The source of every lambda dispatched onto the EDT, braces balanced. */
    private fun mainThreadBlocks(code: String): List<String> =
        mainThreadMarkers
            .flatMap { marker -> markerIndices(code, marker) }
            .mapNotNull { at -> dispatchedBlockAt(code, at) }

    private fun markerIndices(
        code: String,
        marker: String,
    ): List<Int> =
        generateSequence(code.indexOf(marker)) { prev ->
            code.indexOf(marker, prev + marker.length).takeIf { it >= 0 }
        }.takeWhile { it >= 0 }
            .toList()

    /**
     * The lambda this marker dispatches, or null when the marker does not open one.
     *
     * A `}` or a `fun ` between the marker and the brace means the brace opens something else - most
     * often the next declaration after a scope built on Main, which is not a dispatched lambda.
     */
    private fun dispatchedBlockAt(
        code: String,
        at: Int,
    ): String? {
        val open = code.indexOf('{', at)
        val opensALambda =
            open >= 0 &&
                open - at <= markerToBrace &&
                code.substring(at, open).let { between -> !between.contains('}') && !between.contains("fun ") }
        return if (opensALambda) blockAt(code, open) else null
    }

    private fun blockAt(
        code: String,
        open: Int,
    ): String {
        var depth = 0
        for (i in open until code.length) {
            if (code[i] == '{') depth++
            if (code[i] == '}') {
                depth--
                if (depth == 0) return code.substring(open, i + 1)
            }
        }
        return code.substring(open)
    }

    private fun windowAround(
        code: String,
        at: Int,
    ): String = code.substring(maxOf(0, at - 80), minOf(code.length, at + 80))

    // ---- the guards ---------------------------------------------------------------------------

    @Test
    fun `no renderer round trip runs on the EDT`() {
        val root = assertNotNull(repoRoot(), "could not locate the repository root")
        val scanned = kotlinSources(root)

        // Deliberately not a skip: a guard that passes when it cannot see the tree is decoration.
        assertTrue(scanned.size > 100, "only ${scanned.size} files scanned - the walk is not seeing the source")

        val offenders =
            scanned.flatMap { file ->
                val code = codeOf(file)
                val blockers = roundTripFunctions(code)
                mainThreadBlocks(code)
                    .filter { block ->
                        rendererRoundTrips.any { block.contains(it) } ||
                            blockers.any { block.contains("$it(") }
                    }.map { block -> "${file.name}: ${block.take(160)}" }
            }

        assertTrue(
            offenders.isEmpty(),
            "blocking renderer round trip dispatched onto the EDT: $offenders. A renderer that does " +
                "not answer parks the EDT forever and the AppKit main thread parks behind it - the " +
                "whole app and the macOS menu bar freeze. Route it through BoundedBrowserCall.",
        )
    }

    /**
     * The lexer itself, because a guard that reads its input wrong is worse than no guard.
     *
     * The fixture is the minimal shape that used to hide an offender rather than merely report a
     * confusing one: a nested-quote template holding a `}`. Under the old single-pass `skipString`
     * the quotes re-paired, that `}` was read as *code*, and it closed the dispatched block one
     * statement early - so the round trip below it fell outside the block and the file reported
     * clean. Verified red against the previous lexer before this was added.
     */
    @Test
    fun `the lexer does not desync on a nested-quote string template`() {
        val fixture = File.createTempFile("round-trip-lexer", ".kt")
        try {
            fixture.writeText(
                """
                fun b(frame: Frame) {
                    withContext(Dispatchers.Main) {
                        logger.warn("closing: ${'$'}{wrap("}")}")
                        frame.executeJavaScript<Any?>("x")
                    }
                }
                """.trimIndent(),
            )
            val blocks = mainThreadBlocks(codeOf(fixture))
            assertEquals(1, blocks.size, "the dispatched block was not found at all: $blocks")
            assertTrue(
                rendererRoundTrips.any { blocks.single().contains(it) },
                "the template truncated the dispatched block and hid the round trip in it: ${blocks.single()}",
            )
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun `no scope that makes renderer round trips is built on the EDT`() {
        val root = assertNotNull(repoRoot(), "could not locate the repository root")
        val scanned = kotlinSources(root)
        assertTrue(scanned.size > 100, "only ${scanned.size} files scanned - the walk is not seeing the source")

        val mainScopePattern = Regex("""MainScope\(\)|CoroutineScope\(.{0,160}?Dispatchers\.Main""")

        val offenders =
            scanned.flatMap { file ->
                val code = codeOf(file)
                // Only files that actually block on a renderer. Main-dispatched scopes are ordinary
                // and correct throughout the UI; they are a hazard only where a round trip can be
                // launched into one, which is how coBrowseScope and pageEventScope froze the app.
                if (rendererRoundTrips.none { code.contains(it) }) {
                    emptyList()
                } else {
                    mainScopePattern
                        .findAll(code)
                        .map { windowAround(code, it.range.first) }
                        .filterNot { window -> allowedMainScopes.any { window.contains(it) } }
                        .map { "${file.name}: …${it.trim()}…" }
                        .toList()
                }
            }

        assertTrue(
            offenders.isEmpty(),
            "EDT-dispatched scope in a file that blocks on the renderer: $offenders. Anything " +
                "launched into it makes its blocking JxBrowser calls on the EDT. Build it on " +
                "BoundedBrowserCall.dispatcher, or add the reviewed site to allowedMainScopes.",
        )
    }
}
