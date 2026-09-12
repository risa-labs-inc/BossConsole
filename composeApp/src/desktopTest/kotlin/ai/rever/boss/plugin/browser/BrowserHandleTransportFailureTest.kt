package ai.rever.boss.plugin.browser

import com.teamdev.jxbrowser.ObjectClosedException
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserHandleTransportFailureTest {
    @Test
    fun `direct ObjectClosedException is recognized`() {
        assertTrue(isTransportFailure(ObjectClosedException()))
    }

    @Test
    fun `wrapped ObjectClosedException is recognized`() {
        val wrapped = RuntimeException("Wrapper", ObjectClosedException())
        assertTrue(isTransportFailure(wrapped))
    }

    @Test
    fun `bounded cause-chain behaviour detects connection closed`() {
        val root = IllegalStateException("The connection has been closed.")
        val layer1 = IllegalStateException("Failed to receive the response.", root)
        val layer2 = RuntimeException("Some RPC failure", layer1)
        assertTrue(isTransportFailure(layer2))
    }

    @Test
    fun `cause-cycle termination finishes safely and evaluates to false`() {
        val a = RuntimeException("A")

        class CyclicException : RuntimeException("B") {
            override val cause: Throwable get() = a
        }
        a.initCause(CyclicException())

        assertFalse(isTransportFailure(a))
    }

    @Test
    fun `unrelated exception evaluates to false`() {
        assertFalse(isTransportFailure(IllegalArgumentException("Invalid URL")))
        assertFalse(isTransportFailure(IllegalStateException("Something else went wrong")))
    }

    @Test
    fun `ambiguous transient failure evaluates to false without terminal cause`() {
        assertFalse(isTransportFailure(IllegalStateException("Failed to receive the response.")))
    }
}
