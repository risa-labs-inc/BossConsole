package ai.rever.boss.plugin.launchpad.scan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScanDiffAndReportTest {
    private val dir = createTempDirectory("scan-diff").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun jar(
        name: String,
        classes: List<Class<*>>,
        id: String = "ai.rever.boss.plugin.dynamic.sample",
        version: String = "1.0.0",
        permissions: String = "[]",
    ) = ScanFixtures.jar(dir, name, classes, id = id, version = version, permissions = permissions)

    private fun diff(
        old: File,
        new: File,
    ) = ScanDiff.of(PluginJarScanner.scan(old), PluginJarScanner.scan(new))

    @Test
    fun `an update that gains capabilities is reported with the risk of what it gained`() {
        val old = jar("old.jar", listOf(BenignFixture::class.java), version = "1.0.0")
        val gains = listOf(BenignFixture::class.java, ExecFixture::class.java, NetFixture::class.java)
        val new = jar("new.jar", gains, version = "1.1.0")

        val d = diff(old, new)

        assertEquals(setOf("process.exec", "net.client"), d.addedCapabilities.map { it.capability.id }.toSet())
        assertEquals(emptyList(), d.removedCapabilities)
        assertEquals(ScanRisk.HIGH, d.addedRisk)
        assertTrue(d.verdict.startsWith("REVIEW BEFORE LOADING"), d.verdict)
        assertEquals(listOf("exfil.example.test"), d.addedHosts)
        val text = ScanReportText.diff(d)
        assertTrue(text.contains("Capabilities GAINED (2)"), text)
        assertTrue(text.contains("v1.0.0") && text.contains("v1.1.0"), text)
    }

    @Test
    fun `dropping a capability is reported and is not a warning`() {
        val d =
            diff(
                jar("old.jar", listOf(BenignFixture::class.java, ExecFixture::class.java)),
                jar("new.jar", listOf(BenignFixture::class.java)),
            )
        assertEquals(listOf("process.exec"), d.removedCapabilities.map { it.capability.id })
        assertEquals(emptyList(), d.addedCapabilities)
        assertTrue(d.verdict.startsWith("NO NEW CAPABILITIES"), d.verdict)
    }

    @Test
    fun `identical builds have no changes`() {
        val classes = listOf(BenignFixture::class.java, ExecFixture::class.java)
        val d = diff(jar("a.jar", classes), jar("b.jar", classes))
        assertEquals(emptyList(), d.addedCapabilities)
        assertEquals(emptyList(), d.removedCapabilities)
        assertTrue(d.verdict.startsWith("NO NEW CAPABILITIES"))
    }

    @Test
    fun `moving a call to another class is not a change`() {
        val d =
            diff(
                jar("old.jar", listOf(BenignFixture::class.java, ExecFixture::class.java)),
                jar("new.jar", listOf(BenignFixture::class.java, WideConstantsFixture::class.java)),
            )
        assertEquals(emptyList(), d.addedCapabilities)
        assertEquals(emptyList(), d.removedCapabilities)
    }

    @Test
    fun `permission changes are reported and signature findings are not compared`() {
        val old = jar("old.jar", listOf(BenignFixture::class.java), permissions = """["secret.read"]""")
        File(old.path + ".sig").writeText("sig")
        val new = jar("new.jar", listOf(BenignFixture::class.java), permissions = """["secret.read","role.read"]""")

        val d = diff(old, new)

        assertEquals(listOf("role.read"), d.addedPermissions)
        assertFalse((d.addedFindings + d.removedFindings).any { it.id.startsWith("sig.") })
    }

    @Test
    fun `two different plugins are flagged as such and an unreadable JAR is not compared`() {
        val a = jar("a.jar", listOf(BenignFixture::class.java), id = "ai.rever.boss.plugin.dynamic.one")
        val b = jar("b.jar", listOf(BenignFixture::class.java), id = "ai.rever.boss.plugin.dynamic.two")
        assertFalse(diff(a, b).samePlugin)
        assertTrue(ScanReportText.diff(diff(a, b)).contains("do not declare the same pluginId"))

        val junk = File(dir, "junk.jar").apply { writeText("not a zip") }
        assertTrue(diff(junk, a).verdict.startsWith("NOT COMPARED"))
    }

    @Test
    fun `the JSON scan parses back and carries the fields a CI step needs`() {
        val file = jar("j.jar", listOf(BenignFixture::class.java, ExecFixture::class.java))
        val obj =
            Json
                .parseToJsonElement(
                    Json.encodeToString(JsonObject.serializer(), ScanReportJson.scan(PluginJarScanner.scan(file))),
                ).jsonObject

        assertEquals("HIGH", obj["topRisk"]!!.jsonPrimitive.content)
        assertEquals("ai.rever.boss.plugin.dynamic.sample", obj["pluginId"]!!.jsonPrimitive.content)
        val caps = obj["capabilities"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue("process.exec" in caps, "$caps")
        assertTrue(obj["limits"] is JsonArray)
    }

    @Test
    fun `the JSON diff parses back`() {
        val d =
            diff(
                jar("old.jar", listOf(BenignFixture::class.java)),
                jar("new.jar", listOf(BenignFixture::class.java, ExecFixture::class.java)),
            )
        val text = Json.encodeToString(JsonObject.serializer(), ScanReportJson.diff(d))
        val obj = Json.parseToJsonElement(text).jsonObject
        assertEquals("HIGH", obj["addedRisk"]!!.jsonPrimitive.content)
        val added = obj["addedCapabilities"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("process.exec"), added)
    }

    @Test
    fun `a build that only newly names a host is not reported INFO while the verdict says it gained something`() {
        val old = jar("old.jar", listOf(BenignFixture::class.java))
        val new =
            ScanFixtures.jar(
                dir,
                "new.jar",
                listOf(HostOnlyFixture::class.java),
                main = "ai.rever.boss.plugin.launchpad.scan.HostOnlyFixture",
            )

        val d = diff(old, new)

        assertEquals(emptyList(), d.addedFindings, "isolate the host-only case from an unrelated main-class finding")
        assertEquals(emptyList(), d.addedCapabilities, "the fixture triggers no capability, only a host string")
        assertEquals(listOf("exfil.example.test"), d.addedHosts)
        assertTrue(d.verdict.startsWith("REVIEW"), d.verdict)
        // addedRisk drives both the verdict's HIGH branch and BossPluginScanCommand's --fail-on gate; if it
        // stayed INFO here, `--fail-on low` would print "REVIEW" and still exit 0.
        assertEquals(ScanRisk.LOW, d.addedRisk, "verdict says REVIEW, so the gate must not read INFO")
    }

    @Test
    fun `ScanText shows line breaks controls and bidi overrides as escapes`() {
        val out = ScanText.safe("a\nb\rc\td${27.toChar()}e${0x202e.toChar()}f${0x7f.toChar()}${0x85.toChar()}")
        assertEquals("a\\nb\\rc\\td\\u001be\\u202ef\\u007f\\u0085", out)
        assertEquals("plain text, cafe", ScanText.safe("plain text, cafe"))
        assertEquals(13, ScanText.safe("x".repeat(500), max = 10).length)
    }
}
