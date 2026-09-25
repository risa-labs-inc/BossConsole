package ai.rever.boss.mcp.sandbox

import ai.rever.boss.mcp.McpMutatingToolCatalog
import ai.rever.boss.plugin.api.McpToolArgs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [DefaultMcpRiskEvaluator]'s classification contract, pinned name by name: which tool names
 * land in which [McpRiskLevel] tier, how the `mcp__boss__` prefix is normalized, what the
 * shell-command wording heuristic matches, and what the operator-facing
 * [McpRiskAssessment.reason] strings promise.
 *
 * This is the contract the approval flow leans on. `McpPolicyEngine.policyFor` builds its
 * risk-based default from a fresh evaluator called with empty args (`risk >= McpRiskLevel.HIGH`
 * picks the mutating branch), `McpToolRegistryImpl` attaches the assessment of the live
 * invocation to the approval request, and the policy manager dialog previews the same
 * evaluation for the operator. #808's provider readOnly awareness deliberately lives outside
 * this class - the engine hands `declaredReadOnly` to `McpMutatingToolCatalog` beside the risk
 * level - so these tests pin that risk depends on the tool name and the command argument,
 * and nothing else.
 *
 * The evaluator is fail-OPEN at the name level and these tests pin that as-is: unclassified
 * names default to LOW, including near-miss names (`secret_get_all`) and names under a
 * non-boss server prefix (`mcp__vault__secret_get` is not normalized). Nothing else
 * necessarily fails closed for those names: only a provider's explicit `readOnly = false`
 * declaration does. Any move toward a fail-closed default must be a conscious contract change
 * that turns these pins red.
 *
 * [McpRiskLevel.MEDIUM] is reserved by the enum but assigned by no rule today; that absence
 * is pinned too, because a silent future MEDIUM emission would change which tools clear the
 * engine's `>= HIGH` default. Complements `McpToolSandboxTest`'s spot checks with the
 * exhaustive per-category surface.
 */
class McpRiskEvaluatorTest {
    private val evaluator = DefaultMcpRiskEvaluator()
    private val emptyArgs = McpToolArgs(emptyMap(), "{}")

    // The documented tool surface, pinned in full per category.
    private val shellTools =
        setOf(
            "run_command",
            "run_in_sidebar",
            "run_in_panel",
            "send_input",
            "terminal_exec",
            "k8s_exec",
            "open_terminal",
            "terminal_open",
        )
    private val secretVaultTools =
        setOf("secret_create", "secret_update", "secret_delete", "secret_search", "secrets_list")
    private val dockerMutatingTools =
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
    private val k8sMutatingTools =
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
    private val fileWriteTools = setOf("codebase_write", "file_delete", "file_write", "project_replace")
    private val readOnlyTools =
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
            "list_workspaces",
            "workspace_list",
        )

    // The v9.5.21 workspace lifecycle tools: mutating by name, but with no
    // `command` argument, so a flat HIGH (no shell escalation) is the tier.
    private val workspaceMutationTools =
        setOf(
            "open_workspace",
            "workspace_open",
            "create_workspace",
            "workspace_create",
            "close_workspace",
            "workspace_close",
        )

    // Every wording the destructive heuristic pins today. Commands deliberately avoid JSON
    // metacharacters so the mirrored raw-JSON form below stays trivially valid.
    private val destructiveCommands =
        listOf(
            "rm -rf /tmp/cache",
            "del /s /q staging",
            "format /dev/sda1",
            "mkfs.ext4 /dev/sdb",
            "git push --force origin dev",
            "git push -f origin dev",
            "dd if=/dev/zero of=/dev/sda",
            "chmod -r 777 /srv/app",
        )
    private val benignCommands = listOf("ls -la", "git status", "pwd", "echo hello")

    /** Map and raw JSON stay in sync for either representation read by `McpToolArgs.string`. */
    @Suppress("MaxLineLength")
    private fun commandArgs(command: String): McpToolArgs = McpToolArgs(mapOf("command" to command), "{\"command\":\"$command\"}")

    private fun cmdAliasArgs(cmd: String): McpToolArgs = McpToolArgs(mapOf("cmd" to cmd), "{\"cmd\":\"$cmd\"}")

    // ---------------------------------------------------------------------
    // Tier pins: every risk tier the default rules can emit
    // ---------------------------------------------------------------------

    @Test
    fun `read-only inspection tools evaluate to LOW and say so`() {
        for (name in readOnlyTools) {
            val assessment = evaluator.evaluateRisk(name, emptyArgs)
            assertEquals(McpRiskLevel.LOW, assessment.level, name)
            assertTrue(assessment.reason.contains("Read-only"), "$name reason: ${assessment.reason}")
            assertTrue(assessment.reason.contains(name), "$name reason: ${assessment.reason}")
        }
    }

    @Test
    fun `file system write tools evaluate to HIGH`() {
        for (name in fileWriteTools) {
            val assessment = evaluator.evaluateRisk(name, emptyArgs)
            assertEquals(McpRiskLevel.HIGH, assessment.level, name)
            assertTrue(assessment.reason.contains("File system write"), "$name reason: ${assessment.reason}")
        }
    }

    @Test
    fun `reading a plaintext secret is CRITICAL while vault bookkeeping stays HIGH`() {
        // secret_get hands an agent the plaintext credential itself - the one secret operation
        // rated above the vault tools that merely manage where credentials live.
        val read = evaluator.evaluateRisk("secret_get", emptyArgs)
        assertEquals(McpRiskLevel.CRITICAL, read.level)
        assertTrue(read.reason.contains("secret credentials"), read.reason)

        for (name in secretVaultTools) {
            val assessment = evaluator.evaluateRisk(name, emptyArgs)
            assertEquals(McpRiskLevel.HIGH, assessment.level, name)
            assertTrue(assessment.reason.contains("Credential vault"), "$name reason: ${assessment.reason}")
        }
    }

    @Test
    fun `docker infrastructure mutations evaluate to CRITICAL`() {
        for (name in dockerMutatingTools) {
            val assessment = evaluator.evaluateRisk(name, emptyArgs)
            assertEquals(McpRiskLevel.CRITICAL, assessment.level, name)
            assertTrue(
                assessment.reason.contains("Docker infrastructure mutation"),
                "$name reason: ${assessment.reason}",
            )
        }
    }

    @Test
    fun `kubernetes and helm mutations evaluate to CRITICAL`() {
        for (name in k8sMutatingTools) {
            val assessment = evaluator.evaluateRisk(name, emptyArgs)
            assertEquals(McpRiskLevel.CRITICAL, assessment.level, name)
            assertTrue(assessment.reason.contains("Kubernetes/Helm mutation"), "$name reason: ${assessment.reason}")
        }
    }

    // ---------------------------------------------------------------------
    // Shell tools: the only tier that consults the arguments
    // ---------------------------------------------------------------------

    @Test
    fun `shell tools keep at least HIGH risk with no command to inspect`() {
        // The floor matters more than the escalation: with no command argument at all (the
        // shape McpPolicyEngine.policyFor passes) a shell tool must still land in an approval
        // tier, so an empty payload never slips a real shell past the gate.
        for (name in shellTools) {
            val noCommand = evaluator.evaluateRisk(name, emptyArgs)
            assertEquals(McpRiskLevel.HIGH, noCommand.level, name)
            assertTrue(noCommand.reason.contains("arbitrary command execution"), name)

            // An empty command or a bag of unrelated arguments is the same as no command.
            assertEquals(McpRiskLevel.HIGH, evaluator.evaluateRisk(name, commandArgs("")).level, "$name empty command")
            val unrelatedArgs = McpToolArgs(mapOf("path" to "/etc/hosts"), "{\"path\":\"/etc/hosts\"}")
            assertEquals(McpRiskLevel.HIGH, evaluator.evaluateRisk(name, unrelatedArgs).level, "$name non-command args")
        }
    }

    @Test
    fun `shell tools escalate to CRITICAL on destructive command wording`() {
        for (name in shellTools) {
            for (command in destructiveCommands) {
                val assessment = evaluator.evaluateRisk(name, commandArgs(command))
                assertEquals(McpRiskLevel.CRITICAL, assessment.level, "$name: $command")
                assertTrue(assessment.reason.contains("destructive command pattern"), "$name: $command")
            }
        }
    }

    @Test
    fun `benign shell commands stay at HIGH`() {
        for (name in shellTools) {
            for (command in benignCommands) {
                assertEquals(
                    McpRiskLevel.HIGH,
                    evaluator.evaluateRisk(name, commandArgs(command)).level,
                    "$name: $command",
                )
            }
        }
    }

    @Test
    fun `destructive wording is matched case-insensitively after trimming`() {
        for (shouted in listOf("  RM -RF /tmp/cache  ", "GIT PUSH --FORCE origin dev")) {
            val assessment = evaluator.evaluateRisk("run_command", commandArgs(shouted))
            assertEquals(McpRiskLevel.CRITICAL, assessment.level, shouted)
        }
    }

    // #1577: CRITICAL is what makes a saved "Always Allow" ask again, so the destructive tier has
    // to catch the flag shapes a real call takes, not just one spelling of each.
    @Test
    fun `destructive commands are caught in any flag order, spacing, path or chain position`() {
        for (command in listOf(
            "rm -fr build",
            "rm -r -f build",
            "rm  -rf   build",
            "/bin/rm -R build",
            "sudo rm --recursive build",
            "cd /srv && rm -fr cache",
            "true; rm -r /tmp/x",
            "echo $(rm -rf ~)",
            "rd /s /q build",
            "rmdir /S build",
            "Remove-Item -Recurse -Force C:\\build",
            "git push origin main --force",
            "git push --force-with-lease origin dev",
            "git push -uf origin dev",
            "ls\nrm -r /tmp/x",
            "rm notes.txt\nrm -r build",
            "rm build -r",
            "rm -f build -R",
        )) {
            val level = evaluator.evaluateRisk("run_command", commandArgs(command)).level
            assertEquals(McpRiskLevel.CRITICAL, level, command)
        }
    }

    // The other side of the same line: routine calls a user "Always Allow"s must stay HIGH, or
    // escalation would ask for them too and "Always Allow" would mean nothing.
    @Test
    fun `routine commands that share words with destructive ones stay HIGH`() {
        for (command in listOf(
            "rm notes.txt",
            "rm -f notes.txt",
            "grep -r TODO src",
            "ls -R",
            "git push origin main",
            "git push -u origin main",
            "del notes.txt",
            "git log --format=%h",
        )) {
            val level = evaluator.evaluateRisk("run_command", commandArgs(command)).level
            assertEquals(McpRiskLevel.HIGH, level, command)
        }
    }

    @Test
    fun `isShellTool reads names with the same prefix normalization as the evaluator`() {
        assertTrue(DefaultMcpRiskEvaluator.isShellTool("run_command"))
        assertTrue(DefaultMcpRiskEvaluator.isShellTool("mcp__boss__run_command"))
        assertFalse(DefaultMcpRiskEvaluator.isShellTool("mcp__other__run_command"))
        assertFalse(DefaultMcpRiskEvaluator.isShellTool("docker_rm"))
    }

    @Test
    fun `the cmd alias is honored when the command argument is missing`() {
        assertEquals(
            McpRiskLevel.CRITICAL,
            evaluator.evaluateRisk("run_command", cmdAliasArgs("rm -rf /tmp/cache")).level,
        )
        assertEquals(McpRiskLevel.HIGH, evaluator.evaluateRisk("run_command", cmdAliasArgs("ls -la")).level)
    }

    @Test
    fun `a destructive payload under any argument rates CRITICAL, not only under command`() {
        // A merge, not precedence (#1624): a tool may read `cmd`, or `text`, or anything else, so a
        // harmless `command` must not hide a destructive value beside it. Rating on the most
        // dangerous string can only err toward asking.
        val both =
            McpToolArgs(
                mapOf("command" to "ls -la", "cmd" to "rm -rf /tmp/cache"),
                "{\"command\":\"ls -la\",\"cmd\":\"rm -rf /tmp/cache\"}",
            )
        assertEquals(McpRiskLevel.CRITICAL, evaluator.evaluateRisk("run_command", both).level)
    }

    // send_input carries its keystrokes in `text` (TerminalServiceMain's schema), and plugin-defined
    // shell tools may use any key - before #1624 none of these could ever rate CRITICAL.
    @Test
    fun `shell payloads under other keys and inside nested values are read`() {
        fun args(json: String) = McpToolArgs(emptyMap(), json)
        for (json in listOf(
            """{"sessionId":"s1","text":"rm -rf /srv/app"}""",
            """{"input":"git push origin main --force"}""",
            """{"steps":["ls","rm -fr build"]}""",
            """{"script":{"body":"mkfs.ext4 /dev/sdb"}}""",
        )) {
            assertEquals(McpRiskLevel.CRITICAL, evaluator.evaluateRisk("send_input", args(json)).level, json)
        }
        assertEquals(McpRiskLevel.HIGH, evaluator.evaluateRisk("send_input", args("""{"text":"ls -la"}""")).level)
        // Unparseable raw arguments fall back to the named keys rather than failing.
        assertEquals(McpRiskLevel.HIGH, evaluator.evaluateRisk("send_input", args("not json")).level)
    }

    // Review on #1650: the scan walks agent-controlled JSON, so it must survive a hostile shape -
    // a StackOverflowError here would escape the registry's invoke before its ledger record.
    @Test
    fun `a 50k-deep payload is rated without overflowing the stack`() {
        val depth = 50_000
        val deep = "[".repeat(depth) + "\"ls\"" + "]".repeat(depth)

        val assessment = evaluator.evaluateRisk("send_input", McpToolArgs(emptyMap(), deep))

        // Past the node cap the payload cannot be vouched for, so it is asked about.
        assertEquals(McpRiskLevel.CRITICAL, assessment.level)
        assertTrue(assessment.reason.contains("nested too deeply to inspect"), assessment.reason)
    }

    @Test
    fun `deeply nested objects are rated without overflowing the stack`() {
        val depth = 50_000
        val deep = "{\"a\":".repeat(depth) + "\"ls\"" + "}".repeat(depth)

        assertEquals(McpRiskLevel.CRITICAL, evaluator.evaluateRisk("send_input", McpToolArgs(emptyMap(), deep)).level)
    }

    // The depth check counts structure, not text: brackets inside a string are keystrokes.
    @Test
    fun `brackets inside a string are not nesting`() {
        val typed = "[".repeat(500) + " \\\" ] still text"
        val args = McpToolArgs(emptyMap(), """{"text":"$typed"}""")

        assertEquals(McpRiskLevel.HIGH, evaluator.evaluateRisk("send_input", args).level)
    }

    @Test
    fun `a payload wider than the node cap is asked about, not rated on what was seen`() {
        val wide = (1..20_000).joinToString(",", prefix = "[", postfix = "]") { "\"ls\"" }

        val assessment = evaluator.evaluateRisk("send_input", McpToolArgs(emptyMap(), wide))

        assertEquals(McpRiskLevel.CRITICAL, assessment.level)
        assertTrue(assessment.reason.contains("too large to inspect fully"), assessment.reason)
    }

    // The documented fallback, pinned for real: when the raw text does not parse, the named keys
    // are still read.
    @Test
    fun `unparseable raw arguments still rate the command key`() {
        val args = McpToolArgs(mapOf("command" to "rm -rf /"), "not json")

        assertEquals(McpRiskLevel.CRITICAL, evaluator.evaluateRisk("run_command", args).level)
    }

    @Test
    fun `a line continuation cannot split a destructive command in two`() {
        for (command in listOf(
            "rm \\\n-rf /srv",
            "rm -r \\\r\n-f /srv",
            "Remove-Item `\n-Recurse C:\\build",
            "rd ^\n/s build",
        )) {
            val level = evaluator.evaluateRisk("run_command", commandArgs(command)).level
            assertEquals(McpRiskLevel.CRITICAL, level, command)
        }
        assertEquals(McpRiskLevel.HIGH, evaluator.evaluateRisk("run_command", commandArgs("echo \\\nhello")).level)
    }

    @Test
    fun `name-based classifications ignore the command argument`() {
        // Only shell tools consult args. For every other category the tier is fixed by the
        // name, so no argument string can move a mutating tool down or a read-only tool up.
        val destructive = commandArgs("rm -rf /tmp/cache")
        assertEquals(McpRiskLevel.CRITICAL, evaluator.evaluateRisk("docker_rm", destructive).level)
        assertEquals(McpRiskLevel.CRITICAL, evaluator.evaluateRisk("secret_get", destructive).level)
        assertEquals(McpRiskLevel.HIGH, evaluator.evaluateRisk("file_write", destructive).level)
        assertEquals(McpRiskLevel.LOW, evaluator.evaluateRisk("codebase_read", destructive).level)
        // The workspace lifecycle tier is the argument-insensitivity itself: a
        // destructive `command` string must not escalate a tool that cannot
        // execute anything - the reason it is its own set, not SHELL_TOOLS.
        assertEquals(McpRiskLevel.HIGH, evaluator.evaluateRisk("open_workspace", destructive).level)
    }

    // ---------------------------------------------------------------------
    // Unclassified names: the documented fail-open default
    // ---------------------------------------------------------------------

    @Test
    fun `unclassified names default to LOW and quote the requested name`() {
        val assessment = evaluator.evaluateRisk("custom_unknown_tool", emptyArgs)
        assertEquals(McpRiskLevel.LOW, assessment.level)
        assertTrue(assessment.reason.contains("Unclassified tool"), assessment.reason)
        assertTrue(assessment.reason.contains("custom_unknown_tool"), assessment.reason)
    }

    @Test
    fun `odd tool names take the unclassified default`() {
        for (name in listOf("", " ", "mcp__boss__", "0xdeadbeef", "robot-tool", "pkg.sub/tool", "run", "list")) {
            assertEquals(McpRiskLevel.LOW, evaluator.evaluateRisk(name, emptyArgs).level, "[$name]")
        }
    }

    @Test
    fun `near-miss names do not partially match a category`() {
        // Classification is exact-match on purpose; a fuzzy matcher would rate unreviewed
        // names by their spelling instead of a reviewed catalog. These near-misses fall to the
        // LOW default and are not rescued by the mutating catalog unless the provider explicitly
        // declares `readOnly = false`.
        for (name in listOf(
            "secret_get_all",
            "secret_getter",
            "docker_rm_force",
            "codebase_read_write",
            "run_command2",
            "file_writer",
            "k8s_deletes",
        )) {
            assertEquals(McpRiskLevel.LOW, evaluator.evaluateRisk(name, emptyArgs).level, name)
        }
    }

    @Test
    fun `every mutating catalog tool rates at least HIGH in the risk evaluator`() {
        val mismatches =
            McpMutatingToolCatalog.KNOWN_MUTATING_TOOLS.filterTo(mutableSetOf()) { name ->
                evaluator.evaluateRisk(name, emptyArgs).level < McpRiskLevel.HIGH
            }

        assertTrue(mismatches.isEmpty(), "mutating catalog tools rated below HIGH: $mismatches")
    }

    @Test
    fun `no read-only evaluator tool is classified mutating by the catalog`() {
        // The reverse of the sync pin above: the catalog's MUTATING_SUFFIXES
        // grows by name pattern (and a read-only tool can be renamed), so a
        // future _stop/_delete suffix or rename could make the catalog call a
        // tool mutating while the evaluator rates it read-only LOW - a silent
        // contradiction between the two classification points.
        val contradictions =
            readOnlyTools.filterTo(mutableSetOf()) { name ->
                McpMutatingToolCatalog.isMutating(name, declaredReadOnly = true)
            }

        assertTrue(contradictions.isEmpty(), "catalog/evaluator read-only contradictions: $contradictions")
    }

    // ---------------------------------------------------------------------
    // Prefix normalization
    // ---------------------------------------------------------------------

    @Test
    fun `only the boss server prefix is stripped before classification`() {
        // MCP client namespacing is mcp__<server>__<tool>; the evaluator recognizes exactly the
        // boss server's form.
        assertEquals(McpRiskLevel.LOW, evaluator.evaluateRisk("mcp__boss__codebase_read", emptyArgs).level)
        assertEquals(McpRiskLevel.HIGH, evaluator.evaluateRisk("mcp__boss__run_command", commandArgs("ls -la")).level)
        assertEquals(
            McpRiskLevel.CRITICAL,
            evaluator.evaluateRisk("mcp__boss__run_command", commandArgs("rm -rf /tmp/cache")).level,
        )
        assertEquals(McpRiskLevel.CRITICAL, evaluator.evaluateRisk("mcp__boss__secret_get", emptyArgs).level)
        assertEquals(McpRiskLevel.CRITICAL, evaluator.evaluateRisk("mcp__boss__docker_rm", emptyArgs).level)

        // A look-alike prefix is not the boss prefix and is not stripped.
        assertEquals(McpRiskLevel.LOW, evaluator.evaluateRisk("mcp__boss_extra__run_command", emptyArgs).level)
    }

    @Test
    fun `names under a non-boss server prefix are unclassified and stay LOW`() {
        // The fail-open shape, pinned as-is: only the boss server's namespaced form is
        // classified, so the same tool name served by any other MCP server lands in the LOW
        // default. The approval flow must not read this LOW as a safety verdict for those.
        for (name in listOf("mcp__vault__secret_get", "mcp__research__docker_rm", "mcp__ide__run_command")) {
            assertEquals(McpRiskLevel.LOW, evaluator.evaluateRisk(name, emptyArgs).level, name)
        }
    }

    @Test
    fun `assessment reasons quote the original requested tool name`() {
        // The operator-facing reason keeps the full name the caller asked about: the prefix
        // is normalized for matching only, never for display.
        for (name in listOf("mcp__boss__secret_get", "mcp__boss__codebase_read", "mcp__vault__secret_get")) {
            val assessment = evaluator.evaluateRisk(name, emptyArgs)
            assertTrue(assessment.reason.isNotBlank(), name)
            assertTrue(assessment.reason.contains(name), "$name reason: ${assessment.reason}")
        }
    }

    // ---------------------------------------------------------------------
    // Monotonicity and ordering pins
    // ---------------------------------------------------------------------

    @Test
    fun `a read-only tool rates strictly lower than its mutating counterpart`() {
        val pairs =
            listOf(
                "codebase_read" to "codebase_write",
                "codebase_read" to "file_delete",
                "codebase_tree" to "project_replace",
                "git_status" to "file_write",
                "docker_ps" to "docker_rm",
                "k8s_pods" to "k8s_delete",
                "k8s_logs" to "k8s_apply",
                // k8s_exec is a shell tool: even its no-arg HIGH floor sits above k8s_logs.
                "k8s_logs" to "k8s_exec",
                "list_workspaces" to "create_workspace",
            )
        for ((readOnly, mutating) in pairs) {
            val readOnlyLevel = evaluator.evaluateRisk(readOnly, emptyArgs).level
            val mutatingLevel = evaluator.evaluateRisk(mutating, emptyArgs).level
            assertTrue(
                readOnlyLevel < mutatingLevel,
                "$readOnly ($readOnlyLevel) must rate below $mutating ($mutatingLevel)",
            )
        }
    }

    @Test
    fun `adding a mutating operation never lowers the evaluated risk`() {
        // Monotonicity across the argument dimension: escalating a shell tool's payload can
        // only raise its tier, and for name-classified tools the payload cannot move the tier
        // in either direction.
        for (name in shellTools) {
            val floor = evaluator.evaluateRisk(name, emptyArgs).level
            val benign = evaluator.evaluateRisk(name, commandArgs("ls -la")).level
            val destructive = evaluator.evaluateRisk(name, commandArgs("rm -rf /tmp/cache")).level
            assertTrue(benign >= floor, "$name: benign $benign must not fall below the no-arg floor $floor")
            assertTrue(destructive >= benign, "$name: destructive $destructive must not fall below benign $benign")
        }

        val destructiveArgs = commandArgs("rm -rf /tmp/cache")
        for (name in dockerMutatingTools + k8sMutatingTools + fileWriteTools + workspaceMutationTools + readOnlyTools) {
            assertEquals(
                evaluator.evaluateRisk(name, emptyArgs).level,
                evaluator.evaluateRisk(name, destructiveArgs).level,
                name,
            )
        }
    }

    @Test
    fun `the default rules never assign the MEDIUM tier`() {
        // MEDIUM is reserved by McpRiskLevel but produced by no rule today. The absence is
        // contract: the policy engine's default gate keys on >= HIGH, so a silent MEDIUM
        // emission would change which tools stop hitting the approval default.
        val surface =
            shellTools + secretVaultTools + dockerMutatingTools + k8sMutatingTools + fileWriteTools +
                workspaceMutationTools +
                readOnlyTools + setOf("secret_get") +
                shellTools.map { "mcp__boss__$it" } +
                setOf("custom_unknown_tool", "", "mcp__vault__secret_get", "secret_get_all")
        for (name in surface) {
            val level = evaluator.evaluateRisk(name, emptyArgs).level
            assertTrue(level != McpRiskLevel.MEDIUM, "$name: $level")
        }
    }

    @Test
    fun `evaluations are pure so a fresh evaluator per call reproduces them`() {
        // McpPolicyEngine.policyFor constructs a new DefaultMcpRiskEvaluator on every check;
        // identical inputs must keep producing the identical assessment.
        val first = evaluator.evaluateRisk("run_command", commandArgs("rm -rf /tmp/cache"))
        val second = DefaultMcpRiskEvaluator().evaluateRisk("run_command", commandArgs("rm -rf /tmp/cache"))
        assertEquals(first, second)
    }
}
