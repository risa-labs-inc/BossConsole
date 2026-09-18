package ai.rever.boss.mcp.sandbox

import ai.rever.boss.mcp.McpMutatingToolCatalog
import ai.rever.boss.plugin.api.McpToolArgs
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `the cmd alias is honored when the command argument is missing`() {
        assertEquals(
            McpRiskLevel.CRITICAL,
            evaluator.evaluateRisk("run_command", cmdAliasArgs("rm -rf /tmp/cache")).level,
        )
        assertEquals(McpRiskLevel.HIGH, evaluator.evaluateRisk("run_command", cmdAliasArgs("ls -la")).level)
    }

    @Test
    fun `the command argument wins over the cmd alias`() {
        // Precedence, not a merge: `command` is read first and the evaluator never falls
        // through to `cmd` when both are present.
        val both =
            McpToolArgs(
                mapOf("command" to "ls -la", "cmd" to "rm -rf /tmp/cache"),
                "{\"command\":\"ls -la\",\"cmd\":\"rm -rf /tmp/cache\"}",
            )
        assertEquals(McpRiskLevel.HIGH, evaluator.evaluateRisk("run_command", both).level)
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
        for (name in dockerMutatingTools + k8sMutatingTools + fileWriteTools + readOnlyTools) {
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
