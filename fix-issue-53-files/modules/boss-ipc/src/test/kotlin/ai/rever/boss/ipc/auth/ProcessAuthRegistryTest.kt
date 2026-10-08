package ai.rever.boss.ipc.auth

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Pure unit tests for [ProcessAuthRegistry] — no gRPC involved. The interceptors that sit on top of this
 * are exercised end-to-end, over a real channel and server, by `PluginUIServiceBridgeTest` in `composeApp`
 * — that is where a forged or missing token needs to visibly change RPC behaviour, which is the property
 * that actually matters and the reason this class exists.
 */
class ProcessAuthRegistryTest {
    private val registry = ProcessAuthRegistry()

    @Test
    fun `a token issued for a process resolves back to it`() {
        val token = registry.issueToken("boss-app-terminal")

        assertEquals("boss-app-terminal", registry.processIdFor(token))
    }

    @Test
    fun `an unrecognised token resolves to nothing`() {
        assertNull(registry.processIdFor("a-string-nobody-issued"))
    }

    @Test
    fun `two tokens for the same process are not the same value`() {
        // Not a full entropy test - just guarding against an implementation that degenerates to something
        // derived from processId, which would make a token guessable from public information.
        val first = registry.issueToken("boss-app-terminal")
        val second = registry.issueToken("boss-app-terminal")

        assertNotEquals(first, second)
        assertEquals("boss-app-terminal", registry.processIdFor(first))
        assertEquals("boss-app-terminal", registry.processIdFor(second))
    }

    @Test
    fun `a revoked token no longer resolves`() {
        val token = registry.issueToken("boss-app-terminal")

        registry.revoke(token)

        assertNull(registry.processIdFor(token))
    }

    @Test
    fun `revoking an unknown token is a harmless no-op`() {
        // ManagedProcess.destroy() calls this unconditionally; it must never be the reason a shutdown path
        // throws, including the ordinary case of a process that never got as far as being issued one.
        registry.revoke("never-issued")
    }
}
