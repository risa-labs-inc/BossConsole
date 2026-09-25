package ai.rever.boss.mcp.secrets

import ai.rever.boss.mcp.McpApprovalBus
import ai.rever.boss.mcp.McpApprovalDisposition
import ai.rever.boss.mcp.McpApprovalRequest
import ai.rever.boss.mcp.McpOperationLedger
import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.mcp.McpPolicyEngine
import ai.rever.boss.mcp.McpSecretPolicyAction
import ai.rever.boss.mcp.McpToolPolicyConfig
import ai.rever.boss.mcp.McpToolRegistryCore
import ai.rever.boss.mcp.SECRET_READ_PERMISSION
import ai.rever.boss.mcp.sandbox.McpRiskLevel
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The invariants in `docs/MCP_SECRET_REFERENCES.md`, exercised at the registry boundary with a
 * fake vault, an auto-answering operator and every host log entry captured.
 *
 * INV1 non-disclosure · INV2 atomicity · INV3 ordering · INV4 approval binding ·
 * INV5 transparency · INV6 consistency · INV7 bounded work (see the fuzz test for the last).
 */
@Suppress("LargeClass")
class SecretReferenceInvariantTest {
    private val id = "6f1d2c3e-4b5a-4c6d-8e7f-90a1b2c3d4e5"
    private val secret = "hunter2!\"quoted\" & spaced/\\slashed"
    private val record =
        SecretRecord(id = id, website = "github.com", username = "deploy-bot", password = secret, notes = null)
    private val ref = SecretReference(id, SecretField.PASSWORD)

    private class CountingVault(
        private val records: List<SecretRecord>,
    ) : SecretLookup {
        val reads = AtomicInteger()

        override suspend fun page(
            limit: Int,
            offset: Int,
        ): Result<List<SecretRecord>> {
            reads.incrementAndGet()
            return Result.success(records.drop(offset).take(limit))
        }
    }

    private class Harness(
        vault: SecretLookup?,
        policyFile: File? = null,
        config: McpToolPolicyConfig? = null,
        admin: Boolean = true,
        permissions: Set<String> = emptySet(),
    ) {
        // The engine reads its file at construction, so a non-default config is written first.
        val policyEngine =
            McpPolicyEngine(
                policyFile = policyFile?.also { file -> config?.let { file.writeText(Json.encodeToString(it)) } },
            )
        val approvalBus = McpApprovalBus(defaultTimeoutMs = 5_000L)
        val ledger = McpOperationLedger(ledgerFile = null)
        val core =
            McpToolRegistryCore(
                disabledFile = null,
                policyEngine = policyEngine,
                approvalBus = approvalBus,
                ledger = ledger,
                secretLookup = vault,
            )
        val seenRequests = java.util.Collections.synchronizedList(ArrayList<McpApprovalRequest>())

        init {
            require(config == null || policyFile != null) { "a config needs a file for the engine to read it from" }
            core.updateAccess(isAdmin = admin, permissions = permissions)
        }

        fun register(vararg defs: McpToolDefinition) {
            core.registerProvider(
                object : McpToolProvider {
                    override val providerId = "p1"

                    override fun tools() = defs.toList()
                },
            )
        }

        /** Answer every prompt with [approve] until cancelled; remembers what it was shown. */
        fun kotlinx.coroutines.CoroutineScope.operator(approve: Boolean = true): Job =
            launch {
                while (true) {
                    val req = approvalBus.pendingList.first { it.isNotEmpty() }.first()
                    if (req.id !in seenRequests.map { it.id }) seenRequests.add(req)
                    if (approve) approvalBus.approve(req.id) else approvalBus.deny(req.id, "no")
                    approvalBus.pendingList.first { list -> list.none { it.id == req.id } }
                }
            }
    }

    private fun tool(
        name: String,
        handler: McpToolHandler,
    ) = McpToolDefinition(name = name, description = "test $name", handler = handler)

    private fun tempPolicyFile(): File = File(createTempDirectory("mcp-secret-refs").toFile(), "policy.json")

    // ---- INV1: non-disclosure ----------------------------------------------------------------

    @Test
    fun `INV1 - a handler that echoes every supported encoding leaks to no surface`() =
        runBlocking {
            val h = Harness(CountingVault(listOf(record)))
            var received: McpToolArgs? = null
            h.register(
                // The argument is named "content", not "token": the ledger's key-name
                // sanitizer would redact a key called token regardless, and this test wants to
                // show the reference itself surviving into the ledger.
                tool("echo") { args ->
                    received = args
                    val content = args.string("content")!!
                    McpToolResult(
                        args.raw + "\n" + content + "\n" +
                            URLEncoder.encode(content, Charsets.UTF_8) + "\n" +
                            McpResultScrubber.jsonEscape(content),
                    )
                },
            )
            val leak = SecretLeakAssert(secret)
            val (result, logs) =
                captureHostLogs {
                    runBlocking {
                        val op = with(h) { operator() }
                        val r = h.core.invoke("echo", """{"content":"{{secret:$id}}","path":"/tmp/x"}""")
                        op.cancel()
                        r
                    }
                }
            // The handler got the real value.
            assertEquals(secret, received?.string("content"))
            // The agent got a scrubbed echo; the token stands where every form of the value was.
            assertFalse(result.isError, result.text)
            leak.assertAbsent("result", result.text)
            assertTrue(result.text.contains(ref.token), result.text)
            assertTrue(result.text.contains("/tmp/x"))
            // Ledger, approval request, host logs.
            leak.assertAbsentFromLedger(h.ledger.recentOperations.value)
            leak.assertAbsentFromRequests(h.seenRequests)
            leak.assertAbsentFromLogs(logs)
            // And the ledger says which secret the tool received, by reference.
            val rec =
                h.ledger.recentOperations.value
                    .single()
            assertEquals(listOf("$id.password"), rec.secretRefs)
            assertEquals(McpApprovalDisposition.APPROVED_ONCE, rec.approvalDisposition)
            assertEquals(McpPolicyAction.ASK, rec.policyApplied)
            assertTrue(rec.sanitizedArgs["content"]!!.contains("{{secret:$id}}"), rec.sanitizedArgs.toString())
        }

    @Test
    fun `INV1 without the scrubber - the argument path still discloses nothing, only the echo does`() =
        runBlocking {
            val file = tempPolicyFile()
            val h = Harness(CountingVault(listOf(record)), file, McpToolPolicyConfig(resultScrubbingEnabled = false))
            h.register(tool("echo") { args -> McpToolResult("echo:" + args.string("token")) })
            val leak = SecretLeakAssert(secret)
            val (result, logs) =
                captureHostLogs {
                    runBlocking {
                        val op = with(h) { operator() }
                        val r = h.core.invoke("echo", """{"token":"{{secret:$id}}"}""")
                        op.cancel()
                        r
                    }
                }
            // Expected and documented: with the defense-in-depth step off, an echoing handler
            // returns the value. That is exactly the disclosure the scrubber exists to reduce.
            assertTrue(result.text.contains(secret))
            // Everything the guarantee actually rests on still holds.
            leak.assertAbsentFromLedger(h.ledger.recentOperations.value)
            leak.assertAbsentFromRequests(h.seenRequests)
            leak.assertAbsentFromLogs(logs)
        }

    @Test
    fun `INV1 boundary - a transforming handler is outside the guarantee, and the plaintext still is not`() =
        runBlocking {
            val h = Harness(CountingVault(listOf(record)))
            h.register(
                tool("hasher") { args ->
                    val digest = MessageDigest.getInstance("SHA-256").digest(args.string("token")!!.toByteArray())
                    McpToolResult(digest.joinToString("") { "%02x".format(it) })
                },
            )
            val expected =
                MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()).joinToString("") { "%02x".format(it) }
            val leak = SecretLeakAssert(secret)
            val (result, logs) =
                captureHostLogs {
                    runBlocking {
                        val op = with(h) { operator() }
                        val r = h.core.invoke("hasher", """{"token":"{{secret:$id}}"}""")
                        op.cancel()
                        r
                    }
                }
            // The hash is returned unprotected: hashing is a documented non-goal.
            assertEquals(expected, result.text)
            // The plaintext is still absent from every surface.
            leak.assertAbsent("result", result.text)
            leak.assertAbsentFromLedger(h.ledger.recentOperations.value)
            leak.assertAbsentFromRequests(h.seenRequests)
            leak.assertAbsentFromLogs(logs)
        }

    @Test
    fun `INV1 - a handler that throws with the value in its message does not disclose it`() =
        runBlocking {
            val h = Harness(CountingVault(listOf(record)))
            h.register(tool("thrower") { args -> throw IllegalStateException("bad token " + args.string("token")) })
            val leak = SecretLeakAssert(secret)
            val (result, logs) =
                captureHostLogs {
                    runBlocking {
                        val op = with(h) { operator() }
                        val r = h.core.invoke("thrower", """{"token":"{{secret:$id}}"}""")
                        op.cancel()
                        r
                    }
                }
            assertTrue(result.isError)
            leak.assertAbsent("result", result.text)
            leak.assertAbsentFromLedger(h.ledger.recentOperations.value)
            leak.assertAbsentFromLogs(logs)
        }

    @Test
    fun `INV1 - the operator sees the descriptor and an escalated risk, never the value`() =
        runBlocking {
            val h = Harness(CountingVault(listOf(record)))
            h.register(tool("codebase_read") { McpToolResult("ok") })
            val op = with(h) { operator() }
            h.core.invoke("codebase_read", """{"path":"{{secret:$id.username}}"}""")
            op.cancel()
            val req = h.seenRequests.single()
            assertEquals(1, req.secretRefs.size)
            assertEquals("github.com (deploy-bot) - username", req.secretRefs.single().display)
            val risk = assertNotNull(req.riskAssessment)
            // codebase_read is LOW on its own; a secret raises it to HIGH and names the secret.
            assertEquals(McpRiskLevel.HIGH, risk.level)
            assertTrue(risk.reason.contains("github.com (deploy-bot) - username"), risk.reason)
            // Website and username are the metadata tier `secrets_list` already reveals; the
            // password is the protected value and must not be in the prompt.
            SecretLeakAssert(secret).assertAbsentFromRequests(h.seenRequests)
        }

    // ---- INV2: atomicity --------------------------------------------------------------------

    @Test
    fun `INV2 - one unresolvable reference withholds the whole call before any prompt`() =
        runBlocking {
            val other = "00000000-0000-4000-8000-000000000001"
            val h = Harness(CountingVault(listOf(record)))
            var called = false
            h.register(
                tool("write") {
                    called = true
                    McpToolResult("ran")
                },
            )
            val result = h.core.invoke("write", """{"a":"{{secret:$id}}","b":"{{secret:$other}}"}""")
            assertTrue(result.isError)
            assertTrue(result.text.contains(other), result.text)
            assertFalse(called)
            assertTrue(h.seenRequests.isEmpty())
            val rec =
                h.ledger.recentOperations.value
                    .single()
            assertEquals(McpApprovalDisposition.SECRET_UNRESOLVED, rec.approvalDisposition)
            assertEquals(setOf("$id.password", "$other.password"), rec.secretRefs.toSet())
        }

    @Test
    fun `INV2 - a malformed reference is refused, never passed through as text`() =
        runBlocking {
            val h = Harness(CountingVault(listOf(record)))
            var called = false
            h.register(
                tool("write") {
                    called = true
                    McpToolResult("ran")
                },
            )
            val result = h.core.invoke("write", """{"a":"{{secret:$id.totp}}"}""")
            assertTrue(result.isError)
            assertTrue(result.text.contains("unknown field"), result.text)
            assertFalse(called)
            assertEquals(
                McpApprovalDisposition.SECRET_UNRESOLVED,
                h.ledger.recentOperations.value
                    .single()
                    .approvalDisposition,
            )
        }

    @Test
    fun `INV2 - a json-escaped reference is resolved rather than passed through`() =
        runBlocking {
            val h = Harness(CountingVault(listOf(record)))
            var received = ""
            h.register(
                tool("write") { args ->
                    received = args.string("a").orEmpty()
                    McpToolResult("ran")
                },
            )
            val op = with(h) { operator() }
            val escaped = "\\u007b\\u007bsecret:$id\\u007d\\u007d"
            val result = h.core.invoke("write", """{"a":"$escaped"}""")
            op.cancel()
            assertFalse(result.isError)
            assertTrue(received.contains(secret), received)
            assertFalse(received.contains("{{secret:"), received)
        }

    @Test
    fun `INV2 - malformed JSON and non-object arguments are rejected before secret processing`() =
        runBlocking {
            val h = Harness(CountingVault(listOf(record)))
            var called = false
            h.register(
                tool("write") {
                    called = true
                    McpToolResult("ran")
                },
            )
            val op = with(h) { operator() }
            // A lowercase \u followed by non-hex makes the parse genuinely fail, so these calls
            // must fail the argument-shape gate even without a secret marker.
            val result = h.core.invoke("write", """{"path":"C:\users\me\notes.txt"}""")
            val listResult = h.core.invoke("write", """["C:\users\me"]""")
            // Valid JSON of the wrong shape is also refused without resolving secrets.
            val arrayResult = h.core.invoke("write", """["\u0041"]""")
            op.cancel()
            assertTrue(result.isError)
            assertTrue(listResult.isError)
            assertTrue(arrayResult.isError)
            assertFalse(called)
            assertTrue(
                h.ledger.recentOperations.value
                    .all { it.approvalDisposition == McpApprovalDisposition.INVALID_ARGUMENTS },
            )
        }

    @Test
    fun `INV2 - an escaped reference in a non-object payload is refused`() =
        runBlocking {
            val h = Harness(CountingVault(listOf(record)))
            var called = false
            h.register(
                tool("write") {
                    called = true
                    McpToolResult("ran")
                },
            )
            val escaped = "\\u007b\\u007bsecret:$id\\u007d\\u007d"
            val result = h.core.invoke("write", """["$escaped"]""")
            assertTrue(result.isError)
            assertFalse(called)
            assertEquals(
                McpApprovalDisposition.INVALID_ARGUMENTS,
                h.ledger.recentOperations.value
                    .single()
                    .approvalDisposition,
            )
        }

    @Test
    fun `INV2 - an unterminated reference is refused rather than passed through`() =
        runBlocking {
            val h = Harness(CountingVault(listOf(record)))
            var called = false
            h.register(
                tool("write") {
                    called = true
                    McpToolResult("ran")
                },
            )
            val result = h.core.invoke("write", """{"a":"{{secret:$id}"}""")
            assertTrue(result.isError)
            assertFalse(called)
            assertEquals(
                McpApprovalDisposition.SECRET_UNRESOLVED,
                h.ledger.recentOperations.value
                    .single()
                    .approvalDisposition,
            )
        }

    @Test
    fun `INV2 - a registry with no vault refuses rather than delivering placeholders`() =
        runBlocking {
            val h = Harness(vault = null)
            var called = false
            h.register(
                tool("write") {
                    called = true
                    McpToolResult("ran")
                },
            )
            val result = h.core.invoke("write", """{"a":"{{secret:$id}}"}""")
            assertTrue(result.isError)
            assertFalse(called)
            assertEquals(
                McpApprovalDisposition.SECRET_UNRESOLVED,
                h.ledger.recentOperations.value
                    .single()
                    .approvalDisposition,
            )
        }

    // ---- INV3: ordering ---------------------------------------------------------------------

    @Test
    fun `INV3 - a tool DENY is decided before the vault is read`() =
        runBlocking {
            val vault = CountingVault(listOf(record))
            val h = Harness(vault)
            h.policyEngine.setToolPolicy("write", McpPolicyAction.DENY)
            h.register(tool("write") { McpToolResult("ran") })
            val result = h.core.invoke("write", """{"a":"{{secret:$id}}"}""")
            assertTrue(result.isError)
            assertEquals(0, vault.reads.get())
            val rec =
                h.ledger.recentOperations.value
                    .single()
            assertEquals(McpApprovalDisposition.POLICY_DENIED, rec.approvalDisposition)
            assertEquals(listOf("$id.password"), rec.secretRefs)
        }

    @Test
    fun `INV3 - YOLO mode does not cover a secret-bearing call`() =
        runBlocking {
            val h = Harness(CountingVault(listOf(record)))
            var received: McpToolArgs? = null
            h.register(
                tool("write") { args ->
                    received = args
                    McpToolResult("ran")
                },
            )
            h.policyEngine.setYoloMode(true)
            val op = with(h) { operator() }
            val result = h.core.invoke("write", """{"path":"{{secret:$id}}"}""")
            op.cancel()
            // YOLO could not answer this one: the operator was shown the call and approved it.
            assertFalse(result.isError, result.text)
            assertEquals(secret, received?.string("path"))
            assertEquals(1, h.seenRequests.size)
            assertEquals(
                McpApprovalDisposition.APPROVED_ONCE,
                h.ledger.recentOperations.value
                    .single()
                    .approvalDisposition,
            )
            // A plain call whose policy is ASK still gets no prompt under YOLO.
            h.policyEngine.setToolPolicy("write", McpPolicyAction.ASK)
            val plain = h.core.invoke("write", """{"path":"/tmp/x"}""")
            assertFalse(plain.isError, plain.text)
            assertEquals(1, h.seenRequests.size)
            assertEquals(
                McpApprovalDisposition.YOLO_ALLOWED,
                h.ledger.recentOperations.value
                    .first()
                    .approvalDisposition,
            )
        }

    @Test
    fun `INV7 - a deeply nested payload is refused and still leaves its ledger record`() =
        runBlocking {
            val h = Harness(CountingVault(listOf(record)))
            var called = false
            h.register(
                tool("write") {
                    called = true
                    McpToolResult("ran")
                },
            )
            val deep = """{"a":"\u0041","b":""" + "[".repeat(8_000) + "]".repeat(8_000) + "}"
            val result = h.core.invoke("write", deep)
            assertTrue(result.isError, result.text)
            assertFalse(called)
            assertEquals(1, h.ledger.recentOperations.value.size)
            assertEquals(
                McpApprovalDisposition.INVALID_ARGUMENTS,
                h.ledger.recentOperations.value
                    .single()
                    .approvalDisposition,
            )
        }

    @Test
    fun `INV7 - a deeply nested payload carrying a reference is refused, not crashed`() =
        runBlocking {
            val h = Harness(CountingVault(listOf(record)))
            var called = false
            h.register(
                tool("write") {
                    called = true
                    McpToolResult("ran")
                },
            )
            val deep = """{"a":"{{secret:$id}}","b":""" + "[".repeat(8_000) + "]".repeat(8_000) + "}"
            val result = h.core.invoke("write", deep)
            assertTrue(result.isError)
            assertFalse(called)
            val rec =
                h.ledger.recentOperations.value
                    .single()
            assertEquals(McpApprovalDisposition.INVALID_ARGUMENTS, rec.approvalDisposition)
        }

    @Test
    fun `INV3 - a user without secret read is refused before the vault is read`() =
        runBlocking {
            val vault = CountingVault(listOf(record))
            val h = Harness(vault, admin = false, permissions = setOf("something.else"))
            h.register(tool("write") { McpToolResult("ran") })
            val result = h.core.invoke("write", """{"a":"{{secret:$id}}"}""")
            assertTrue(result.isError)
            assertTrue(result.text.contains("secret.read"))
            assertEquals(0, vault.reads.get())
            assertEquals(
                McpApprovalDisposition.SECRET_FORBIDDEN,
                h.ledger.recentOperations.value
                    .single()
                    .approvalDisposition,
            )
        }

    @Test
    fun `INV3 - a non-admin holding secret read may use references`() =
        runBlocking {
            val h = Harness(CountingVault(listOf(record)), admin = false, permissions = setOf("secret.read"))
            h.register(tool("write") { args -> McpToolResult("len=" + args.string("a")!!.length) })
            val op = with(h) { operator() }
            val result = h.core.invoke("write", """{"a":"{{secret:$id}}"}""")
            op.cancel()
            assertEquals("len=${secret.length}", result.text)
        }

    @Test
    fun `INV3 - secretBearingCalls DENY refuses before the vault is read`() =
        runBlocking {
            val vault = CountingVault(listOf(record))
            val file = tempPolicyFile()
            val h = Harness(vault, file, McpToolPolicyConfig(secretBearingCalls = McpSecretPolicyAction.DENY))
            h.register(tool("write") { McpToolResult("ran") })
            val result = h.core.invoke("write", """{"a":"{{secret:$id}}"}""")
            assertTrue(result.isError)
            assertEquals(0, vault.reads.get())
            assertEquals(
                McpApprovalDisposition.SECRET_FORBIDDEN,
                h.ledger.recentOperations.value
                    .single()
                    .approvalDisposition,
            )
        }

    @Test
    fun `INV3 - the feature flag off refuses before the vault is read`() =
        runBlocking {
            val vault = CountingVault(listOf(record))
            val file = tempPolicyFile()
            val h = Harness(vault, file, McpToolPolicyConfig(secretReferencesEnabled = false))
            var called = false
            h.register(
                tool("write") {
                    called = true
                    McpToolResult("ran")
                },
            )
            val result = h.core.invoke("write", """{"a":"{{secret:$id}}"}""")
            assertTrue(result.isError)
            assertFalse(called)
            assertEquals(0, vault.reads.get())
            assertEquals(
                McpApprovalDisposition.SECRET_FORBIDDEN,
                h.ledger.recentOperations.value
                    .single()
                    .approvalDisposition,
            )
        }

    @Test
    fun `INV3 - an AI provider key is forbidden after lookup and before any prompt`() =
        runBlocking {
            val tagged = record.copy(tags = listOf(SecretReferenceResolver.AI_PROVIDER_TAG))
            val h = Harness(CountingVault(listOf(tagged)))
            h.register(tool("write") { McpToolResult("ran") })
            val result = h.core.invoke("write", """{"a":"{{secret:$id}}"}""")
            assertTrue(result.isError)
            assertTrue(h.seenRequests.isEmpty())
            assertEquals(
                McpApprovalDisposition.SECRET_FORBIDDEN,
                h.ledger.recentOperations.value
                    .single()
                    .approvalDisposition,
            )
        }

    @Test
    fun `INV3 - a tool-wide ALLOW rule still asks when the call carries a reference`() =
        runBlocking {
            val h = Harness(CountingVault(listOf(record)))
            h.policyEngine.setToolPolicy("codebase_write", McpPolicyAction.ALLOW)
            h.register(tool("codebase_write") { McpToolResult("ran") })
            // Sanity: without a reference the ALLOW rule runs the call silently.
            assertEquals("ran", h.core.invoke("codebase_write", """{"a":"plain"}""").text)
            assertTrue(h.seenRequests.isEmpty())
            val op = with(h) { operator() }
            val result = h.core.invoke("codebase_write", """{"a":"{{secret:$id}}"}""")
            op.cancel()
            assertEquals("ran", result.text)
            assertEquals(1, h.seenRequests.size)
        }

    @Test
    fun `INV3 - session trust for the tool never covers a secret-bearing call`() =
        runBlocking {
            val h = Harness(CountingVault(listOf(record)))
            // A HIGH-risk write keeps the plain-call baseline silent; docker_build is
            // CRITICAL by name and now requires a prompt even without a reference.
            h.policyEngine.trustForSession("file_write", "p1")
            h.register(tool("file_write") { McpToolResult("ran") })
            assertEquals("ran", h.core.invoke("file_write", """{"a":"plain"}""").text)
            assertTrue(h.seenRequests.isEmpty())
            val op = with(h) { operator() }
            h.core.invoke("file_write", """{"a":"{{secret:$id}}"}""")
            op.cancel()
            assertEquals(1, h.seenRequests.size)
        }

    @Test
    fun `INV3 - secret-bearing approval cannot persist Always Allow`() =
        runBlocking {
            val file = tempPolicyFile()
            val h = Harness(CountingVault(listOf(record)), file)
            h.register(tool("codebase_write") { McpToolResult("ran") })
            val approvals = AtomicInteger()
            val op =
                launch {
                    while (true) {
                        val req =
                            h.approvalBus.pendingList
                                .first { it.isNotEmpty() }
                                .first()
                        approvals.incrementAndGet()
                        h.approvalBus.approve(req.id, persistPolicy = true)
                        h.approvalBus.pendingList.first { list -> list.none { it.id == req.id } }
                    }
                }
            h.core.invoke("codebase_write", """{"a":"{{secret:$id}}"}""")
            assertEquals(null, h.policyEngine.config.value.rules["codebase_write"])
            h.core.invoke("codebase_write", """{"a":"{{secret:$id}}"}""")
            op.cancel()
            assertEquals(2, approvals.get())
        }

    @Test
    fun `INV3 - an operator denial withholds the call and the handler never sees the value`() =
        runBlocking {
            val h = Harness(CountingVault(listOf(record)))
            var called = false
            h.register(
                tool("write") {
                    called = true
                    McpToolResult("ran")
                },
            )
            val op = with(h) { operator(approve = false) }
            val result = h.core.invoke("write", """{"a":"{{secret:$id}}"}""")
            op.cancel()
            assertTrue(result.isError)
            assertFalse(called)
            assertEquals(
                McpApprovalDisposition.DENIED_BY_OPERATOR,
                h.ledger.recentOperations.value
                    .single()
                    .approvalDisposition,
            )
        }

    // ---- INV4: binding ----------------------------------------------------------------------

    @Test
    fun `INV4 - a DENY saved while the prompt is open refuses the approved call`() =
        runBlocking {
            val h = Harness(CountingVault(listOf(record)))
            var called = false
            h.register(
                tool("write") {
                    called = true
                    McpToolResult("ran")
                },
            )
            val pending = async { h.core.invoke("write", """{"a":"{{secret:$id}}"}""") }
            val req =
                h.approvalBus.pendingList
                    .first { it.isNotEmpty() }
                    .first()
            h.policyEngine.setToolPolicy("write", McpPolicyAction.DENY)
            h.approvalBus.approve(req.id)
            val result = pending.await()
            assertTrue(result.isError)
            assertFalse(called)
        }

    @Test
    fun `INV4 - losing secret read while the prompt is open withholds the value`() =
        runBlocking {
            val h = Harness(CountingVault(listOf(record)), admin = false, permissions = setOf(SECRET_READ_PERMISSION))
            var called = false
            h.register(
                tool("write") {
                    called = true
                    McpToolResult("ran")
                },
            )
            val pending = async { h.core.invoke("write", """{"a":"{{secret:$id}}"}""") }
            val req =
                h.approvalBus.pendingList
                    .first { it.isNotEmpty() }
                    .first()
            h.core.updateAccess(isAdmin = false, permissions = emptySet())
            h.approvalBus.approve(req.id)
            val result = pending.await()
            assertTrue(result.isError)
            assertFalse(called)
        }

    @Test
    fun `INV4 - cancellation while awaiting approval runs nothing and records the reference`() =
        runBlocking {
            val h = Harness(CountingVault(listOf(record)))
            var called = false
            h.register(
                tool("write") {
                    called = true
                    McpToolResult("ran")
                },
            )
            val pending = async { h.core.invoke("write", """{"a":"{{secret:$id}}"}""") }
            h.approvalBus.pendingList.first { it.isNotEmpty() }
            pending.cancel()
            var thrown: CancellationException? = null
            try {
                pending.await()
            } catch (e: CancellationException) {
                thrown = e
            }
            assertNotNull(thrown)
            assertFalse(called)
            val rec =
                h.ledger.recentOperations.value
                    .single()
            assertEquals(McpApprovalDisposition.CANCELLED_AWAITING_APPROVAL, rec.approvalDisposition)
            assertEquals(listOf("$id.password"), rec.secretRefs)
        }

    // ---- INV5: transparency -----------------------------------------------------------------

    @Test
    fun `INV5 - a call without references is untouched, reads no vault and records no references`() =
        runBlocking {
            val vault = CountingVault(listOf(record))
            val h = Harness(vault)
            var received: McpToolArgs? = null
            h.register(
                tool("read") { args ->
                    received = args
                    McpToolResult("ok " + args.raw)
                },
            )
            val raw = """{ "path" : "/tmp/{secret}",  "n": 1.50, "deep": {"k": [1, "two", null]} }"""
            val result = h.core.invoke("read", raw)
            assertEquals("ok $raw", result.text)
            assertEquals(raw, received?.raw)
            assertEquals(0, vault.reads.get())
            val rec =
                h.ledger.recentOperations.value
                    .single()
            assertTrue(rec.secretRefs.isEmpty())
            assertEquals(McpApprovalDisposition.AUTO_ALLOWED, rec.approvalDisposition)
        }

    @Test
    fun `INV2 - the marker inside a key is refused before vault access or handler execution`() =
        runBlocking {
            val vault = CountingVault(listOf(record))
            val h = Harness(vault)
            var called = false
            h.register(
                tool("read") {
                    called = true
                    McpToolResult("ran")
                },
            )
            val raw = """{"{{secret:$id}}":"v"}"""
            assertTrue(h.core.invoke("read", raw).isError)
            assertFalse(called)
            assertTrue(h.seenRequests.isEmpty())
            assertEquals(0, vault.reads.get())
        }

    // ---- INV6: consistency ------------------------------------------------------------------

    @Test
    fun `INV6 - the scalar map and the raw json a handler parses agree after substitution`() =
        runBlocking {
            val h = Harness(CountingVault(listOf(record)))
            var received: McpToolArgs? = null
            h.register(
                tool("write") { args ->
                    received = args
                    McpToolResult("ok")
                },
            )
            val op = with(h) { operator() }
            h.core.invoke(
                "write",
                """{"content":"A={{secret:$id}}\nB={{secret:$id.username}}","nested":{"x":["{{secret:$id}}"]},"n":7}""",
            )
            op.cancel()
            val args = assertNotNull(received)
            assertEquals("A=$secret\nB=deploy-bot", args.string("content"))
            assertEquals(7, args.int("n"))
            val parsed = Json.parseToJsonElement(args.raw).jsonObject
            assertEquals("A=$secret\nB=deploy-bot", parsed["content"]!!.jsonPrimitive.content)
            val nested = parsed["nested"].toString()
            assertTrue(nested.contains(McpResultScrubber.jsonEscape(secret)), nested)
            // Nested objects reach the scalar map as their raw JSON, as before this change.
            assertTrue(args.string("nested")!!.contains(McpResultScrubber.jsonEscape(secret)))
        }

    // ---- kill-switch and cap ----------------------------------------------------------------

    @Test
    fun `a kill-switched tool never reads the vault`() =
        runBlocking {
            val vault = CountingVault(listOf(record))
            val h = Harness(vault)
            h.register(tool("write") { McpToolResult("ran") })
            h.core.setToolEnabled("write", false)
            val result = h.core.invoke("write", """{"a":"{{secret:$id}}"}""")
            assertTrue(result.isError)
            assertEquals(0, vault.reads.get())
            assertNull(
                h.ledger.recentOperations.value
                    .firstOrNull(),
            )
        }

    @Test
    fun `scrubbing happens before the cap, so a cut cannot expose half a value`() =
        runBlocking {
            val policyEngine = McpPolicyEngine(policyFile = null)
            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5_000L)
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    maxResultChars = 400,
                    policyEngine = policyEngine,
                    approvalBus = approvalBus,
                    ledger = McpOperationLedger(ledgerFile = null),
                    secretLookup = CountingVault(listOf(record)),
                )
            core.updateAccess(isAdmin = true, permissions = emptySet())
            core.registerProvider(
                object : McpToolProvider {
                    override val providerId = "p1"

                    override fun tools() = listOf(tool("echo") { args -> McpToolResult(args.string("t")!!.repeat(50)) })
                },
            )
            val op =
                launch {
                    val req = approvalBus.pendingList.first { it.isNotEmpty() }.first()
                    approvalBus.approve(req.id)
                }
            val result = core.invoke("echo", """{"t":"{{secret:$id}}"}""")
            op.cancel()
            assertTrue(result.text.length <= 400 + 300, "cap applied: ${result.text.length}")
            SecretLeakAssert(secret).assertAbsent("capped result", result.text)
            assertTrue(result.text.contains("BOSS host cap"))
        }
}
