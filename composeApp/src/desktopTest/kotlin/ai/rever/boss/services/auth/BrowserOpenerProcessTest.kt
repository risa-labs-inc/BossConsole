package ai.rever.boss.services.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [exitsCleanly] decides whether the sign-in page opened, so it must read the opener's exit
 * status and nothing else. Runs real `sh` processes; skipped on Windows, which has none.
 */
class BrowserOpenerProcessTest {
    private val isWindows =
        System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .contains("win")

    @Test
    fun `an opener that prints a lot and exits 0 counts as opened`() {
        if (isWindows) return
        // Far more than a pipe buffer, so a closed or unread pipe would break or block it.
        val chatty =
            "i=0; while [ \$i -lt 5000 ]; do echo 'gio: warning: xxxxxxxxxxxxxxxxxxxxxxxx'; i=\$((i+1)); done; exit 0"
        assertTrue(exitsCleanly(listOf("sh", "-c", chatty)))
    }

    @Test
    fun `a non-zero exit counts as not opened`() {
        if (isWindows) return
        assertFalse(exitsCleanly(listOf("sh", "-c", "exit 1")))
        assertFalse(exitsCleanly(listOf("no-such-opener-for-boss-tests")))
    }

    @Test
    fun `an opener still running at the deadline is killed and counts as not opened`() {
        if (isWindows) return
        val marker = "boss-opener-test-${System.nanoTime()}"
        val started = System.nanoTime()
        assertFalse(exitsCleanly(listOf("sh", "-c", "exec sleep 30 # $marker"), timeoutSeconds = 1))
        assertTrue(System.nanoTime() - started < 10_000_000_000L, "waited for the process instead of the deadline")
        // destroyForcibly is asynchronous; give it a moment, then nothing may still carry the marker.
        Thread.sleep(500)
        val survivors =
            ProcessHandle
                .allProcesses()
                .filter { p ->
                    p
                        .info()
                        .commandLine()
                        .orElse("")
                        .contains(marker)
                }.count()
        assertTrue(survivors == 0L, "the timed-out opener is still running")
    }
}
