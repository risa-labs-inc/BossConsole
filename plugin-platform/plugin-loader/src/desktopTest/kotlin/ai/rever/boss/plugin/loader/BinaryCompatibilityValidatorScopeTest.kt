package ai.rever.boss.plugin.loader

import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
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
 * Delegates normally, except that loading [throwing] fails the way a sealed package or a signed-JAR
 * digest mismatch does: with a `SecurityException`, which is neither a `LinkageError` nor a
 * `ClassNotFoundException` and so escapes the validator's per-class handling.
 */
private class SealingViolationLoader(
    private val throwing: String,
    parent: ClassLoader,
) : ClassLoader(parent) {
    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> {
        if (name == throwing) throw SecurityException("sealing violation: package is sealed")
        return super.loadClass(name, resolve)
    }
}

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

    private val probeEntry = ValidatorScopeProbe::class.java.name.replace('.', '/') + ".class"

    /**
     * A JAR whose FIRST entry, [corruptName], is corrupt at the deflate level, so its name still
     * enumerates but reading its bytes throws. The entries in [intact] follow it unharmed.
     */
    private fun jarWithCorruptFirstEntry(
        fileName: String,
        corruptName: String,
        corruptPayload: ByteArray = ByteArray(4096) { (it % 251).toByte() },
        intact: List<Pair<String, ByteArray>> = emptyList(),
    ): Path {
        val jar = tempDir.resolve(fileName)
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(JarEntry(corruptName))
            out.write(corruptPayload)
            out.closeEntry()
            for ((name, bytes) in intact) {
                out.putNextEntry(JarEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }

        // Scribble inside the first entry's deflated data. A local file header is 30 bytes plus
        // the name, so this lands past the header and inside the compressed stream: the central
        // directory still lists the entry, and the failure only appears on read.
        val raw = Files.readAllBytes(jar)
        val dataStart = 30 + corruptName.length
        for (i in dataStart + 10 until dataStart + 70) raw[i] = (raw[i].toInt() xor 0x5A).toByte()
        Files.write(jar, raw)
        return jar
    }

    private fun jarWithUnreadableBundledClass(): String =
        jarWithCorruptFirstEntry("mixed.jar", bundledName, intact = listOf(probeEntry to probeBytes()))
            .toAbsolutePath()
            .toString()

    /** Validate [jar] the way the host does: through a class loader over the JAR itself. */
    private fun validateThroughJarLoader(jar: Path): BinaryCompatibilityValidator.ValidationResult =
        URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader).use { loader ->
            BinaryCompatibilityValidator.validate(loader, jar.toAbsolutePath().toString())
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
        assertTrue(
            result.errors.single().startsWith("Failed to read JAR"),
            "a JAR that never opened must still be reported as unreadable: ${result.errors}",
        )
    }

    /**
     * A failure inside the loop is a class failing to load, not the archive failing to read.
     *
     * The JAR-level catch used to wrap only the read pass; reading bytes inside the loop moved
     * `Class.forName` under it too. So a `SecurityException` from one class (a sealed package, a
     * signed-JAR digest mismatch) came back as "Failed to read JAR", pointing at the wrong thing,
     * and threw away every per-class error already found. The JAR is still refused either way.
     */
    @Test
    fun `a class load that throws mid-validation is reported against that class`() {
        val missing = "ai/rever/boss/plugin/scopeprivate/Missing.class"
        val jar = tempDir.resolve("sealed.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            // Validated first, and not resolvable anywhere, so it records an ordinary error.
            out.putNextEntry(JarEntry(missing))
            out.write(probeBytes())
            out.closeEntry()
            out.putNextEntry(JarEntry(probeEntry))
            out.write(probeBytes())
            out.closeEntry()
        }
        val loader = SealingViolationLoader(ValidatorScopeProbe::class.java.name, javaClass.classLoader)

        val result = BinaryCompatibilityValidator.validate(loader, jar.toAbsolutePath().toString())

        assertFalse(result.isCompatible, "a class that cannot be loaded still refuses the plugin")
        assertTrue(
            result.errors.any {
                it.startsWith(ValidatorScopeProbe::class.java.name) && it.contains("SecurityException")
            },
            "the failure must name the class whose load threw: ${result.errors}",
        )
        assertTrue(
            result.errors.any { it.startsWith("ai.rever.boss.plugin.scopeprivate.Missing") },
            "errors found before the throw must survive it: ${result.errors}",
        )
        assertFalse(
            result.errors.any { it.contains("Failed to read JAR") },
            "the archive was read fine; blaming it sends a reader to the wrong place: ${result.errors}",
        )
    }

    /**
     * An entry the validator cannot read is skipped when the loader resolves the name elsewhere.
     *
     * The loader would never define this JAR's copy of the name, so its bytes genuinely do not
     * matter. That is a deliberate loosening: it used to fail the whole JAR through the read pass.
     */
    @Test
    fun `a corrupt copy of a class the host already provides is skipped`() {
        val jar = jarWithCorruptFirstEntry("shadowed.jar", probeEntry)

        val result = validateThroughJarLoader(jar)

        assertTrue(result.isCompatible, "the host's copy is what loads, so this copy is not a defect: ${result.errors}")
    }

    /**
     * The other half of that loosening, and the half that matters: the plugin's OWN corrupt class
     * is still caught, now by its loader rather than by the read pass that no longer sees it.
     */
    @Test
    fun `a corrupt class only the plugin provides is still refused, against that class`() {
        val jar = jarWithCorruptFirstEntry("private.jar", "ai/rever/boss/plugin/scopeprivate/Own.class")

        val result = validateThroughJarLoader(jar)

        assertFalse(result.isCompatible, "a plugin class nothing can load must refuse the plugin")
        assertTrue(
            result.errors.any { it.startsWith("ai.rever.boss.plugin.scopeprivate.Own") },
            "the refusal must name the class: ${result.errors}",
        )
        assertFalse(
            result.errors.any { it.contains("Failed to read JAR") },
            "this is a class failing to load, not the archive failing to read: ${result.errors}",
        )
    }
}
