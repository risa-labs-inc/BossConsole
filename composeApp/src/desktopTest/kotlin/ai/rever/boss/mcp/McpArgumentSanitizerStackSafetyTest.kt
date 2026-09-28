package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The argument sanitizer builds the ledger record inside invoke's `finally`, AFTER the tool has
 * run, and the approval request before the prompt. Either way it must return: java.util.regex
 * recurses once per iteration of a greedy or lazy GROUP repeat, the agent chooses the input, and
 * a StackOverflowError there loses the ledger row of a call that already executed (the hole
 * #1650's review closed for the JSON parser, reopened one layer down by the regexes).
 *
 * Stated as a property: every rule's trigger, followed by every pathological filler, at the size
 * the ledger admits and at four times it (the approval path does not cap), sanitizes on a stack
 * far smaller than any real thread's without throwing and without falling back to the
 * "could not be sanitized" backstop - so it pins the rules themselves, not the catch.
 */
class McpArgumentSanitizerStackSafetyTest {
    @Test
    fun `every rule sanitizes pathological input on a small stack without throwing`() {
        val failures = mutableListOf<String>()
        onSmallStack {
            for (prefix in TRIGGERS) {
                for (filler in FILLERS) {
                    for (size in SIZES) {
                        val input = prefix + filler.repeat(size / filler.length)
                        val cell = "${label(prefix)} + ${label(filler)} x ${size / filler.length}"
                        try {
                            val out = McpArgumentSanitizer.sanitizeMessage(input)
                            if (out == McpArgumentSanitizer.SANITIZE_FAILED) failures += "$cell -> backstop"
                        } catch (e: StackOverflowError) {
                            failures += "$cell -> ${e::class.simpleName}"
                        }
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), "${failures.size} cells:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `the payload that overflowed still redacts what it did before`() {
        // Possessive repeats never give back what they took; for these rules nothing after them
        // needs anything back, so the match - and what is redacted - is unchanged.
        val encrypted =
            "-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nDEK-Info: AES-128-CBC,00FF\n\n" +
                "MIIEpAIBAAKCAQEA0Z3VS5JJcds3xfn/ygWyF8PbnGy0AHB7MqvM\n-----END RSA PRIVATE KEY-----"
        assertEquals("key=[REDACTED] tail", McpArgumentSanitizer.sanitizeMessage("key=$encrypted tail"))
        assertEquals("AWS_[REDACTED] x", McpArgumentSanitizer.sanitizeMessage("AWS_SECRET_ACCESS_KEY=abc123 x"))
        assertEquals("max_tokens=4096", McpArgumentSanitizer.sanitizeMessage("max_tokens=4096"))
    }

    @Test
    fun `a call that already ran under ALLOW keeps its ledger row whatever its arguments carry`() =
        runBlocking<Unit> {
            val f = Fixture()
            f.policyEngine.setToolPolicy("run_command", McpPolicyAction.ALLOW)
            val result = f.core.invoke("run_command", HOSTILE_ARGS)
            assertFalse(result.isError, result.text)
            assertEquals(1, f.calls, "the tool ran")
            val row =
                f.ledger.recentOperations.value
                    .single()
            assertEquals(McpApprovalDisposition.AUTO_ALLOWED, row.approvalDisposition)
            // The command an audit reads is there, and the key block is not.
            assertEquals("ls -la", row.sanitizedArgs["command"])
            assertEquals("[REDACTED]", row.sanitizedArgs["note"]?.trim())
        }

    @Test
    fun `a prompted call carrying the same payload reaches the operator and the ledger`() =
        runBlocking<Unit> {
            val f = Fixture()
            val operator =
                launch {
                    val request =
                        f.approvalBus.pendingList
                            .first { it.isNotEmpty() }
                            .single()
                    assertNotEquals(McpArgumentSanitizer.SANITIZE_FAILED, request.arguments["command"])
                    f.approvalBus.approve(request.id)
                }
            val result = f.core.invoke("run_command", HOSTILE_ARGS)
            operator.join()
            assertFalse(result.isError, result.text)
            assertEquals(1, f.calls)
            assertEquals(
                McpApprovalDisposition.APPROVED_ONCE,
                f.ledger.recentOperations.value
                    .single()
                    .approvalDisposition,
            )
        }

    private class Fixture {
        val approvalBus = McpApprovalBus(defaultTimeoutMs = 5_000L)
        val policyEngine = McpPolicyEngine(policyFile = null)
        val ledger = McpOperationLedger(ledgerFile = null)
        var calls = 0
        val core =
            McpToolRegistryCore(
                disabledFile = null,
                policyEngine = policyEngine,
                approvalBus = approvalBus,
                ledger = ledger,
            ).also { core ->
                core.registerProvider(
                    object : McpToolProvider {
                        override val providerId = "p1"

                        override fun tools() =
                            listOf(
                                McpToolDefinition(
                                    name = "run_command",
                                    description = "test",
                                    handler =
                                        McpToolHandler {
                                            calls++
                                            McpToolResult("ok")
                                        },
                                ),
                            )
                    },
                )
            }
    }

    private fun onSmallStack(block: () -> Unit) {
        var thrown: Throwable? = null
        val thread = Thread(null, { runCatching(block).onFailure { thrown = it } }, "small-stack", SMALL_STACK)
        thread.start()
        thread.join()
        thrown?.let { throw it }
    }

    /** A real newline and tab are named, so they cannot be mistaken for the escaped `\n` filler. */
    private fun label(s: String): String =
        s
            .replace("\n", "<LF>")
            .replace("\t", "<TAB>")
            .take(48)
            .let { "\"$it\"" }

    private companion object {
        /** A quarter of the default thread stack; a linear rule needs a small constant of it. */
        const val SMALL_STACK = 256L * 1024

        /** The ledger's own cap on raw arguments, and four times it for the uncapped approval path. */
        val SIZES = listOf(16_384, 65_536)

        /** What makes each rule in the chain start matching. */
        val TRIGGERS =
            listOf(
                "-----BEGIN PRIVATE KEY-----",
                "-----BEGIN RSA PRIVATE KEY-----\n",
                "-----BEGIN RSA PRIVATE KEY-----\nProc-Type: ",
                "AKIA",
                "curl -u ",
                "curl --user=",
                "curl -b ",
                "--cookie ",
                "eyJhbGciOiJIUzI1NiJ9.",
                "ghp_",
                "sk_live_",
                "password",
                "password_",
                "PASSWORD=",
                "{\\\"password\\\":\\\"",
                "Authorization: ",
                "Authorization: Basic ",
                "--password ",
                "mysql -u root -p",
                "redis-cli -h h -a ",
                "//registry.npmjs.org/:_authToken ",
                "Bearer ",
                "postgres://admin:",
                "https://",
                "TOKEN={{secret:",
            )

        /** Repeated after a trigger until the input reaches its size. */
        val FILLERS =
            listOf(
                " ",
                "\n",
                "\\n",
                "\t",
                ":",
                "-",
                "_a",
                "a",
                "=",
                "/",
                "\"",
                "'",
                "\\",
                "@",
                "Proc-Type: 4,ENCRYPTED\n",
                "A:\\n",
                "\u00e9",
                "{{",
            )

        /**
         * A benign command, and beside it under a key no schema declares, the PEM rule's trigger
         * and 16,000 spaces: under the ledger's 16,384-character cap, and past what a default
         * thread stack survived in that rule before its repeats were made possessive.
         */
        val HOSTILE_ARGS: String =
            "{\"command\":\"ls -la\",\"note\":\"-----BEGIN PRIVATE KEY-----" + " ".repeat(16_000) + "\"}"
    }
}
