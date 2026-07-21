package ai.rever.boss.mcp

/**
 * The host's copy of the Tool Evolver plugin's MCP contract.
 *
 * These strings are OWNED by the `tool-evolver` plugin
 * (boss-plugin-tool-evolver, `ToolEvolverMcpTools.kt`) — the host deliberately
 * has no compile-time dependency on the plugin, so this object is the single
 * greppable place where the runtime coupling lives. If the plugin renames the
 * tool or its argument, update here too; the only symptom of drift is the
 * "Open Evolver" panel-menu item disappearing (tool-name mismatch) or the
 * evolver opening for the wrong/missing plugin (argument mismatch).
 */
object EvolverContract {
    /** Tool that opens the evolver tab for a plugin; ungated, so it gates the "Report Issue" item. */
    const val OPEN_TOOL = "evolver_open"

    /**
     * The permission-gated evolve tool. Its presence in the (RBAC-filtered)
     * registry means the current user may evolve, so it gates the "Open Evolver"
     * menu item — shown only to users who hold the evolve permission (or admins).
     */
    const val EVOLVE_TOOL = "evolver_evolve"

    /** Required argument of [OPEN_TOOL]: the target plugin's id. */
    const val ARG_PLUGIN_ID = "plugin_id"

    /** Optional argument of [OPEN_TOOL]: which section to open ([SECTION_ISSUE], probe, evolve). */
    const val ARG_SECTION = "section"

    /** [ARG_SECTION] value that opens the evolver's file-a-GitHub-issue section. */
    const val SECTION_ISSUE = "issue"
}
