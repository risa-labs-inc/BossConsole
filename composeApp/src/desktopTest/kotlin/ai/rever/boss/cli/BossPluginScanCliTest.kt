package ai.rever.boss.cli

import ai.rever.boss.plugin.launchpad.scan.BenignFixture
import ai.rever.boss.plugin.launchpad.scan.ExecFixture
import ai.rever.boss.plugin.launchpad.scan.NetFixture
import ai.rever.boss.plugin.launchpad.scan.ScanFixtures
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BossPluginScanCliTest {
    private val dir = createTempDirectory("boss-plugin-scan-cli").toFile()
    private val originalOut = System.out
    private val originalErr = System.err
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()

    @BeforeTest
    fun capture() {
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
    }

    @AfterTest
    fun restore() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        dir.deleteRecursively()
    }

    private fun run(vararg args: String): Int =
        try {
            createBossCLI().parse(arrayOf("plugin", "scan") + args)
            0
        } catch (e: ProgramResult) {
            e.statusCode
        }

    private fun jar(
        name: String,
        vararg classes: Class<*>,
    ) = ScanFixtures.jar(dir, name, classes.toList()).path

    @Test
    fun `a scan prints the report and exits 0 by default`() {
        assertEquals(0, run(jar("a.jar", BenignFixture::class.java, ExecFixture::class.java)))
        val text = out.toString()
        assertTrue(text.contains("process.exec"), text)
        assertTrue(text.contains("REVIEW BEFORE LOADING"), text)
        assertTrue(text.contains("Limits"), text)
    }

    @Test
    fun `fail-on turns the risk into an exit status`() {
        val risky = jar("risky.jar", BenignFixture::class.java, ExecFixture::class.java)
        val calm = jar("calm.jar", BenignFixture::class.java)
        assertEquals(1, run(risky, "--fail-on", "high"))
        assertEquals(0, run(calm, "--fail-on", "high"))
        assertEquals(1, run(risky, "--fail-on", "low"))
    }

    @Test
    fun `json output is one parseable object`() {
        assertEquals(0, run(jar("a.jar", BenignFixture::class.java, ExecFixture::class.java), "--json"))
        val obj = Json.parseToJsonElement(out.toString()).jsonObject
        assertEquals("HIGH", obj["topRisk"]!!.jsonPrimitive.content)
    }

    @Test
    fun `against reports what the newer build gained and gates on it`() {
        val old = jar("old.jar", BenignFixture::class.java)
        val new = jar("new.jar", BenignFixture::class.java, ExecFixture::class.java, NetFixture::class.java)

        assertEquals(1, run(new, "--against", old, "--fail-on", "high"))
        val text = out.toString()
        assertTrue(text.contains("SCAN DIFF") && text.contains("Capabilities GAINED"), text)

        out.reset()
        assertEquals(0, run(old, "--against", new, "--fail-on", "high"), "dropping capabilities must not fail the gate")
    }

    @Test
    fun `a file that is not a JAR fails with a reason`() {
        val junk = File(dir, "junk.jar").apply { writeText("not a zip") }
        assertEquals(1, run(junk.path))
        assertTrue(out.toString().contains("NOT SCANNED"), out.toString())
    }

    @Test
    fun `a class padded past the size limit still fails the gate rather than reading as clean`() {
        // 9 MiB of zeros compresses to a few KiB, so nothing about the archive's own size gives it away.
        val file = File(dir, "padded.jar")
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("bomb/Big.class"))
            zip.write(ByteArray(9 * 1024 * 1024))
            zip.closeEntry()
        }
        assertEquals(1, run(file.path, "--fail-on", "medium"))
        val text = out.toString()
        assertTrue(text.contains("REVIEW"), text)
        assertTrue(text.contains("did not read the whole JAR"), text)
        out.reset()
        assertEquals(0, run(file.path, "--fail-on", "high"), "the incomplete-scan finding is MEDIUM, not HIGH")
    }
}
