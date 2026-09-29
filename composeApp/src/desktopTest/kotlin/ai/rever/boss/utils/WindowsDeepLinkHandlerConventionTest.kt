package ai.rever.boss.utils

import ai.rever.boss.testsupport.repoRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Asserts that the Windows branch of [DeepLinkHandler] registers no `APP_OPEN_URI` handler.
 *
 * The JDK implements that action only in the macOS Desktop peer, so `Desktop.setOpenURIHandler`
 * throws `UnsupportedOperationException` on Windows however it is called. The call sat in
 * `setupWindowsHandler` for as long as the branch existed: the handler was never registered, no
 * link ever arrived through it, and every Windows launch logged the refusal at WARN with a stack
 * trace. Windows links arrive in `argv` instead, which `processCommandLineArgs` handles.
 *
 * A convention test because nothing else can catch the reintroduction. The mistake is
 * attractive - the macOS branch immediately above and the default branch immediately below both
 * call `setOpenURIHandler`, so adding it back to the Windows branch reads as fixing an
 * inconsistency - and it is invisible at runtime on CI: the function is private, runs from `init`
 * behind a real `os.name` read, and its only symptom is a log line on an OS no runner here uses.
 *
 * A text check, with these limits:
 * - it reads the one function by name, so moving the call into a helper the branch calls would
 *   not be seen;
 * - it brace-matches from the function's opening `{` without skipping strings or comments, so a
 *   literal brace inside the body would end the span early (there is none today);
 * - it says nothing about the macOS and default branches, which register the handler
 *   deliberately - macOS because it works there, the default branch because #437 keeps the call
 *   and logs the X11 peer's refusal at DEBUG.
 */
class WindowsDeepLinkHandlerConventionTest {
    private val source = "composeApp/src/desktopMain/kotlin/ai/rever/boss/utils/DeepLinkHandler.kt"

    @Test
    fun `the Windows branch registers no APP_OPEN_URI handler`() {
        val file = File(repoRoot(), source)
        assertTrue(file.isFile, "$source not found at ${file.absolutePath}")
        val text = file.readText()

        val signature = "private fun setupWindowsHandler() {"
        val start = text.indexOf(signature)
        if (start < 0) {
            fail("setupWindowsHandler not found in $source - if it was renamed, retarget this test")
        }

        var depth = 0
        var end = -1
        for (i in start + signature.length - 1 until text.length) {
            if (text[i] == '{') {
                depth++
            } else if (text[i] == '}') {
                depth--
                if (depth == 0) {
                    end = i
                    break
                }
            }
        }
        if (end < 0) fail("could not find the end of setupWindowsHandler in $source")

        val body = text.substring(start, end)
        if (body.contains("setOpenURIHandler")) {
            fail(
                "setupWindowsHandler calls setOpenURIHandler, which always throws " +
                    "UnsupportedOperationException on Windows: the handler is never registered and the " +
                    "refusal is logged on every launch. Windows links arrive through " +
                    "processCommandLineArgs (argv).",
            )
        }
    }
}
