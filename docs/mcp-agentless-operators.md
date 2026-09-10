# MCP tools for agent-less operators

BOSS exposes MCP on loopback (`127.0.0.1`, typically port `7677`) for local coding CLIs
(Claude Code, Codex, Gemini, OpenCode, …). Kill-switches and the tool inventory must stay
reachable **without** those CLIs attached.

## Where to manage kill-switches

1. Open **Toolbox** (sidebar / host actions; panel id `plugin-manager`).
2. Use the **MCP** tab to browse contributed tools and toggle per-tool kill-switches.
3. Or use **double-shift search**: query a tool name (clients see `mcp__boss__<name>`). Selecting
   an MCP row opens Toolbox and shows a status hint naming the tool. Select the MCP tab, then search
   for that name; the host does not select the tab or scroll to the toggle automatically. It does **not**
   invoke the tool (arguments cannot be collected from search).

If Toolbox is unavailable in this window, selection reports that instead. The status hint is
shared across windows, while Toolbox opens only in the window where you selected the row.

Persisted disables live in the app data file `mcp-disabled-tools.json`. Fail-closed faults
surface in the bottom status bar (`McpKillSwitchFault`).

## Minimum attach path (for agents)

1. Sign in to BOSS.
2. Open Toolbox > MCP. Its server controls require the Terminal plugin to be loaded.
3. Use the attach button for your installed client. Check the displayed server port rather than
   assuming `7677`, since another listener can cause a different port to be selected.
4. Prefer kill-switches in Toolbox before relying on RBAC alone - for an admin desktop session,
   `permitted()` short-circuits on admin, so the kill-switch is the control that always applies.

See also issue [#380](https://github.com/risa-labs-inc/BossConsole/issues/380).
