package ai.rever.boss.mcp.sandbox

import ai.rever.boss.plugin.api.McpToolArgs

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

    companion object {
        private val SHELL_TOOLS =
            setOf(
                "run_command",
                "run_in_sidebar",
                "run_in_panel",
                "send_input",
                "terminal_exec",
                "k8s_exec",
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
