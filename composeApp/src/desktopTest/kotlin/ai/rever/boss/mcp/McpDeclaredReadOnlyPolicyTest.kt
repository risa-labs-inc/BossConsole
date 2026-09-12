package ai.rever.boss.mcp

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The governance default must read a tool's own `McpToolDefinition.readOnly`, not only guess
 * from its name (BossConsole#332).
 *
 * `McpMutatingToolCatalog` and `DefaultMcpRiskEvaluator` are both name tables. A tool whose
 * name appears in neither resolves to LOW risk and a non-mutating verdict, so it takes
 * [McpToolPolicyConfig.defaultReadOnlyAction] and runs with no operator prompt - even when
 * the plugin that contributed it declared side effects. These pin that the declaration is
 * consulted, and equally that it can only ever add caution.
 */
class McpDeclaredReadOnlyPolicyTest {
    private val tempFiles = mutableListOf<File>()

    private fun createTempPolicyFile(): File {
        val dir =
            kotlin.io.path
                .createTempDirectory("mcp-declared-readonly-test")
                .toFile()
        return File(dir, "mcp-tool-policy.json").also { tempFiles.add(it) }
    }

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.parentFile?.deleteRecursively() }
        tempFiles.clear()
    }

    /**
     * Names chosen to be absent from every table on both paths: not in
     * `McpMutatingToolCatalog.KNOWN_MUTATING_TOOLS`, carrying none of its mutating suffixes,
     * and unclassified by `DefaultMcpRiskEvaluator` so its risk is LOW. Each one mutates
     * something a user would mind.
     */
    private val unclassifiedMutatingNames = listOf("git_push", "send_email", "publish_release")

    @Test
    fun `a tool that declares side effects is governed as mutating though its name is unclassified`() {
        val engine = McpPolicyEngine(policyFile = null)

        unclassifiedMutatingNames.forEach { name ->
            assertEquals(
                McpPolicyAction.ALLOW,
                engine.policyFor(name),
                "$name should be auto-allowed on its name alone; if not, it no longer demonstrates the gap",
            )
            assertEquals(
                McpPolicyAction.ASK,
                engine.policyFor(name, declaredReadOnly = false),
                "$name declares side effects, so it must reach the operator",
            )
        }
    }

    @Test
    fun `a declared read-only flag cannot weaken the name heuristic`() {
        val engine = McpPolicyEngine(policyFile = null)

        // readOnly defaults to true in the API, so a tool whose author never considered the
        // question is indistinguishable from one that answered "no side effects". Believing
        // it would let any plugin opt out of approval by saying nothing at all.
        listOf("k8s_delete", "docker_rm", "run_command", "secret_get", "codebase_write").forEach { name ->
            assertEquals(
                McpPolicyAction.ASK,
                engine.policyFor(name, declaredReadOnly = true),
                "$name is caught by name and must stay caught whatever it declares",
            )
        }
    }

    @Test
    fun `omitting the declaration preserves the previous name-only behaviour`() {
        val engine = McpPolicyEngine(policyFile = null)

        listOf("k8s_delete", "docker_rm", "secret_get", "run_command").forEach {
            assertEquals(engine.policyFor(it), engine.policyFor(it, declaredReadOnly = true))
        }
        listOf("git_status", "k8s_pods", "codebase_read", "git_push").forEach {
            assertEquals(engine.policyFor(it), engine.policyFor(it, declaredReadOnly = true))
        }
    }

    @Test
    fun `an operator rule outranks the declaration in both directions`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)

        // The declaration reaches only the default branch, below every rule the operator set,
        // so a plugin can never re-prompt for something the operator chose to allow.
        engine.setToolPolicy("git_push", McpPolicyAction.ALLOW)
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("git_push", declaredReadOnly = false))

        engine.setToolPolicy("git_status", McpPolicyAction.DENY)
        assertEquals(McpPolicyAction.DENY, engine.policyFor("git_status", declaredReadOnly = true))
    }

    @Test
    fun `session trust outranks the declaration`() {
        val engine = McpPolicyEngine(policyFile = null)

        engine.trustForSession("git_push")
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("git_push", declaredReadOnly = false))

        engine.revokeSessionTrust("git_push")
        assertEquals(McpPolicyAction.ASK, engine.policyFor("git_push", declaredReadOnly = false))
    }

    @Test
    fun `an unreadable policy file still fails closed for a declared-mutating tool`() {
        val file = createTempPolicyFile()
        file.parentFile?.mkdirs()
        file.writeText("{ this is not json")

        val engine = McpPolicyEngine(policyFile = file)
        assertEquals(McpPolicyAction.DENY, engine.policyFor("git_push", declaredReadOnly = false))
        assertEquals(McpPolicyAction.DENY, engine.policyFor("git_status", declaredReadOnly = true))
    }

    @Test
    fun `isMutating composes the declaration with the name heuristic`() {
        // Declared mutating: believed, whatever the name says.
        assertTrue(McpMutatingToolCatalog.isMutating("git_push", declaredReadOnly = false))
        assertTrue(McpMutatingToolCatalog.isMutating("docker_rm", declaredReadOnly = false))

        // Declared read-only: not believed. The name still decides.
        assertFalse(McpMutatingToolCatalog.isMutating("git_push", declaredReadOnly = true))
        assertTrue(McpMutatingToolCatalog.isMutating("docker_rm", declaredReadOnly = true))

        // The single-argument overload keeps answering on the name alone.
        assertFalse(McpMutatingToolCatalog.isMutating("git_push"))
        assertTrue(McpMutatingToolCatalog.isMutating("docker_rm"))
    }

    @Test
    fun `resolveAction reads the declaration and still yields to an explicit rule`() {
        val base = McpToolPolicyConfig()
        assertEquals(McpPolicyAction.ALLOW, McpMutatingToolCatalog.resolveAction("git_push", base))
        assertEquals(
            McpPolicyAction.ASK,
            McpMutatingToolCatalog.resolveAction("git_push", base, declaredReadOnly = false),
        )

        val ruled = McpToolPolicyConfig(rules = mapOf("git_push" to McpPolicyAction.ALLOW))
        assertEquals(
            McpPolicyAction.ALLOW,
            McpMutatingToolCatalog.resolveAction("git_push", ruled, declaredReadOnly = false),
        )
    }
}
