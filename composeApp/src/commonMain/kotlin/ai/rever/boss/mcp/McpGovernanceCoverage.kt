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
            // Defined nowhere in this repository and in no terminal-tab definition, which by
            // the elimination rule above is evidence it is served here rather than evidence it
            // is registry-served. Recorded as unenforceable deliberately: if the name is dead
            // the claim costs nothing, and if it is live it is BossTerm's. The optimistic
            // default is the one answer that could mislead, on the one entry where the
            // evidence leans the other way.
            "terminal_exec",
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
     * **Nothing calls this yet.** It is a record and a drift guard, not a gate, and a
     * reader who finds this object first should not assume otherwise. The obvious first
     * caller is the Persisted MCP policies surface: an operator can set and see a
     * persisted DENY on `run_command` today, and nothing tells them it will not fire.
     *
     * **The default is a convenience, not a fail-safe, and it is worth being exact about
     * which error it risks.** The two ways this can be wrong are reporting `true` for a
     * tool nothing governs, and reporting `false` for one that is governed. For an object
     * whose whole job is to say what is covered, the first is the dangerous one: it is
     * the error #495 found in the tables, reproduced one layer up. An unknown name is
     * assumed governed anyway, because the registry is open-ended and cannot be
     * enumerated from here, so defaulting to `false` would mark every plugin tool
     * ungoverned and make this useless. That is a practical choice about the common case,
     * and it should not be read as a safety argument it does not make.
     *
     * A three-state answer (GOVERNED / EXTERNALLY_SERVED / UNKNOWN) would force the first
     * real caller to decide what an unclassified name means, which is the shape AGENTS.md
     * prescribes for `blockingDependentsOf`'s `isDisabled` predicate and for the same
     * reason. Worth doing when that caller exists; premature while this has none.
     *
     * Both agent-facing prefixes are stripped. AGENTS.md documents `mcp__bossterm__` as
     * the standalone BossTerm app's namespace, which is the very server these tools come
     * from and so the likeliest prefix to meet them under; leaving it would answer `true`
     * for `mcp__bossterm__run_command`. `DefaultMcpRiskEvaluator` still normalises only
     * `mcp__boss__`, so the two disagree on that namespace until it is widened too.
     */
    fun isEnforceable(toolName: String): Boolean =
        toolName
            .removePrefix("mcp__bossterm__")
            .removePrefix("mcp__boss__") !in EXTERNALLY_SERVED_TOOLS
}
