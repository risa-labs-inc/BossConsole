package ai.rever.boss.utils

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The CLI shims and [DeepLinkHandler] must agree on query-parameter names.
 *
 * `boss <command>` is not one program. The shipped shims (`scripts/boss`, `scripts/boss.ps1`,
 * `scripts/boss.bat`, installed to the user's PATH by `CLIInstaller`) turn a command into a
 * `boss://` URL and hand it to the OS, and `DeepLinkHandler` reads it back. Nothing compiled
 * them together, so nothing noticed when they disagreed: all three shims emitted
 * `boss://workspace?config=`, `handleWorkspaceLink` read only `path`, and `boss workspace`
 * did nothing on every platform but log "Missing 'path' parameter". The in-app CLI
 * (`BossCommand.kt`) was the only emitter that got it right, which is why it never showed up
 * in a test.
 *
 * Parsed out of the sources rather than duplicated here, in the same shape as
 * `OsOpenArgumentsTest`'s subcommand pin: a copy of the table would drift the same way the
 * shims did.
 */
class CliShimDeepLinkContractTest {
    private val repoRoot: File by lazy {
        assertNotNull(
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "composeApp/build.gradle.kts").isFile },
            "could not locate the repository root",
        )
    }

    private fun source(relative: String): File =
        File(repoRoot, relative).also {
            assertTrue(it.isFile, "$relative not found at ${it.absolutePath}")
        }

    /** Every `boss://<host>?<param>=` a file emits, as host to the set of parameter names. */
    private fun emissionsIn(relative: String): Map<String, Set<String>> =
        Regex("""boss://([a-z]+)\?([a-zA-Z_]+)=""")
            .findAll(source(relative).readText())
            .groupBy({ it.groupValues[1] }, { it.groupValues[2] })
            .mapValues { (_, names) -> names.toSet() }

    private val shims = listOf("scripts/boss", "scripts/boss.ps1", "scripts/boss.bat")
    private val inAppCli = "composeApp/src/desktopMain/kotlin/ai/rever/boss/cli/BossCommand.kt"
    private val handler = "composeApp/src/desktopMain/kotlin/ai/rever/boss/utils/DeepLinkHandler.kt"

    @Test
    fun `every parameter a shim emits is one the deep-link handler reads`() {
        val read =
            Regex("""params\["([a-zA-Z_]+)"]""")
                .findAll(source(handler).readText())
                .map { it.groupValues[1] }
                .toSet()
        assertTrue(read.isNotEmpty(), "no parameter reads parsed out of DeepLinkHandler.kt")

        shims.forEach { shim ->
            val emitted = emissionsIn(shim).values.flatten().toSet()
            assertTrue(emitted.isNotEmpty(), "no boss:// emissions parsed out of $shim")
            val unread = emitted - read
            assertEquals(
                emptySet(),
                unread,
                "$shim emits $unread, which DeepLinkHandler never reads, so those commands do nothing",
            )
        }
    }

    @Test
    fun `all three shims emit the same parameter names for the same host`() {
        val perShim = shims.associateWith { emissionsIn(it) }
        val hosts = perShim.values.flatMap { it.keys }.toSet()

        hosts.forEach { host ->
            val byShim = perShim.mapNotNull { (shim, m) -> m[host]?.let { shim to it } }
            val distinct = byShim.map { it.second }.distinct()
            assertEquals(
                1,
                distinct.size,
                "shims disagree on boss://$host parameters: " +
                    byShim.joinToString { "${it.first}=${it.second}" },
            )
        }
    }

    @Test
    fun `the shims and the in-app CLI emit the same parameter names for the same host`() {
        // BossCommand.kt is what `boss` runs when BOSS is already the running process; the
        // shims are what runs when it is not. A user cannot tell which one served their
        // command, so the two must be indistinguishable.
        val app = emissionsIn(inAppCli)
        assertTrue(app.isNotEmpty(), "no boss:// emissions parsed out of BossCommand.kt")

        shims.forEach { shim ->
            emissionsIn(shim).forEach { (host, shimParams) ->
                app[host]?.let { appParams ->
                    assertEquals(
                        appParams,
                        shimParams,
                        "$shim and the in-app CLI disagree on boss://$host",
                    )
                }
            }
        }
    }

    @Test
    fun `the workspace link carries a path, which is the case that was broken`() {
        // Named explicitly so a regression reads as itself rather than as a set difference.
        (shims + inAppCli).forEach { file ->
            assertEquals(
                setOf("path"),
                emissionsIn(file)["workspace"],
                "$file must emit boss://workspace?path=",
            )
        }
    }
}
