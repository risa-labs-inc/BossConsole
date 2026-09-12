package ai.rever.boss.cli

import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * RIGHTWARDS ARROW, which neither Cp1252 nor US-ASCII can encode. Built from its code point to keep
 * this file ASCII.
 */
private val ARROW = Char(0x2192).toString()

/** An arrow, and LATIN SMALL LETTER E WITH ACUTE, which Cp1252 and UTF-8 encode differently. */
internal val ENCODING_PROBE_TEXT = "arrow $ARROW e-acute ${Char(0xE9)}"

class HeadlessOutputEncodingTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `only a stream the JVM did not mark as a console is switched to UTF-8`() {
        assertTrue(writesUtf8(null))
        assertTrue(writesUtf8(""))
        assertFalse(writesUtf8("Cp437"))
        assertFalse(writesUtf8("UTF-8"))
    }

    @Test
    fun `a redirected stream is replaced with UTF-8 and a console stream is left as it is`() {
        val originalOut = System.out
        val originalErr = System.err
        val captured = ByteArrayOutputStream()
        try {
            configureHeadlessOutputEncoding(
                stdoutConsoleEncoding = null,
                stderrConsoleEncoding = "Cp437",
                stdout = { captured },
                stderr = { error("a console stream must not be replaced") },
            )
            System.out.print(ARROW)

            assertContentEquals(ARROW.toByteArray(StandardCharsets.UTF_8), captured.toByteArray())
            assertSame(originalErr, System.err)
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }

    @Test
    fun `piped CLI output is UTF-8 even when the JVM default charset cannot encode it`() {
        val output = runProbe(configure = true)

        assertEquals(ENCODING_PROBE_TEXT, String(output, StandardCharsets.UTF_8).trim())
    }

    @Test
    fun `without the fix the same piped output loses the text, so the test above can fail`() {
        val output = runProbe(configure = false)

        assertFalse(String(output, StandardCharsets.UTF_8).contains(ENCODING_PROBE_TEXT))
        assertTrue(String(output, StandardCharsets.US_ASCII).contains("arrow ? e-acute ?"))
    }

    /**
     * Runs [HeadlessOutputEncodingProbe] in a child JVM whose stdout is a pipe and whose default charset is
     * US-ASCII, so the result does not depend on the platform running the test. Launched the way
     * [HeadlessLoggingTest] launches its probe, for the same Windows command-line limit.
     */
    private fun runProbe(configure: Boolean): ByteArray {
        val urls =
            generateSequence(javaClass.classLoader) { it.parent }
                .filterIsInstance<URLClassLoader>()
                .flatMap { it.getURLs().asSequence() }
                .map { File(it.toURI()).path }
                .toList()
        val classpath =
            (urls + System.getProperty("java.class.path").split(File.pathSeparator))
                .distinct()
                .joinToString(File.pathSeparator)
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val argumentsFile = tempDir.resolve("encoding probe.args")
        val quotedClasspath = classpath.replace("\\", "\\\\").replace("\"", "\\\"")
        val mainClass = HeadlessOutputEncodingProbe::class.java.name
        val mode = if (configure) "configure" else "control"
        Files.writeString(argumentsFile, "-Dfile.encoding=US-ASCII\n-cp\n\"$quotedClasspath\"\n$mainClass\n$mode\n")
        val process = ProcessBuilder(java, "@${argumentsFile.toAbsolutePath()}").start()
        try {
            val output = process.inputStream.readBytes()
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "encoding probe must finish")
            assertEquals(0, process.exitValue(), String(process.errorStream.readBytes()))
            return output
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

/** Prints non-ASCII text the way a headless CLI command would; never starts the application. */
object HeadlessOutputEncodingProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "configure") configureHeadlessOutputEncoding()
        println(ENCODING_PROBE_TEXT)
    }
}
