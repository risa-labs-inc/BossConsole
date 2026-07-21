package ai.rever.boss.plugin.ipc

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The proxy-side re-filter that maps the kernel's raw flat scan entries back
 * to FileSystemDataProvider contract semantics. This logic compensates for
 * the kernel's name-only hidden filtering, so every rule is pinned here:
 * nested-under-hidden, nested-under-skipped, scanning inside skipped/hidden
 * roots, and the prefix-mismatch degradation.
 */
class ProviderScanFilterTest {

    private val root = "/home/user/proj"

    @Test
    fun `top-level dot entries follow the showHidden flag`() {
        assertFalse(ProviderScanFilter.isVisibleProviderEntry(root, "$root/.env", showHidden = false))
        assertTrue(ProviderScanFilter.isVisibleProviderEntry(root, "$root/.env", showHidden = true))
        assertTrue(ProviderScanFilter.isVisibleProviderEntry(root, "$root/readme.md", showHidden = false))
    }

    @Test
    fun `children of a hidden dir are hidden without the flag despite innocent names`() {
        // The kernel's walkTopDown name-filter lets /root/.git/config through;
        // the proxy filter must re-hide it.
        assertFalse(ProviderScanFilter.isVisibleProviderEntry(root, "$root/.git/config", showHidden = false))
        assertTrue(ProviderScanFilter.isVisibleProviderEntry(root, "$root/.git/config", showHidden = true))
    }

    @Test
    fun `entries under build or node_modules are always hidden`() {
        assertFalse(ProviderScanFilter.isVisibleProviderEntry(root, "$root/build", showHidden = true))
        assertFalse(ProviderScanFilter.isVisibleProviderEntry(root, "$root/build/output.jar", showHidden = true))
        assertFalse(ProviderScanFilter.isVisibleProviderEntry(root, "$root/node_modules/lodash/index.js", showHidden = false))
    }

    @Test
    fun `explicitly scanning inside build or a dot-directory still works`() {
        // Segments ABOVE the root must be ignored: a root of .../build or
        // ~/.config/... would otherwise filter everything to an empty scan.
        val buildRoot = "$root/build"
        assertTrue(ProviderScanFilter.isVisibleProviderEntry(buildRoot, "$buildRoot/output.jar", showHidden = false))

        val dotRoot = "/home/user/.config/myproj"
        assertTrue(ProviderScanFilter.isVisibleProviderEntry(dotRoot, "$dotRoot/settings.json", showHidden = false))
        assertFalse(ProviderScanFilter.isVisibleProviderEntry(dotRoot, "$dotRoot/.secret", showHidden = false))
    }

    @Test
    fun `prefix mismatch degrades to a name-only check instead of emptying the scan`() {
        // Caller path and kernel absolutePath disagree (case, normalization,
        // symlinks): the entry's own name decides, ancestors are not misread.
        val callerRoot = "/Home/User/.config/myproj" // case differs from entry paths
        assertTrue(
            ProviderScanFilter.isVisibleProviderEntry(callerRoot, "/home/user/.config/myproj/file.txt", showHidden = false)
        )
        assertFalse(
            ProviderScanFilter.isVisibleProviderEntry(callerRoot, "/home/user/.config/myproj/.secret", showHidden = false)
        )
    }

    @Test
    fun `the root itself is not a visible entry`() {
        assertFalse(ProviderScanFilter.isVisibleProviderEntry(root, root, showHidden = true))
        assertFalse(ProviderScanFilter.isVisibleProviderEntry(root, "$root/", showHidden = true))
    }

    @Test
    fun `windows separators are handled in relative segments`() {
        val winRoot = """C:\Users\dev\proj"""
        assertFalse(ProviderScanFilter.isVisibleProviderEntry(winRoot, """$winRoot\.git\config""", showHidden = false))
        assertFalse(ProviderScanFilter.isVisibleProviderEntry(winRoot, """$winRoot\build\out.jar""", showHidden = true))
        assertTrue(ProviderScanFilter.isVisibleProviderEntry(winRoot, """$winRoot\src\main.kt""", showHidden = false))
    }

    @Test
    fun `mixed separators between caller root and kernel paths still match as descendants`() {
        // Forward-slash caller root vs backslash kernel absolutePath must NOT
        // silently fall into the name-only branch: .git children stay hidden.
        val callerRoot = "C:/Users/dev/proj"
        assertFalse(
            ProviderScanFilter.isVisibleProviderEntry(callerRoot, """C:\Users\dev\proj\.git\config""", showHidden = false)
        )
        assertTrue(
            ProviderScanFilter.isVisibleProviderEntry(callerRoot, """C:\Users\dev\proj\src\main.kt""", showHidden = false)
        )
    }

    @Test
    fun `prefix requires a path-segment boundary`() {
        // "/a/.foo" must not claim "/a/.foobar/config" as its descendant and
        // mis-parse the leading segment as "bar/config".
        val root = "/a/.foo"
        // Falls into the name-only branch: "config" is visible by name.
        assertTrue(ProviderScanFilter.isVisibleProviderEntry(root, "/a/.foobar/config", showHidden = false))
        // A genuine descendant of the dot-root works (segments above root ignored).
        assertTrue(ProviderScanFilter.isVisibleProviderEntry(root, "/a/.foo/config", showHidden = false))
        // Boundary check must not misjudge sibling dirs sharing a name prefix.
        assertTrue(ProviderScanFilter.isVisibleProviderEntry("/p/src", "/p/src2/file.txt", showHidden = false))
    }

    @Test
    fun `trailing separator on the root is tolerated`() {
        assertFalse(ProviderScanFilter.isVisibleProviderEntry("$root/", "$root/.env", showHidden = false))
        assertTrue(ProviderScanFilter.isVisibleProviderEntry("$root/", "$root/readme.md", showHidden = false))
    }
}
