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

            // Workspace lifecycle mutations
            normalizedName in WORKSPACE_MUTATION_TOOLS -> {
                McpRiskAssessment(McpRiskLevel.HIGH, "Workspace lifecycle mutation '$toolName'")
            }

            // Secrets access
            normalizedName == "secret_get" -> {
                McpRiskAssessment(McpRiskLevel.CRITICAL, "Accessing plaintext secret credentials via '$toolName'")
            }

            normalizedName in SECRET_MANAGEMENT_TOOLS -> {
                McpRiskAssessment(McpRiskLevel.HIGH, "Credential vault operation via '$toolName'")
            }

            // Docker infrastructure mutations
            normalizedName in DOCKER_DESTRUCTIVE_TOOLS -> {
                McpRiskAssessment(McpRiskLevel.CRITICAL, "Docker infrastructure mutation '$toolName'")
            }

            // Destructive Kubernetes / Helm infrastructure operations
            normalizedName in K8S_DESTRUCTIVE_TOOLS -> {
                McpRiskAssessment(McpRiskLevel.CRITICAL, "Kubernetes/Helm mutation '$toolName'")
            }

            // File / Codebase write or delete operations
            normalizedName in FILE_WRITE_TOOLS -> {
                McpRiskAssessment(McpRiskLevel.HIGH, "File system write operation via '$toolName'")
            }

            // Installs code and writes durable MCP policy in one call
            normalizedName in POLICY_WRITING_TOOLS -> {
                McpRiskAssessment(
                    level = McpRiskLevel.HIGH,
                    reason = "Installs plugins and writes durable MCP policy via '$toolName'",
                )
            }

            // Read-only / safe tools
            normalizedName in READ_ONLY_TOOLS -> {
                McpRiskAssessment(McpRiskLevel.LOW, "Read-only tool (returned data may be sensitive) '$toolName'")
            }

            // Unknown / unclassified tools default to LOW
            else -> {
                McpRiskAssessment(McpRiskLevel.LOW, "Unclassified tool '$toolName' - defaulting to low risk")
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
    // CRITICAL is also what makes a saved "Always Allow" on a shell tool ask again (#1577), so the
    // matcher reads each command of a chain for the flag shapes a destructive call really takes,
    // not only the one spelling of each: `rm -fr`, `rm -r -f`, `/bin/rm -R`, `git push origin
    // main --force`, `Remove-Item -Recurse`. [cmd] arrives lowercased and trimmed.
    private fun isDestructiveShellCommand(cmd: String): Boolean {
        if (cmd.isEmpty()) return false
        val normalized = cmd.replace(WHITESPACE, " ")
        // Split the raw command, not [normalized]: collapsing whitespace first turns a newline
        // into a space, and the next command would hide inside the previous one's tokens.
        return DESTRUCTIVE_WORDING.any { it in normalized } ||
            cmd.split(COMMAND_SEPARATOR).any { segment ->
                val tokens =
                    segment
                        .split(WHITESPACE)
                        .map { token -> token.trim { it in TOKEN_QUOTES } }
                        .filter { it.isNotEmpty() }
                isRecursiveRm(tokens) || isRecursiveWindowsDelete(tokens) || isForcePush(tokens)
            }
    }

    /**
     * `rm` (by any path, after `sudo` or not) with a recursive flag in any spelling or order.
     * GNU rm also takes options after operands (`rm build -r`), so every token up to `--` counts,
     * and every `rm` in the segment is checked, not only the first.
     */
    private fun isRecursiveRm(tokens: List<String>): Boolean {
        val rms = tokens.indices.filter { tokens[it] == "rm" || tokens[it].endsWith("/rm") }
        return rms.any { rm ->
            tokens.drop(rm + 1).takeWhile { it != "--" }.any { flag ->
                flag.startsWith("--recursive") || (flag.startsWith("-") && !flag.startsWith("--") && 'r' in flag)
            }
        }
    }

    /** cmd's `del`/`erase`/`rd`/`rmdir /s`, and PowerShell's `Remove-Item` (or `del`) `-Recurse`. */
    private fun isRecursiveWindowsDelete(tokens: List<String>): Boolean =
        tokens.any { it in WINDOWS_DELETE_COMMANDS } &&
            tokens.any { it == "/s" || it.startsWith("-recurse") }

    /** `git push` with a force flag anywhere after `push`, including `--force-with-lease` and `-uf`. */
    private fun isForcePush(tokens: List<String>): Boolean {
        val push = tokens.indexOf("push")
        if (push < 1 || "git" !in tokens.subList(0, push)) return false
        return tokens.drop(push + 1).any { flag ->
            flag.startsWith("--force") || (flag.startsWith("-") && !flag.startsWith("--") && 'f' in flag)
        }
    }

    companion object {
        /**
         * Whether [toolName] is one of the shell tools, read with the same `mcp__boss__`
         * normalization [evaluateRisk] applies - so a caller deciding "is this a shell call"
         * and the evaluator rating it can never disagree about the name.
         */
        fun isShellTool(toolName: String): Boolean = toolName.removePrefix("mcp__boss__") in SHELL_TOOLS

        private val DESTRUCTIVE_WORDING =
            listOf("rm -rf", "del /s", "format ", "mkfs", "git push --force", "git push -f", "dd if=", "chmod -r 777")

        private val WHITESPACE = Regex("""\s+""")

        /** Where one command of a chain ends: `;`, `&`/`&&`, `|`/`||`, a newline, `$(` or a backtick. */
        private val COMMAND_SEPARATOR = Regex("""[;&|\n`]|\$\(""")

        /** Quoting and grouping stripped from a token's ends, so `(rm` and `"-rf"` still read. */
        private val TOKEN_QUOTES = setOf('"', '\'', '(', ')', '{', '}')

        private val WINDOWS_DELETE_COMMANDS = setOf("del", "erase", "rd", "rmdir", "remove-item")

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

        // The workspace provider's lifecycle tools are family siblings of the terminal
        // tools above: they mutate the workspace catalog (opening windows, persisting or
        // deleting workspace state) but take no `command` argument, so unlike the shell
        // tools they carry a flat HIGH instead of a command-inspection floor.
        private val WORKSPACE_MUTATION_TOOLS =
            setOf(
                "open_workspace",
                "workspace_open",
                "create_workspace",
                "workspace_create",
                "close_workspace",
                "workspace_close",
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
                // The editor-tab plugin's write door. Its name ends in "_file", so no
                // suffix heuristic sees it, and leaving it unclassified rated a file
                // write LOW while McpMutatingToolCatalog already names it (#1329 review).
                "editor_write_file",
            )

        /**
         * Tools that install executable code and write durable policy in one approved call.
         *
         * HIGH rather than the unclassified default of LOW, and the level is doing real work
         * here: the gate itself is unchanged (`pack_apply` is mutating by name and by
         * declaration, so `policyFor` already reaches `defaultMutatingAction`), but the host
         * requires Review-then-Confirm before a HIGH/CRITICAL tool can be granted a *durable*
         * ALLOW. Left at LOW, one "Always Allow" - or one "Trust This Plugin" on the pack
         * plugin - hands any agent the operator's own policy authority, unattended and for
         * good: `pack_apply` installs plugins from the network and writes ALLOW rules for
         * arbitrary tools and providers. The parser's pack-tool guard stops a pack from
         * bootstrapping that; it cannot stop the one-click grant that reaches it directly.
         */
        private val POLICY_WRITING_TOOLS =
            setOf(
                "pack_apply",
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
                // The workspace provider's listing tools are pure reads.
                "list_workspaces",
                "workspace_list",
            )
    }
}
