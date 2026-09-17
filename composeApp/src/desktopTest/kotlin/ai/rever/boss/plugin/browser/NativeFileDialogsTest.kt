package ai.rever.boss.plugin.browser

import com.teamdev.jxbrowser.browser.callback.BrowserCallback
import com.teamdev.jxbrowser.browser.callback.OpenFileCallback
import com.teamdev.jxbrowser.browser.callback.OpenFilesCallback
import com.teamdev.jxbrowser.browser.callback.OpenFolderCallback
import com.teamdev.jxbrowser.browser.callback.SaveAsPdfCallback
import com.teamdev.jxbrowser.browser.callback.SaveFileCallback
import com.teamdev.jxbrowser.callback.Advisable
import com.teamdev.jxbrowser.callback.Callback
import com.teamdev.jxbrowser.callback.internal.DefaultCallbacks
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins what [NativeFileDialogs] rests on.
 *
 * The panels themselves cannot be asserted here - they are native, and two of the three CI
 * legs have no display - so what is tested is everything that decides whether they are
 * reached and answered correctly, plus the pure helpers the page's input feeds.
 */
class NativeFileDialogsTest {
    /**
     * A stand-in for `Browser`, which is the `Advisable` JxBrowser actually registers into.
     */
    private class RecordingAdvisable : Advisable<BrowserCallback> {
        val callbacks = mutableMapOf<Class<out Callback>, BrowserCallback>()

        // set/remove return the *previous* callback, which is null the first time - hence
        // the nullable returns against the unannotated Java signatures.
        @Suppress("UNCHECKED_CAST")
        override fun <C : BrowserCallback> set(
            type: Class<C>,
            callback: C,
        ): C? = callbacks.put(type, callback) as C?

        @Suppress("UNCHECKED_CAST")
        override fun <C : BrowserCallback> get(type: Class<C>): Optional<C> = Optional.ofNullable(callbacks[type] as C?)

        @Suppress("UNCHECKED_CAST")
        override fun <C : BrowserCallback> remove(type: Class<C>): C? = callbacks.remove(type) as C?
    }

    /**
     * Every dialog the page can raise has to be claimed, because an unclaimed one silently
     * gets JxBrowser's Swing `JFileChooser` back and nothing else in the tree notices.
     *
     * Asserted against `registerOn` rather than `installOn`: the latter is gated on macOS, so
     * this would assert nothing on two of the three CI legs and sleep through the regression.
     */
    @Test
    fun `every page-driven file dialog is claimed`() {
        val advisable = RecordingAdvisable()

        NativeFileDialogs.registerOn(advisable)

        val expected =
            listOf(
                OpenFileCallback::class.java,
                OpenFilesCallback::class.java,
                OpenFolderCallback::class.java,
                SaveFileCallback::class.java,
                SaveAsPdfCallback::class.java,
            ).map { it.simpleName }.sorted()
        assertEquals(
            expected,
            advisable.callbacks.keys
                .map { it.simpleName }
                .sorted(),
            "a missing one falls back to the Swing chooser",
        )
    }

    /**
     * The whole fix is "set ours first and JxBrowser's view will not replace it".
     *
     * That is `DefaultCallbacks.register()` skipping any type already present - library
     * behaviour, not ours, and invisible from our own code. A JxBrowser upgrade that made
     * defaults overwrite would silently put the Swing `JFileChooser` back on every page
     * with an upload field.
     */
    @Test
    fun `a default callback never replaces one already set`() {
        val advisable = RecordingAdvisable()
        val ours = OpenFileCallback { _, action -> action.cancel() }
        advisable.set(OpenFileCallback::class.java, ours)

        val theirs = OpenFileCallback { _, action -> action.cancel() }
        val untouched = OpenFilesCallback { _, action -> action.cancel() }
        DefaultCallbacks
            .of(advisable)
            .add(theirs)
            .add(untouched)
            .build()
            .register()

        assertSame(ours, advisable.callbacks[OpenFileCallback::class.java], "ours must survive registration")
        assertSame(
            untouched,
            advisable.callbacks[OpenFilesCallback::class.java],
            "a type we did not claim must still get the default",
        )
    }

    /**
     * And the other half: a default we never claimed *is* installed, so the test above
     * cannot pass just because `register()` does nothing at all.
     */
    @Test
    fun `a default callback is installed when nothing claimed the type`() {
        val advisable = RecordingAdvisable()
        val theirs = OpenFileCallback { _, action -> action.cancel() }

        DefaultCallbacks
            .of(advisable)
            .add(theirs)
            .build()
            .register()

        assertSame(theirs, advisable.callbacks[OpenFileCallback::class.java])
    }

    /**
     * The other half of the same claim, and the one that does not need an upgrade to bite.
     *
     * `FullscreenBrowserWindow` builds and disposes a Swing view over a live browser on every
     * fullscreen enter and exit, and the Compose view is recreated on tab switches. If the
     * view's `unregister()` stripped whatever it found rather than only what it registered,
     * the first detach would take our callbacks with it and the next attach would install the
     * Swing chooser - silently, and indistinguishable from the bug being fixed.
     */
    @Test
    fun `unregistering the defaults leaves ours in place`() {
        val advisable = RecordingAdvisable()
        val ours = OpenFileCallback { _, action -> action.cancel() }
        advisable.set(OpenFileCallback::class.java, ours)

        val theirs = OpenFileCallback { _, action -> action.cancel() }
        val untouched = OpenFilesCallback { _, action -> action.cancel() }
        val defaults =
            DefaultCallbacks
                .of(advisable)
                .add(theirs)
                .add(untouched)
                .build()
        defaults.register()
        defaults.unregister()

        assertSame(ours, advisable.callbacks[OpenFileCallback::class.java], "a view detach must not strip ours")
        assertFalse(
            advisable.callbacks.containsKey(OpenFilesCallback::class.java),
            "a default it did install must still be removed, or the test proves nothing",
        )
    }

    /**
     * A page's file input stays pending until something answers, so a dialog that throws
     * must still cancel rather than wedge the input for the life of the tab.
     */
    @Test
    fun `a throw while picking still cancels`() {
        val cancels = AtomicInteger()
        val opens = AtomicInteger()
        val done = CountDownLatch(1)

        NativeFileDialogs.answerOnce<String>(
            pick = { error("dialog blew up") },
            open = { opens.incrementAndGet() },
            cancel = {
                cancels.incrementAndGet()
                done.countDown()
            },
        )

        assertTrue(done.await(5, TimeUnit.SECONDS), "the callback was never answered")
        assertEquals(1, cancels.get())
        assertEquals(0, opens.get())
    }

    /** Answering twice is the other way to break the page, so a pick answers once only. */
    @Test
    fun `a successful pick answers exactly once`() {
        val cancels = AtomicInteger()
        val opens = AtomicInteger()
        val done = CountDownLatch(1)

        NativeFileDialogs.answerOnce(
            pick = { "chosen" },
            open = {
                opens.incrementAndGet()
                done.countDown()
            },
            cancel = { cancels.incrementAndGet() },
        )

        assertTrue(done.await(5, TimeUnit.SECONDS), "the callback was never answered")
        // Drain the EDT so a second answer posted behind ours would have landed by now.
        javax.swing.SwingUtilities.invokeAndWait { }
        assertEquals(1, opens.get())
        assertEquals(0, cancels.get())
    }

    @Test
    fun `suffixes are normalised and mime types dropped`() {
        assertEquals(listOf("png", "jpeg"), fileDialogSuffixes(listOf(".png", "jpeg")))
        assertEquals(listOf("pdf"), fileDialogSuffixes(listOf(" pdf ")))
        // A MIME pattern or a bare wildcard names no suffix. The empty list reads as "no
        // filter" - a panel showing everything, rather than one where every file is greyed out.
        assertEquals(emptyList(), fileDialogSuffixes(listOf("image/*", "*", "", ".")))
    }

    /**
     * A mixed list accepts MORE than its suffixes describe, so filtering on the survivors
     * would grey out a file the page explicitly allows.
     */
    @Test
    fun `a mixed accept list produces no filter at all`() {
        assertEquals(emptyList(), fileDialogSuffixes(listOf(".png", "image/*")))
        assertEquals(emptyList(), fileDialogSuffixes(listOf("pdf", "*")))
        // Still narrowed when every token really is a suffix.
        assertEquals(listOf("png", "gif"), fileDialogSuffixes(listOf(".png", ".gif")))
    }

    @Test
    fun `suffix matching ignores case and requires the dot`() {
        val suffixes = listOf("png")
        assertTrue(matchesSuffix("Photo.PNG", suffixes))
        assertTrue(matchesSuffix("a.b.png", suffixes))
        assertFalse(matchesSuffix("png", suffixes), "a bare name is not a match")
        assertFalse(matchesSuffix("notapng", suffixes))
    }

    @Test
    fun `a save-as-pdf name keeps its extension without doubling it`() {
        // Relative and built from segments on purpose: a literal "/tmp/report" has the parent
        // "\\tmp" on the Windows CI leg, which is the separator's business, not this rule's.
        val bare = Paths.get("reports", "report")
        assertEquals("report.pdf", pathWithExtension(bare, "pdf").fileName.toString())
        val already = pathWithExtension(Paths.get("reports", "report.pdf"), "pdf")
        assertEquals("report.pdf", already.fileName.toString())
        val uppercase = pathWithExtension(Paths.get("reports", "report.PDF"), "pdf")
        assertEquals("report.PDF", uppercase.fileName.toString(), "matching is case-insensitive")
        assertEquals(bare.parent, pathWithExtension(bare, "pdf").parent, "the file must not move directory")
    }

    /**
     * The first native panel confirmed `report`, but Chromium will write `report.pdf`. If that
     * second path exists, it must become the name in a native panel before it can be returned.
     */
    @Test
    fun `an existing appended target is presented to the native panel`() {
        val selected = Paths.get("reports", "report")
        val actualTarget = Paths.get("reports", "report.pdf")
        val picks = mutableListOf<Path?>(selected, actualTarget)
        val suggestions = mutableListOf<Pair<String, String>>()

        val result =
            chooseSaveTarget(
                suggestedFileName = "initial.pdf",
                suggestedDirectory = "downloads",
                requiredExtension = "pdf",
                targetExists = { it == actualTarget },
            ) { fileName, directory ->
                suggestions += fileName to directory
                picks.removeFirstOrNull()
            }

        assertEquals(actualTarget, result)
        assertTrue(picks.isEmpty(), "the policy must stop after the confirmed target")
        assertEquals(
            listOf("initial.pdf" to "downloads", "report.pdf" to selected.parent.toString()),
            suggestions,
            "the second panel must name the file that will really be replaced",
        )
    }

    @Test
    fun `cancelling the appended-target confirmation refuses the save`() {
        val selected = Paths.get("reports", "report")
        val actualTarget = Paths.get("reports", "report.pdf")
        val picks = mutableListOf<Path?>(selected, null)

        val result =
            chooseSaveTarget(
                suggestedFileName = "report",
                suggestedDirectory = "reports",
                requiredExtension = "pdf",
                targetExists = { it == actualTarget },
            ) { _, _ -> picks.removeFirstOrNull() }

        assertEquals(null, result, "cancel must not fall through to the unconfirmed target")
        assertTrue(picks.isEmpty())
    }

    @Test
    fun `cancelling the first required-extension panel refuses the save`() {
        var probes = 0

        val result =
            chooseSaveTarget(
                suggestedFileName = "report",
                suggestedDirectory = "reports",
                requiredExtension = "pdf",
                targetExists = {
                    probes += 1
                    true
                },
            ) { _, _ -> null }

        assertEquals(null, result)
        assertEquals(0, probes, "cancellation must not manufacture or probe a target")
    }

    @Test
    fun `renaming to another colliding target can reach a third panel`() {
        val first = Paths.get("reports", "report")
        val second = Paths.get("reports", "renamed")
        val confirmed = Paths.get("reports", "final.pdf")
        val picks = mutableListOf<Path?>(first, second, confirmed)
        val collisions = setOf(pathWithExtension(first, "pdf"), pathWithExtension(second, "pdf"))

        val result =
            chooseSaveTarget(
                suggestedFileName = "report",
                suggestedDirectory = "reports",
                requiredExtension = "pdf",
                targetExists = { it in collisions },
            ) { _, _ -> picks.removeFirstOrNull() }

        assertEquals(confirmed, result)
        assertTrue(picks.isEmpty(), "the third accepted target must terminate the loop")
    }

    @Test
    fun `save-as-pdf callback requires the pdf extension`() {
        assertEquals("pdf", requiredExtensionFor(SaveAsPdfCallback::class.java))
        assertEquals(null, requiredExtensionFor(SaveFileCallback::class.java))
    }

    @Test
    fun `an indeterminate disk probe is treated as a collision`() {
        val target = Paths.get("reports", "report.pdf")

        assertTrue(targetExistsOrUnknown(target) { false })
        assertFalse(targetExistsOrUnknown(target) { true })
    }

    @Test
    fun `an already-suffixed target needs no second panel`() {
        val selected = Paths.get("reports", "report.PDF")
        var pickCount = 0

        val result =
            chooseSaveTarget(
                suggestedFileName = selected.fileName.toString(),
                suggestedDirectory = selected.parent.toString(),
                requiredExtension = "pdf",
                targetExists = { true },
            ) { _, _ ->
                pickCount += 1
                selected
            }

        assertEquals(selected, result)
        assertEquals(1, pickCount, "the first panel already confirmed this exact target")
    }

    @Test
    fun `a new appended target is returned without a redundant second panel`() {
        val selected = Paths.get("reports", "fresh-report")
        var pickCount = 0

        val result =
            chooseSaveTarget(
                suggestedFileName = selected.fileName.toString(),
                suggestedDirectory = selected.parent.toString(),
                requiredExtension = "pdf",
                targetExists = { false },
            ) { _, _ ->
                pickCount += 1
                selected
            }

        assertEquals(Paths.get("reports", "fresh-report.pdf"), result)
        assertEquals(1, pickCount)
    }

    @Test
    fun `an ordinary browser save returns the selected path without probing the disk`() {
        val selected = Paths.get("downloads", "page.html")
        var pickCount = 0

        val result =
            chooseSaveTarget(
                suggestedFileName = selected.fileName.toString(),
                suggestedDirectory = selected.parent.toString(),
                requiredExtension = null,
                targetExists = { error("ordinary saves already confirm their visible target") },
            ) { _, _ ->
                pickCount += 1
                selected
            }

        assertEquals(selected, result)
        assertEquals(1, pickCount)
    }

    @Test
    fun `a prefilled save name keeps unicode but loses any path`() {
        // FileNameSanitizer would turn this into underscores; the name field is read by a
        // person, and the path we return comes from the panel, not from this string.
        assertEquals("報告書.pdf", safePrefill("報告書.pdf"))
        assertEquals("x", safePrefill("../../x"))
        assertEquals("b.txt", safePrefill("/etc/a/b.txt"))
        assertEquals("my report.txt", safePrefill("my report.txt"), "a space is not a control character")
        assertEquals("", safePrefill(".."), "accepting \"..\" would hand back a directory")
        assertEquals("", safePrefill("."))
        assertEquals("ab.txt", safePrefill("a\u0001b.txt"), "control characters are dropped")
    }
}
