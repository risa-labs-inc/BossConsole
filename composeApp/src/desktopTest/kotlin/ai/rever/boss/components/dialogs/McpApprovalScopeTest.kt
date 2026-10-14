package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpApprovalRequest
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

    // #1624 and its review: no saved *allow* can pre-approve an escalated (CRITICAL) call, but a
    // saved *deny* is never overridden - so the prompt keeps "Always" for its deny half only, and
    // its allow button stays at once whatever is selected.
    @Test
    fun `an escalated prompt can save a deny but never an allow`() {
        val request =
            McpApprovalRequest(toolName = "run_command", providerId = "p", arguments = emptyMap(), timeoutMs = 1_000)
        val escalated = request.copy(escalated = true)

        assertEquals(McpApprovalScope.entries, McpPromptChoices.scopesFor(request))
        assertEquals(listOf(McpApprovalScope.ONCE, McpApprovalScope.ALWAYS_TOOL), McpPromptChoices.scopesFor(escalated))

        for (scope in McpPromptChoices.scopesFor(escalated)) {
            val flags = McpPromptChoices.allowFlagsFor(escalated, scope)
            assertEquals(McpApprovalScope.ONCE.approveFlags(), flags, "$scope")
            assertEquals("Allow once", McpPromptChoices.allowLabelFor(escalated, scope), "$scope")
        }
        assertTrue(McpApprovalScope.ALWAYS_TOOL.persistsDeny(), "the deny half must still persist")

        // Unescalated prompts are unchanged.
        val always = McpApprovalScope.ALWAYS_TOOL
        assertEquals(always.approveFlags(), McpPromptChoices.allowFlagsFor(request, always))
        assertEquals("Always allow", McpPromptChoices.allowLabelFor(request, always))
    }
}
