package ai.rever.boss.plugin.ipc

import kotlin.test.Test
import kotlin.test.assertEquals

class AdvisoryActionIdTest {
    @Test
    fun `duplicate labels remain distinct within one menu`() {
        val labels = List(12) { "Copy" }
        val ids = labels.mapIndexed { index, label -> advisoryActionId(label, index) }

        assertEquals(labels.size, ids.toSet().size)
    }

    @Test
    fun `empty labels and index-like label suffixes remain distinct`() {
        val labels = listOf("", "_", "Copy_1", "Copy", "Copy_", "_0", "", "Copy_1", "Copy", "_", "Copy", "Copy_1")
        val ids = labels.mapIndexed { index, label -> advisoryActionId(label, index) }

        assertEquals(labels.size, ids.toSet().size)
    }
}
