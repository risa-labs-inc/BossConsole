package ai.rever.boss.plugin.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SingleCaptureRequestSlotTest {
    @Test
    fun `publishing a second request returns the one the picker can no longer show`() {
        val slot = SingleCaptureRequestSlot<String>()

        assertNull(slot.publish("first", "first callback") {})
        assertEquals("first callback", slot.publish("second", "second callback") {})
        assertFalse(slot.contains("first"))
        assertTrue(slot.contains("second"))
    }

    @Test
    fun `a stale picker action cannot clear the current request`() {
        val slot = SingleCaptureRequestSlot<String>()
        var visibleRequest = "second"
        slot.publish("second", "second callback") {}

        assertNull(slot.take("first") { visibleRequest = "none" })
        assertEquals("second", visibleRequest)
        assertTrue(slot.contains("second"))
    }

    @Test
    fun `taking the active request clears its picker in the same transition`() {
        val slot = SingleCaptureRequestSlot<String>()
        var visibleRequest = "first"
        slot.publish("first", "first callback") {}

        assertEquals("first callback", slot.take("first") { visibleRequest = "none" })
        assertEquals("none", visibleRequest)
        assertFalse(slot.contains("first"))
    }
}
