package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.loader.PluginManifestReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Pins [resolveReloadJarPath], shared by `PluginLoaderDelegateImpl.doReloadPlugin` and
 * [DynamicPluginManager.reloadPlugin].
 *
 * The bug it exists for: a reload is usually triggered BY an update that has already replaced the
 * jar, and since the new file is version-named the old path is deleted. Reloading from the loaded
 * record therefore unloaded the plugin and then failed to load it, leaving it gone until restart -
 * observed with fluck-browser 1.2.14 -> 1.2.15. It is silent because the unload half succeeds.
 */
class ReloadJarPathTest {
    private fun resolve(
        loaded: String?,
        persisted: String? = null,
        present: Set<String> = emptySet(),
        relocated: String? = null,
    ) = resolveReloadJarPath(
        candidates = ReloadJarCandidates(loadedJarPath = loaded, persistedJarPath = persisted),
        exists = { it in present },
        relocated = { relocated },
    )

    @Test
    fun `picks the newer version when the stale loaded jar could not be deleted on Windows`() {
        // Simulates the Windows bug: the updater downloaded 1.2.15 and updated the installed.json
        // record, but the JVM lock prevented deleting the old 1.2.14 jar. The resolver must still
        // pick 1.2.15 by manifest version.
        withTempDir { dir ->
            val stale =
                PluginJarTestFixtures.writeJar(dir, "fluck-browser-1.2.14.jar", "ai.rever.boss.fluck", "1.2.14")
            val update =
                PluginJarTestFixtures.writeJar(dir, "fluck-browser-1.2.15.jar", "ai.rever.boss.fluck", "1.2.15")

            val resolved =
                resolveReloadJarPath(
                    candidates =
                        ReloadJarCandidates(
                            loadedJarPath = stale.absolutePath,
                            persistedJarPath = update.absolutePath,
                        ),
                    exists = { java.io.File(it).isFile },
                    relocated = { findRelocatedPluginJar(dir, "ai.rever.boss.fluck")?.absolutePath },
                    manifestVersion = { path ->
                        PluginManifestReader.readFromJar(path).version
                    },
                )

            assertEquals(update.absolutePath, resolved)
        }
    }

    @Test
    fun `falls back to the installed record when an update deleted the loaded jar`() {
        // The exact fluck-browser failure: loaded from 1.2.14, which the 1.2.15 download removed.
        assertEquals(
            "/p/boss-plugin-fluck-browser-1.2.15.jar",
            resolve(
                loaded = "/p/boss-plugin-fluck-browser-1.2.14.jar",
                persisted = "/p/boss-plugin-fluck-browser-1.2.15.jar",
                present = setOf("/p/boss-plugin-fluck-browser-1.2.15.jar"),
            ),
        )
    }

    @Test
    fun `keeps the loaded jar when it is still there`() {
        // In-place hot reload (evolver copies over the same path) must behave exactly as before.
        assertEquals(
            "/p/tool.jar",
            resolve(loaded = "/p/tool.jar", persisted = "/p/tool.jar", present = setOf("/p/tool.jar")),
        )
    }

    @Test
    fun `prefers the loaded jar over a differing record while it exists`() {
        // A stale installed.json must not silently downgrade a plugin running fine from a newer jar.
        assertEquals(
            "/p/new.jar",
            resolve(loaded = "/p/new.jar", persisted = "/p/old.jar", present = setOf("/p/new.jar", "/p/old.jar")),
        )
    }

    @Test
    fun `re-resolves from the directory when the record has not caught up yet`() {
        // The ordering gap the record alone cannot close: a reload landing between the download and
        // the installer's addInstalledPlugin write sees TWO stale paths, and without relocation it
        // reproduces the original symptom.
        assertEquals(
            "/p/boss-plugin-fluck-browser-1.2.15.jar",
            resolve(
                loaded = "/p/boss-plugin-fluck-browser-1.2.14.jar",
                persisted = "/p/boss-plugin-fluck-browser-1.2.14.jar",
                present = emptySet(),
                relocated = "/p/boss-plugin-fluck-browser-1.2.15.jar",
            ),
        )
    }

    @Test
    fun `relocation is a last resort, not a preference`() {
        assertEquals(
            "/p/loaded.jar",
            resolve(loaded = "/p/loaded.jar", present = setOf("/p/loaded.jar"), relocated = "/p/other.jar"),
        )
    }

    @Test
    fun `returns null when nothing exists, so the caller can leave the plugin running`() {
        assertNull(resolve(loaded = "/p/gone.jar", persisted = "/p/also-gone.jar"))
    }

    @Test
    fun `resolves from the record when there is no loaded path`() {
        // Names what this pins: the RESOLVER's answer. Whether a reload can restore a plugin with
        // no state entry is a separate question - uninstallPlugin fails "Plugin not found" first,
        // so end to end that case still returns null. See DynamicPluginManager.uninstallPlugin.
        assertEquals(
            "/p/recorded.jar",
            resolve(loaded = null, persisted = "/p/recorded.jar", present = setOf("/p/recorded.jar")),
        )
    }

    @Test
    fun `returns null when there is nothing to go on`() {
        assertNull(resolve(loaded = null, persisted = null, present = setOf("/p/anything.jar")))
    }

    @Test
    fun `picks the loaded jar when it has a higher version than the persisted record`() {
        // Mirrors the stale-record case but with versioned jars: the loaded jar is newer, so the
        // resolver must prefer it even though it is not the installed record.
        withTempDir { dir ->
            val newer = PluginJarTestFixtures.writeJar(dir, "tool-1.2.15.jar", "ai.rever.boss.tool", "1.2.15")
            val older = PluginJarTestFixtures.writeJar(dir, "tool-1.2.14.jar", "ai.rever.boss.tool", "1.2.14")

            val resolved =
                resolveReloadJarPath(
                    candidates =
                        ReloadJarCandidates(
                            loadedJarPath = newer.absolutePath,
                            persistedJarPath = older.absolutePath,
                        ),
                    exists = { java.io.File(it).isFile },
                    relocated = { findRelocatedPluginJar(dir, "ai.rever.boss.tool")?.absolutePath },
                    manifestVersion = { path ->
                        PluginManifestReader.readFromJar(path).version
                    },
                )

            assertEquals(newer.absolutePath, resolved)
        }
    }

    @Test
    fun `does not consult the directory while a known candidate still exists`() {
        // A routine reload must not directory-scan at all: a stray dev build or a pinned/downgraded
        // install under the same pluginId must never win, and a download streaming onto a scannable
        // <pluginId>-<version>.jar name must not be swept mid-write.
        var relocatedCalled = false
        withTempDir { dir ->
            val loadedJar = PluginJarTestFixtures.writeJar(dir, "tool-1.0.0.jar", "ai.rever.boss.tool", "1.0.0")
            val recordedJar = PluginJarTestFixtures.writeJar(dir, "tool-1.0.1.jar", "ai.rever.boss.tool", "1.0.1")

            val resolved =
                resolveReloadJarPath(
                    candidates =
                        ReloadJarCandidates(
                            loadedJarPath = loadedJar.absolutePath,
                            persistedJarPath = recordedJar.absolutePath,
                        ),
                    exists = { java.io.File(it).isFile },
                    relocated = {
                        relocatedCalled = true
                        findRelocatedPluginJar(dir, "ai.rever.boss.tool")?.absolutePath
                    },
                    manifestVersion = { path ->
                        PluginManifestReader.readFromJar(path).version
                    },
                )

            assertEquals(recordedJar.absolutePath, resolved)
            assertFalse(relocatedCalled)
        }
    }

    @Test
    fun `keeps position order and reports the failed paths when no manifest can be read`() {
        // A manifest that throws must not promote the persisted record over the running jar, and a
        // candidate scored as if it had no version must be diagnosable instead of silently mis-scored.
        val failures = mutableListOf<String>()

        val resolved =
            resolveReloadJarPath(
                candidates =
                    ReloadJarCandidates(
                        loadedJarPath = "/p/tool.jar",
                        persistedJarPath = "/p/recorded.jar",
                    ),
                exists = { it == "/p/tool.jar" || it == "/p/recorded.jar" },
                relocated = { null },
                manifestVersion = { path -> throw IllegalStateException("corrupt manifest: $path") },
                onManifestVersionReadFailed = { failures.add(it) },
            )

        assertEquals("/p/tool.jar", resolved)
        assertEquals(listOf("/p/tool.jar", "/p/recorded.jar"), failures)
    }

    @Test
    fun `prefers the loaded jar when versions tie`() {
        // Equal versions keep the position preference, so reload behavior only changes when a known
        // candidate is strictly newer - the tie must not silently swap the running jar.
        withTempDir { dir ->
            val loadedJar = PluginJarTestFixtures.writeJar(dir, "tool-1.2.15-a.jar", "ai.rever.boss.tool", "1.2.15")
            val recordedJar = PluginJarTestFixtures.writeJar(dir, "tool-1.2.15-b.jar", "ai.rever.boss.tool", "1.2.15")

            val resolved =
                resolveReloadJarPath(
                    candidates =
                        ReloadJarCandidates(
                            loadedJarPath = loadedJar.absolutePath,
                            persistedJarPath = recordedJar.absolutePath,
                        ),
                    exists = { java.io.File(it).isFile },
                    relocated = { null },
                    manifestVersion = { path ->
                        PluginManifestReader.readFromJar(path).version
                    },
                )

            assertEquals(loadedJar.absolutePath, resolved)
        }
    }

    @Test
    fun `does not read any manifest when fewer than two known candidates survive`() {
        // The menu-driven reload path supplies only the loaded jar, and it runs on the composition
        // dispatcher: a single surviving candidate cannot be out-voted, so the resolver must skip
        // the manifest read entirely instead of paying a blocking jar open + JSON parse on the UI
        // thread (docs/THREADING.md) for a value it would discard.
        var manifestReads = 0

        val resolved =
            resolveReloadJarPath(
                candidates =
                    ReloadJarCandidates(
                        loadedJarPath = "/p/tool.jar",
                        persistedJarPath = null,
                    ),
                exists = { it == "/p/tool.jar" },
                relocated = { null },
                manifestVersion = { _ ->
                    manifestReads++
                    "1.0.0"
                },
                onManifestVersionReadFailed = {},
            )

        assertEquals("/p/tool.jar", resolved)
        assertEquals(0, manifestReads)
    }

    @Test
    fun `scores build metadata as the plain release version, not as version-less`() {
        // The manifest reader accepts `1.10.0+build.5` (relaxed semver regex), but Version.parse
        // cannot split the `+` suffix, which silently scored the RUNNING jar as version-less and
        // let a strictly older record win - a downgrade main's position order never had. Semver
        // S10 says build metadata is ignored for precedence, so it is stripped before parsing.
        val resolved =
            resolveReloadJarPath(
                candidates =
                    ReloadJarCandidates(
                        loadedJarPath = "/p/tool-running.jar",
                        persistedJarPath = "/p/tool-recorded.jar",
                    ),
                exists = { true },
                relocated = { null },
                manifestVersion = { path ->
                    if (path == "/p/tool-running.jar") "1.10.0+build.5" else "1.9.0"
                },
            )

        assertEquals("/p/tool-running.jar", resolved)
    }

    @Test
    fun `version comparison table`() {
        // Exhaustive table over the comparison the Windows-stale-jar fix introduced: multi-digit
        // components, prerelease ordering, build metadata, unreadable/missing versions, and ties.
        // Expected winner is relative to the candidates' paths: "loaded" keeps the running jar,
        // "persisted" takes the recorded one.
        data class Case(
            val name: String,
            val loaded: String?,
            val persisted: String?,
            val expected: String,
        )

        val cases =
            listOf(
                Case("multi-digit minor beats lower", "1.10.0", "1.9.0", "loaded"),
                Case("lower loses", "1.2.14", "1.2.15", "persisted"),
                Case("prerelease loses to release", "1.2.3-alpha.1", "1.2.3", "persisted"),
                Case("later prerelease wins", "1.2.3-beta.1", "1.2.3-alpha.2", "loaded"),
                Case("build metadata ignored (loaded carries it)", "1.10.0+build.5", "1.10.0", "loaded"),
                Case("build metadata ignored (persisted carries it)", "1.2.14", "1.2.14+build.9", "loaded"),
                Case("unparsable record keeps the running jar", "1.2.3", "not-a-version", "loaded"),
                Case("unparsable running jar loses to a parseable record", "1.2.x", "1.2.4", "persisted"),
                Case("both unparsable keep position order", "garbage-a", "garbage-b", "loaded"),
                Case("null versions keep position order", null, null, "loaded"),
            )

        for (case in cases) {
            val resolved =
                resolveReloadJarPath(
                    candidates =
                        ReloadJarCandidates(
                            loadedJarPath = "/p/loaded.jar",
                            persistedJarPath = "/p/persisted.jar",
                        ),
                    exists = { true },
                    relocated = { null },
                    manifestVersion = { path ->
                        when (path) {
                            "/p/loaded.jar" -> case.loaded
                            "/p/persisted.jar" -> case.persisted
                            else -> null
                        }
                    },
                )
            val expected = if (case.expected == "loaded") "/p/loaded.jar" else "/p/persisted.jar"
            assertEquals(expected, resolved, "case '${case.name}'")
        }
    }
}
