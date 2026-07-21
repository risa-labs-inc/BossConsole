package ai.rever.boss.plugin

import ai.rever.boss.ipc.IpcVersion
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards [PluginStoreSetup.ipcIncompatibilityReason] — the IPC-compat gate that
 * stops `downloadSystemPluginFromGitHub` from auto-installing a plugin the host
 * cannot load (issue #740, terminal-tab v2.0.3 onto a 1.0.x host).
 *
 * Inputs are derived from [IpcVersion.CURRENT] so the assertions survive a
 * future bump of the host contract version. The test also pins the reflection
 * contract the gate depends on (`IpcVersion.CURRENT`, `isCompatible`,
 * `CompatResult.Incompatible.reason`) — a rename in `:boss-ipc` would otherwise
 * silently turn the gate into a no-op.
 */
class PluginStoreSetupIpcGateTest {

    private val host = IpcVersion.parse(IpcVersion.CURRENT)
        ?: error("IpcVersion.CURRENT is not parseable: ${IpcVersion.CURRENT}")

    @Test
    fun `blank minIpcVersion is not gated`() {
        // Legacy JARs from before the field existed must keep installing.
        assertNull(PluginStoreSetup.ipcIncompatibilityReason(""))
    }

    @Test
    fun `version equal to host is compatible`() {
        assertNull(PluginStoreSetup.ipcIncompatibilityReason(IpcVersion.CURRENT))
    }

    @Test
    fun `older minor within same major is compatible`() {
        if (host.second == 0) return // nothing older within this major to test
        val older = "${host.first}.${host.second - 1}.0"
        assertNull(PluginStoreSetup.ipcIncompatibilityReason(older))
    }

    @Test
    fun `newer minor within same major is rejected`() {
        // The terminal-tab v2.0.3 scenario: plugin requires a higher IPC minor
        // than the host speaks.
        val tooNew = "${host.first}.${host.second + 1}.0"
        val reason = PluginStoreSetup.ipcIncompatibilityReason(tooNew)
        assertNotNull(reason, "expected $tooNew to be rejected against host ${IpcVersion.CURRENT}")
        assertTrue(reason.contains("BossConsole"), "actionable reason expected, got: $reason")
    }

    @Test
    fun `newer major is rejected`() {
        val nextMajor = "${host.first + 1}.0.0"
        val reason = PluginStoreSetup.ipcIncompatibilityReason(nextMajor)
        assertNotNull(reason, "expected $nextMajor to be rejected against host ${IpcVersion.CURRENT}")
    }
}
