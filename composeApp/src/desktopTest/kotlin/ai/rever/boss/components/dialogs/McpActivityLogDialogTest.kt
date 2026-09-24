package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpApprovalDisposition
import ai.rever.boss.mcp.McpOperationRecord
import ai.rever.boss.mcp.McpPolicyAction
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [McpApprovalDisposition.unsuccessfulCategory] is the one thing standing between the activity
 * log's "N unsuccessful" summary and mislabeling a governance decision (an operator's own Deny)
 * or a host disk fault (a persisted approval that failed to save) as a tool fault - both of which
 * shipped as bugs before this classification existed (review on #636/#662).
 */
class McpActivityLogDialogTest {
    @Test
    fun `queue overflow withholds a call without an operator cancellation`() {
        assertEquals(McpUnsuccessfulCategory.WITHHELD, McpApprovalDisposition.QUEUE_FULL.unsuccessfulCategory)
    }

    @Test
    fun `every denial disposition classifies as denied`() {
        listOf(
            McpApprovalDisposition.DENIED_BY_OPERATOR,
            McpApprovalDisposition.POLICY_DENIED,
            McpApprovalDisposition.PERSISTENTLY_DENIED,
        ).forEach { disposition ->
            assertEquals(McpUnsuccessfulCategory.DENIED, disposition.unsuccessfulCategory, disposition.name)
        }
    }

    @Test
    fun `every cancellation-shaped disposition classifies as cancelled`() {
        listOf(
            McpApprovalDisposition.CANCELLED,
            McpApprovalDisposition.CANCELLED_AWAITING_APPROVAL,
            McpApprovalDisposition.CANCELLED_IN_FLIGHT,
            McpApprovalDisposition.TIMEOUT,
        ).forEach { disposition ->
            assertEquals(McpUnsuccessfulCategory.CANCELLED, disposition.unsuccessfulCategory, disposition.name)
        }
    }

    @Test
    fun `a failed persisted-approval write is withheld, not a tool fault`() {
        // The tool never ran - McpToolRegistryImpl's denial branch returns isError = true for
        // this disposition without ever reaching execution. Counting it as "failed" attributes a
        // host disk fault to the tool.
        assertEquals(
            McpUnsuccessfulCategory.WITHHELD,
            McpApprovalDisposition.POLICY_PERSIST_FAILED.unsuccessfulCategory,
        )
    }

    @Test
    fun `a call that ran and then failed classifies as a true tool fault`() {
        listOf(
            McpApprovalDisposition.AUTO_ALLOWED,
            McpApprovalDisposition.APPROVED_ONCE,
            McpApprovalDisposition.SESSION_TRUSTED,
            McpApprovalDisposition.PERSISTENTLY_ALLOWED,
            McpApprovalDisposition.PROVIDER_TRUSTED,
            // Unlike POLICY_PERSIST_FAILED, this one's own KDoc says the call in hand still
            // executes - a rare isError = true here is the executed tool genuinely failing.
            McpApprovalDisposition.PROVIDER_TRUST_PERSIST_FAILED,
            McpApprovalDisposition.YOLO_ALLOWED,
        ).forEach { disposition ->
            assertEquals(McpUnsuccessfulCategory.FAILED, disposition.unsuccessfulCategory, disposition.name)
        }
    }

    @Test
    fun `the breakdown only counts entries marked isError, in a fixed category order`() {
        val operations =
            listOf(
                record(McpApprovalDisposition.DENIED_BY_OPERATOR, isError = true),
                record(McpApprovalDisposition.POLICY_DENIED, isError = true),
                record(McpApprovalDisposition.CANCELLED_IN_FLIGHT, isError = true),
                record(McpApprovalDisposition.POLICY_PERSIST_FAILED, isError = true),
                // A successful call recorded with a "denial-shaped" disposition never happens in
                // practice, but the breakdown must still key off isError, not the disposition alone.
                record(McpApprovalDisposition.AUTO_ALLOWED, isError = false),
            )

        assertEquals(
            listOf("2 denied", "1 cancelled", "1 withheld"),
            operations.unsuccessfulBreakdown(),
        )
    }

    @Test
    fun `an empty operation list has no breakdown`() {
        assertEquals(emptyList(), emptyList<McpOperationRecord>().unsuccessfulBreakdown())
    }

    @Test
    fun `readable lowercases with Locale ROOT so a Turkish default locale cannot mangle it`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(
                "Cancelled In Flight",
                McpApprovalDisposition.CANCELLED_IN_FLIGHT.readable(),
            )
        } finally {
            Locale.setDefault(original)
        }
    }

    private fun record(
        disposition: McpApprovalDisposition,
        isError: Boolean,
    ): McpOperationRecord =
        McpOperationRecord(
            id = "id",
            timestamp = 0L,
            toolName = "tool",
            providerId = "provider",
            policyApplied = McpPolicyAction.ASK,
            approvalDisposition = disposition,
            durationMs = 1L,
            isError = isError,
            sanitizedArgs = emptyMap(),
        )
}
