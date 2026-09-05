package ai.rever.boss.ipc.auth

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The credential table [ProcessIdentityInterceptor] verifies against.
 *
 * A caller's identity must come from here, never from a request field (BossConsole#53) - these tests
 * pin the properties that guarantee makes true: every token names exactly one process, a respawn's
 * credential replaces rather than joins its predecessor's, and revocation is real.
 */
class ProcessTokenRegistryTest {
    @Test
    fun `a freshly issued token resolves to the process it was issued for`() {
        val registry = ProcessTokenRegistry()

        val token = registry.issue("boss-app-terminal")

        assertEquals("boss-app-terminal", registry.identityFor(token))
    }

    @Test
    fun `a token this registry never issued resolves to nothing`() {
        val registry = ProcessTokenRegistry()
        registry.issue("boss-app-terminal")

        assertNull(registry.identityFor("0000000000000000000000000000000000000000000000000000000000000000"))
    }

    @Test
    fun `a corrupted token resolves to nothing, not to the process it was corrupted from`() {
        val registry = ProcessTokenRegistry()
        val token = registry.issue("boss-app-terminal")
        val flippedLastChar = token.dropLast(1) + if (token.last() == '0') '1' else '0'

        assertNull(registry.identityFor(flippedLastChar))
    }

    @Test
    fun `blank and absent tokens resolve to nothing`() {
        val registry = ProcessTokenRegistry()
        registry.issue("boss-app-terminal")

        assertNull(registry.identityFor(""))
        assertNull(registry.identityFor(null))
    }

    @Test
    fun `two processes never share a token`() {
        val registry = ProcessTokenRegistry()

        val first = registry.issue("plugin-a")
        val second = registry.issue("plugin-b")

        assertNotEquals(first, second)
        assertEquals("plugin-a", registry.identityFor(first))
        assertEquals("plugin-b", registry.identityFor(second))
    }

    @Test
    fun `reissuing for the same process id invalidates the previous token - a respawn cannot inherit it`() {
        val registry = ProcessTokenRegistry()
        val beforeRestart = registry.issue("boss-app-terminal")

        val afterRestart = registry.issue("boss-app-terminal")

        assertNotEquals(beforeRestart, afterRestart, "a restart must get a genuinely new credential")
        assertNull(registry.identityFor(beforeRestart), "the old process's token must stop working")
        assertEquals("boss-app-terminal", registry.identityFor(afterRestart))
    }

    @Test
    fun `revoking a process invalidates its token immediately`() {
        val registry = ProcessTokenRegistry()
        val token = registry.issue("boss-app-terminal")

        registry.revoke("boss-app-terminal")

        assertNull(registry.identityFor(token))
    }

    @Test
    fun `revoking one process does not disturb another's token`() {
        val registry = ProcessTokenRegistry()
        val survivor = registry.issue("plugin-a")
        registry.issue("plugin-b")

        registry.revoke("plugin-b")

        assertEquals("plugin-a", registry.identityFor(survivor))
    }

    @Test
    fun `revoking a process nothing ever issued a token for is a no-op`() {
        // Must not throw - the shutdown reap path calls this for every child regardless of whether it
        // ever actually connected with a credential.
        ProcessTokenRegistry().revoke("never-issued")
    }

    @Test
    fun `every issued token is 64 lowercase hex characters`() {
        val registry = ProcessTokenRegistry()

        val token = registry.issue("boss-app-terminal")

        assertEquals(64, token.length)
        assertEquals(token, token.lowercase(), "must not vary by case - identityFor's lookup is exact")
        assertEquals(true, token.all { it in "0123456789abcdef" })
    }
}
