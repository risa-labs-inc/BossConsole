package ai.rever.boss.app

import ai.rever.boss.components.dialogs.TabType
import ai.rever.boss.components.events.DashboardNewTabEvent
import ai.rever.boss.plugin.api.TabTypeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewTabDialogRequestStateTest {
    @Test
    fun `request stays in its source window and pane`() {
        val first = NewTabDialogRequestState("first")
        val second = NewTabDialogRequestState("second")
        val type = TabTypeId("query", "chosen")
        val event = DashboardNewTabEvent("first", type)
        assertTrue(first.receive(event, "pane-a"))
        assertFalse(second.receive(event, "pane-b"))
        assertEquals(type, first.requestedType)
        assertEquals("pane-a", first.sourcePanelId)
        assertNull(second.requestedType)
        assertNull(second.sourcePanelId)
    }

    @Test
    fun `dismiss clears selection and split before an unrelated dialog`() {
        val state = NewTabDialogRequestState("first")
        state.receive(DashboardNewTabEvent("first", TabTypeId("query", "chosen")), "pane-a")
        state.initialType = TabType.FILE
        var splitPending = true
        state.dismiss { splitPending = false }
        assertNull(state.initialType)
        assertNull(state.requestedType)
        assertNull(state.sourcePanelId)
        assertFalse(splitPending)
        state.receive(DashboardNewTabEvent("first"), "pane-b")
        assertNull(state.requestedType)
        assertEquals("pane-b", state.sourcePanelId)
    }
}
