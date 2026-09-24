package ai.rever.boss.cli

import ai.rever.boss.plugin.launchpad.HostMeta
import ai.rever.boss.plugin.launchpad.PluginManifest
import ai.rever.boss.plugin.launchpad.PluginMcpToolDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BossPluginDiffTest {
    private fun manifest(
        pluginId: String = "ai.rever.boss.test",
        version: String = "1.0.0",
        permissions: List<String> = emptyList(),
        mcpTools: List<PluginMcpToolDeclaration> = emptyList(),
        mainClass: String = "ai.rever.boss.test.Main",
        apiVersion: String = HostMeta.CURRENT_API_VERSION,
    ): PluginManifest =
        PluginManifest(
            pluginId = pluginId,
            displayName = pluginId,
            version = version,
            apiVersion = apiVersion,
            mainClass = mainClass,
            requiredPermissions = permissions,
            mcpTools = mcpTools,
        )

    private fun tool(
        name: String,
        admin: Boolean = false,
    ) = PluginMcpToolDeclaration(name = name, description = "Tool $name", adminOnly = admin)

    @Test
    fun `identical manifests produce no changes`() {
        val manifest = manifest(permissions = listOf("filesystem", "mcp"), mcpTools = listOf(tool("a"), tool("b")))
        val diff = PluginDiffer.diff(manifest, manifest)
        assertFalse(diff.hasChanges())
        assertEquals(emptyList(), diff.permissionsAdded)
        assertEquals(emptyList(), diff.permissionsRemoved)
        assertEquals(emptyList(), diff.mcpToolsAdded)
        assertEquals(emptyList(), diff.mcpToolsRemoved)
    }

    @Test
    fun `a permission added on the right appears in permissionsAdded`() {
        val left = manifest(permissions = listOf("filesystem"))
        val right = manifest(permissions = listOf("filesystem", "terminal"))
        val diff = PluginDiffer.diff(left, right)
        assertEquals(listOf("terminal"), diff.permissionsAdded)
        assertEquals(emptyList(), diff.permissionsRemoved)
        assertTrue(diff.hasChanges())
    }

    @Test
    fun `a permission removed on the right appears in permissionsRemoved`() {
        val left = manifest(permissions = listOf("filesystem", "terminal"))
        val right = manifest(permissions = listOf("filesystem"))
        val diff = PluginDiffer.diff(left, right)
        assertEquals(listOf("terminal"), diff.permissionsRemoved)
        assertTrue(diff.hasChanges())
    }

    @Test
    fun `permissionsKept is the intersection, sorted`() {
        val left = manifest(permissions = listOf("filesystem", "mcp"))
        val right = manifest(permissions = listOf("filesystem", "terminal"))
        val diff = PluginDiffer.diff(left, right)
        assertEquals(listOf("filesystem"), diff.permissionsKept)
        assertEquals(listOf("terminal"), diff.permissionsAdded)
    }

    @Test
    fun `mcp tools added on the right appear in mcpToolsAdded`() {
        val left = manifest(mcpTools = listOf(tool("read")))
        val right = manifest(mcpTools = listOf(tool("read"), tool("write")))
        val diff = PluginDiffer.diff(left, right)
        assertEquals(listOf("write"), diff.mcpToolsAdded.map { it.toolName })
        assertEquals(listOf("read"), diff.mcpToolsKept.map { it.toolName })
        assertTrue(diff.hasChanges())
    }

    @Test
    fun `mcp tools removed on the right appear in mcpToolsRemoved`() {
        val left = manifest(mcpTools = listOf(tool("read"), tool("write")))
        val right = manifest(mcpTools = listOf(tool("read")))
        val diff = PluginDiffer.diff(left, right)
        assertEquals(listOf("write"), diff.mcpToolsRemoved.map { it.toolName })
        assertTrue(diff.hasChanges())
    }

    @Test
    fun `flipping adminOnly on a kept tool is reported separately`() {
        val left = manifest(mcpTools = listOf(tool("destructive", admin = false)))
        val right = manifest(mcpTools = listOf(tool("destructive", admin = true)))
        val diff = PluginDiffer.diff(left, right)
        assertEquals(emptyList(), diff.mcpToolsAdded)
        assertEquals(emptyList(), diff.mcpToolsRemoved)
        val flip = diff.mcpToolAdminScopeFlipped.single()
        assertEquals("destructive", flip.toolName)
        assertEquals(false, flip.leftAdminOnly)
        assertEquals(true, flip.rightAdminOnly)
        assertTrue(diff.hasChanges())
    }

    @Test
    fun `unrecognised permissions are surfaced as a separate bucket`() {
        // `sneaky.injection` is not in the host vocabulary - it would silently
        // bypass the host's permission check if treated as a normal permission.
        val left = manifest(permissions = listOf("filesystem", "sneaky.injection"))
        val right = manifest(permissions = listOf("filesystem", "sneaky.injection", "websocket.tunnel"))
        val diff = PluginDiffer.diff(left, right)
        assertEquals(emptyList(), diff.permissionsAdded)
        assertEquals(listOf("websocket.tunnel"), diff.unrecognisedPermissionsAdded)
        assertTrue(diff.hasChanges(), "an unrecognised permission is still a change")
    }

    @Test
    fun `api version change is captured when it differs`() {
        val left = manifest(apiVersion = "1.0.0")
        val right = manifest(apiVersion = "1.1.0")
        val diff = PluginDiffer.diff(left, right)
        assertNotNull(diff.apiVersionDelta)
        assertEquals("1.0.0", diff.apiVersionDelta?.left)
        assertEquals("1.1.0", diff.apiVersionDelta?.right)
        assertTrue(diff.hasChanges())
    }

    @Test
    fun `matching api version leaves apiVersionDelta null`() {
        val left = manifest()
        val right = manifest()
        val diff = PluginDiffer.diff(left, right)
        assertNull(diff.apiVersionDelta)
    }

    @Test
    fun `main class change is captured`() {
        val left = manifest(mainClass = "ai.rever.boss.test.Main")
        val right = manifest(mainClass = "ai.rever.boss.test.MainV2")
        val diff = PluginDiffer.diff(left, right)
        assertNotNull(diff.mainClassDelta)
        assertTrue(diff.mainClassDelta!!.contains("MainV2"))
        assertTrue(diff.hasChanges())
    }

    @Test
    fun `versionDelta reports no change when versions match`() {
        val left = manifest(version = "1.0.0")
        val right = manifest(version = "1.0.0")
        val diff = PluginDiffer.diff(left, right)
        assertEquals("no version change", diff.versionDelta)
    }

    @Test
    fun `versionDelta reports the bump`() {
        val left = manifest(version = "1.0.0")
        val right = manifest(version = "1.0.1")
        val diff = PluginDiffer.diff(left, right)
        assertEquals("1.0.0 → 1.0.1", diff.versionDelta)
    }

    @Test
    fun `pluginId is captured on both sides for a self-comparison sanity check`() {
        val m = manifest(pluginId = "ai.rever.boss.sample")
        val diff = PluginDiffer.diff(m, m)
        assertEquals("ai.rever.boss.sample", diff.leftId)
        assertEquals("ai.rever.boss.sample", diff.rightId)
    }
}
