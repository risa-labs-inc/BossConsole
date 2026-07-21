package ai.rever.boss.plugin.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the pure managed-profile helpers in [BrowserServiceImpl] — no
 * JxBrowser engine required (the lazy engine-backed fields are never touched here).
 *
 * These cover the RPA capabilities that were folded into the central browser
 * service: cookie-domain parsing, profile-name sanitization, LRU eviction, and
 * named-profile metadata persistence.
 */
class BrowserServiceImplTest {

    @Test
    fun `cookieDomain strips scheme, path, query, fragment and port`() {
        assertEquals("example.com", BrowserServiceImpl.cookieDomain("https://example.com/path?q=1#frag"))
        assertEquals("sub.example.com", BrowserServiceImpl.cookieDomain("http://sub.example.com"))
        assertEquals("example.com", BrowserServiceImpl.cookieDomain("example.com:8443"))
        assertEquals("example.com", BrowserServiceImpl.cookieDomain("example.com/only/path"))
        assertEquals("example.com", BrowserServiceImpl.cookieDomain("  example.com  "))
        assertEquals("example.com", BrowserServiceImpl.cookieDomain("example.com"))
        // userinfo must be stripped, not returned as the domain
        assertEquals("host.com", BrowserServiceImpl.cookieDomain("http://user@host.com/"))
        assertEquals("host.com", BrowserServiceImpl.cookieDomain("http://user:pass@host.com:8443/p"))
        // IPv6 literal: keep through the closing bracket, drop the port
        assertEquals("[::1]", BrowserServiceImpl.cookieDomain("http://[::1]:8443/p"))
    }

    @Test
    fun `sanitize keeps safe profile-name chars and replaces the rest`() {
        assertEquals("ok.name-1_2", BrowserServiceImpl.sanitize("ok.name-1_2"))
        assertEquals("a_b_c", BrowserServiceImpl.sanitize("a/b c"))
        assertEquals("a_b", BrowserServiceImpl.sanitize("a@b"))
        assertEquals("https___x.com_p", BrowserServiceImpl.sanitize("https://x.com/p"))
    }

    private fun c(id: String, lastUsedMs: Long, sizeBytes: Long) =
        BrowserServiceImpl.EvictCandidate(id, "rpa-named-$id", lastUsedMs, sizeBytes)

    @Test
    fun `selectEvictionVictims returns nothing when under cap`() {
        val victims = BrowserServiceImpl.selectEvictionVictims(
            listOf(c("a", 1, 100), c("b", 2, 100)), emptySet(), capBytes = 1000
        )
        assertTrue(victims.isEmpty())
    }

    @Test
    fun `selectEvictionVictims evicts oldest first until under cap`() {
        // total 300 > cap 150: drop a (oldest) -> 200, still > 150 -> drop b -> 100, stop.
        val victims = BrowserServiceImpl.selectEvictionVictims(
            listOf(c("c", 30, 100), c("a", 10, 100), c("b", 20, 100)), emptySet(), capBytes = 150
        )
        assertEquals(listOf("a", "b"), victims)
    }

    @Test
    fun `selectEvictionVictims never evicts an in-use profile`() {
        // total 300 > cap 250: a is the oldest but in use, so it's skipped; evicting
        // b (next oldest, not in use) brings the total to 200 <= 250 and we stop —
        // d (newest) is kept and in-use a is never touched.
        val victims = BrowserServiceImpl.selectEvictionVictims(
            listOf(c("a", 10, 100), c("b", 20, 100), c("d", 30, 100)),
            inUseNames = setOf("rpa-named-a"), capBytes = 250
        )
        assertEquals(listOf("b"), victims)
    }

    @Test
    fun `meta json round-trips and tolerates missing diskBytes (back-compat)`() {
        val list = listOf(
            BrowserServiceImpl.NamedMeta("id1", "rpa-named-id1", "/p/1", 123L, 456L),
            BrowserServiceImpl.NamedMeta("id2", "rpa-named-id2", "/p/2", 789L), // diskBytes default 0
        )
        assertEquals(list, BrowserServiceImpl.decodeMeta(BrowserServiceImpl.encodeMeta(list)))

        // Metadata written before diskBytes existed must decode with diskBytes = 0.
        val legacy = """[{"id":"id1","name":"rpa-named-id1","path":"/p/1","lastUsedMs":123}]"""
        assertEquals(0L, BrowserServiceImpl.decodeMeta(legacy).single().diskBytes)
    }
}
