package ai.rever.boss.cli

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/**
 * Makes headless CLI output UTF-8 wherever it is not a Windows console.
 *
 * JDK 17, the runtime BOSS is built and shipped with, encodes `System.out` and `System.err` with the
 * platform default charset unless the stream is a Windows console. On Windows that default is the ANSI
 * code page, so output read by an agent, a pipe or a file loses every character outside it: an arrow is
 * written as `?`, and an accented letter as a byte that is not valid UTF-8. That covers `boss mcp invoke`
 * tool output and every `--json` response, and no reader can repair a `?` afterwards.
 *
 * A Windows console is left alone. The JDK already encodes for the console's code page there, and UTF-8
 * bytes would be drawn as the wrong characters. The JDK marks those streams by setting
 * `sun.stdout.encoding` and `sun.stderr.encoding`, which it does for a console and nothing else.
 *
 * The replacement streams have no buffer below the encoder, so nothing is left unwritten when the CLI
 * calls `exitProcess`.
 */
internal fun configureHeadlessOutputEncoding(
    stdoutConsoleEncoding: String? = System.getProperty("sun.stdout.encoding"),
    stderrConsoleEncoding: String? = System.getProperty("sun.stderr.encoding"),
    stdout: () -> OutputStream = { FileOutputStream(FileDescriptor.out) },
    stderr: () -> OutputStream = { FileOutputStream(FileDescriptor.err) },
) {
    if (writesUtf8(stdoutConsoleEncoding)) System.setOut(utf8PrintStream(stdout()))
    if (writesUtf8(stderrConsoleEncoding)) System.setErr(utf8PrintStream(stderr()))
}

/** Whether a standard stream gets UTF-8: only when the JVM did not mark it as a console. */
internal fun writesUtf8(consoleEncoding: String?): Boolean = consoleEncoding.isNullOrBlank()

internal fun utf8PrintStream(out: OutputStream): PrintStream = PrintStream(out, true, StandardCharsets.UTF_8)
