package ai.rever.boss.cli.plugin

import ai.rever.boss.cli.createBossCLI
import ai.rever.boss.plugin.launchpad.HostMeta
import ai.rever.boss.plugin.launchpad.PluginManifest
import ai.rever.boss.plugin.launchpad.ValidationReport
import ai.rever.boss.plugin.launchpad.launchpadJson
import ai.rever.boss.utils.ReloadResult
import ai.rever.boss.utils.SingleInstanceManager
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PluginEndToEndEvalTest {
    @TempDir
    lateinit var tempDir: Path

    private val originalOut = System.out
    private val originalErr = System.err

    @BeforeTest
    fun setUp() {
        val runtimeDir = File(tempDir.toFile(), "run")
        SingleInstanceManager.runtimeDirOverride = runtimeDir
        SingleInstanceManager.pluginReloadHandlerOverride = null
        ai.rever.boss.plugin.launchpad.DevPluginArtifacts.stagingRootOverride = File(tempDir.toFile(), "dev")
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        SingleInstanceManager.release()
        SingleInstanceManager.pluginReloadHandlerOverride = null
        SingleInstanceManager.runtimeDirOverride = null
        ai.rever.boss.plugin.launchpad.DevPluginArtifacts.stagingRootOverride = null
    }

    @Test
    fun `scaffolds plugin and immediately validates with 100 percent pass score`() {
        val pluginDir = File(tempDir.toFile(), "pristine-plugin")

        // 1. Run `boss plugin init`
        val (initOut, initErr) =
            captureStreams {
                createBossCLI().parse(
                    listOf(
                        "plugin",
                        "init",
                        "pristine-plugin",
                        "--template",
                        "mcp-tool",
                        "--dir",
                        pluginDir.absolutePath,
                    ),
                )
            }
        assertTrue(initOut.contains("[✓] Plugin 'com.example.pristine-plugin' scaffolded successfully"))
        assertTrue(File(pluginDir, "plugin.json").exists())
        assertTrue(File(pluginDir, "gradlew").exists())
        assertTrue(File(pluginDir, "gradlew.bat").exists())
        assertTrue(File(pluginDir, "gradle/wrapper/gradle-wrapper.properties").exists())

        // 2. Run `boss plugin validate` (human-readable)
        val (validateOut, _) =
            captureStreams {
                createBossCLI().parse(
                    listOf("plugin", "validate", pluginDir.absolutePath),
                )
            }
        assertTrue(validateOut.contains("[✓] Validation passed"), "Expected human-readable validation success")
        assertTrue(validateOut.contains("[✓] id-format"))
        assertTrue(validateOut.contains("[✓] version-format"))
        assertTrue(validateOut.contains("[✓] min-api-version"))
        assertTrue(validateOut.contains("[✓] entrypoint-class"))

        // 3. Run `boss plugin validate --json`
        val (jsonOut, jsonErr) =
            captureStreams {
                createBossCLI().parse(
                    listOf("plugin", "validate", pluginDir.absolutePath, "--json"),
                )
            }
        assertEquals("", jsonErr, "stderr must be empty on valid JSON validation")
        val report = launchpadJson.decodeFromString<ValidationReport>(jsonOut.trim())
        assertTrue(report.success, "Pristine scaffold must have 100% valid validation report")
        assertTrue(report.checks.isNotEmpty())
        assertEquals(0, report.failures.size)
        assertEquals(report.totalChecks, report.checksPassed)
    }

    @Test
    fun `machine-readable failure contract on nonexistent directory`() {
        val nonExistentPath = File(tempDir.toFile(), "nonexistent-dir-" + System.currentTimeMillis()).absolutePath

        val (stdout, stderr) =
            captureStreams {
                val ex =
                    assertFailsWith<ProgramResult> {
                        createBossCLI().parse(
                            listOf("plugin", "validate", nonExistentPath, "--json"),
                        )
                    }
                assertEquals(1, ex.statusCode, "Nonexistent target must exit with code 1")
            }

        // Assert: stdout is valid parseable JSON containing success: false and structured check failures
        val report = launchpadJson.decodeFromString<ValidationReport>(stdout.trim())
        assertFalse(report.success, "Report success must be false")
        assertTrue(report.failures.isNotEmpty(), "Failures list must contain failure entries")
        val targetExistsFailure = report.failures.firstOrNull { it.checkName == "target-exists" }
        assertNotNull(targetExistsFailure, "target-exists failure must be reported")

        // Assert: stderr contains NO raw error text
        assertEquals("", stderr.trim(), "stderr must contain NO raw error text in --json mode")
    }

    @Test
    fun `linking unbuilt project fails with clear compiled jar requirement`() {
        val pluginDir = File(tempDir.toFile(), "unbuilt-plugin")
        createBossCLI().parse(
            listOf("plugin", "init", "unbuilt-plugin", "--dir", pluginDir.absolutePath),
        )

        val (stdout, stderr) =
            captureStreams {
                val ex =
                    assertFailsWith<ProgramResult> {
                        createBossCLI().parse(
                            listOf("plugin", "link", pluginDir.absolutePath, "--json"),
                        )
                    }
                assertEquals(1, ex.statusCode)
            }
        assertTrue(
            stdout.contains("No compiled JAR found in target directory"),
            "Must explain compiled JAR requirement",
        )
        assertEquals("", stderr.trim(), "stderr must be clean in --json mode")
    }

    @Test
    fun `links plugin when BossConsole is offline and stages cleanly with version-rotation`() {
        val pluginDir = File(tempDir.toFile(), "offline-link-plugin")
        createBossCLI().parse(
            listOf("plugin", "init", "offline-link-plugin", "--dir", pluginDir.absolutePath),
        )

        createSyntheticJar(pluginDir, "com.example.offline-link-plugin")

        val (stdout, _) =
            captureStreams {
                createBossCLI().parse(
                    listOf("plugin", "link", pluginDir.absolutePath, "--json"),
                )
            }

        val jsonResult = Json.parseToJsonElement(stdout).jsonObject
        assertEquals("staged", jsonResult["status"]?.jsonPrimitive?.content)
        assertEquals("com.example.offline-link-plugin", jsonResult["pluginId"]?.jsonPrimitive?.content)
        assertEquals(false, jsonResult["running"]?.jsonPrimitive?.boolean)

        val stagedPath = jsonResult["stagedPath"]?.jsonPrimitive?.content
        assertNotNull(stagedPath)
        val stagedFile = File(stagedPath)
        assertTrue(stagedFile.exists(), "Staged JAR must exist on disk")
        assertTrue(stagedFile.name == "com.example.offline-link-plugin.jar")

        // Verify version-rotation pattern: dev/plugins/<pluginId>/v<timestamp>/<pluginId>.jar
        val versionParent = stagedFile.parentFile
        assertTrue(versionParent.name.startsWith("v"), "Parent folder must be timestamped rotation directory")
        assertEquals("com.example.offline-link-plugin", versionParent.parentFile.name)
    }

    @Test
    fun `links plugin when BossConsole is online and live reload succeeds or fails gracefully`() {
        val pluginDir = File(tempDir.toFile(), "live-link-plugin")
        createBossCLI().parse(
            listOf("plugin", "init", "live-link-plugin", "--dir", pluginDir.absolutePath),
        )

        createSyntheticJar(pluginDir, "com.example.live-link-plugin")

        // 1. Success case: host is online and reload succeeds
        assertTrue(SingleInstanceManager.acquireLock(), "Acquire lock to simulate online BossConsole")
        var reloadDispatchedFor: String? = null
        SingleInstanceManager.pluginReloadHandlerOverride = { id ->
            reloadDispatchedFor = id
            true
        }

        val (linkOut, _) =
            captureStreams {
                createBossCLI().parse(
                    listOf("plugin", "link", pluginDir.absolutePath, "--json"),
                )
            }

        assertTrue(linkOut.contains("\"status\":\"linked_and_reloaded\""))
        assertTrue(linkOut.contains("\"running\":true"))
        assertEquals("com.example.live-link-plugin", reloadDispatchedFor)

        // 2. Failure case: host reports reload error
        SingleInstanceManager.pluginReloadHandlerOverride = { _ ->
            error("ExceptionInInitializerError on host")
        }

        val (failOut, _) =
            captureStreams {
                val ex =
                    assertFailsWith<ProgramResult> {
                        createBossCLI().parse(
                            listOf("plugin", "link", pluginDir.absolutePath, "--json"),
                        )
                    }
                assertEquals(1, ex.statusCode)
            }
        assertTrue(failOut.contains("\"status\":\"linked_reload_failed\""))
        assertTrue(failOut.contains("ExceptionInInitializerError on host"))

        // 3. Offline case: BossConsole is closed, link stages and exits cleanly with 0
        SingleInstanceManager.release()
        SingleInstanceManager.pluginReloadHandlerOverride = null

        val (offlineJsonOut, _) =
            captureStreams {
                createBossCLI().parse(
                    listOf("plugin", "link", pluginDir.absolutePath, "--json"),
                )
            }
        assertTrue(offlineJsonOut.contains("\"status\":\"staged\""))
        assertTrue(offlineJsonOut.contains("\"running\":false"))

        val (offlineHumanOut, _) =
            captureStreams {
                createBossCLI().parse(
                    listOf("plugin", "link", pluginDir.absolutePath),
                )
            }
        assertTrue(offlineHumanOut.contains("BossConsole is offline"))
    }

    @Test
    fun `tests SingleInstanceManager reloadDevPlugin handshake protocol`() {
        var reloadResultShouldSucceed = true
        SingleInstanceManager.pluginReloadHandlerOverride = { _ ->
            reloadResultShouldSucceed
        }

        assertTrue(SingleInstanceManager.acquireLock(), "Acquire lock for IPC test")

        // Test success
        val successRes = SingleInstanceManager.reloadDevPlugin("com.example.sample-tool")
        assertIs<ReloadResult.Success>(successRes)

        // Test failure with message
        reloadResultShouldSucceed = false
        val failRes = SingleInstanceManager.reloadDevPlugin("com.example.sample-tool")
        assertIs<ReloadResult.Failed>(failRes)
        assertTrue(failRes.reason.contains("Failed to reload plugin com.example.sample-tool"))
    }

    @Test
    fun `scaffolds eval-test-tool and verifies wrapper files and structure`() {
        val targetDir = File(tempDir.toFile(), "eval-test-tool")
        createBossCLI().parse(
            listOf(
                "plugin",
                "init",
                "eval-test-tool",
                "--template",
                "mcp-tool",
                "--dir",
                targetDir.absolutePath,
                "--force",
            ),
        )

        assertTrue(File(targetDir, "gradlew").exists(), "gradlew must exist")
        assertTrue(File(targetDir, "gradlew.bat").exists(), "gradlew.bat must exist")
        val wrapperProps = File(targetDir, "gradle/wrapper/gradle-wrapper.properties")
        assertTrue(wrapperProps.exists(), "gradle-wrapper.properties must exist")
        assertTrue(File(targetDir, "gradle/wrapper/gradle-wrapper.jar").exists(), "gradle-wrapper.jar must exist")
        assertTrue(File(targetDir, "src/main/kotlin/com/example/evaltesttool/EvalTestToolPlugin.kt").exists())
        assertTrue(File(targetDir, "src/test/kotlin/com/example/evaltesttool/EvalTestToolPluginTest.kt").exists())
        assertTrue(File(targetDir, "src/main/resources/META-INF/boss-plugin/plugin.json").exists())
    }

    @Test
    fun `scaffolds eval-test-tool and compiles out-of-the-box with gradlew test`() {
        val targetDir = File(tempDir.toFile(), "eval-test-tool-compile")
        targetDir.deleteRecursively()

        try {
            createBossCLI().parse(
                listOf(
                    "plugin",
                    "init",
                    "eval-test-tool",
                    "--template",
                    "mcp-tool",
                    "--dir",
                    targetDir.absolutePath,
                    "--force",
                ),
            )

            assertTrue(File(targetDir, "gradlew").exists(), "gradlew must exist")
            assertTrue(File(targetDir, "gradlew.bat").exists(), "gradlew.bat must exist")

            val isWindows = System.getProperty("os.name").lowercase().contains("windows")
            val gradlewCmd =
                if (isWindows) {
                    File(targetDir, "gradlew.bat").absolutePath
                } else {
                    "./gradlew"
                }

            val pb =
                ProcessBuilder(
                    gradlewCmd,
                    "test",
                    "--no-daemon",
                    "-Dorg.gradle.parallel=false",
                    "-Dorg.gradle.vfs.watch=false",
                ).directory(targetDir)
                    .redirectErrorStream(true)

            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            assertEquals(0, exitCode, "Scaffolded project ./gradlew test must exit with 0. Output:\n$output")
            assertTrue(output.contains("BUILD SUCCESSFUL"), "Build must succeed")
        } finally {
            targetDir.deleteRecursively()
        }
    }

    private fun createSyntheticJar(
        pluginDir: File,
        pluginId: String,
    ) {
        val libsDir = File(pluginDir, "build/libs")
        libsDir.mkdirs()
        val jarFile = File(libsDir, "$pluginId-0.1.0.jar")

        val classEntryPath = EndToEndFixturePlugin::class.java.name.replace('.', '/') + ".class"
        val realClassBytes =
            EndToEndFixturePlugin::class.java.classLoader
                .getResourceAsStream(classEntryPath)!!
                .readBytes()

        val manifest =
            PluginManifest(
                pluginId = pluginId,
                displayName = pluginId,
                version = "0.1.0",
                apiVersion = HostMeta.CURRENT_API_VERSION,
                mainClass = EndToEndFixturePlugin::class.java.name,
            )
        val manifestBytes = launchpadJson.encodeToString(manifest).toByteArray(StandardCharsets.UTF_8)

        JarOutputStream(FileOutputStream(jarFile)).use { jos ->
            val mEntry = JarEntry("META-INF/boss-plugin/plugin.json")
            jos.putNextEntry(mEntry)
            jos.write(manifestBytes)
            jos.closeEntry()

            val cEntry = JarEntry(classEntryPath)
            jos.putNextEntry(cEntry)
            jos.write(realClassBytes)
            jos.closeEntry()
        }
    }

    private fun captureStreams(block: () -> Unit): Pair<String, String> {
        val outBaos = ByteArrayOutputStream()
        val errBaos = ByteArrayOutputStream()
        val outPs = PrintStream(outBaos, true, "UTF-8")
        val errPs = PrintStream(errBaos, true, "UTF-8")
        val prevOut = System.out
        val prevErr = System.err
        try {
            System.setOut(outPs)
            System.setErr(errPs)
            block()
        } finally {
            System.setOut(prevOut)
            System.setErr(prevErr)
        }
        return Pair(outBaos.toString("UTF-8"), errBaos.toString("UTF-8"))
    }
}

class EndToEndFixturePlugin : ai.rever.boss.plugin.api.Plugin {
    override val pluginId: String = "com.example.test-fixture"
    override val displayName: String = "Test Fixture Plugin"

    override fun register(context: ai.rever.boss.plugin.api.PluginContext) {
        // No-op for test fixture
    }
}
