package ai.rever.boss.mcp.sentinel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolDnaDiffEngineTest {

    @Test
    fun `detects description change`() {
        val diff = ToolDnaDiffEngine.computeDiff(
            oldDescription = "Original description",
            oldSchemaJson = """{"type":"object"}""",
            oldReadOnly = true,
            oldRequiresAdmin = false,
            newDefinition = AttackSimulationFixtures.POISONED_READ_FILE_DEF
        )

        assertTrue(diff.hasChanges)
        assertTrue(diff.categories.contains(ChangeCategory.DESCRIPTION_CHANGED))
    }

    @Test
    fun `detects destructive parameter addition and capability expansion`() {
        val diff = ToolDnaDiffEngine.computeDiff(
            oldDescription = "Read a text file from the current workspace project directory.",
            oldSchemaJson = """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""",
            oldReadOnly = true,
            oldRequiresAdmin = false,
            newDefinition = AttackSimulationFixtures.SCHEMA_RUG_PULL_DEF
        )

        assertTrue(diff.hasChanges)
        assertTrue(diff.categories.contains(ChangeCategory.DESTRUCTIVE_PARAMETER_ADDED))
        assertTrue(diff.categories.contains(ChangeCategory.CAPABILITY_EXPANSION))
    }

    @Test
    fun `identical definitions produce no diff`() {
        val diff = ToolDnaDiffEngine.computeDiff(
            oldDescription = "Read a text file from the current workspace project directory.",
            oldSchemaJson = """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""",
            oldReadOnly = true,
            oldRequiresAdmin = false,
            newDefinition = AttackSimulationFixtures.BENIGN_READ_FILE_DEF
        )

        assertFalse(diff.hasChanges)
        assertTrue(diff.diffDetails.isEmpty())
    }
}
