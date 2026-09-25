package ai.rever.boss.plugin

import ai.rever.boss.plugin.loader.PluginBundledTrust
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the deletion-safety contract of [PluginArtifactCleanup]: an uninstall deletes exactly the
 * jar paths it was handed - plus the sidecars the loader names beside those paths - and nothing
 * else.
 *
 * It runs only once a plugin is already unloaded ([PluginRemoval.remove]) or during the startup
 * sweep of retired plugins (`RetiredPlugins.sweep`), and it is the last code that touches the
 * user's plugin directory. There is no directory scan: every delete is a plain `File.delete()`
 * on a handed-in absolute path, so the hazard is not under-deleting but over-deleting - a
 * neighbour plugin's jar, a near-miss filename, a subdirectory. Every test plants decoys that
 * must survive.
 *
 * Hermetic by construction: each test works inside its own [createTempDirectory] that [cleanup]
 * removes, and `forgetRow` is always injected because its production default rewrites the
 * developer's own `installed.json` (resolved from `PluginStoreSetup.getPluginDir()`). The two
 * delete seams keep their production defaults, so what runs is the real `File.delete` path -
 * including its quiet handling of files that are already gone.
 */
class PluginArtifactCleanupTest {
    private val temps = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        temps.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): File = createTempDirectory("plugin-artifact-cleanup").toFile().also { temps += it }

    private companion object {
        const val PLUGIN = "ai.rever.boss.plugin.dynamic.probe"
    }

    /** Hooks whose two delete seams are the production defaults; only the row update is a fake. */
    private fun realHooks(forgotten: MutableList<String>): PluginArtifactCleanup.Hooks =
        PluginArtifactCleanup.Hooks(forgetRow = { forgotten += it })

    @Test
    fun `uninstall removes the jar, its three sidecars, and nothing else in the directory`() {
        val dir = tempDir()
        val jar = File(dir, "probe-1.0.0.jar").apply { writeText("jar bytes") }
        val sig = File(PluginSignatureSidecar.pathFor(jar.absolutePath)).apply { writeText("c2ln") }
        val unsignable = File(PluginSignatureSidecar.unsignablePathFor(jar.absolutePath)).apply { writeText("anchor") }
        val trust = File(PluginBundledTrust.pathFor(jar.absolutePath)).apply { writeText("digest") }
        // Decoys: a neighbour plugin with its own sidecar, near-miss names sharing the jar's
        // prefix, and a subdirectory - none of them belong to the uninstalled plugin.
        val neighbourJar = File(dir, "other-plugin-2.0.0.jar").apply { writeText("other jar") }
        val neighbourSig = File(PluginSignatureSidecar.pathFor(neighbourJar.absolutePath)).apply { writeText("c2ln") }
        val nearMissBefore = File(dir, "probe-1.0.0.jar-old.sig").apply { writeText("stale") }
        val nearMissAfter = File(dir, "probe-1.0.0.jar.sig.backup").apply { writeText("backup") }
        val nestedDir = File(dir, "snapshot").apply { mkdirs() }
        val nestedFile = File(nestedDir, "probe-1.0.0.jar.bundled-trust").apply { writeText("marker") }

        val forgotten = mutableListOf<String>()
        PluginArtifactCleanup.remove(PLUGIN, jar.absolutePath, realHooks(forgotten))

        assertFalse(jar.exists(), "the jar survived its own uninstall")
        assertFalse(
            sig.exists(),
            "the .sig sidecar survived: a reused filename then hard-fails the next load of that name",
        )
        assertFalse(unsignable.exists(), "the .nosig marker survived and keeps pinning the plugin as known-unsigned")
        assertFalse(trust.exists(), "the bundled-trust marker survived and would exempt the next JAR at this filename")
        assertTrue(neighbourJar.exists(), "deleted another plugin's jar")
        assertTrue(neighbourSig.exists(), "deleted another plugin's sidecar")
        assertTrue(nearMissBefore.exists(), "removed by prefix match rather than the exact sidecar path")
        assertTrue(nearMissAfter.exists(), "removed by prefix match rather than the exact sidecar path")
        assertTrue(nestedFile.exists(), "swept a subdirectory the uninstall never owned")
        assertEquals(listOf(PLUGIN), forgotten, "the installed.json row must be dropped exactly once")
    }

    @Test
    fun `additional jar paths go too, each with its own sidecars`() {
        val root = tempDir()
        // Two windows can each hold their own copy of a plugin; PluginRemoval passes the other
        // windows' recorded jarPaths as additionalJarPaths, and half an uninstall is a reinstall.
        val first =
            File(root, "window-a/probe.jar").apply {
                parentFile.mkdirs()
                writeText("a")
            }
        val firstSig = File(PluginSignatureSidecar.pathFor(first.absolutePath)).apply { writeText("c2ln") }
        val second =
            File(root, "window-b/probe.jar").apply {
                parentFile.mkdirs()
                writeText("b")
            }
        val secondTrust = File(PluginBundledTrust.pathFor(second.absolutePath)).apply { writeText("digest") }
        val straggler = File(root, "window-b/straggler.jar").apply { writeText("not this plugin's uninstall") }

        val forgotten = mutableListOf<String>()
        PluginArtifactCleanup.remove(
            PLUGIN,
            first.absolutePath,
            realHooks(forgotten),
            additionalJarPaths = listOf(second.absolutePath),
        )

        assertFalse(first.exists(), "the primary jar survived")
        assertFalse(firstSig.exists(), "the primary jar's sidecar survived")
        assertFalse(second.exists(), "an additional jar survived: half an uninstall is a reinstall")
        assertFalse(secondTrust.exists(), "an additional jar's trust marker survived")
        assertTrue(straggler.exists(), "removed a jar nobody asked about")
        assertEquals(listOf(PLUGIN), forgotten)
    }

    @Test
    fun `blank and duplicate additional paths are filtered, never attempted`() {
        val dir = tempDir()
        val jar = File(dir, "probe.jar").apply { writeText("jar") }
        val extra = File(dir, "probe-extra.jar").apply { writeText("extra") }

        val defaults = PluginArtifactCleanup.Hooks(forgetRow = {})
        val jarCalls = mutableListOf<String>()
        val forgotten = mutableListOf<String>()
        val hooks =
            PluginArtifactCleanup.Hooks(
                deleteJar = { path ->
                    jarCalls += path
                    defaults.deleteJar(path)
                },
                deleteSidecar = { defaults.deleteSidecar(it) },
                forgetRow = { forgotten += it },
            )

        // The duplicate is two windows sharing one file - PluginRemoval passes every window's
        // copy, so distinct() is what keeps the second attempt from reporting a fresh failure.
        // The blanks are a window with no recorded jar: a blank path aimed at the process's
        // working directory is the one path this class must never touch.
        PluginArtifactCleanup.remove(
            PLUGIN,
            jar.absolutePath,
            hooks,
            additionalJarPaths = listOf(jar.absolutePath, "", "   ", extra.absolutePath),
        )

        assertEquals(
            listOf(jar.absolutePath, extra.absolutePath),
            jarCalls,
            "each owned path must be attempted once, and only owned paths",
        )
        assertFalse(jar.exists())
        assertFalse(extra.exists())
        assertEquals(listOf(PLUGIN), forgotten)
    }

    @Test
    fun `each jar goes before its sidecars, and the row only after every jar`() {
        val dir = tempDir()
        val first = File(dir, "a.jar").apply { writeText("a") }
        val second = File(dir, "b.jar").apply { writeText("b") }

        val defaults = PluginArtifactCleanup.Hooks(forgetRow = {})
        val events = mutableListOf<String>()
        val hooks =
            PluginArtifactCleanup.Hooks(
                deleteJar = { path ->
                    events += "jar"
                    defaults.deleteJar(path)
                },
                deleteSidecar = { path ->
                    events += "sidecar"
                    defaults.deleteSidecar(path)
                },
                forgetRow = { events += "row" },
            )

        PluginArtifactCleanup.remove(
            PLUGIN,
            first.absolutePath,
            hooks,
            additionalJarPaths = listOf(second.absolutePath),
        )

        // The row must be the last thing to go: a crash above this line leaves a row pointing
        // at files that still exist, which the next launch reconciles - never the reverse, a
        // row pointing at files that are already gone.
        assertEquals(listOf("jar", "sidecar", "jar", "sidecar", "row"), events)
    }

    @Test
    fun `an already-clean uninstall is a quiet no-op that still forgets the row`() {
        val dir = tempDir()
        // The jar can vanish before the cleanup reaches it - a reconciler sweep, a manual
        // removal, a failed install that discarded it. Absence is the postcondition, not an error.
        val vanished = File(dir, "probe-1.0.0.jar")
        val bystander = File(dir, "bystander.jar").apply { writeText("mine") }

        val forgotten = mutableListOf<String>()
        PluginArtifactCleanup.remove(PLUGIN, vanished.absolutePath, realHooks(forgotten))

        assertFalse(vanished.exists())
        assertTrue(bystander.exists(), "a no-op uninstall touched a bystander")
        assertEquals(listOf(PLUGIN), forgotten, "the row has to go or the plugin is retried every launch")
    }

    @Test
    fun `a jar that vanishes mid-cleanup does not fail the uninstall`() {
        val dir = tempDir()
        val first = File(dir, "probe-1.0.0.jar").apply { writeText("a") }
        val second = File(dir, "probe-1.0.0-old.jar").apply { writeText("b") }

        val defaults = PluginArtifactCleanup.Hooks(forgetRow = {})
        val jarCalls = mutableListOf<String>()
        val forgotten = mutableListOf<String>()
        val hooks =
            PluginArtifactCleanup.Hooks(
                deleteJar = { path ->
                    // A reconciler sweep or an antivirus can beat the cleanup to a file it is
                    // about to reach: the second jar is gone before its turn, mid-run.
                    if (path == first.absolutePath) second.delete()
                    jarCalls += path
                    defaults.deleteJar(path)
                },
                deleteSidecar = { defaults.deleteSidecar(it) },
                forgetRow = { forgotten += it },
            )

        PluginArtifactCleanup.remove(
            PLUGIN,
            first.absolutePath,
            hooks,
            additionalJarPaths = listOf(second.absolutePath),
        )

        // The vanished jar is still attempted, quietly yields false, and the uninstall
        // completes: no throw, row gone, the jar that was still there is gone too.
        assertEquals(listOf(first.absolutePath, second.absolutePath), jarCalls)
        assertFalse(first.exists())
        assertEquals(listOf(PLUGIN), forgotten)
    }

    @Test
    fun `a row that cannot be forgotten does not unwind the uninstall`() {
        val dir = tempDir()
        val jar = File(dir, "probe.jar").apply { writeText("jar") }

        // installed.json sits beside real installs; a locked or corrupted file is an ordinary
        // filesystem failure, and rethrowing would report a failed uninstall after the jar is
        // already gone - the worse half of the two half-done states.
        PluginArtifactCleanup.remove(
            PLUGIN,
            jar.absolutePath,
            PluginArtifactCleanup.Hooks(forgetRow = { throw IOException("installed.json is locked") }),
        )

        assertFalse(jar.exists(), "the unlink must precede the row update, so the jar is already gone")
    }

    @Test
    fun `a non-empty directory at the jar path is refused, not recursively swept`() {
        val dir = tempDir()
        // A stale row can point at a directory - a renamed plugin dir, a bad hand-edit. The
        // default seam is a flat File.unlink, which refuses non-empty directories, and that is
        // the safe direction: this class must never escalate a wrong path into a recursive
        // sweep of whatever lives there.
        val mistaken = File(dir, "probe-1.0.0.jar").apply { mkdirs() }
        val inside =
            File(mistaken, "nested/probe-1.0.0.jar").apply {
                parentFile.mkdirs()
                writeText("do not touch")
            }

        val forgotten = mutableListOf<String>()
        PluginArtifactCleanup.remove(PLUGIN, mistaken.absolutePath, realHooks(forgotten))

        assertTrue(inside.exists(), "a non-empty directory at the jar path was swept")
        assertTrue(mistaken.isDirectory, "the directory itself was removed")
        assertEquals(listOf(PLUGIN), forgotten)
    }

    @Test
    fun `a symlinked jar unlinks the link, never the file it points to`() {
        val pluginDir = tempDir()
        val elsewhere = tempDir()
        val target = File(elsewhere, "real-probe-1.0.0.jar").apply { writeText("the only copy") }
        val link = File(pluginDir, "probe-1.0.0.jar")
        val linkCreated = runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }
        assumeTrue(linkCreated.isSuccess, "platform refuses symlink creation")

        val forgotten = mutableListOf<String>()
        PluginArtifactCleanup.remove(PLUGIN, link.absolutePath, realHooks(forgotten))

        assertFalse(
            Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS),
            "the link itself survived the uninstall",
        )
        assertTrue(target.isFile, "the unlink escaped the link and destroyed the target file")
        assertEquals("the only copy", target.readText())
        assertEquals(listOf(PLUGIN), forgotten)
    }
}
