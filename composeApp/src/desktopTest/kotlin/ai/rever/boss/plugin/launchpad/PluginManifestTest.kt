package ai.rever.boss.plugin.launchpad

import ai.rever.boss.components.plugin.DefaultPlugin
import ai.rever.boss.utils.ReloadResult
import ai.rever.boss.utils.SingleInstanceManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogEntry
import ai.rever.boss.utils.logging.LogLevel
import ai.rever.boss.utils.logging.LogListener
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
import kotlin.test.assertNotNull
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
    fun `pruning leaves staging in progress intact so its writer can finish`() {
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
        assertEquals(listOf("v1000", "v2000", "v3000", "v4000", "v5000"), remaining)
        Files.move(v4.resolve("ignore-empty-test.jar.part"), v4.resolve("ignore-empty-test.jar"))
        assertEquals("incomplete", Files.readString(v4.resolve("ignore-empty-test.jar")))
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
    fun `DefaultPlugin deduplicateJars prefers version-rotated dev JAR over standard installed JAR`() {
        val stagingBase = File(tempDir.toFile(), "staging-test")
        stagingBase.mkdirs()
        DevPluginArtifacts.stagingRootOverride = stagingBase

        try {
            val pluginsDir = File(tempDir.toFile(), "plugins-dir")
            pluginsDir.mkdirs()

            // 1. Standard installed JAR
            val standardJar = File(pluginsDir, "my-plugin.jar")
            writeSyntheticJar(standardJar, "my-plugin")

            // 2. Version-rotated dev JAR in staging root: my-plugin/v1000/my-plugin.jar
            val versionDir = File(stagingBase, "my-plugin/v1000")
            versionDir.mkdirs()
            val versionJar = File(versionDir, "my-plugin.jar")
            writeSyntheticJar(versionJar, "my-plugin")

            val discoveredDevJars = DefaultPlugin.findActiveDevJars(stagingBase)
            assertEquals(1, discoveredDevJars.size)
            assertEquals(versionJar.absolutePath, discoveredDevJars.single().absolutePath)

            // deduplicateJars must drop the standard JAR and retain the dev JAR candidate
            val deduplicated = DefaultPlugin.deduplicateJars(listOf(standardJar, versionJar))
            assertEquals(1, deduplicated.size)
            assertEquals(versionJar.absolutePath, deduplicated.single().absolutePath)
        } finally {
            DevPluginArtifacts.stagingRootOverride = null
        }
    }

    @Test
    fun `DefaultPlugin deduplicateJars respects isProtectedPredicate`() {
        val stagingBase = File(tempDir.toFile(), "protected-staging-root")
        stagingBase.mkdirs()
        DevPluginArtifacts.stagingRootOverride = stagingBase

        try {
            val pluginsDir = File(tempDir.toFile(), "protected-plugins-dir")
            pluginsDir.mkdirs()

            val standardJar = File(pluginsDir, "protected-plugin.jar")
            writeSyntheticJar(standardJar, "protected-plugin")
            standardJar.setLastModified(2000L)

            val versionDir = File(stagingBase, "protected-plugin/v1000")
            versionDir.mkdirs()
            val versionJar = File(versionDir, "protected-plugin.jar")
            writeSyntheticJar(versionJar, "protected-plugin")
            versionJar.setLastModified(5000L)

            // Protected dev JARs are removed before grouping, regardless of timestamps.
            val deduplicated =
                DefaultPlugin.deduplicateJars(listOf(standardJar, versionJar)) { pluginId ->
                    pluginId == "protected-plugin"
                }
            assertEquals(1, deduplicated.size)
            assertEquals(standardJar.absolutePath, deduplicated.single().absolutePath)
        } finally {
            DevPluginArtifacts.stagingRootOverride = null
        }
    }

    @Test
    fun `DefaultPlugin deduplicateJars drops lone dev JAR claiming protected system plugin`() {
        val stagingBase = File(tempDir.toFile(), "lone-protected-staging-root")
        stagingBase.mkdirs()
        DevPluginArtifacts.stagingRootOverride = stagingBase

        try {
            val versionDir = File(stagingBase, "protected-system-plugin/v1000")
            versionDir.mkdirs()
            val versionJar = File(versionDir, "protected-system-plugin.jar")
            writeSyntheticJar(versionJar, "protected-system-plugin")
            versionJar.setLastModified(5000L)

            // A lone dev JAR claiming a protected plugin ID must be dropped immediately before grouping
            val deduplicated =
                DefaultPlugin.deduplicateJars(listOf(versionJar)) { pluginId ->
                    pluginId == "protected-system-plugin"
                }
            assertTrue(deduplicated.isEmpty(), "Lone dev JAR claiming protected plugin ID must be dropped")
        } finally {
            DevPluginArtifacts.stagingRootOverride = null
        }
    }

    @Test
    fun `DefaultPlugin deduplicateJars logs diagnostic messages when dropping or superseding JARs`() {
        val stagingBase = File(tempDir.toFile(), "logging-staging-root")
        stagingBase.mkdirs()
        DevPluginArtifacts.stagingRootOverride = stagingBase

        val logs = java.util.concurrent.CopyOnWriteArrayList<LogEntry>()
        val listener = LogListener { entry -> logs.add(entry) }
        val previousLevel = BossLogger.globalLevel
        BossLogger.setGlobalLevel(LogLevel.TRACE)
        BossLogger.addListener(listener)

        try {
            val pluginsDir = File(tempDir.toFile(), "logging-plugins-dir")
            pluginsDir.mkdirs()

            // 1. Test dropping protected dev JAR
            val protectedDevJar = File(stagingBase, "protected-plugin/v1000/protected-plugin.jar")
            writeSyntheticJar(protectedDevJar, "protected-plugin")

            val deduplicatedProtected =
                DefaultPlugin.deduplicateJars(listOf(protectedDevJar)) { id ->
                    id == "protected-plugin"
                }
            assertTrue(deduplicatedProtected.isEmpty())

            val dropWarn =
                logs.firstOrNull { entry ->
                    entry.level == LogLevel.WARN &&
                        entry.category == LogCategory.SYSTEM &&
                        entry.message.contains("Dropped dev JAR claiming protected plugin ID: protected-plugin")
                }
            assertNotNull(dropWarn, "Must log warning when dropping dev JAR claiming protected plugin ID")
            assertEquals("protected-plugin", dropWarn.data?.get("pluginId"))

            // 2. Test superseding duplicate JAR
            logs.clear()
            val standardJar = File(pluginsDir, "sample-plugin.jar")
            writeSyntheticJar(standardJar, "sample-plugin")

            val devJar = File(stagingBase, "sample-plugin/v1000/sample-plugin.jar")
            writeSyntheticJar(devJar, "sample-plugin")

            val deduplicatedSample = DefaultPlugin.deduplicateJars(listOf(standardJar, devJar))
            assertEquals(1, deduplicatedSample.size)
            assertEquals(devJar.absolutePath, deduplicatedSample.single().absolutePath)

            val dedupInfo =
                logs.firstOrNull { entry ->
                    entry.level == LogLevel.INFO &&
                        entry.category == LogCategory.SYSTEM &&
                        entry.message.contains("Deduplicating plugin 'sample-plugin'")
                }
            assertNotNull(dedupInfo, "Must log info when deduplicating multiple JARs for a plugin")
            assertEquals("sample-plugin", dedupInfo.data?.get("pluginId"))
            assertEquals(devJar.absolutePath, dedupInfo.data?.get("selected"))
            assertEquals(standardJar.absolutePath, dedupInfo.data?.get("dropped"))
        } finally {
            BossLogger.removeListener(listener)
            BossLogger.setGlobalLevel(previousLevel)
            DevPluginArtifacts.stagingRootOverride = null
        }
    }

    @Test
    fun `extractPluginIdFromManifestText reads pluginId with deps preceding it`() {
        val manifestWithDepsFirst =
            """
            {
              "manifestVersion": 1,
              "dependencies": [
                {
                  "id": "ai.rever.boss.gateway",
                  "version": "1.0.0"
                }
              ],
              "pluginId": "com.example.actual-plugin"
            }
            """.trimIndent()

        val extracted = DevPluginArtifacts.extractPluginIdFromManifestText(manifestWithDepsFirst)
        assertEquals("com.example.actual-plugin", extracted)

        val jarFile = File(tempDir.toFile(), "deps-first.jar")
        jarFile.parentFile?.mkdirs()
        java.util.jar.JarOutputStream(java.io.FileOutputStream(jarFile)).use { out ->
            val entry = java.util.jar.JarEntry("META-INF/boss-plugin/plugin.json")
            out.putNextEntry(entry)
            out.write(manifestWithDepsFirst.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            out.closeEntry()
        }

        assertTrue(DevPluginArtifacts.isValidDevJar(jarFile, "com.example.actual-plugin"))
        assertFalse(DevPluginArtifacts.isValidDevJar(jarFile, "ai.rever.boss.gateway"))
        assertEquals("com.example.actual-plugin", DefaultPlugin.extractPluginId(jarFile))
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
