package ai.rever.boss.cli

import ai.rever.boss.components.dialogs.McpUnsuccessfulCategory
import ai.rever.boss.mcp.McpApprovalDisposition
import ai.rever.boss.mcp.McpOperationLedger
import ai.rever.boss.mcp.McpPolicyAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the `boss mcp ledger` read path.
 *
 * These drive [McpLedgerCli] directly rather than parsing CLI output, because the thing worth
 * pinning is which records are read and how they are classified - the Clikt wiring above it is
 * covered by the argument conventions `BossMcpCliTest` already exercises.
 */
class McpLedgerCliTest {
    private val tempFiles = mutableListOf<File>()

    private fun createTempLedgerFile(): File {
        val dir =
            kotlin.io.path
                .createTempDirectory("mcp-ledger-cli-test")
                .toFile()
        return File(dir, "mcp-calls.jsonl").also { tempFiles.add(it) }
    }

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.parentFile?.deleteRecursively() }
        tempFiles.clear()
    }

    private fun record(
        ledger: McpOperationLedger,
        toolName: String,
        disposition: McpApprovalDisposition = McpApprovalDisposition.AUTO_ALLOWED,
        isError: Boolean = false,
        secretRefs: List<String> = emptyList(),
        escalated: Boolean = false,
        providerId: String = "provider",
    ) {
        ledger.record(
            toolName = toolName,
            providerId = providerId,
            policyApplied = McpPolicyAction.ALLOW,
            approvalDisposition = disposition,
            durationMs = 7L,
            isError = isError,
            rawArgs = mapOf("path" to "/project"),
            secretRefs = secretRefs,
            escalated = escalated,
        )
        // Persistence is asynchronous: drain the writer so file asserts below see it.
        assertTrue(ledger.awaitIdle(), "ledger writer never drained")
    }

    private fun okText(outcome: McpLedgerOutcome): String = assertIs<McpLedgerOutcome.Ok>(outcome).text

    private fun failureMessage(outcome: McpLedgerOutcome): String = assertIs<McpLedgerOutcome.Failed>(outcome).message

    private fun toolNames(json: String): List<String> =
        Json
            .parseToJsonElement(json)
            .jsonObject
            .getValue("records")
            .jsonArray
            .map {
                it.jsonObject
                    .getValue("toolName")
                    .jsonPrimitive.content
            }

    @Test
    fun `verify rejects a directory passed as the ledger file`() {
        val file = createTempLedgerFile()

        val message = failureMessage(McpLedgerCli.verify(file.parentFile.absolutePath, json = false))

        assertTrue(message.contains("No MCP operation ledger"), message)
    }

    @Test
    fun `verify distinguishes an empty ledger from stripped hash history`() {
        val file = createTempLedgerFile().apply { writeText("") }

        val message = failureMessage(McpLedgerCli.verify(file.absolutePath, json = false))

        assertTrue(message.contains("ledger is empty"), message)
    }

    @Test
    fun `verify identifies an all legacy ledger as an upgrade state`() {
        val file = createTempLedgerFile()
        file.writeText(
            """{"id":"legacy","timestamp":1,"toolName":"tool_old","providerId":"provider",""" +
                """"policyApplied":"ALLOW","approvalDisposition":"AUTO_ALLOWED","durationMs":1,""" +
                """"isError":false,"sanitizedArgs":{}}""" + "\n",
        )

        val message = failureMessage(McpLedgerCli.verify(file.absolutePath, json = false))

        assertTrue(message.contains("every record predates integrity tracking"), message)
        assertTrue(message.contains("1 record(s) carry no hash"), message)
    }

    @Test
    fun `backup only history remains readable but verification fails closed`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file, maxFileSizeBytes = 200L, maxBackupIndex = 3)
        repeat(12) { index -> record(ledger, "tool_$index") }
        assertTrue(File(file.absolutePath + ".1").exists(), "a rotation should exist")
        file.delete()

        val tail = okText(McpLedgerCli.tail(file.absolutePath, 50, McpLedgerQuery(), json = true))
        val verification = failureMessage(McpLedgerCli.verify(file.absolutePath, json = false))

        assertTrue(toolNames(tail).isNotEmpty(), "surviving backup records must remain readable")
        assertTrue(verification.contains("INCOMPLETE"), verification)
        assertTrue(verification.contains("${file.name} missing"), verification)
    }

    @Test
    fun `tail reads the durable file rather than the in-memory ring buffer`() {
        val file = createTempLedgerFile()
        // The ring buffer holds 3; the file holds 10. tail must answer from the file, because the
        // ring buffer is a UI snapshot and not the audit trail.
        val ledger = McpOperationLedger(ledgerFile = file, ringBufferCapacity = 3)
        repeat(10) { index -> record(ledger, "tool_$index") }
        assertEquals(3, ledger.recentOperations.value.size)

        val text = okText(McpLedgerCli.tail(file.absolutePath, 5, McpLedgerQuery(), json = true))

        assertEquals(listOf("tool_9", "tool_8", "tool_7", "tool_6", "tool_5"), toolNames(text))
        assertEquals(
            10,
            Json
                .parseToJsonElement(text)
                .jsonObject
                .getValue("matched")
                .jsonPrimitive.content
                .toInt(),
        )
    }

    @Test
    fun `tail keeps the newest records and says how many matched`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        repeat(10) { index -> record(ledger, "tool_$index") }

        val human = okText(McpLedgerCli.tail(file.absolutePath, 3, McpLedgerQuery(), json = false))

        assertTrue(human.contains("tool_9"), "newest record must be shown: $human")
        assertTrue(human.contains("Showing 3 of 10 matching records."), "truncation must be stated: $human")
    }

    @Test
    fun `tail exports secret references in human and JSON audit output`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        val reference = "6f1d2c3e-4b5a-4c6d-8e7f-90a1b2c3d4e5.password"
        record(ledger, "write", secretRefs = listOf(reference))

        val human = okText(McpLedgerCli.tail(file.absolutePath, 1, McpLedgerQuery(), json = false))
        val json = okText(McpLedgerCli.tail(file.absolutePath, 1, McpLedgerQuery(), json = true))
        val exported =
            Json
                .parseToJsonElement(json)
                .jsonObject
                .getValue("records")
                .jsonArray
                .single()
                .jsonObject
                .getValue("secretRefs")
                .jsonArray
                .single()
                .jsonPrimitive
                .content

        assertTrue(human.contains("secrets: $reference"), human)
        assertEquals(reference, exported)
    }

    @Test
    fun `tail shows which calls were escalated in human and JSON audit output`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        record(ledger, "run_command", disposition = McpApprovalDisposition.YOLO_ALLOWED, escalated = true)
        record(ledger, "run_command", disposition = McpApprovalDisposition.YOLO_ALLOWED)

        val human = okText(McpLedgerCli.tail(file.absolutePath, 2, McpLedgerQuery(), json = false))
        val json = okText(McpLedgerCli.tail(file.absolutePath, 2, McpLedgerQuery(), json = true))
        val flags =
            Json
                .parseToJsonElement(json)
                .jsonObject
                .getValue("records")
                .jsonArray
                .map {
                    it.jsonObject
                        .getValue("escalated")
                        .jsonPrimitive.content
                }

        assertEquals(1, Regex("escalated:").findAll(human).count(), human)
        assertEquals(listOf("false", "true").sorted(), flags.sorted())
    }

    @Test
    fun `tail filters by an exact tool name`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        record(ledger, "git_status")
        record(ledger, "git_status_extra")
        record(ledger, "git_status")

        val text =
            okText(
                McpLedgerCli.tail(file.absolutePath, 10, McpLedgerQuery(tool = "git_status"), json = true),
            )

        assertEquals(listOf("git_status", "git_status"), toolNames(text))
    }

    @Test
    fun `tail classifies unsuccessful calls into the four disposition categories`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        record(ledger, "allowed_ok")
        record(ledger, "denied", McpApprovalDisposition.DENIED_BY_OPERATOR, isError = true)
        record(ledger, "cancelled", McpApprovalDisposition.CANCELLED, isError = true)
        record(ledger, "withheld", McpApprovalDisposition.POLICY_PERSIST_FAILED, isError = true)
        record(ledger, "ran_and_failed", McpApprovalDisposition.AUTO_ALLOWED, isError = true)

        fun category(name: String): List<String> =
            toolNames(
                okText(
                    McpLedgerCli.tail(
                        file.absolutePath,
                        10,
                        McpLedgerQuery(category = McpLedgerCli.parseCategory(name)),
                        json = true,
                    ),
                ),
            )

        assertEquals(listOf("denied"), category("denied"))
        assertEquals(listOf("cancelled"), category("cancelled"))
        assertEquals(listOf("withheld"), category("withheld"))
        // "failed" means the tool or host actually failed. A successful call is never in it, even
        // though the category classifier has a bucket for its disposition.
        assertEquals(listOf("ran_and_failed"), category("failed"))
    }

    @Test
    fun `search bounds results by time and reports the full match count`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        repeat(4) { index -> record(ledger, "tool_$index") }
        val now = System.currentTimeMillis()

        val all = okText(McpLedgerCli.search(file.absolutePath, 10, McpLedgerQuery(), json = true))
        assertEquals(4, toolNames(all).size)

        val future =
            okText(
                McpLedgerCli.search(
                    file.absolutePath,
                    10,
                    McpLedgerQuery(fromMillis = now + 3_600_000L),
                    json = true,
                ),
            )
        assertEquals(0, toolNames(future).size)

        val past =
            okText(
                McpLedgerCli.search(
                    file.absolutePath,
                    10,
                    McpLedgerQuery(toMillis = now - 3_600_000L),
                    json = true,
                ),
            )
        assertEquals(0, toolNames(past).size)

        val limited = okText(McpLedgerCli.search(file.absolutePath, 2, McpLedgerQuery(), json = true))
        assertEquals(listOf("tool_3", "tool_2"), toolNames(limited))
        assertEquals(
            4,
            Json
                .parseToJsonElement(limited)
                .jsonObject
                .getValue("matched")
                .jsonPrimitive.content
                .toInt(),
        )
    }

    @Test
    fun `records carry their chain hash into the JSON output`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        record(ledger, "tool_1")

        val text = okText(McpLedgerCli.tail(file.absolutePath, 1, McpLedgerQuery(), json = true))
        val entry =
            Json
                .parseToJsonElement(text)
                .jsonObject
                .getValue("records")
                .jsonArray
                .first()
                .jsonObject

        assertEquals(
            64,
            entry
                .getValue("hash")
                .jsonPrimitive.content.length,
        )
        assertEquals(file.name, entry.getValue("file").jsonPrimitive.content)
        assertEquals(
            1,
            entry
                .getValue("line")
                .jsonPrimitive.content
                .toInt(),
        )
    }

    @Test
    fun `verify succeeds on an intact chain and fails on a broken one`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        record(ledger, "tool_1")
        record(ledger, "tool_2")

        assertTrue(okText(McpLedgerCli.verify(file.absolutePath, json = false)).contains("intact"))

        val lines = file.readLines().toMutableList()
        lines[0] = lines[0].replace("\"tool_1\"", "\"tool_1_forged\"")
        file.writeText(lines.joinToString("\n") + "\n")

        val failure = failureMessage(McpLedgerCli.verify(file.absolutePath, json = false))
        assertTrue(failure.contains("BROKEN"), "a break must be reported as a failure: $failure")
        assertTrue(failure.contains("record 1 of 2"), "the break must be located: $failure")
    }

    @Test
    fun `verify refuses to report an absent ledger as intact`() {
        val file = File(createTempLedgerFile().parentFile, "absent.jsonl")

        val failure = failureMessage(McpLedgerCli.verify(file.absolutePath, json = false))

        assertTrue(failure.contains("No MCP operation ledger"), "the absence must be stated: $failure")
    }

    @Test
    fun `parseCategory accepts the four categories and nothing else`() {
        assertEquals(McpUnsuccessfulCategory.DENIED, McpLedgerCli.parseCategory("denied"))
        assertEquals(McpUnsuccessfulCategory.WITHHELD, McpLedgerCli.parseCategory(" WITHHELD "))
        assertNull(McpLedgerCli.parseCategory("allowed"))
        assertNull(McpLedgerCli.parseCategory(null))
        assertEquals("denied, cancelled, withheld, failed", McpLedgerCli.categoryLabels())
    }

    @Test
    fun `parseTime accepts epoch millis, a date, and ISO-8601`() {
        assertEquals(1_700_000_000_000L, McpLedgerCli.parseTime("1700000000000", endOfDay = false))
        assertEquals(0L, McpLedgerCli.parseTime("1970-01-01", endOfDay = false))
        assertEquals(86_399_999L, McpLedgerCli.parseTime("1970-01-01", endOfDay = true))
        assertEquals(0L, McpLedgerCli.parseTime("1970-01-01T00:00:00Z", endOfDay = false))
        assertEquals(0L, McpLedgerCli.parseTime("1970-01-01T00:00:00", endOfDay = false))
        assertNull(McpLedgerCli.parseTime("yesterday", endOfDay = false))
        assertNull(McpLedgerCli.parseTime("  ", endOfDay = false))
    }

    // ---- secrets: which tools received a credential, and when ------------------------------

    private val secretA = "6f1d2c3e-4b5a-4c6d-8e7f-90a1b2c3d4e5"
    private val secretB = "00000000-0000-4000-8000-000000000001"

    /** Four calls naming secret A two ways, one naming B, one naming nothing. */
    private fun secretLedger(): File {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        record(ledger, "open_terminal", McpApprovalDisposition.APPROVED_ONCE, secretRefs = listOf("$secretA.password"))
        record(
            ledger,
            "open_terminal",
            McpApprovalDisposition.SECRET_FORBIDDEN,
            isError = true,
            secretRefs = listOf("$secretA.password"),
        )
        record(
            ledger,
            "deploy",
            McpApprovalDisposition.DENIED_BY_OPERATOR,
            isError = true,
            secretRefs = listOf("$secretA.username"),
            providerId = "deployer",
        )
        // One call naming two fields of one secret is one call.
        record(
            ledger,
            "login",
            McpApprovalDisposition.CANCELLED_IN_FLIGHT,
            isError = true,
            secretRefs = listOf("$secretA.username", "$secretA.password"),
        )
        record(ledger, "run_command", McpApprovalDisposition.APPROVED_ONCE, secretRefs = listOf("$secretB.password"))
        record(ledger, "list_files")
        return file
    }

    private fun secretSummaries(json: String) =
        Json
            .parseToJsonElement(json)
            .jsonObject
            .getValue("secrets")
            .jsonArray
            .associateBy {
                it.jsonObject
                    .getValue("id")
                    .jsonPrimitive.content
            }.mapValues { it.value.jsonObject }

    @Test
    fun `secrets counts delivered and withheld apart, per secret, one call once`() {
        val file = secretLedger()

        val report = okText(McpLedgerSecrets.secrets(file.absolutePath, McpLedgerQuery(), json = true))
        val summaries = secretSummaries(report)

        assertEquals(setOf(secretA, secretB), summaries.keys)
        val a = summaries.getValue(secretA)
        assertEquals("4", a.getValue("calls").jsonPrimitive.content)
        // Approved once, and cancelled after its handler had started: both handed the value over.
        assertEquals("2", a.getValue("delivered").jsonPrimitive.content)
        assertEquals("2", a.getValue("withheld").jsonPrimitive.content)
        assertEquals(listOf("password", "username"), a.getValue("fields").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(setOf("open_terminal", "deploy", "login"), a.getValue("tools").jsonObject.keys)
        assertEquals(
            "2",
            a
                .getValue("tools")
                .jsonObject
                .getValue("open_terminal")
                .jsonPrimitive.content,
        )
        assertEquals(
            listOf("deployer", "provider"),
            a.getValue("providers").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            "1",
            summaries
                .getValue(secretB)
                .getValue("delivered")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `a field selector counts that field's references only`() {
        val file = secretLedger()

        val query = McpLedgerQuery(secret = "$secretA.username")
        val report = okText(McpLedgerSecrets.secrets(file.absolutePath, query, json = true))
        val a = secretSummaries(report).getValue(secretA)

        // The denied deploy and the cancelled login named the username; the approved call did not.
        assertEquals("2", a.getValue("calls").jsonPrimitive.content)
        assertEquals("1", a.getValue("delivered").jsonPrimitive.content)
        assertEquals(listOf("username"), a.getValue("fields").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `search filters by secret, by field, and by provider`() {
        val file = secretLedger()

        val tools = { query: McpLedgerQuery ->
            toolNames(okText(McpLedgerCli.search(file.absolutePath, 50, query, json = true)))
        }

        assertEquals(
            listOf("login", "deploy", "open_terminal", "open_terminal"),
            tools(McpLedgerQuery(secret = secretA)),
        )
        assertEquals(listOf("login", "deploy"), tools(McpLedgerQuery(secret = "$secretA.username")))
        assertEquals(listOf("deploy"), tools(McpLedgerQuery(provider = "deployer")))
        assertEquals(listOf("run_command"), tools(McpLedgerQuery(secret = secretB)))
    }

    @Test
    fun `the human report says which secrets were delivered and never prints a value`() {
        val file = secretLedger()

        val text = okText(McpLedgerSecrets.secrets(file.absolutePath, McpLedgerQuery(secret = secretB), json = false))

        assertTrue(text.startsWith(secretB), text)
        assertTrue(text.contains("1 call(s): 1 delivered to a handler, 0 withheld"), text)
        assertTrue(text.contains("tools:     run_command (1)"), text)
        // Provider ids, labelled as such: the JSON key says `providers` and a CLI cannot name plugins.
        assertTrue(text.contains("providers: provider"), text)
        assertTrue(text.contains("no value is read or shown"), text)
        // Which file it came from, how far back it reaches, and how to check it was not edited.
        assertTrue(text.contains("Ledger: ${file.absolutePath} and its rotated backups"), text)
        assertTrue(text.contains("older than the oldest backup is not counted"), text)
        assertTrue(text.contains("boss mcp ledger verify"), text)
    }

    @Test
    fun `a plugin id matches every provider the plugin registered, and a scoped id matches one`() {
        val file = createTempLedgerFile()
        val ledger = McpOperationLedger(ledgerFile = file)
        // How TrackingPluginContext records a plugin's tools: `<pluginId>::<providerId>`.
        val calls =
            listOf(
                Triple("vault_get", "password", "secret-manager::vault"),
                Triple("vault_list", "username", "secret-manager::index"),
                Triple("open_terminal", "password", "boss-workspace"),
            )
        for ((tool, field, provider) in calls) {
            val refs = listOf("$secretA.$field")
            record(ledger, tool, McpApprovalDisposition.APPROVED_ONCE, secretRefs = refs, providerId = provider)
        }

        val tools = { provider: String ->
            val query = McpLedgerQuery(provider = provider)
            toolNames(okText(McpLedgerCli.search(file.absolutePath, 50, query, json = true)))
        }
        assertEquals(listOf("vault_list", "vault_get"), tools("secret-manager"))
        assertEquals(listOf("vault_get"), tools("secret-manager::vault"))
        assertEquals(listOf("open_terminal"), tools("boss-workspace"))
        // A prefix of a plugin id is not the plugin.
        assertEquals(emptyList<String>(), tools("secret"))

        val plugin = McpLedgerQuery(provider = "secret-manager")
        val report = okText(McpLedgerSecrets.secrets(file.absolutePath, plugin, json = true))
        val a = secretSummaries(report).getValue(secretA)
        assertEquals("2", a.getValue("delivered").jsonPrimitive.content)
        assertEquals(
            listOf("secret-manager::index", "secret-manager::vault"),
            a.getValue("providers").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `a legacy CANCELLED record counts as a possible delivery`() {
        // It predates the split into awaiting-approval and in-flight, so it cannot say whether the
        // handler had the value. An audit that under-reports exposure is the wrong way to be wrong.
        assertTrue(McpApprovalDisposition.CANCELLED.reachedHandler)
        assertTrue(McpApprovalDisposition.CANCELLED_IN_FLIGHT.reachedHandler)
        assertFalse(McpApprovalDisposition.CANCELLED_AWAITING_APPROVAL.reachedHandler)
    }

    @Test
    fun `a secret selector must be an id, optionally with a field a reference can name`() {
        assertEquals(secretA, McpLedgerSecrets.parseSelector(secretA.uppercase()))
        assertEquals("$secretA.notes", McpLedgerSecrets.parseSelector(" $secretA.notes "))
        assertNull(McpLedgerSecrets.parseSelector("github"))
        assertNull(McpLedgerSecrets.parseSelector("$secretA.totp"))
        assertNull(McpLedgerSecrets.parseSelector("$secretA."))
    }

    @Test
    fun `no referenced secret is an answer, not an error`() {
        val file = createTempLedgerFile()
        record(McpOperationLedger(ledgerFile = file), "list_files")

        val text = okText(McpLedgerSecrets.secrets(file.absolutePath, McpLedgerQuery(), json = false))

        assertTrue(text.startsWith("No matching ledger record references a secret."), text)
        // An empty answer is the one acted on, so it says how far back it looked as well.
        assertTrue(text.contains("older than the oldest backup is not counted"), text)
    }
}
