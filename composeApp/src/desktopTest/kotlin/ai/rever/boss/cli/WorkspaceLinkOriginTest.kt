package ai.rever.boss.cli

import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.PanelConfig
import ai.rever.boss.components.workspaces.SpaceLoadDisposition
import ai.rever.boss.components.workspaces.TabConfig
import ai.rever.boss.components.workspaces.WorkspaceSerializer
import ai.rever.boss.components.workspaces.spaceLoadDisposition
import ai.rever.boss.components.workspaces.terminalCommands
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.utils.DeepLinkHandler
import ai.rever.boss.utils.DeepLinkOrigin
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards that `boss://workspace` is held to the rule `boss://terminal?command=` already follows.
 *
 * A Space's terminal tabs run their `initialCommand` when it is applied, so a link that loads a
 * Space can start a shell command. `boss://` is registered with the OS, so the link reaching BOSS
 * says nothing about who sent it - only [DeepLinkOrigin] does, and it has to survive every hop from
 * the link to the window that parses the file.
 */
class WorkspaceLinkOriginTest {
    private val link = "boss://workspace?path=%2Ftmp%2Fshared%2Fspace.json"

    @Test
    fun `an external workspace link carries its origin into the load`() {
        val command = DeepLinkHandler.workspaceLinkCommand(link, DeepLinkOrigin.EXTERNAL)

        assertEquals(CLICommand.LoadWorkspace("/tmp/shared/space.json", DeepLinkOrigin.EXTERNAL), command)
        assertTrue(command!!.requiresConfirmation)
    }

    @Test
    fun `the operator's own boss workspace invocation loads without a prompt`() {
        // Built exactly as BossWorkspaceCommand builds it, spaces and all.
        val path = "/Users/me/My Spaces/space.json"
        val operatorLink = "boss://workspace?path=${URLEncoder.encode(path, "UTF-8")}"

        val command = DeepLinkHandler.workspaceLinkCommand(operatorLink, DeepLinkOrigin.OPERATOR_CLI)

        assertEquals(CLICommand.LoadWorkspace(path, DeepLinkOrigin.OPERATOR_CLI), command)
        assertFalse(command!!.requiresConfirmation)
    }

    @Test
    fun `a load with no stated origin is treated as external`() {
        assertEquals(DeepLinkOrigin.EXTERNAL, CLICommand.LoadWorkspace("space.json").origin)
        assertTrue(CLICommand.LoadWorkspace("space.json").requiresConfirmation)
    }

    @Test
    fun `a link without a path loads nothing`() {
        assertNull(DeepLinkHandler.workspaceLinkCommand("boss://workspace", DeepLinkOrigin.EXTERNAL))
        assertNull(DeepLinkHandler.workspaceLinkCommand("boss://workspace?config=x.json", DeepLinkOrigin.EXTERNAL))
    }

    @Test
    fun `a cold-start queue keeps the origin of a queued load`() {
        // A link that launches BOSS waits for Last Session before it loads; dropping the origin
        // there would make the one path a link-launched app takes the unconfirmed one.
        val queue = ReadinessQueue<CLICommand.LoadWorkspace>()
        val external = CLICommand.LoadWorkspace("/tmp/a.json", DeepLinkOrigin.EXTERNAL)
        val operator = CLICommand.LoadWorkspace("/tmp/b.json", DeepLinkOrigin.OPERATOR_CLI)

        assertFalse(queue.enqueueOrClaimForCaller(external))
        assertFalse(queue.enqueueOrClaimForCaller(operator))

        assertEquals(listOf(true, false), queue.markReadyAndClaimQueued().map { it.requiresConfirmation })
    }

    @Test
    fun `a Space whose terminal runs a command is held when a link asks for it, and loads when the operator does`() {
        val tab = TabConfig(type = "terminal", title = "t", initialCommand = "echo from the file")
        val spaceFile =
            WorkspaceSerializer.serialize(
                LayoutWorkspace(
                    name = "Shared",
                    description = "",
                    layout = SinglePanel(PanelConfig("main", listOf(tab))),
                ),
            )
        val commands = WorkspaceSerializer.deserialize(spaceFile).terminalCommands()

        val external = DeepLinkHandler.workspaceLinkCommand(link, DeepLinkOrigin.EXTERNAL)!!
        val operator = DeepLinkHandler.workspaceLinkCommand(link, DeepLinkOrigin.OPERATOR_CLI)!!

        assertEquals(SpaceLoadDisposition.CONFIRM, spaceLoadDisposition(commands, external.requiresConfirmation))
        assertEquals(SpaceLoadDisposition.LOAD, spaceLoadDisposition(commands, operator.requiresConfirmation))
    }
}
