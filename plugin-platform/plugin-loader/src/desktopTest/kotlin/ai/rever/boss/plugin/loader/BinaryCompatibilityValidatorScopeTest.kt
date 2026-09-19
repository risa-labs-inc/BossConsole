package ai.rever.boss.plugin.loader

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A real, resolvable class under the validated prefix; its bytes stand in for a plugin's own. */
class ValidatorScopeProbe

/**
 * The validator reads only what it validates.
 *
 * It checks the plugin's own `ai.rever.boss.plugin.*` classes against the host and deliberately
 * ignores the third-party runtime a plugin bundles, on the stated grounds that such classes "must
 * not disable the plugin". Reading every class's bytes up front contradicted that: a bundled entry
 * that could not be read threw into the JAR-level catch, which returns `isCompatible = false`, and
 * `DynamicPluginLoaderImpl` refuses the load on exactly that. A plugin could therefore be rejected
 * over bytes the validator had already decided were none of its business.
 *
 * The same change is what stops the host holding a JAR's entire uncompressed class content at once:
 * entry names are needed for every class, bytes for only the few that are validated.
 */
class BinaryCompatibilityValidatorScopeTest {
    @TempDir
    lateinit var tempDir: Path

    private val bundledName = "thirdparty/Bundled.class"

    /** The probe's real class file, so the validated class resolves the way a plugin's would. */
    private fun probeBytes(): ByteArray {
        val path = "/" + ValidatorScopeProbe::class.java.name.replace('.', '/') + ".class"
        return checkNotNull(javaClass.getResourceAsStream(path)) {
            "the probe class file must be on the test classpath"
        }.use { it.readBytes() }
    }

    /**
     * A JAR whose bundled third-party entry is corrupt at the deflate level, so its name still
     * enumerates but reading its bytes throws. The plugin's own entry is left intact.
     */
    private fun jarWithUnreadableBundledClass(): String {
        val jar = tempDir.resolve("mixed.jar")
        val payload = ByteArray(4096) { (it % 251).toByte() }
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(JarEntry(bundledName))
            out.write(payload)
            out.closeEntry()
            val ownName = ValidatorScopeProbe::class.java.name
            out.putNextEntry(JarEntry(ownName.replace('.', '/') + ".class"))
            out.write(probeBytes())
            out.closeEntry()
        }

        // Scribble inside the first entry's deflated data. A local file header is 30 bytes plus
        // the name, so this lands past the header and inside the compressed stream: the central
        // directory still lists the entry, and the failure only appears on read.
        val raw = Files.readAllBytes(jar)
        val dataStart = 30 + bundledName.length
        for (i in dataStart + 10 until dataStart + 70) raw[i] = (raw[i].toInt() xor 0x5A).toByte()
        Files.write(jar, raw)
        return jar.toAbsolutePath().toString()
    }

    @Test
    fun `an unreadable bundled class does not fail the plugin`() {
        val result =
            BinaryCompatibilityValidator.validate(
                javaClass.classLoader,
                jarWithUnreadableBundledClass(),
            )

        assertTrue(
            result.isCompatible,
            "a third-party class the validator never inspects must not refuse the plugin: ${result.errors}",
        )
        assertFalse(
            result.errors.any { it.contains("Failed to read JAR") },
            "the JAR-level read failure must not be reachable from a class that is not validated",
        )
    }

    @Test
    fun `a JAR that cannot be opened at all is still refused`() {
        // The boundary of the change: not reading every entry must not turn a genuinely unusable
        // archive into a pass.
        val notAJar = tempDir.resolve("broken.jar")
        Files.write(notAJar, ByteArray(256) { 0x41 })

        val result = BinaryCompatibilityValidator.validate(javaClass.classLoader, notAJar.toAbsolutePath().toString())

        assertFalse(result.isCompatible, "an unreadable archive is still a failure")
    }
}
