package ai.rever.boss.cache

import kotlin.test.Test
import kotlin.test.assertEquals

class FaviconRetentionTest {
    @Test
    fun `large retention windows use long arithmetic`() {
        val now = 2_000_000_000_000L
        val days = 30_000

        assertEquals(now - (days.toLong() * 86_400_000L), staleCutoffMillis(now, days))
    }
}
