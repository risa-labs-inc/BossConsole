package ai.rever.boss.plugin.loader

import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Each of the plugin's own class files is capped, and the cap is checked before anything loads it.
 *
 * Zeros deflate about 1000:1, so a JAR of a few hundred KiB can carry a class file bigger than the
 * heap. The ordering is the part that matters: the plugin's own class loader reads the whole entry
 * inside `Class.forName` to define the class, so a cap checked after loading never gets the chance.
 * With the cap missing, a 573 KiB JAR holding one 576 MiB class ran a 512 MiB heap out of memory in
 * `forName`. The cap and that ordering are from BossConsole#1293.
 *
 * Every test validates through a class loader over the JAR itself, which is what
 * `DynamicPluginLoaderImpl` passes. The test's own loader cannot see these classes, so it would
 * answer ClassNotFoundException without ever reading the entry - which is how a probe of this
 * nearly passed for the wrong reason.
 */
class BinaryCompatibilityValidatorClassCapTest {
    @TempDir
    lateinit var tempDir: Path

    private val max = BinaryCompatibilityValidator.MAX_CLASS_BYTES

    /** A JAR with one entry of [size] zero bytes, deflated, under [entryName]. */
    private fun jarWith(
        entryName: String,
        size: Int,
    ): Path {
        val jar = tempDir.resolve("cap-${entryName.replace('/', '-')}-$size.jar")
        val chunk = ByteArray(CHUNK_BYTES)
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(JarEntry(entryName))
            var left = size
            while (left > 0) {
                val n = minOf(left, chunk.size)
                out.write(chunk, 0, n)
                left -= n
            }
            out.closeEntry()
        }
        return jar
    }

    private fun validateWithPluginLoader(jar: Path): BinaryCompatibilityValidator.ValidationResult =
        URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader).use { loader ->
            BinaryCompatibilityValidator.validate(loader, jar.toAbsolutePath().toString())
        }

    @Test
    fun `a plugin class past the cap is refused before its loader reads it`() {
        val jar = jarWith("ai/rever/boss/plugin/capprobe/Huge.class", max + 1)
        assertTrue(Files.size(jar) < max / 100, "the JAR itself stays small: ${Files.size(jar)} bytes")

        val result = validateWithPluginLoader(jar)

        assertFalse(result.isCompatible, "a class file past the cap must refuse the plugin")
        assertEquals(1, result.errors.size, "${result.errors}")
        assertTrue(result.errors.single().contains("larger than $max bytes"), result.errors.single())
        // Zeros are not a class file, so had the loader been reached it would have answered
        // ClassFormatError. Its absence is what shows the cap was checked first.
        assertFalse(result.errors.single().contains("ClassFormatError"), result.errors.single())
    }

    @Test
    fun `a plugin class exactly at the cap is read and reaches the loader`() {
        val result = validateWithPluginLoader(jarWith("ai/rever/boss/plugin/capprobe/AtCap.class", max))

        // Pins the boundary: the cap refuses what is larger than it, not what is equal to it.
        assertEquals(1, result.errors.size, "${result.errors}")
        assertTrue(result.errors.single().contains("ClassFormatError"), result.errors.single())
    }

    @Test
    fun `a class outside the plugin package is never read, whatever its size`() {
        // The cap applies only where bytes are read, and bytes are read only for the plugin's own
        // classes. A bundled library class past the cap is never inflated, so it cannot refuse the
        // plugin and costs nothing.
        val result = validateWithPluginLoader(jarWith("thirdparty/Huge.class", max + 1))

        assertTrue(result.isCompatible, "${result.errors}")
    }

    private companion object {
        const val CHUNK_BYTES = 1024 * 1024
    }
}
