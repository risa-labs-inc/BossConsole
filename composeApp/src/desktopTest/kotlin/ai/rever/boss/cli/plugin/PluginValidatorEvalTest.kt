package ai.rever.boss.cli.plugin

import ai.rever.boss.plugin.launchpad.HostMeta
import ai.rever.boss.plugin.launchpad.PluginManifest
import ai.rever.boss.plugin.launchpad.PluginValidator
import ai.rever.boss.plugin.launchpad.launchpadJson
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PluginValidatorEvalTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `tests valid directory passes validation`() {
        val dir = File(tempDir.toFile(), "valid-dir")
        dir.mkdirs()

        val manifest =
            PluginManifest(
                pluginId = "com.example.valid-plugin",
                displayName = "Valid Plugin",
                version = "1.0.0",
                description = "A valid test plugin",
                author = "Test Author",
                apiVersion = HostMeta.CURRENT_API_VERSION,
                mainClass = "com.example.ValidPlugin",
                permissions = listOf("mcp", "terminal"),
            )
        File(dir, "plugin.json").writeText(launchpadJson.encodeToString(manifest))

        val result = PluginValidator.validate(dir)
        assertTrue(result.isValid, "Valid directory should pass validation")
        assertTrue(result.checks.all { it.passed })
    }

    @Test
    fun `tests missing plugin json fails with exact diagnostic`() {
        val emptyDir = File(tempDir.toFile(), "missing-manifest-dir")
        emptyDir.mkdirs()

        val result = PluginValidator.validate(emptyDir)
        assertFalse(result.isValid, "Directory without plugin.json must fail validation")

        val manifestCheck = result.checks.firstOrNull { it.name == "manifest-exists" }
        assertNotNull(manifestCheck)
        assertFalse(manifestCheck.passed)
        assertTrue(manifestCheck.message.contains("plugin.json not found in directory"))
    }

    @Test
    fun `tests corrupt JSON fails cleanly without uncaught exception`() {
        val corruptDir = File(tempDir.toFile(), "corrupt-json-dir")
        corruptDir.mkdirs()
        File(corruptDir, "plugin.json").writeText("{ id: 'broken', unclosed: ")

        val result = PluginValidator.validate(corruptDir)
        assertFalse(result.isValid, "Corrupt JSON must fail validation")

        val jsonCheck = result.checks.firstOrNull { it.name == "manifest-json-valid" }
        assertNotNull(jsonCheck)
        assertFalse(jsonCheck.passed)
        assertTrue(jsonCheck.message.contains("Malformed plugin.json"))
    }

    @Test
    fun `tests incompatible minApiVersion 999 fails`() {
        val incompatibleDir = File(tempDir.toFile(), "incompatible-api-dir")
        incompatibleDir.mkdirs()

        val manifest =
            PluginManifest(
                pluginId = "com.example.future-plugin",
                displayName = "Future Plugin",
                version = "1.0.0",
                description = "Needs future API",
                author = "Time Traveler",
                apiVersion = "999.0.0",
                mainClass = "com.example.FuturePlugin",
            )
        File(incompatibleDir, "plugin.json").writeText(launchpadJson.encodeToString(manifest))

        val result = PluginValidator.validate(incompatibleDir)
        assertFalse(result.isValid, "minApiVersion 999.0.0 must fail validation")

        val apiCheck = result.checks.firstOrNull { it.name == "min-api-version" }
        assertNotNull(apiCheck)
        assertFalse(apiCheck.passed)
        assertTrue(apiCheck.message.contains("incompatible or exceeds host API version"))
    }

    @Test
    fun `tests synthetic JAR with missing class entry fails bytecode verification`() {
        val jarFile = File(tempDir.toFile(), "missing-class.jar")
        val manifest =
            PluginManifest(
                pluginId = "com.example.test-jar-plugin",
                displayName = "Test Jar Plugin",
                version = "0.2.0",
                apiVersion = HostMeta.CURRENT_API_VERSION,
                mainClass = "com.example.MissingClass",
            )
        val manifestBytes = launchpadJson.encodeToString(manifest).toByteArray(StandardCharsets.UTF_8)

        // Create JAR with only plugin.json, missing bytecode entry
        createJar(jarFile, mapOf("plugin.json" to manifestBytes))

        val result = PluginValidator.validate(jarFile)
        assertFalse(result.isValid, "JAR missing bytecode entry must fail validation")

        val bytecodeCheck = result.checks.firstOrNull { it.name == "bytecode-entrypoint" }
        assertNotNull(bytecodeCheck)
        assertFalse(bytecodeCheck.passed)
        val expectedMsg = "Bytecode entry 'com/example/MissingClass.class' not found in archive"
        assertTrue(bytecodeCheck.message.contains(expectedMsg))
    }

    @Test
    fun `tests synthetic JAR with present class entry passes bytecode verification`() {
        val jarFile = File(tempDir.toFile(), "valid-class.jar")
        val manifest =
            PluginManifest(
                pluginId = "com.example.test-jar-plugin",
                displayName = "Test Jar Plugin",
                version = "0.2.0",
                apiVersion = HostMeta.CURRENT_API_VERSION,
                mainClass = ValidatorTestFixturePlugin::class.java.name,
            )
        val manifestBytes = launchpadJson.encodeToString(manifest).toByteArray(StandardCharsets.UTF_8)
        val classEntryPath = ValidatorTestFixturePlugin::class.java.name.replace('.', '/') + ".class"
        val realClassBytes =
            ValidatorTestFixturePlugin::class.java.classLoader
                .getResourceAsStream(classEntryPath)!!
                .readBytes()

        // Create JAR with plugin.json and real class bytes
        createJar(
            jarFile,
            mapOf(
                "META-INF/boss-plugin/plugin.json" to manifestBytes,
                classEntryPath to realClassBytes,
            ),
        )

        val result = PluginValidator.validate(jarFile)
        val failedMessages = result.checks.filter { !it.passed }.map { it.message }
        assertTrue(
            result.isValid,
            "JAR with present bytecode entry must pass validation: $failedMessages",
        )

        val bytecodeCheck = result.checks.firstOrNull { it.name == "bytecode-entrypoint" }
        assertNotNull(bytecodeCheck)
        assertTrue(bytecodeCheck.passed)
        assertTrue(bytecodeCheck.message.contains("verified in archive"))

        val implementsCheck = result.checks.firstOrNull { it.name == "bytecode-implements-plugin" }
        assertNotNull(implementsCheck)
        assertTrue(implementsCheck.passed)
    }

    @Test
    fun `tests synthetic JAR with class not implementing Plugin fails bytecode verification`() {
        val jarFile = File(tempDir.toFile(), "non-plugin-class.jar")
        val manifest =
            PluginManifest(
                pluginId = "com.example.non-plugin",
                displayName = "Non Plugin",
                version = "0.2.0",
                apiVersion = HostMeta.CURRENT_API_VERSION,
                mainClass = NonPluginFixture::class.java.name,
            )
        val manifestBytes = launchpadJson.encodeToString(manifest).toByteArray(StandardCharsets.UTF_8)
        val classEntryPath = NonPluginFixture::class.java.name.replace('.', '/') + ".class"
        val realClassBytes =
            NonPluginFixture::class.java.classLoader
                .getResourceAsStream(classEntryPath)!!
                .readBytes()

        createJar(
            jarFile,
            mapOf(
                "META-INF/boss-plugin/plugin.json" to manifestBytes,
                classEntryPath to realClassBytes,
            ),
        )

        val result = PluginValidator.validate(jarFile)
        assertFalse(result.isValid, "JAR with non-Plugin class must fail validation")

        val implementsCheck = result.checks.firstOrNull { it.name == "bytecode-implements-plugin" }
        assertNotNull(implementsCheck)
        assertFalse(implementsCheck.passed)
        assertTrue(implementsCheck.message.contains("must implement ai.rever.boss.plugin.api.Plugin"))
    }

    @Test
    fun `tests reverse-domain dotted IDs pass validation`() {
        val dir = File(tempDir.toFile(), "dotted-id-dir")
        dir.mkdirs()
        val manifest =
            PluginManifest(
                pluginId = "com.example.my.awesome.tool",
                displayName = "Awesome Tool",
                version = "1.0.0",
                apiVersion = HostMeta.CURRENT_API_VERSION,
                mainClass = "com.example.AwesomeTool",
            )
        File(dir, "plugin.json").writeText(launchpadJson.encodeToString(manifest))
        val result = PluginValidator.validate(dir)
        val idCheck = result.checks.firstOrNull { it.name == "id-format" }
        assertNotNull(idCheck)
        assertTrue(idCheck.passed, "com.example.my.awesome.tool must pass id-format check")
    }

    @Test
    fun `tests directory with resource manifest passes validation`() {
        val dir = File(tempDir.toFile(), "resource-manifest-dir")
        val metaInf = File(dir, "src/main/resources/META-INF/boss-plugin").apply { mkdirs() }
        val manifest =
            PluginManifest(
                pluginId = "com.example.resource.plugin",
                displayName = "Resource Plugin",
                version = "1.0.0",
                apiVersion = HostMeta.CURRENT_API_VERSION,
                mainClass = "com.example.ResourcePlugin",
            )
        File(metaInf, "plugin.json").writeText(launchpadJson.encodeToString(manifest))
        val result = PluginValidator.validate(dir)
        assertTrue(result.isValid, "Directory with resource manifest must pass validation")
    }

    @Test
    fun `tests SemVer 2 pre-release and build metadata pass validation`() {
        val semVerCases = listOf("1.0.0-alpha.1", "0.2.0-rc.3", "0.1.0+build.2026")
        for (versionStr in semVerCases) {
            val dir = File(tempDir.toFile(), "semver-${versionStr.replace(Regex("[^a-zA-Z0-9]"), "-")}")
            dir.mkdirs()
            val manifest =
                PluginManifest(
                    pluginId = "com.example.semver-plugin",
                    displayName = "SemVer Plugin",
                    version = versionStr,
                    apiVersion = HostMeta.CURRENT_API_VERSION,
                    mainClass = "com.example.ValidPlugin",
                )
            File(dir, "plugin.json").writeText(launchpadJson.encodeToString(manifest))
            val result = PluginValidator.validate(dir)
            val versionCheck = result.checks.firstOrNull { it.name == "version-format" }
            assertNotNull(versionCheck, "version-format check missing for $versionStr")
            assertTrue(versionCheck.passed, "SemVer string '$versionStr' must validate as true")
        }
    }

    @Test
    fun `tests canonical permissions vocabulary validation`() {
        val dir = File(tempDir.toFile(), "canonical-perms-dir")
        dir.mkdirs()
        val allCanonical =
            listOf(
                "network",
                "filesystem",
                "terminal",
                "browser",
                "notifications",
                "auth",
                "mcp",
                "editor",
                "clipboard",
                "settings",
                "system",
                "storage",
            )
        val manifest =
            PluginManifest(
                pluginId = "com.example.perms-plugin",
                displayName = "Permissions Plugin",
                version = "1.0.0",
                apiVersion = HostMeta.CURRENT_API_VERSION,
                mainClass = "com.example.ValidPlugin",
                permissions = allCanonical,
            )
        File(dir, "plugin.json").writeText(launchpadJson.encodeToString(manifest))

        val result = PluginValidator.validate(dir)
        val permsCheck = result.checks.firstOrNull { it.name == "permissions" }
        assertNotNull(permsCheck)
        assertTrue(permsCheck.passed, "All canonical permissions must pass")

        // Test invalid permission
        val invalidDir = File(tempDir.toFile(), "invalid-perms-dir")
        invalidDir.mkdirs()
        val invalidManifest = manifest.copy(permissions = listOf("invalid_perm_xyz"))
        File(invalidDir, "plugin.json").writeText(launchpadJson.encodeToString(invalidManifest))
        val invalidResult = PluginValidator.validate(invalidDir)
        val invalidCheck = invalidResult.checks.firstOrNull { it.name == "permissions" }
        assertNotNull(invalidCheck)
        assertFalse(invalidCheck.passed, "Unknown permission must fail")
    }

    @Test
    fun `tests dotless plugin ID fails id-format check`() {
        val dir = File(tempDir.toFile(), "dotless-id-dir")
        dir.mkdirs()
        val manifest =
            PluginManifest(
                pluginId = "dotless-plugin",
                displayName = "Dotless Plugin",
                version = "1.0.0",
                apiVersion = HostMeta.CURRENT_API_VERSION,
                mainClass = "com.example.DotlessPlugin",
            )
        File(dir, "plugin.json").writeText(launchpadJson.encodeToString(manifest))
        val result = PluginValidator.validate(dir)
        assertFalse(result.isValid, "Dotless plugin ID must fail validation")
        val idCheck = result.checks.firstOrNull { it.name == "id-format" }
        assertNotNull(idCheck)
        assertFalse(idCheck.passed, "Dotless plugin ID must fail id-format check")
        assertTrue(idCheck.message.contains("must follow reverse domain notation"))
    }

    private fun createJar(
        jarFile: File,
        entries: Map<String, ByteArray>,
    ) {
        JarOutputStream(FileOutputStream(jarFile)).use { jos ->
            for ((name, bytes) in entries) {
                val entry = JarEntry(name)
                jos.putNextEntry(entry)
                jos.write(bytes)
                jos.closeEntry()
            }
        }
    }
}

class ValidatorTestFixturePlugin : ai.rever.boss.plugin.api.Plugin {
    override val pluginId = "com.example.test-jar-plugin"
    override val displayName = "Test Jar Plugin"

    override fun register(context: ai.rever.boss.plugin.api.PluginContext) {
        // No-op for test fixture
    }
}

class NonPluginFixture {
    val someProperty = "not a plugin"
}
