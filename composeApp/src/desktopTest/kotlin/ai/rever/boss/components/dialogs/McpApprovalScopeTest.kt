package ai.rever.boss.components.dialogs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The approval dialog now asks for a scope once and answers with two buttons. Each scope must
 * reach exactly the `onApprove` / `onDeny` flags the old per-scope buttons sent, because the
 * approval bus and policy engine behind them are unchanged.
 */
class McpApprovalScopeTest {
    @Test
    fun `each scope approves with the flags its old button sent`() {
        assertEquals(McpApproveFlags(false, false, false), McpApprovalScope.ONCE.approveFlags())
        assertEquals(McpApproveFlags(true, false, false), McpApprovalScope.SESSION.approveFlags())
        assertEquals(McpApproveFlags(false, true, false), McpApprovalScope.ALWAYS_TOOL.approveFlags())
        assertEquals(McpApproveFlags(false, false, true), McpApprovalScope.ALWAYS_PLUGIN.approveFlags())
    }

    @Test
    fun `only the tool-wide scope persists a denial, and the label says so`() {
        assertTrue(McpApprovalScope.ALWAYS_TOOL.persistsDeny())
        assertEquals("Always deny", McpApprovalScope.ALWAYS_TOOL.denyLabel())
        for (scope in listOf(McpApprovalScope.ONCE, McpApprovalScope.SESSION, McpApprovalScope.ALWAYS_PLUGIN)) {
            assertFalse(scope.persistsDeny(), "$scope must not persist a deny")
        }
        // A scope that cannot deny durably must not let the Deny button imply it does.
        assertEquals("Deny once", McpApprovalScope.SESSION.denyLabel())
        assertEquals("Deny once", McpApprovalScope.ALWAYS_PLUGIN.denyLabel())
    }
}
