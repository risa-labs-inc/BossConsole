package ai.rever.boss.plugin.loader

import ai.rever.boss.plugin.api.Plugin
import ai.rever.boss.plugin.api.PluginContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A plugin whose static initializer throws, which is plugin code the host runs during load.
 *
 * Declared here rather than synthesised, because it has to be a real `Plugin` implementation to
 * reach the instantiation step at all - the loader checks `isAssignableFrom` first. Only its bytes
 * are used, read off the test classpath by name, so this object is never initialized in the test
 * JVM itself.
 */
object InitThrowingPlugin : Plugin {
    override val pluginId: String = "com.example.malformed"
    override val displayName: String = "Init Throwing"

    init {
        error("this plugin's initializer throws")
    }

    override fun register(context: PluginContext) = Unit
}

/**
 * One contract, held against every shape of bad plugin: **`loadPlugin` returns, and leaves nothing
 * behind.**
 *
 * Stated as two properties, checked identically in every case below:
 *  - it comes back as `Result.failure` rather than by throwing. The signature promises a `Result`,
 *    and the install and startup-scan paths read it with `getOrElse` / `onFailure`; anything that
 *    leaves by being thrown goes straight past them. A case that breaks this does not fail by
 *    assertion here - it fails by whatever escaped, which is the plainest possible statement of it.
 *  - no classloader stays registered for the plugin id. `PluginClassLoader` extends
 *    `URLClassLoader`, so one left behind holds the JAR open, and an open handle is what stops that
 *    file being replaced on Windows. Since the install paths refuse an occupied destination, a leak
 *    here means the plugin can neither load nor be reinstalled until BOSS restarts.
 *
 * The inputs are the ones a plugin directory actually accumulates - half-written downloads, a build
 * from the wrong JDK, a hand-edited manifest, a file renamed to `.jar` - not invented exotica. Each
 * builds a real JAR and drives the real loader; nothing here is mocked, because the bug this suite
 * grew out of lived in the gap between what the code appeared to handle and what the JVM actually
 * threw.
 */
class PluginLoaderMalformedJarTest {
    @TempDir
    lateinit var tempDir: Path

    private val manifestPath = "META-INF/boss-plugin/plugin.json"
    private val pluginId = "com.example.malformed"
    private val mainClass = "com.example.malformed.Main"

    private fun manifest(
        id: String = pluginId,
        main: String = mainClass,
    ) = """
        {
          "manifestVersion": 1,
          "pluginId": "$id",
          "displayName": "Malformed Probe",
          "version": "1.0.0",
          "apiVersion": "1.0.0",
          "mainClass": "$main"
        }
        """.trimIndent()

    /** Builds a JAR from [entries] (path to bytes) and returns its absolute path. */
    private fun jarOf(
        name: String,
        entries: Map<String, ByteArray>,
    ): String {
        val jar = tempDir.resolve(name)
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            entries.forEach { (path, bytes) ->
                out.putNextEntry(JarEntry(path))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return jar.toAbsolutePath().toString()
    }

    /** A real, linkable class file: this test's own. Only what a case changes is under test. */
    private fun realClassBytes(): ByteArray {
        val resource = "/" + PluginLoaderMalformedJarTest::class.java.name.replace('.', '/') + ".class"
        return checkNotNull(PluginLoaderMalformedJarTest::class.java.getResourceAsStream(resource)) {
            "the test's own class file must be readable from the test classpath"
        }.use { it.readBytes() }
    }

    /**
     * The contract itself. Not wrapped in a try: anything thrown out of [DynamicPluginLoaderImpl]
     * fails the case by escaping, which is exactly the failure being guarded against.
     */
    private fun assertRefusedCleanly(
        jarPath: String,
        id: String = pluginId,
    ) {
        val manager = PluginClassLoaderManager()
        val loader = DynamicPluginLoaderImpl(classLoaderManager = manager)

        val result = runBlocking { loader.loadPlugin(jarPath) }

        assertTrue(result.isFailure, "must be refused as a Result, not accepted: $jarPath")
        assertFalse(
            manager.hasClassLoader(id),
            "a refused plugin must leave no classloader holding its JAR open: $jarPath",
        )
    }

    @Test
    fun `a jar with no manifest at all`() {
        assertRefusedCleanly(jarOf("no-manifest.jar", mapOf("README.txt" to "nothing here".toByteArray())))
    }

    @Test
    fun `a manifest that is not JSON`() {
        // A half-written download, or a proxy that served an HTML error page into the file.
        assertRefusedCleanly(jarOf("bad-json.jar", mapOf(manifestPath to "<html>404</html>".toByteArray())))
    }

    @Test
    fun `a manifest that is valid JSON but missing required fields`() {
        assertRefusedCleanly(jarOf("thin-json.jar", mapOf(manifestPath to """{"manifestVersion":1}""".toByteArray())))
    }

    @Test
    fun `a manifest naming a main class the jar does not contain`() {
        assertRefusedCleanly(jarOf("absent-main.jar", mapOf(manifestPath to manifest().toByteArray())))
    }

    @Test
    fun `a main class entry that is not a class file`() {
        // Truncation and byte corruption both land here: the JVM answers ClassFormatError, which
        // is a LinkageError and so is not an Exception.
        assertRefusedCleanly(
            jarOf(
                "garbage-class.jar",
                mapOf(
                    manifestPath to manifest().toByteArray(),
                    mainClass.replace('.', '/') + ".class" to ByteArray(64) { 0x7A },
                ),
            ),
        )
    }

    @Test
    fun `a main class that is a real class but does not implement Plugin`() {
        // The class links and instantiates; it simply is not a plugin. Named to match this test
        // class so its own bytes carry the right internal name.
        val own = PluginLoaderMalformedJarTest::class.java.name
        assertRefusedCleanly(
            jarOf(
                "not-a-plugin.jar",
                mapOf(
                    manifestPath to manifest(main = own).toByteArray(),
                    own.replace('.', '/') + ".class" to realClassBytes(),
                ),
            ),
        )
    }

    @Test
    fun `a file with a jar name that is not an archive`() {
        val notAJar = tempDir.resolve("not-really.jar")
        Files.write(notAJar, ByteArray(512) { 0x41 })
        assertRefusedCleanly(notAJar.toAbsolutePath().toString())
    }

    @Test
    fun `a truncated jar`() {
        // A download interrupted partway: the central directory never arrived.
        val complete = jarOf("whole.jar", mapOf(manifestPath to manifest().toByteArray()))
        val whole = Files.readAllBytes(Path.of(complete))
        val cut = tempDir.resolve("truncated.jar")
        Files.write(cut, whole.copyOf(whole.size / 2))
        assertRefusedCleanly(cut.toAbsolutePath().toString())
    }

    @Test
    fun `a plugin whose static initializer throws`() {
        // The one case nothing earlier can pre-empt. Reading a Kotlin object's INSTANCE field runs
        // its initializer - the plugin's own code, on the load thread - and the JVM wraps whatever
        // it throws in ExceptionInInitializerError, a LinkageError. BinaryCompatibilityValidator
        // cannot catch it in advance: it loads classes with initialize=false precisely so that it
        // does not execute plugin code. Unlike the class-version and corruption cases, this one is
        // reachable for every plugin whatever package it lives in.
        val name = InitThrowingPlugin::class.java.name
        val bytes =
            checkNotNull(javaClass.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
                "the throwing plugin's class file must be on the test classpath"
            }.use { it.readBytes() }

        assertRefusedCleanly(
            jarOf(
                "init-throws.jar",
                mapOf(
                    manifestPath to manifest(main = name).toByteArray(),
                    name.replace('.', '/') + ".class" to bytes,
                ),
            ),
        )
    }

    @Test
    fun `a manifest whose declared plugin id is empty`() {
        assertRefusedCleanly(
            jarOf("empty-id.jar", mapOf(manifestPath to manifest(id = "").toByteArray())),
            id = "",
        )
    }
}
