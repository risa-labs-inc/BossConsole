package ai.rever.boss.components.wizard.plugin

import ai.rever.boss.components.plugin.DynamicPluginInfo
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginState
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the wizard does to files on disk, and what it refuses to do.
 *
 * The skip guard fixed in this PR made one more input reachable. `disablePlugin` unregisters panels
 * and sets DISABLED but never unloads, so a user-disabled plugin keeps its id in the loader. A
 * DISABLED entry is only counted as installed while its jar exists, so once its recorded path goes
 * stale the wizard offers it again. Both install paths then moved the download onto the installed
 * artifact before `loadPlugin` could refuse the resident id, and the failure branch deleted the file
 * `installed.json` still named.
 *
 * The rule these pin is that nothing already installed is ever overwritten. An earlier attempt
 * replaced the artifact and tried to roll back on failure, which grew failure paths of its own; each
 * of those could lose the artifact the rollback existed to protect. Refusing has no such tail, and
 * it makes the cleanup unambiguous: everything at the destination afterwards belongs to this call.
 *
 * That last part only holds if the refusal covers the artifact's whole footprint and promotion is
 * all-or-nothing, which is what the partial-promotion cases below exist to pin. Promotion is two
 * file moves; refusing an occupied jar does not make two moves indivisible.
 *
 * Real files and real moves throughout, because the bug was never in the decision alone.
 */
class WizardStagedInstallTest {
    private val dir: File = Files.createTempDirectory("wizard-staged-install").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun file(
        name: String,
        bytes: String,
    ) = File(dir, name).apply { writeText(bytes) }

    private fun sidecarOf(jar: File) = File(jar.absolutePath + ".sig")

    private fun loaded(id: String) =
        Result.success(
            DynamicPluginInfo(
                manifest =
                    PluginManifest(
                        pluginId = id,
                        displayName = id,
                        version = "1.0.0",
                        apiVersion = "1.0.0",
                        mainClass = "com.example.Main",
                    ),
                jarPath = File(dir, "$id-1.0.0.jar").absolutePath,
                state = PluginState.LOADED,
                loadedAt = 0L,
                enabled = true,
            ),
        )

    private fun alreadyLoaded(id: String) = IllegalStateException("Plugin already loaded: $id")

    private fun refused(id: String): Result<DynamicPluginInfo> = Result.failure(alreadyLoaded(id))

    // -----------------------------------------------------------------
    // Nothing installed is ever overwritten.
    // -----------------------------------------------------------------

    @Test
    fun `an occupied destination is refused and its bytes are untouched`() {
        runBlocking {
            val installed = file("demo-1.0.0.jar", "the installed bytes")
            val staged = file("demo-1.0.0.jar.downloading.1", "the new bytes")
            var loaderReached = false

            val result =
                stageAndInstall(
                    downloadedFile = staged,
                    finalFile = installed,
                    pluginId = "demo",
                    isResident = { false },
                ) {
                    loaderReached = true
                    loaded("demo")
                }

            assertTrue(result.isFailure)
            assertEquals("the installed bytes", installed.readText(), "byte for byte, untouched")
            assertFalse(loaderReached, "nothing should be loaded when the destination is occupied")
            assertFalse(staged.exists(), "the download is ours, so it is cleaned up")
        }
    }

    @Test
    fun `a blocked destination sidecar leaves nothing behind and the retry succeeds`() {
        runBlocking {
            // The exact obstruction from review: the final `.sig` path is a nonempty directory, so
            // the sidecar cannot be moved onto it. Verified against the filesystem rather than
            // simulated - Files.move onto a nonempty directory throws FileSystemException.
            val installed = File(dir, "demo-1.0.0.jar")
            val obstruction = File(installed.absolutePath + ".sig")
            obstruction.mkdirs()
            File(obstruction, "occupant").writeText("someone else's file")
            val staged = file("demo-1.0.0.jar.downloading.1", "the new bytes")
            sidecarOf(staged).writeText("the new signature")
            var loaderReached = false

            val result =
                stageAndInstall(
                    downloadedFile = staged,
                    finalFile = installed,
                    pluginId = "demo",
                    isResident = { false },
                ) {
                    loaderReached = true
                    loaded("demo")
                }

            assertTrue(result.isFailure)
            assertFalse(loaderReached, "a half-promoted artifact must never reach the loader")
            assertFalse(installed.exists(), "no jar may be left at the scannable installed name")
            assertTrue(obstruction.isDirectory, "the obstruction is not ours to remove")
            assertEquals(
                "someone else's file",
                File(obstruction, "occupant").readText(),
                "nothing inside it is ours to touch either",
            )

            // And the wreckage of the first attempt does not block the second.
            obstruction.deleteRecursively()
            val retryJar = file("demo-1.0.0.jar.downloading.2", "the new bytes")
            sidecarOf(retryJar).writeText("the new signature")

            val retry =
                stageAndInstall(
                    downloadedFile = retryJar,
                    finalFile = installed,
                    pluginId = "demo",
                    isResident = { false },
                ) { loaded("demo") }

            assertTrue(retry.isSuccess, "the retry must not be refused by the first attempt's mess")
            assertEquals("the new bytes", installed.readText())
            assertEquals("the new signature", sidecarOf(installed).readText())
        }
    }

    @Test
    fun `a promotion onto an occupied path fails rather than replacing it`() {
        // The occupied-path check and the move are two operations, so the check alone cannot make
        // promotion exclusive against a second installer. This pins the half that is enforceable:
        // the move itself refuses. `atomicMoveFrom` would not - ATOMIC_MOVE is rename(2) on POSIX
        // and replaces the target silently, which is why promotion does not use it.
        val staged = file("demo-1.0.0.jar.downloading.1", "the new bytes")
        val occupiedByAnotherInstaller = file("demo-1.0.0.jar", "bytes from the other installer")

        val thrown =
            runCatching {
                SignedArtifact(staged).moveTo(SignedArtifact(occupiedByAnotherInstaller))
            }.exceptionOrNull()

        assertTrue(thrown is java.nio.file.FileAlreadyExistsException, "got ${thrown?.let { it::class }}")
        assertEquals(
            "bytes from the other installer",
            occupiedByAnotherInstaller.readText(),
            "a promotion must never silently replace bytes it did not establish were absent",
        )
    }

    @Test
    fun `an unsigned download is refused rather than promoted beside an old signature`() {
        runBlocking {
            // A jar removed by hand can leave its sidecar behind. Promoting an unsigned download
            // next to it would leave that signature asserting bytes it never vetted, which is the
            // one thing PluginSignatureSidecar's own contract says never to do - and it is worse
            // than no signature, because the loader would treat it as present and invalid.
            val installed = File(dir, "demo-1.0.0.jar")
            val stale = sidecarOf(installed).apply { writeText("a signature for bytes long gone") }
            val staged = file("demo-1.0.0.jar.downloading.1", "unsigned new bytes")
            var loaderReached = false

            val result =
                stageAndInstall(
                    downloadedFile = staged,
                    finalFile = installed,
                    pluginId = "demo",
                    isResident = { false },
                ) {
                    loaderReached = true
                    loaded("demo")
                }

            assertTrue(result.isFailure)
            assertFalse(loaderReached)
            assertFalse(installed.exists(), "the jar must not be promoted next to a foreign sidecar")
            assertEquals(
                "a signature for bytes long gone",
                stale.readText(),
                "a pre-existing sidecar is not ours to delete either",
            )
            // The refusal has to name the file the user must remove, or it is a dead end.
            assertContains(result.exceptionOrNull()?.message.orEmpty(), stale.name)
        }
    }

    @Test
    fun `a resident plugin is refused before anything moves`() {
        runBlocking {
            val installed = file("demo-1.0.0.jar", "the installed bytes")
            val staged = file("demo-1.0.0.jar.downloading.1", "the new bytes")
            var loaderReached = false

            val result =
                stageAndInstall(
                    downloadedFile = staged,
                    finalFile = installed,
                    pluginId = "demo",
                    isResident = { true },
                ) {
                    loaderReached = true
                    refused("demo")
                }

            assertTrue(result.isFailure)
            assertEquals("the installed bytes", installed.readText())
            assertFalse(loaderReached, "a refusal after the move could not be rolled back")
            assertFalse(staged.exists())
            assertContains(result.exceptionOrNull()?.message.orEmpty(), "Restart BOSS")
        }
    }

    @Test
    fun `the staged signature is cleaned up with its jar on a refusal`() {
        runBlocking {
            val staged = file("demo-1.0.0.jar.downloading.1", "the new bytes")
            val stagedSidecar = file("demo-1.0.0.jar.downloading.1.sig", "the new signature")

            stageAndInstall(
                downloadedFile = staged,
                finalFile = File(dir, "demo-1.0.0.jar"),
                pluginId = "demo",
                isResident = { true },
            ) { refused("demo") }

            assertFalse(staged.exists())
            assertFalse(stagedSidecar.exists(), "a sidecar must not outlive the jar it describes")
        }
    }

    // -----------------------------------------------------------------
    // A first install promotes the whole artifact.
    // -----------------------------------------------------------------

    @Test
    fun `the signature travels with the jar it signs`() {
        // RemotePluginRepository writes the sidecar beside the DOWNLOAD, so promotion is the moment
        // it has to move. Moving only the jar left every signed store install loading unsigned.
        runBlocking {
            val staged = file("demo-1.0.0.jar.downloading.1", "the new bytes")
            file("demo-1.0.0.jar.downloading.1.sig", "the signature")
            val finalFile = File(dir, "demo-1.0.0.jar")

            val result =
                stageAndInstall(
                    downloadedFile = staged,
                    finalFile = finalFile,
                    pluginId = "demo",
                    isResident = { false },
                ) { loaded("demo") }

            assertTrue(result.isSuccess)
            assertEquals("the new bytes", finalFile.readText())
            assertTrue(sidecarOf(finalFile).isFile, "the jar must arrive signed")
            assertEquals("the signature", sidecarOf(finalFile).readText())
            assertFalse(staged.exists())
        }
    }

    @Test
    fun `the loader is given the final path, not the staging one`() {
        runBlocking {
            val staged = file("demo-1.0.0.jar.downloading.1", "bytes")
            val finalFile = File(dir, "demo-1.0.0.jar")
            var seen: String? = null

            stageAndInstall(
                downloadedFile = staged,
                finalFile = finalFile,
                pluginId = "demo",
                isResident = { false },
            ) {
                seen = it
                // The signature has to be in place before the loader looks for it.
                assertTrue(sidecarOf(finalFile).isFile || !sidecarOf(staged).exists())
                loaded("demo")
            }

            assertEquals(finalFile.absolutePath, seen)
        }
    }

    // -----------------------------------------------------------------
    // A failure removes only what this call created.
    // -----------------------------------------------------------------

    @Test
    fun `a failed install removes the jar and its signature`() {
        runBlocking {
            val staged = file("demo-1.0.0.jar.downloading.1", "the new bytes")
            file("demo-1.0.0.jar.downloading.1.sig", "the signature")
            val finalFile = File(dir, "demo-1.0.0.jar")

            val result =
                stageAndInstall(
                    downloadedFile = staged,
                    finalFile = finalFile,
                    pluginId = "demo",
                    isResident = { false },
                ) { refused("demo") }

            assertTrue(result.isFailure)
            assertFalse(finalFile.exists(), "nothing was there before, so nothing should be now")
            assertFalse(sidecarOf(finalFile).exists(), "and no orphan signature is left behind")
        }
    }

    @Test
    fun `a loader that throws is a failure, not an escape`() {
        runBlocking {
            val staged = file("demo-1.0.0.jar.downloading.1", "bytes")
            val finalFile = File(dir, "demo-1.0.0.jar")

            val result =
                stageAndInstall(
                    downloadedFile = staged,
                    finalFile = finalFile,
                    pluginId = "demo",
                    isResident = { false },
                ) { error("the loader blew up") }

            assertTrue(result.isFailure)
            assertFalse(finalFile.exists(), "a throw must not leave an unloadable jar installed")
        }
    }

    @Test
    fun `the original loader failure is passed through rather than replaced`() {
        runBlocking {
            val result =
                stageAndInstall(
                    downloadedFile = file("demo-1.0.0.jar.downloading.1", "bytes"),
                    finalFile = File(dir, "demo-1.0.0.jar"),
                    pluginId = "demo",
                    isResident = { false },
                ) { Result.failure(IllegalStateException("a very specific loader complaint")) }

            assertEquals("a very specific loader complaint", result.exceptionOrNull()?.message)
        }
    }

    // -----------------------------------------------------------------
    // Staging names.
    // -----------------------------------------------------------------

    @Test
    fun `a staging name is unique and cannot be mistaken for a plugin`() {
        val first = stagingNameFor("demo-1.0.0.jar")
        val second = stagingNameFor("demo-1.0.0.jar")

        assertFalse(first.endsWith(".jar"), "a directory scan must not read it as an installed jar")
        assertTrue(first != second, "two installs of one plugin must not collide on a staging name")
    }

    @Test
    fun `the installed name round-trips through the staging name`() {
        val staged = File(dir, stagingNameFor("demo-1.0.0.jar"))

        assertEquals("demo-1.0.0.jar", finalFileForStaged(staged).name)
        assertEquals(dir, finalFileForStaged(staged).parentFile)
    }

    @Test
    fun `a GitHub URL yields its owner and repo, and a clone URL loses its suffix`() {
        assertEquals(
            "risa-labs-inc" to "BossConsole",
            ownerAndRepo("https://github.com/risa-labs-inc/BossConsole"),
        )
        assertEquals("o" to "r", ownerAndRepo("https://github.com/o/r.git"))
        assertEquals("o" to "r", ownerAndRepo("https://github.com/o/r/"))
        assertEquals(null, ownerAndRepo("https://example.com/o/r"))
    }
}
