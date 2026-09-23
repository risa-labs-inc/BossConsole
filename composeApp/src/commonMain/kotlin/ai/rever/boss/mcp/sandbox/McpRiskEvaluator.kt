package ai.rever.boss.mcp.sandbox

import ai.rever.boss.components.workspaces.PanelConfig
import ai.rever.boss.components.workspaces.PredefinedWorkspaces
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.workspace.LayoutWorkspace
import ai.rever.boss.plugin.workspace.SplitConfig

/**
 * Evaluates the risk level of an MCP tool call based on tool name and parsed arguments.
 */
fun interface McpRiskEvaluator {
    fun evaluateRisk(
        toolName: String,
        args: McpToolArgs,
    ): McpRiskAssessment
}

/**
 * Deterministic rule-based risk evaluator for MCP tools.
 *
 * Does not use an LLM and does not log sensitive arguments or secrets.
 */
class DefaultMcpRiskEvaluator : McpRiskEvaluator {
    override fun evaluateRisk(
        toolName: String,
        args: McpToolArgs,
    ): McpRiskAssessment {
        val normalizedName = toolName.removePrefix("mcp__boss__")

        return when {
            // Workspace template materialisation: the agent cannot name a command, only a
            // built-in template id, but a template can still launch `claude
            // --dangerously-skip-permissions`. Classify by what the template runs so a HIGH-risk
            // template never falls through to the LOW default an unclassified tool gets, and the
            // approval dialog shows the actual startup commands in the reason.
            normalizedName in APPLY_TEMPLATE_TOOLS -> {
                evaluateApplyTemplate(toolName, args)
            }

            // Shell / Command execution
            normalizedName in SHELL_TOOLS -> {
                evaluateShellCommand(normalizedName, args)
            }

            // Secrets access
            normalizedName == "secret_get" -> {
                McpRiskAssessment(
                    level = McpRiskLevel.CRITICAL,
                    reason = "Accessing plaintext secret credentials via '$toolName'",
                )
            }

            normalizedName in SECRET_MANAGEMENT_TOOLS -> {
                McpRiskAssessment(
                    level = McpRiskLevel.HIGH,
                    reason = "Credential vault operation via '$toolName'",
                )
            }

            // Docker infrastructure mutations
            normalizedName in DOCKER_DESTRUCTIVE_TOOLS -> {
                McpRiskAssessment(
                    level = McpRiskLevel.CRITICAL,
                    reason = "Docker infrastructure mutation '$toolName'",
                )
            }

            // Destructive Kubernetes / Helm infrastructure operations
            normalizedName in K8S_DESTRUCTIVE_TOOLS -> {
                McpRiskAssessment(
                    level = McpRiskLevel.CRITICAL,
                    reason = "Kubernetes/Helm mutation '$toolName'",
                )
            }

            // File / Codebase write or delete operations
            normalizedName in FILE_WRITE_TOOLS -> {
                McpRiskAssessment(
                    level = McpRiskLevel.HIGH,
                    reason = "File system write operation via '$toolName'",
                )
            }

            // Read-only / safe tools
            normalizedName in READ_ONLY_TOOLS -> {
                McpRiskAssessment(
                    level = McpRiskLevel.LOW,
                    reason = "Read-only tool (returned data may be sensitive) '$toolName'",
                )
            }

            // Unknown / unclassified tools default to LOW
            else -> {
                McpRiskAssessment(
                    level = McpRiskLevel.LOW,
                    reason = "Unclassified tool '$toolName' - defaulting to low risk",
                )
            }
        }
    }

    private fun evaluateShellCommand(
        toolName: String,
        args: McpToolArgs,
    ): McpRiskAssessment {
        val command = args.string("command") ?: args.string("cmd") ?: ""
        val lowerCmd = command.lowercase().trim()

        return when {
            isDestructiveShellCommand(lowerCmd) -> {
                McpRiskAssessment(
                    level = McpRiskLevel.CRITICAL,
                    reason = "Shell execution tool '$toolName' contains potentially destructive command pattern",
                )
            }

            else -> {
                McpRiskAssessment(
                    level = McpRiskLevel.HIGH,
                    reason = "Shell execution tool '$toolName' allows arbitrary command execution",
                )
            }
        }
    }

    // Wording heuristic only: both HIGH and CRITICAL must require approval. This is not a shell parser.
    private fun isDestructiveShellCommand(cmd: String): Boolean {
        if (cmd.isEmpty()) return false
        return cmd.contains("rm -rf") ||
            cmd.contains("del /s") ||
            cmd.contains("format ") ||
            cmd.contains("mkfs") ||
            cmd.contains("git push --force") ||
            cmd.contains("git push -f") ||
            cmd.contains("dd if=") ||
            cmd.contains("chmod -r 777")
    }

    /**
     * Classify [toolName] (`apply_template` / `workspace_apply_template`) by what the named
     * built-in template runs. The agent supplies only the template id, but Claude Code and
     * Code Review launch `claude --dangerously-skip-permissions` and must rate HIGH, while Codex /
     * Gemini / OpenCode launch agent CLIs and rate MEDIUM. Pure editor/browser templates stay LOW.
     *
     * The dialog renders `${level}: ${reason}`, so the commands are embedded in the reason rather
     * than a side channel - the operator sees what is about to start before approving.
     */
    private fun evaluateApplyTemplate(
        toolName: String,
        args: McpToolArgs,
    ): McpRiskAssessment {
        val templateId = args.string("templateId")
        val template = PredefinedWorkspaces.allWorkspaces.firstOrNull { it.id == templateId }
        val commands = template?.layout?.let { extractInitialCommands(it) } ?: emptyList()

        return when {
            templateId.isNullOrBlank() -> {
                McpRiskAssessment(
                    level = McpRiskLevel.HIGH,
                    reason = "Template materialisation '$toolName' has no templateId - failing closed",
                )
            }

            template == null -> {
                McpRiskAssessment(
                    level = McpRiskLevel.HIGH,
                    reason = "Template '$templateId' is not one of the eight shipped layouts",
                )
            }

            commands.isEmpty() -> {
                McpRiskAssessment(
                    level = McpRiskLevel.LOW,
                    reason = "Template '${template.name}' runs no startup commands",
                )
            }

            else -> {
                riskFromCommands(template, commands)
            }
        }
    }

    private fun riskFromCommands(
        template: LayoutWorkspace,
        commands: List<String>,
    ): McpRiskAssessment {
        val commandsText = commands.joinToString(" | ")
        return when {
            commands.any { it.containsDangerouslySkipPermissions() } -> {
                McpRiskAssessment(
                    level = McpRiskLevel.HIGH,
                    reason =
                        "Template '${template.name}' launches an agent with permission skipping. " +
                            "Commands: $commandsText",
                )
            }

            commands.any { it.isAgentCliCommand() } -> {
                McpRiskAssessment(
                    level = McpRiskLevel.MEDIUM,
                    reason = "Template '${template.name}' launches an agent CLI. Commands: $commandsText",
                )
            }

            else -> {
                McpRiskAssessment(
                    level = McpRiskLevel.LOW,
                    reason = "Template '${template.name}' runs only safe shell commands. Commands: $commandsText",
                )
            }
        }
    }

    /**
     * Walk the split tree of [layout] and return every non-blank `initialCommand` set on a
     * terminal tab. Empty when the template opens nothing (Browser Only) or only editor / browser
     * tabs (Code Review's top panes, Claude Code's right pane).
     */
    private fun extractInitialCommands(layout: SplitConfig): List<String> {
        val out = mutableListOf<String>()
        walkPanels(layout) { panel ->
            for (tab in panel.tabs) {
                val cmd = tab.initialCommand
                if (!cmd.isNullOrBlank()) out += cmd
            }
        }
        return out
    }

    private fun walkPanels(
        split: SplitConfig,
        visit: (PanelConfig) -> Unit,
    ) {
        when (split) {
            is SplitConfig.SinglePanel -> {
                visit(split.panel)
            }

            is SplitConfig.VerticalSplit -> {
                walkPanels(split.left, visit)
                walkPanels(split.right, visit)
            }

            is SplitConfig.HorizontalSplit -> {
                walkPanels(split.top, visit)
                walkPanels(split.bottom, visit)
            }
        }
    }

    @Suppress("MaxLineLength")
    private fun String.containsDangerouslySkipPermissions(): Boolean = this.contains("--dangerously-skip-permissions", ignoreCase = true)

    /**
     * A command that starts an agent CLI - Claude, Codex, Gemini or OpenCode. Wording heuristic
     * only, like `isDestructiveShellCommand` - we only need to know whether the operator is
     * approving an agent invocation, not to parse the shell.
     */
    private fun String.isAgentCliCommand(): Boolean {
        val trimmed = this.trim().lowercase()
        return trimmed.contains("claude ") ||
            trimmed.endsWith("claude") ||
            trimmed.contains(" codex ") ||
            trimmed.endsWith(" codex") ||
            trimmed.contains(" gemini ") ||
            trimmed.endsWith(" gemini") ||
            trimmed.contains(" opencode ") ||
            trimmed.endsWith(" opencode")
    }

    companion object {
        /**
         * Tool names the runtime-risk escalation applies to. Currently only
         * `apply_template` / `workspace_apply_template`: the templates those tools launch can run
         * `claude --dangerously-skip-permissions` and must not be silently ALLOWed by a saved
         * rule. Other tools do not trigger the runtime escalation - their classification is
         * fixed and resolved at policyFor time.
         */
        val APPLY_TEMPLATE_TOOLS =
            setOf(
                "apply_template",
                "workspace_apply_template",
            )

        private val SHELL_TOOLS =
            setOf(
                "run_command",
                "run_in_sidebar",
                "run_in_panel",
                "send_input",
                "terminal_exec",
                "k8s_exec",
                // The MCP workspace provider's terminal tool opens a real shell; its
                // `command` argument is the same argument name evaluateShellCommand reads.
                "open_terminal",
                "terminal_open",
            )

        private val SECRET_MANAGEMENT_TOOLS =
            setOf(
                "secret_create",
                "secret_update",
                "secret_delete",
                "secret_search",
                "secrets_list",
            )

        private val DOCKER_DESTRUCTIVE_TOOLS =
            setOf(
                "docker_rm",
                "docker_stop",
                "docker_compose_down",
                "docker_compose_up",
                "docker_build",
                "docker_start",
                "docker_restart",
                "docker_run",
                "docker_exec",
            )

        private val K8S_DESTRUCTIVE_TOOLS =
            setOf(
                "k8s_delete",
                "helm_uninstall",
                "helm_rollback",
                "helm_install",
                "helm_upgrade",
                "k8s_apply",
                "k8s_scale",
                "k8s_rollout_restart",
                "k8s_use_context",
                "k8s_port_forward_stop",
            )

        private val FILE_WRITE_TOOLS =
            setOf(
                "codebase_write",
                "file_delete",
                "file_write",
                "project_replace",
            )

        private val READ_ONLY_TOOLS =
            setOf(
                "codebase_read",
                "codebase_tree",
                "git_status",
                "git_log",
                "docker_ps",
                "k8s_pods",
                "k8s_logs",
                "bookmarks_list",
                "downloads_list",
                "plugins_list",
                "list_tabs",
                "read_scrollback",
            )
    }
}
