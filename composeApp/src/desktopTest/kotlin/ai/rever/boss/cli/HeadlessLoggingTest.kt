package ai.rever.boss.cli

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeadlessLoggingTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `headless logging remains on stderr after main class initialization`() {
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
        // The dependency classpath exceeds Windows CreateProcess's command-line limit.
        // Keep it in a launcher argument file, quoting spaces and escaping Windows backslashes.
        val argumentsFile = tempDir.resolve("headless logging.args")
        val quotedClasspath = classpath.replace("\\", "\\\\").replace("\"", "\\\"")
        Files.writeString(argumentsFile, "-cp\n\"$quotedClasspath\"\n${HeadlessLoggingProbe::class.java.name}\n")
        val process = ProcessBuilder(java, "@${argumentsFile.toAbsolutePath()}").start()
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "logging probe must finish")
            assertEquals(0, process.exitValue())
            assertEquals("", process.inputStream.bufferedReader().readText())
            assertTrue(
                process.errorStream
                    .bufferedReader()
                    .readText()
                    .contains("headless-warning-probe"),
            )
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

/** A logging-only fixture; never calls the application entry point or contacts IPC. */
object HeadlessLoggingProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.out")
        Class.forName("ai.rever.boss.MainKt")
        configureHeadlessLogging()
        org.slf4j.LoggerFactory
            .getLogger("headless-probe")
            .warn("headless-warning-probe")
    }
}
