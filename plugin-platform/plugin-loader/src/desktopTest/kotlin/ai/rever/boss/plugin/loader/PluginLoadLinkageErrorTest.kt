package ai.rever.boss.plugin.loader

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
 * What the loader does with a plugin the JVM refuses to link.
 *
 * Every other load failure arrives as an `Exception` and leaves through `Result.failure`. Linkage
 * failures do not: the JVM reports them as `Error`, and `Error` is not an `Exception`. The most
 * ordinary packaging mistake there is - building a plugin on a newer JDK than the host runs -
 * surfaces as `UnsupportedClassVersionError`, which is a `LinkageError`. `loadClass` is wrapped in
 * `catch (e: ClassNotFoundException)` and the whole body in `catch (e: Exception)`, so it matches
 * neither and leaves the function by being thrown.
 *
 * Two things break when it does, and this test pins both:
 *  - the caller is handed a thrown `Error` instead of the `Result` the signature promises, so every
 *    `getOrElse` / `onFailure` on the install and startup-scan paths is bypassed;
 *  - `closeClassLoader` sits in the catch blocks that were skipped, so the plugin's classloader
 *    stays registered and keeps its JAR open. On Windows that file then cannot be replaced, which
 *    is precisely the state the install paths refuse to overwrite - so the plugin can neither load
 *    nor be reinstalled without a restart.
 */
class PluginLoadLinkageErrorTest {
    @TempDir
    lateinit var tempDir: Path

    private val manifestPath = "META-INF/boss-plugin/plugin.json"
    private val pluginId = "com.example.linkageprobe"
    private val mainClass = "com.example.linkageprobe.FromTheFuture"

    /**
     * A JAR whose main class is valid in every way except its class-file version.
     *
     * Byte-patched rather than compiled, because no JDK on this machine can emit a version this
     * host cannot read - which is the whole scenario. Bytes 6-7 are the major version; 99 is far
     * enough ahead to stay wrong however new the CI JDK gets.
     */
    private fun futureVersionJar(): Path {
        val classBytes = realClassBytes().copyOf()
        classBytes[6] = 0
        classBytes[7] = 99
        val jar = tempDir.resolve("future.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(JarEntry(manifestPath))
            out.write(manifestJson().toByteArray())
            out.closeEntry()
            out.putNextEntry(JarEntry(mainClass.replace('.', '/') + ".class"))
            out.write(classBytes)
            out.closeEntry()
        }
        return jar
    }

    /** Any real, linkable class file; only its version header is under test. */
    private fun realClassBytes(): ByteArray {
        val resource = "/" + PluginLoadLinkageErrorTest::class.java.name.replace('.', '/') + ".class"
        return checkNotNull(PluginLoadLinkageErrorTest::class.java.getResourceAsStream(resource)) {
            "the test's own class file must be readable from the test classpath"
        }.use { it.readBytes() }
    }

    private fun manifestJson() =
        """
        {
          "manifestVersion": 1,
          "pluginId": "$pluginId",
          "displayName": "Linkage Probe",
          "version": "1.0.0",
          "apiVersion": "1.0.0",
          "mainClass": "$mainClass"
        }
        """.trimIndent()

    @Test
    fun `a plugin built for a newer JDK fails as a Result, not as a thrown Error`() {
        val manager = PluginClassLoaderManager()
        val loader = DynamicPluginLoaderImpl(classLoaderManager = manager)
        val jar = futureVersionJar()

        val result =
            runBlocking {
                // Deliberately NOT wrapped: if a LinkageError escapes, this test fails by error
                // rather than by assertion, which is the defect stated as plainly as it can be.
                loader.loadPlugin(jar.toAbsolutePath().toString())
            }

        val failure = result.exceptionOrNull()
        assertTrue(
            result.isFailure,
            "a plugin the JVM cannot link must come back as Result.failure, not be thrown",
        )
        // The failure must be ABOUT the linkage error, not an unrelated earlier rejection that
        // would make this test pass without ever reaching the code under test.
        assertTrue(
            generateSequence(failure) { it.cause }.any { it is LinkageError },
            "the failure must carry the LinkageError as its cause, got: $failure",
        )
        assertFalse(
            manager.hasClassLoader(pluginId),
            "the classloader must be closed on a linkage failure, or the JAR stays held open",
        )
    }
}
