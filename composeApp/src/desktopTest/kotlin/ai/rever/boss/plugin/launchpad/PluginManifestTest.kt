package ai.rever.boss.plugin.launchpad

import ai.rever.boss.components.plugin.DefaultPlugin
import ai.rever.boss.utils.ReloadResult
import ai.rever.boss.utils.SingleInstanceManager
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PluginManifestTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        val runtimeDir = File(tempDir.toFile(), "run")
        SingleInstanceManager.runtimeDirOverride = runtimeDir
        SingleInstanceManager.pluginReloadHandlerOverride = null
    }

    @AfterTest
    fun tearDown() {
        SingleInstanceManager.release()
        SingleInstanceManager.pluginReloadHandlerOverride = null
        SingleInstanceManager.runtimeDirOverride = null
    }

    @Test
    fun `reload fails when the running host has no development reload handler`() {
        assertTrue(SingleInstanceManager.acquireLock())

        val result = SingleInstanceManager.reloadDevPlugin("sample-plugin")

        assertIs<ReloadResult.Failed>(result)
        assertTrue(result.reason.contains("not available"))
    }

    @Test
    fun `HostMeta CURRENT_API_VERSION decouples from desktop app version and matches 1 dot x`() {
        val apiVersion = HostMeta.CURRENT_API_VERSION
        assertTrue(
            apiVersion.matches(Regex("""^1\.\d+\.\d+.*""")),
            "CURRENT_API_VERSION must adhere to plugin API baseline (1.x.x), got: $apiVersion",
        )
        assertNotEquals("9.5.10", apiVersion, "CURRENT_API_VERSION must not be the desktop app version (9.5.10)")
    }

    @Test
    fun `SemVerValidator isCompatible evaluates true for compatible API versions`() {
        val currentApi = HostMeta.CURRENT_API_VERSION
        assertTrue(
            SemVerValidator.isCompatible("1.0.51", currentApi),
            "1.0.51 should be compatible with host API version $currentApi",
        )
        assertTrue(
            SemVerValidator.isCompatible("1.0.88", currentApi),
            "1.0.88 should be compatible with host API version $currentApi",
        )
    }

    @Test
    fun `SemVerValidator isCompatible evaluates false for incompatible major version`() {
        val currentApi = HostMeta.CURRENT_API_VERSION
        assertFalse(
            SemVerValidator.isCompatible("2.0.0", currentApi),
            "Major version 2.0.0 must be incompatible with host API version $currentApi",
        )
    }

    @Test
    fun `pruneStagingHistory retains at most 3 latest version directories`() {
        val pluginDevDir = Files.createDirectory(tempDir.resolve("sample-plugin"))
        val timestamps = listOf(1000L, 2000L, 3000L, 4000L, 5000L)

        for (ts in timestamps) {
            val vDir = pluginDevDir.resolve("v$ts")
            Files.createDirectory(vDir)
            Files.writeString(vDir.resolve("sample-plugin.jar"), "dummy jar content")
        }

        assertEquals(5, Files.list(pluginDevDir).use { it.count() })

        DevPluginArtifacts.pruneStagingHistory(pluginDevDir.toFile(), maxVersionsToKeep = 3)

        val remaining =
            Files.list(pluginDevDir).use { stream ->
                stream.map { it.fileName.toString() }.toList().sorted()
            }
        assertEquals(3, remaining.size, "Must keep at most 3 latest versions")
        assertEquals(listOf("v3000", "v4000", "v5000"), remaining, "Must keep 3 newest timestamps")
    }

    @Test
    fun `pruneStagingHistory ignores empty and part-only directories from failed links`() {
        val pluginDevDir = Files.createDirectory(tempDir.resolve("ignore-empty-test"))
        val v1 = Files.createDirectory(pluginDevDir.resolve("v1000"))
        Files.writeString(v1.resolve("ignore-empty-test.jar"), "valid jar 1")
        val v2 = Files.createDirectory(pluginDevDir.resolve("v2000"))
        Files.writeString(v2.resolve("ignore-empty-test.jar"), "valid jar 2")

        Files.createDirectory(pluginDevDir.resolve("v3000"))
        val v4 = Files.createDirectory(pluginDevDir.resolve("v4000"))
        Files.writeString(v4.resolve("ignore-empty-test.jar.part"), "incomplete")
        val v5 = Files.createDirectory(pluginDevDir.resolve("v5000"))
        Files.writeString(v5.resolve("ignore-empty-test.jar"), "")

        DevPluginArtifacts.pruneStagingHistory(pluginDevDir.toFile(), maxVersionsToKeep = 2)

        val remaining =
            Files.list(pluginDevDir).use { stream ->
                stream.map { it.fileName.toString() }.toList().sorted()
            }
        assertEquals(listOf("v1000", "v2000"), remaining, "Valid builds must be retained despite corrupt directories")
    }

    @Test
    fun `SingleInstanceManager captures host-side exception during reload and returns structured RELOAD_FAILED`() {
        SingleInstanceManager.pluginReloadHandlerOverride = { _ ->
            throw OutOfMemoryError("Metaspace out of memory during plugin classloading")
        }

        assertTrue(SingleInstanceManager.acquireLock(), "Acquire lock for IPC test")

        val result = SingleInstanceManager.reloadDevPlugin("sample-failing-tool")
        assertIs<ReloadResult.Failed>(result)
        assertTrue(
            result.reason.contains("Metaspace out of memory"),
            "Error response must preserve host-side exception message without socket drop: ${result.reason}",
        )
        assertFalse(
            result.reason.contains("Host closed connection unexpectedly"),
            "Socket connection must remain open and return structured RELOAD_FAILED",
        )
    }

    @Test
    fun `findActiveDevJar resolves newest timestamp directory`() {
        val devRoot = Files.createDirectory(tempDir.resolve("dev-root"))
        val pluginDir = Files.createDirectory(devRoot.resolve("my-tool"))
        val timestamps = listOf(1000L, 2000L, 5000L, 3000L)

        for (ts in timestamps) {
            val vDir = Files.createDirectory(pluginDir.resolve("v$ts"))
            Files.writeString(vDir.resolve("my-tool.jar"), "content-$ts")
        }

        val activeJar = DevPluginArtifacts.findActiveDevJar("my-tool", devRoot.toFile())
        assertTrue(activeJar != null, "Active dev jar should be found")
        assertTrue(activeJar.toString().contains("v5000"), "Active dev jar must be newest timestamp (v5000)")

        val allActive = DevPluginArtifacts.findAllActiveDevJars(devRoot.toFile())
        assertEquals(1, allActive.size)
        assertEquals(activeJar, allActive.first())
    }

    @Test
    fun `SingleInstanceManager captures nested exception cause and returns root cause detail`() {
        SingleInstanceManager.pluginReloadHandlerOverride = { _ ->
            val root = ClassNotFoundException("com.example.missing.RequiredDependency")
            throw IllegalStateException("Failed to instantiate plugin class", root)
        }

        assertTrue(SingleInstanceManager.acquireLock(), "Acquire lock for IPC test")

        val result = SingleInstanceManager.reloadDevPlugin("sample-nested-fail")
        assertIs<ReloadResult.Failed>(result)
        assertTrue(
            result.reason.contains("ClassNotFoundException") &&
                result.reason.contains("RequiredDependency"),
            "Error response must unwrap to root cause: ${result.reason}",
        )
    }

    @Test
    fun `SingleInstanceManager safely handles cyclic exception causes without hanging`() {
        val cyclicError = RuntimeException("Outer cyclic error")
        val innerError = RuntimeException("Inner cyclic error", cyclicError)
        try {
            val causeField = Throwable::class.java.getDeclaredField("cause")
            causeField.isAccessible = true
            causeField.set(cyclicError, innerError)
        } catch (_: Exception) {
        }

        SingleInstanceManager.pluginReloadHandlerOverride = { _ ->
            throw cyclicError
        }

        assertTrue(SingleInstanceManager.acquireLock(), "Acquire lock for IPC test")

        val result = SingleInstanceManager.reloadDevPlugin("sample-cyclic-fail")
        assertIs<ReloadResult.Failed>(result)
        assertTrue(
            result.reason.contains("RuntimeException") && result.reason.contains("cyclic"),
            "Error response must unwrap cyclic cause safely: ${result.reason}",
        )
    }

    @Test
    fun `SingleInstanceManager transmits non-ASCII UTF-8 characters cleanly without corruption`() {
        val unicodeMessage = "Échec du chargement du module: 日本語エラー / 🚀"
        SingleInstanceManager.pluginReloadHandlerOverride = { _ ->
            throw IllegalStateException(unicodeMessage)
        }

        assertTrue(SingleInstanceManager.acquireLock(), "Acquire lock for IPC test")

        val result = SingleInstanceManager.reloadDevPlugin("sample-unicode-tool")
        assertIs<ReloadResult.Failed>(result)
        assertTrue(
            result.reason.contains("日本語エラー") && result.reason.contains("🚀"),
            "Error response must preserve UTF-8 Unicode characters cleanly: ${result.reason}",
        )
    }

    @Test
    fun `DefaultPlugin deduplicateJars prefers version-rotated dev JAR over legacy flat dev JAR`() {
        val devBase = File(tempDir.toFile(), "dev-test")
        devBase.mkdirs()

        // 1. Legacy flat dev JAR
        val flatJar = File(devBase, "my-plugin.jar")
        writeSyntheticJar(flatJar, "my-plugin")

        // 2. Version-rotated dev JAR in my-plugin/v1000/my-plugin.jar
        val versionDir = File(devBase, "my-plugin/v1000")
        versionDir.mkdirs()
        val versionJar = File(versionDir, "my-plugin.jar")
        writeSyntheticJar(versionJar, "my-plugin")

        // findActiveDevJars should find both the flat JAR and the versioned JAR
        val discovered = DefaultPlugin.findActiveDevJars(devBase)
        assertEquals(2, discovered.size)

        // deduplicateJars must drop the legacy flat JAR and retain the version-rotated JAR
        val deduplicated = DefaultPlugin.deduplicateJars(discovered)
        assertEquals(1, deduplicated.size)
        assertEquals(versionJar.absolutePath, deduplicated.single().absolutePath)
    }

    @Test
    fun `DevPluginArtifacts isDevPluginJar avoids false positives on paths containing dev username`() {
        val stagingBase = File(tempDir.toFile(), "staging-root")
        stagingBase.mkdirs()
        DevPluginArtifacts.stagingRootOverride = stagingBase

        try {
            // Path inside staging root with version folder: TRUE
            val validDevJar = File(stagingBase, "my-plugin/v1700000000/my-plugin.jar")
            validDevJar.parentFile.mkdirs()
            validDevJar.writeText("fake-jar")
            assertTrue(DevPluginArtifacts.isDevPluginJar(validDevJar))

            // Normal installed plugin path on a machine where username has 'dev': FALSE
            val installedDirWithDevUser = File(tempDir.toFile(), "Users/dev_user/AppData/Local/plugins/my-plugin.jar")
            installedDirWithDevUser.parentFile.mkdirs()
            installedDirWithDevUser.writeText("fake-jar")
            assertFalse(DevPluginArtifacts.isDevPluginJar(installedDirWithDevUser))

            // Sibling directory named 'dev' outside staging root: FALSE
            val outsideDevDir = File(tempDir.toFile(), "some/dev/folder/my-plugin.jar")
            outsideDevDir.parentFile.mkdirs()
            outsideDevDir.writeText("fake-jar")
            assertFalse(DevPluginArtifacts.isDevPluginJar(outsideDevDir))
        } finally {
            DevPluginArtifacts.stagingRootOverride = null
        }
    }

    @Test
    fun `SingleInstanceManager reloadDevPlugin returns HostOffline on stale descriptor with dead port`() {
        // Find an unused loopback TCP port by binding and immediately closing
        val deadPort = java.net.ServerSocket(0).use { it.localPort }

        // Create a fake instance descriptor file pointing to this dead port
        val runDir = File(tempDir.toFile(), "run")
        runDir.mkdirs()
        SingleInstanceManager.runtimeDirOverride = runDir

        val descriptorFile = File(runDir, "boss-instance.txt")
        val token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        descriptorFile.writeText("transport=TCP\nendpoint=$deadPort\ntoken=$token\n")

        val result = SingleInstanceManager.reloadDevPlugin("my-plugin", timeoutMs = 1000)
        assertIs<ReloadResult.HostOffline>(result, "Should report HostOffline when port connection is refused")
    }

    @Test
    fun `reloadDevPlugin reports TimedOut when the host is alive but the reload does not confirm in time`() {
        // The handler blocks past the client budget. The host still answers the
        // probe ping, which is what separates "running and busy" from "offline".
        SingleInstanceManager.pluginReloadHandlerOverride = { _ ->
            Thread.sleep(4_000)
            true
        }
        assertTrue(SingleInstanceManager.acquireLock(), "Acquire lock for IPC test")

        val result = SingleInstanceManager.reloadDevPlugin("slow-tool", timeoutMs = 1_000)
        assertIs<ReloadResult.TimedOut>(result, "A live host that does not confirm is busy, not offline")
    }

    @Test
    fun `reloadDevPlugin refuses a path-shaped plugin id before touching the channel`() {
        assertTrue(SingleInstanceManager.acquireLock())

        val result = SingleInstanceManager.reloadDevPlugin("..")
        assertIs<ReloadResult.Failed>(result)
        assertTrue(result.reason.contains("Invalid plugin id"), "Got: ${result.reason}")
    }

    private fun writeSyntheticJar(
        targetFile: File,
        pluginId: String,
    ) {
        targetFile.parentFile?.mkdirs()
        java.util.jar.JarOutputStream(java.io.FileOutputStream(targetFile)).use { out ->
            val entry = java.util.jar.JarEntry("META-INF/boss-plugin/plugin.json")
            out.putNextEntry(entry)
            val manifestJson = """{"id":"$pluginId","version":"1.0.0","displayName":"Test Plugin"}"""
            out.write(manifestJson.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            out.closeEntry()
        }
    }
}
