package ai.rever.boss.mcp

/**
 * Which MCP tools the governance path can actually act on.
 *
 * Policy, operator approval and the operation ledger all live inside
 * [McpToolRegistryCore.invoke], and that function opens by looking the tool up in
 * the registry. A tool the registry does not own cannot be governed by it, cannot
 * be refused by it, and never appears in the ledger. It simply never arrives.
 *
 * The MCP server an agent attaches to is BossTerm's, and terminal-tab assembles it
 * from three sources, only one of which is the registry:
 *
 * | Source | Reaches the gate |
 * |---|---|
 * | `McpDynamicTools.registerOne`, which calls back through `registry.invoke` | yes |
 * | `McpHostTools.bossHostMcpTools`, via `BossTermMcpConfig.additionalTools` | no |
 * | BossTerm's own built-ins, served by `ai.rever.bossterm.compose.mcp` | no |
 *
 * The first source is the browser, editor, docker, kubernetes and secret tools.
 * The other two are the terminal, which is where shell execution lives.
 *
 * This object exists because both governance tables name tools from the second and
 * third rows. That is not a mistake in the tables, and the fix is not to delete the
 * entries: the names are right, the classification is right, and if the gate ever
 * gains a way to receive these tools the entries are what will make them work.
 * What was missing was anywhere that said the entries are currently inert, so the
 * tables read as coverage they do not provide.
 *
 * See BossConsole#495 for the full write-up and the options for closing it.
 */
object McpGovernanceCoverage {
    /**
     * Tools served to agents by BossTerm rather than by the host registry, and so
     * outside everything in this package.
     *
     * Derived by elimination rather than by reading BossTerm, which is a bundled
     * library: none of these is defined anywhere in this repository, and none is in
     * terminal-tab's `bossHostMcpToolDefs` except where noted, which leaves
     * BossTerm's own server as the only source. If that is wrong for any entry, the
     * entry is wrong and this set is the one place to correct it.
     */
    val EXTERNALLY_SERVED_TOOLS: Set<String> =
        setOf(
            // Shell execution, and the reason this object exists.
            "run_command",
            "run_in_panel",
            "send_input",
            "send_signal",
            // terminal-tab's own, through BossTermMcpConfig.additionalTools.
            "run_in_sidebar",
            "cli",
            // Terminal reads. Scrollback routinely contains tokens.
            "read_scrollback",
            "read_debug_console",
            "search_output",
            "get_last_command",
            // Workspace structure and display.
            "list_tabs",
            "get_active_tab",
            "list_panes",
            "close_panel",
            "show_image",
            // Edits the tool surface itself, and cannot be disabled through it.
            "manage_tools",
        )

    /**
     * Whether a governance decision about [toolName] can be enforced.
     *
     * False does not mean the tool is safe or that the policy for it is wrong. It
     * means the decision is currently advisory: the tables will answer, and nothing
     * consults them on the path this tool actually takes.
     *
     * A name this does not know is assumed governed, because the registry is the
     * only source this repository can enumerate, and a plugin tool that is wrongly
     * reported as governed is a much smaller error than a terminal tool wrongly
     * reported as ungoverned would be. [EXTERNALLY_SERVED_TOOLS] is the exception
     * list precisely so that the default is the common case.
     */
    fun isEnforceable(toolName: String): Boolean =
        toolName.removePrefix("mcp__boss__") !in EXTERNALLY_SERVED_TOOLS
}
