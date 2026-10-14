package ai.rever.boss.components.plugin.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two rules the picker's result channel and its filters have to keep.
 *
 * Both were handed back on #552 as shared-provider defects rather than as consequences of the
 * ownership fix, and both are invisible from the dialog itself: one produces a second answer to a
 * question asked once, the other produces a panel that lists nothing and reads as "no matching
 * files here".
 *
 * `onResult` and the filter rule are exercised through the pure helpers rather than through
 * `DesktopFilePickerProvider`, which opens a real AWT panel and blocks on the EDT. The provider
 * calls exactly these, so a revert at the call site shows up as a compile error rather than as a
 * silently unexercised test.
 */
class FilePickerDeliveryTest {
    // ------------------------------------------------------------------
    // Exactly one answer per pick.
    // ------------------------------------------------------------------

    @Test
    fun `a successful selection is delivered once`() {
        val seen = mutableListOf<String?>()
        val deliver = deliverOnce { seen.add(it) }

        deliver("/home/u/report.csv")

        assertEquals(listOf<String?>("/home/u/report.csv"), seen)
    }

    @Test
    fun `a throwing plugin callback does not get a second answer`() {
        // The defect: onResult was called inside the try that catches picker failures, so a plugin
        // whose callback threw received its path and then a null, which is what a cancel looks
        // like. One pick, two answers, and the second one wrong.
        var calls = 0
        val deliver =
            deliverOnce {
                calls++
                error("the plugin's callback blew up")
            }

        assertFailsWith<IllegalStateException> { deliver("/home/u/report.csv") }
        // What the catch block then does. It must not reach the plugin again.
        deliver(null)

        assertEquals(1, calls, "the plugin must see exactly one result for one pick")
    }

    @Test
    fun `a picker failure before any delivery still reports the cancel`() {
        // The other side of the same rule: when the picker itself throws, nothing has been
        // delivered yet, so the catch's null is the plugin's only answer and must arrive.
        val seen = mutableListOf<String?>()
        val deliver = deliverOnce { seen.add(it) }

        deliver(null)

        assertEquals(listOf<String?>(null), seen)
    }

    @Test
    fun `only the first of several answers survives`() {
        val seen = mutableListOf<String?>()
        val deliver = deliverOnce { seen.add(it) }

        deliver("/first")
        deliver("/second")
        deliver(null)

        assertEquals(listOf<String?>("/first"), seen)
    }

    // ------------------------------------------------------------------
    // Filters mean the same thing however the caller spelled them.
    // ------------------------------------------------------------------

    @Test
    fun `a leading dot is accepted and does not survive`() {
        // `.csv` used to be matched as `..csv`, so the filter hid every valid choice instead of
        // narrowing to them.
        assertEquals(listOf("csv"), normalizedExtensions(listOf(".csv")))
        assertEquals(listOf("csv"), normalizedExtensions(listOf("csv")))
        assertEquals(listOf("csv", "tsv"), normalizedExtensions(listOf(".csv", "tsv")))
    }

    @Test
    fun `surrounding whitespace and blank entries are dropped`() {
        assertEquals(listOf("csv"), normalizedExtensions(listOf("  .csv  ")))
        assertEquals(emptyList<String>(), normalizedExtensions(listOf("", "   ", ".")))
    }

    @Test
    fun `no filter and an empty filter are the same thing`() {
        assertEquals(emptyList<String>(), normalizedExtensions(null))
        assertEquals(emptyList<String>(), normalizedExtensions(emptyList()))
    }

    @Test
    fun `both spellings select the same files`() {
        val bare = normalizedExtensions(listOf("csv"))
        val dotted = normalizedExtensions(listOf(".csv"))

        assertTrue(matchesAnyExtension("report.csv", bare))
        assertTrue(matchesAnyExtension("report.csv", dotted))
        assertFalse(matchesAnyExtension("report.txt", dotted))
    }

    @Test
    fun `extension matching ignores case on both sides`() {
        val exts = normalizedExtensions(listOf(".CSV"))

        assertTrue(matchesAnyExtension("report.csv", exts))
        assertTrue(matchesAnyExtension("REPORT.CSV", exts))
    }

    @Test
    fun `an empty filter list accepts everything rather than nothing`() {
        // The callers only install a filter when there is one, so this keeps the helper and the
        // call sites in step if that ever changes: a filter matching nothing would empty the panel.
        assertTrue(matchesAnyExtension("anything.at.all", emptyList()))
    }

    @Test
    fun `a bare name without an extension does not match a filter`() {
        assertFalse(matchesAnyExtension("Makefile", normalizedExtensions(listOf("csv"))))
        assertFalse(matchesAnyExtension("csv", normalizedExtensions(listOf("csv"))))
    }
}
