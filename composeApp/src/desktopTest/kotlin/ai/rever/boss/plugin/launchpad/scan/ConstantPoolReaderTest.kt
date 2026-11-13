package ai.rever.boss.plugin.launchpad.scan

import ai.rever.boss.plugin.api.PluginContext
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The class file is a stranger's input: it must end in a result or in [MalformedClassException], and nothing else. */
class ConstantPoolReaderTest {
    private val sample = ScanFixtures.bytes(ExecFixture::class.java)

    @Test
    fun `a real class reads back its name and the references it makes`() {
        val info = ConstantPoolReader.read(sample)
        assertEquals("ai/rever/boss/plugin/launchpad/scan/ExecFixture", info.name)
        assertEquals("java/lang/Object", info.superName)
        assertTrue(info.methodRefs.any { it.owner == "java/lang/Runtime" && it.name == "exec" })
        assertTrue(info.methodRefs.any { it.owner == "java/lang/ProcessBuilder" && it.name == "<init>" })
    }

    @Test
    fun `array class constants are reduced to their element type`() {
        val info = ConstantPoolReader.read(ScanFixtures.bytes(FileFixture::class.java))
        assertTrue(info.classRefs.none { it.startsWith("[") }, "${info.classRefs}")
    }

    @Test
    fun `descriptor types count as mentioned`() {
        assertEquals(listOf("java/lang/String", "java/io/File"), typesIn("(Ljava/lang/String;I[Ljava/io/File;)V"))
    }

    @Test
    fun `not a class file`() {
        assertFailsWith<MalformedClassException> { ConstantPoolReader.read(ByteArray(0)) }
        assertFailsWith<MalformedClassException> { ConstantPoolReader.read(ByteArray(64) { 7 }) }
        val zipHeader = byteArrayOf(0x50, 0x4b, 3, 4, 32, 110, 111, 116)
        assertFailsWith<MalformedClassException> { ConstantPoolReader.read(zipHeader) }
    }

    @Test
    fun `every truncation of a real class fails cleanly`() {
        for (length in sample.indices) {
            try {
                ConstantPoolReader.read(sample.copyOf(length))
            } catch (_: MalformedClassException) {
                // the only failure a caller has to handle
            }
        }
    }

    @Test
    fun `a pool that claims more entries than the file holds fails cleanly`() {
        val lying = sample.copyOf()
        ByteBuffer.wrap(lying).putShort(8, 0xFFFF.toShort())
        assertFailsWith<MalformedClassException> { ConstantPoolReader.read(lying) }
    }

    @Test
    fun `a string longer than the file fails without allocating what it claims`() {
        val bytes = ByteBuffer.allocate(64)
        bytes
            .putInt(0xCAFEBABE.toInt())
            .putShort(0)
            .putShort(61)
            .putShort(2)
        bytes.put(1).putShort(0xFFFF.toShort()) // a Utf8 entry claiming 65535 bytes in a 64-byte file
        assertFailsWith<MalformedClassException> { ConstantPoolReader.read(bytes.array()) }
    }

    @Test
    fun `an unknown constant pool tag fails cleanly`() {
        val bytes = ByteBuffer.allocate(32)
        bytes
            .putInt(0xCAFEBABE.toInt())
            .putShort(0)
            .putShort(61)
            .putShort(2)
            .put(99)
        assertFailsWith<MalformedClassException> { ConstantPoolReader.read(bytes.array()) }
    }

    @Test
    fun `random damage to a real class never escapes as another exception`() {
        val random = Random(20260920)
        repeat(3000) {
            val damaged = sample.copyOf()
            repeat(1 + random.nextInt(8)) { damaged[random.nextInt(damaged.size)] = random.nextInt().toByte() }
            try {
                ConstantPoolReader.read(damaged)
            } catch (_: MalformedClassException) {
                // fine
            }
        }
    }

    @Test
    fun `hundreds of real Kotlin classes read without one being rejected`() {
        // The host's own plugin API, compiled by the real toolchain: long and double constants, method handles,
        // invokedynamic, coroutine state machines. Hand-made fixtures alone would not catch a parser that
        // miscounts one entry kind; a real corpus does.
        val location =
            File(
                PluginContext::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        val classes: List<ByteArray> =
            if (location.isDirectory) {
                location
                    .walkTopDown()
                    .filter { it.extension == "class" }
                    .map { it.readBytes() }
                    .toList()
            } else {
                ZipFile(location).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .filter { it.name.endsWith(".class") }
                        .map { e -> zip.getInputStream(e).use { it.readBytes() } }
                        .toList()
                }
            }
        assertTrue(classes.size > 200, "only ${classes.size} classes found at $location")
        val rejected = classes.count { runCatching { ConstantPoolReader.read(it) }.isFailure }
        assertEquals(0, rejected, "the parser rejected real class files")
    }
}
