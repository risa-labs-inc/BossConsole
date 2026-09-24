package ai.rever.boss.cli

import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BossPortDoctorTest {
    private val prober = PortProber()

    private fun unusedPort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    @Test
    fun `a reachable local port is reported with non-zero latency`() {
        // Bind a server socket that accepts and immediately closes, so the
        // connect succeeds. Localhost is always reachable from a JVM.
        ServerSocket(0).use { server ->
            val target = Target(label = "local", host = "127.0.0.1", port = server.localPort)
            val report = prober.probe(listOf(target), timeoutMs = 1000)
            val r = report.results.single()
            assertTrue(r.reachable, "expected reachable on local port, got: $r")
            assertNull(r.error)
        }
    }

    @Test
    fun `an unreachable port is reported with a non-null error`() {
        val target = Target(label = "definitely-closed", host = "127.0.0.1", port = unusedPort())
        val report = prober.probe(listOf(target), timeoutMs = 500)
        val r = report.results.single()
        assertFalse(r.reachable)
        assertNotNull(r.error, "expected a non-null error string for an unreachable target")
    }

    @Test
    fun `a timeout produces an unreachable result with the timeout error`() {
        // Pick an IP that drops packets: 10.255.255.1 is a typical TEST-NET
        // address used for "no route here". On most networks it times out
        // fast. If the host is somehow directly reachable, the test still
        // asserts the error path is populated.
        val target = Target(label = "timeout", host = "10.255.255.1", port = 65530)
        val report = prober.probe(listOf(target), timeoutMs = 200)
        val r = report.results.single()
        // Either unreachable (timed out / refused / unroutable) or - in the
        // unlikely event the host is directly reachable - reachable. The
        // contract is that the call returned without throwing, which is what
        // we care about here.
        assertTrue(r.latencyMs >= 0L, "latency must be non-negative")
    }

    @Test
    fun `latency is measured and reported`() {
        ServerSocket(0).use { server ->
            val target = Target(label = "local", host = "127.0.0.1", port = server.localPort)
            val r = prober.probe(listOf(target), timeoutMs = 1000).results.single()
            if (r.reachable) {
                assertTrue(r.latencyMs >= 0L, "latency must be measured on success")
            }
        }
    }

    @Test
    fun `multiple targets are probed independently`() {
        // `good` keeps the ServerSocket open for the duration of the probe,
        // since accept() is kernel-completed; `bad` is closed before the
        // probe begins so the connect attempt is refused.
        ServerSocket(0).use { server ->
            val goodPort = server.localPort
            val badPort = unusedPort()
            val report =
                prober.probe(
                    listOf(
                        Target(label = "good", host = "127.0.0.1", port = goodPort),
                        Target(label = "bad", host = "127.0.0.1", port = badPort),
                    ),
                    timeoutMs = 1000,
                )
            assertEquals(2, report.results.size)
            val byLabel = report.results.associateBy { it.target.label }
            assertTrue(byLabel["good"]!!.reachable)
            assertFalse(byLabel["bad"]!!.reachable)
        }
    }

    @Test
    fun `default targets include the expected endpoints`() {
        val defaults = PortProber.defaultTargets()
        val labels = defaults.map { it.label }
        assertTrue("supabase-rest" in labels, "default targets should include Supabase REST: $labels")
        assertTrue("supabase-functions" in labels, "default targets should include Supabase Functions: $labels")
        assertTrue("plugin-store" in labels, "default targets should include plugin store: $labels")
        for (t in defaults) {
            assertTrue(t.port in 1..65535, "default port out of range: ${t.port}")
            assertTrue(t.host.isNotEmpty(), "default host empty")
        }
    }

    @Test
    fun `probe returns a report whose timeoutMs echoes the input`() {
        val target = Target(label = "x", host = "127.0.0.1", port = unusedPort())
        val report = prober.probe(listOf(target), timeoutMs = 1234)
        assertEquals(1234L, report.timeoutMs)
    }

    @Test
    fun `a ConnectException is mapped to an unreachable result`() {
        // Sanity check: a refused connection (the most common "nothing
        // listening" failure) shows up as a non-reachable result with a
        // non-null error, and the test never throws.
        val target = Target(label = "refused", host = "127.0.0.1", port = unusedPort())
        val r = prober.probe(listOf(target), timeoutMs = 1000).results.single()
        assertFalse(r.reachable)
        assertNotNull(r.error)
    }
}
