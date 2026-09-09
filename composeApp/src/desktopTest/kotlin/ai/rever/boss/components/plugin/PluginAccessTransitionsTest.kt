package ai.rever.boss.components.plugin

import kotlin.test.Test
import kotlin.test.assertEquals

class PluginAccessTransitionsTest {
    private val silentReconciliation = PluginAccessReconciliation(reconcile = true, reportMissingDependencies = false)
    private val accessChange = PluginAccessReconciliation(reconcile = true, reportMissingDependencies = true)
    private val unchanged = PluginAccessReconciliation(reconcile = false, reportMissingDependencies = false)

    private fun snapshot(
        userId: String? = "alice",
        isAdmin: Boolean = false,
        permissions: Set<String> = emptySet(),
    ) = PluginAccessSnapshot(userId = userId, isAdmin = isAdmin, permissions = permissions)

    @Test
    fun `startup with existing permissions reconciles without prompting`() {
        val transitions = PluginAccessTransitions()

        assertEquals(silentReconciliation, transitions.accept(snapshot(permissions = setOf("terminal"))))
    }

    @Test
    fun `startup with admin access reconciles without prompting`() {
        val transitions = PluginAccessTransitions()

        assertEquals(silentReconciliation, transitions.accept(snapshot(isAdmin = true)))
    }

    @Test
    fun `first grant to an authenticated user with no permissions is reportable`() {
        val transitions = PluginAccessTransitions()

        assertEquals(silentReconciliation, transitions.accept(snapshot()))
        assertEquals(accessChange, transitions.accept(snapshot(permissions = setOf("terminal"))))
    }

    @Test
    fun `repeated access does not reconcile or prompt again`() {
        val transitions = PluginAccessTransitions()
        val granted = snapshot(permissions = setOf("terminal", "editor"))
        transitions.accept(granted)

        assertEquals(unchanged, transitions.accept(snapshot(permissions = setOf("editor", "terminal"))))
        assertEquals(unchanged, transitions.accept(granted))
    }

    @Test
    fun `permission restoration after revocation remains reportable`() {
        val transitions = PluginAccessTransitions()
        val granted = snapshot(permissions = setOf("terminal"))
        transitions.accept(granted)

        assertEquals(accessChange, transitions.accept(snapshot()))
        assertEquals(accessChange, transitions.accept(granted))
    }

    @Test
    fun `admin promotion after startup is reportable`() {
        val transitions = PluginAccessTransitions()
        transitions.accept(snapshot())

        assertEquals(accessChange, transitions.accept(snapshot(isAdmin = true)))
    }

    @Test
    fun `authentication arriving after manager startup establishes a silent baseline`() {
        val transitions = PluginAccessTransitions()

        assertEquals(silentReconciliation, transitions.accept(snapshot(userId = null)))
        assertEquals(silentReconciliation, transitions.accept(snapshot(permissions = setOf("terminal"))))
    }

    @Test
    fun `authentication with no permissions does not swallow the later first grant`() {
        val transitions = PluginAccessTransitions()
        transitions.accept(snapshot(userId = null))

        assertEquals(silentReconciliation, transitions.accept(snapshot()))
        assertEquals(accessChange, transitions.accept(snapshot(permissions = setOf("terminal"))))
    }

    @Test
    fun `switching users with identical access establishes a fresh silent baseline`() {
        val transitions = PluginAccessTransitions()
        transitions.accept(snapshot())

        assertEquals(silentReconciliation, transitions.accept(snapshot(userId = "bob")))
        assertEquals(accessChange, transitions.accept(snapshot(userId = "bob", permissions = setOf("terminal"))))
    }

    @Test
    fun `signing out and back in does not treat restored session access as a grant`() {
        val transitions = PluginAccessTransitions()
        val granted = snapshot(permissions = setOf("terminal"))
        transitions.accept(granted)

        assertEquals(silentReconciliation, transitions.accept(snapshot(userId = null)))
        assertEquals(silentReconciliation, transitions.accept(granted))
        assertEquals(unchanged, transitions.accept(granted))
    }
}
