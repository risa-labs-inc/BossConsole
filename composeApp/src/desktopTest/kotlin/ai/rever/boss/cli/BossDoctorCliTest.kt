package ai.rever.boss.cli

import ai.rever.boss.utils.SingleInstanceManager
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BossDoctorCliTest {
    private val runtimeDir = Files.createTempDirectory("boss-doctor-cli-test")
    private val originalOut = System.out
    private val originalErr = System.err
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()

    @BeforeTest
    fun setUp() {
        SingleInstanceManager.runtimeDirOverride = runtimeDir.toFile()
        SingleInstanceManager.statusProviderOverride = null
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        SingleInstanceManager.statusProviderOverride = null
        SingleInstanceManager.release()
        SingleInstanceManager.runtimeDirOverride = null
        runtimeDir.toFile().deleteRecursively()
    }

    @Test
    fun `doctor exits 0 and says so when no problems are reported`() {
        serve(HEALTHY)

        assertEquals(0, exitOf("doctor"))
        assertTrue(out.toString().contains("No problems found."))
    }

    @Test
    fun `doctor exits 2 and lists each finding with its suggestion when degraded`() {
        serve(DEGRADED)

        assertEquals(EXIT_DEGRADED, exitOf("doctor"))
        val report = out.toString()
        assertTrue(report.contains("[warning] Plugin 'Terminal Tab' was stopped after repeated failures."), report)
        assertTrue(report.contains("    Suggested: Restart BOSS."), report)
        assertTrue(report.contains("1 problem found."), report)
        assertTrue(report.contains("Not checked: browser"), report)
    }

    @Test
    fun `a finding whose message contains line breaks is printed on one line`() {
        serve(MULTILINE)

        assertEquals(EXIT_DEGRADED, exitOf("doctor"))
        val report = out.toString()
        assertTrue(report.contains("[critical] The file could not be read. JSON input: [1,2"), report)
        assertFalse(report.lines().any { it.startsWith("JSON input") }, report)
    }

    @Test
    fun `doctor json prints only the health object and still exits 2 when degraded`() {
        serve(DEGRADED)

        assertEquals(EXIT_DEGRADED, exitOf("doctor", "--json"))
        val printed = Json.parseToJsonElement(out.toString().trim()).jsonObject
        assertEquals(true, printed["degraded"]?.jsonPrimitive?.booleanOrNull)
        assertFalse(printed.containsKey("version"), "doctor --json must print the health object, not the status")
    }

    @Test
    fun `doctor exits 1 on stderr when the running BOSS returns no health report`() {
        serve(NO_HEALTH)

        assertEquals(1, exitOf("doctor"))
        assertEquals("", out.toString())
        assertTrue(err.toString().contains("did not return a health report"), err.toString())
    }

    @Test
    fun `doctor exits 1 when BOSS is not running`() {
        assertEquals(1, exitOf("doctor"))
        assertTrue(err.toString().contains("BOSS is not running"), err.toString())
    }

    @Test
    fun `status keeps exit 0 when degraded and points at doctor`() {
        serve(DEGRADED)

        assertEquals(0, exitOf("status"))
        assertTrue(out.toString().contains("Health:         1 problem (run 'boss doctor')"), out.toString())
    }

    @Test
    fun `status prints no health line for a BOSS that does not report health`() {
        serve(NO_HEALTH)

        assertEquals(0, exitOf("status"))
        assertFalse(out.toString().contains("Health:"), out.toString())
    }

    @Test
    fun `doctor is registered as a subcommand`() {
        assertTrue(createBossCLI().registeredSubcommands().any { it.commandName == "doctor" })
    }

    private fun serve(status: String) {
        SingleInstanceManager.statusProviderOverride = { status }
        assertTrue(SingleInstanceManager.acquireLock())
        // The in-process server logs to stdout while it starts. Waiting for it to answer puts that
        // logging behind us, so exitOf can capture only the command's own output.
        assertTrue(SingleInstanceManager.queryStatus().isSuccess)
    }

    /** Run the CLI and return its exit code, with [out] and [err] holding only its own output. */
    private fun exitOf(vararg args: String): Int {
        out.reset()
        err.reset()
        return try {
            createBossCLI().parse(args.toList())
            0
        } catch (e: ProgramResult) {
            e.statusCode
        }
    }

    private companion object {
        const val NO_HEALTH = """{"running":true,"version":"9.5.12"}"""

        const val HEALTHY = """{"running":true,"health":{"degraded":false,"findings":[],"unchecked":[]}}"""

        val DEGRADED =
            """
            {"running":true,"version":"9.5.12","health":{"degraded":true,"unchecked":["browser"],"findings":[
              {"area":"plugins","severity":"warning","code":"plugin_stopped_after_failures",
               "summary":"Plugin 'Terminal Tab' was stopped after repeated failures.",
               "subject":"terminaltab","remedy":"Restart BOSS."}]}}
            """.trimIndent()

        // The summary carries a JSON-escaped line break, as a parser error message does.
        val MULTILINE =
            """
            {"running":true,"health":{"degraded":true,"unchecked":[],"findings":[
              {"area":"mcp","severity":"critical","code":"mcp_tools_withheld",
               "summary":"The file could not be read.\nJSON input: [1,2"}]}}
            """.trimIndent()
    }
}
