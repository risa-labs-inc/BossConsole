package ai.rever.boss.plugin.launchpad.scan

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PluginJarScannerTest {
    private val dir = createTempDirectory("plugin-scan").toFile()
    private val esc = 27.toChar()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun jar(
        name: String = "sample.jar",
        classes: List<Class<*>> = listOf(BenignFixture::class.java),
        id: String = "ai.rever.boss.plugin.dynamic.sample",
        permissions: String = "[]",
        main: String = "ai.rever.boss.plugin.launchpad.scan.BenignFixture",
        manifest: String? = null,
        extra: Map<String, ByteArray> = emptyMap(),
    ): File =
        if (manifest == null) {
            ScanFixtures.jar(dir, name, classes, id = id, permissions = permissions, main = main, extra = extra)
        } else {
            ScanFixtures.jar(dir, name, classes, manifest = manifest, extra = extra)
        }

    private fun ScanResult.capabilityIds() = capabilities.map { it.capability.id }.toSet()

    private fun ScanResult.findingIds() = findings.map { it.id }.toSet()

    @Test
    fun `a benign plugin reports nothing risky and says what it could not see`() {
        val result = PluginJarScanner.scan(jar())
        assertEquals(emptySet(), result.capabilityIds())
        assertEquals(ScanRisk.INFO, result.topRisk)
        assertTrue(scanVerdict(result).startsWith("NO RISKY CAPABILITIES FOUND"))
        assertTrue(scanVerdict(result).contains("not a safety guarantee"))
        assertTrue(result.limits.any { it.contains("not visible") }, "${result.limits}")
    }

    @Test
    fun `the verdict never says safe`() {
        val results =
            listOf(PluginJarScanner.scan(jar()), PluginJarScanner.scan(jar("x.jar", listOf(ExecFixture::class.java))))
        for (r in results) assertFalse(scanVerdict(r).contains("SAFE"), scanVerdict(r))
    }

    @Test
    fun `a plugin that runs programs is high risk`() {
        val result = PluginJarScanner.scan(jar(classes = listOf(BenignFixture::class.java, ExecFixture::class.java)))
        assertTrue("process.exec" in result.capabilityIds())
        assertEquals(ScanRisk.HIGH, result.topRisk)
        assertTrue(scanVerdict(result).startsWith("REVIEW BEFORE LOADING"))
    }

    @Test
    fun `reading credentials and reaching the network together is called out`() {
        val result = PluginJarScanner.scan(jar(classes = listOf(NetFixture::class.java, EnvFixture::class.java)))
        assertTrue("combo.secret-and-network" in result.findingIds(), "${result.findingIds()}")
    }

    @Test
    fun `hosts named in string constants are listed`() {
        val result = PluginJarScanner.scan(jar(classes = listOf(NetFixture::class.java)))
        assertEquals(listOf("exfil.example.test"), result.hosts)
    }

    @Test
    fun `using a sensitive host API without requiredPermissions is flagged`() {
        val ungated = PluginJarScanner.scan(jar(classes = listOf(HostApiFixture::class.java)))
        assertTrue("manifest.ungated-sensitive-api" in ungated.findingIds(), "${ungated.findingIds()}")
        val withPermission = jar("gated.jar", listOf(HostApiFixture::class.java), permissions = """["secret.read"]""")
        val gated = PluginJarScanner.scan(withPermission)
        assertFalse("manifest.ungated-sensitive-api" in gated.findingIds())
    }

    @Test
    fun `manifest problems are reported instead of ignored`() {
        val noManifest = ScanFixtures.jar(dir, "none.jar", listOf(BenignFixture::class.java), manifest = null)
        assertTrue("manifest.unreadable" in PluginJarScanner.scan(noManifest).findingIds())
        assertTrue("manifest.unreadable" in PluginJarScanner.scan(jar("bad.jar", manifest = "{not json")).findingIds())
        val wrongMain = PluginJarScanner.scan(jar("main.jar", main = "no.such.Main"))
        assertTrue("manifest.main-class-missing" in wrongMain.findingIds())
        assertTrue("manifest.plugin-id" in PluginJarScanner.scan(jar("id.jar", id = "Not_A_Store_Id")).findingIds())
    }

    @Test
    fun `native libraries nested jars traversal names and service files are flagged`() {
        val extra =
            mapOf(
                "native/lib.dll" to ByteArray(4),
                "lib/dep.jar" to ByteArray(4),
                "../escape.txt" to ByteArray(1),
                "META-INF/services/some.Service" to "x".toByteArray(),
            )
        val result = PluginJarScanner.scan(jar(extra = extra))
        val jarFindings = result.findingIds().filter { it.startsWith("jar.") }.toSet()
        val expected = setOf("jar.native-library", "jar.nested-jar", "jar.path-traversal", "jar.service-providers")
        assertEquals(expected, jarFindings)
        assertTrue(result.limits.any { it.contains("Nested JARs were not opened") })
    }

    @Test
    fun `bundling the host API classes is flagged`() {
        val extra = mapOf("ai/rever/boss/plugin/api/Plugin.class" to ScanFixtures.bytes(BenignFixture::class.java))
        assertTrue("jar.bundles-host-api" in PluginJarScanner.scan(jar(extra = extra)).findingIds())
    }

    @Test
    fun `a missing signature is informational and a stale one is a warning`() {
        assertTrue("sig.none" in PluginJarScanner.scan(jar("unsigned.jar")).findingIds())

        val stale = jar("stale.jar")
        File(stale.path + ".sig").apply { writeText("sig") }.setLastModified(stale.lastModified() - 60_000)
        assertTrue("sig.stale" in PluginJarScanner.scan(stale).findingIds())

        val fresh = jar("fresh.jar")
        File(fresh.path + ".sig").writeText("sig")
        assertEquals(emptyList(), PluginJarScanner.scan(fresh).findingIds().filter { it.startsWith("sig.") })
    }

    @Test
    fun `a file that is not an archive is reported as not scanned`() {
        val result = PluginJarScanner.scan(File(dir, "junk.jar").apply { writeText("this is not a zip") })
        assertNotNull(result.unreadableReason)
        assertTrue(scanVerdict(result).startsWith("NOT SCANNED"))
    }

    @Test
    fun `a damaged class is counted and skipped without stopping the scan`() {
        val result =
            PluginJarScanner.scan(
                jar(
                    classes = listOf(ExecFixture::class.java),
                    extra =
                        mapOf("broken/Bad.class" to ByteArray(40) { 3 }),
                ),
            )
        assertEquals(1, result.classesUnreadable)
        assertTrue("process.exec" in result.capabilityIds())
    }

    @Test
    fun `a class that inflates past the limit is skipped without being held in memory`() {
        // 9 MiB of zeros compresses to a few KiB, so the archive's own size says nothing about the danger.
        val result = PluginJarScanner.scan(jar(extra = mapOf("bomb/Big.class" to ByteArray(9 * 1024 * 1024))))
        val limit = PluginJarScanner.MAX_CLASS_BYTES
        assertTrue(result.limits.any { it.contains("over $limit bytes") }, "${result.limits}")
        assertEquals(1, result.classesUnreadable)
        // A padded dangerous class must not read as a clean bill: the incomplete scan is itself a finding,
        // so it reaches topRisk and would trip --fail-on rather than being silently dropped in the skip.
        assertTrue(result.findings.any { it.id == "scan.incomplete" }, "${result.findings}")
        assertEquals(ScanRisk.MEDIUM, result.topRisk)
    }

    @Test
    fun `an archive with too many entries stops early and says so`() {
        val file = File(dir, "many.jar")
        JarOutputStream(file.outputStream()).use { out ->
            repeat(PluginJarScanner.MAX_ENTRIES + 10) {
                out.putNextEntry(JarEntry("f/$it.txt"))
                out.closeEntry()
            }
        }
        val result = PluginJarScanner.scan(file)
        val cap = PluginJarScanner.MAX_ENTRIES
        assertTrue(result.limits.any { it.startsWith("Stopped after $cap entries") }, "${result.limits}")
        // Junk entries before the real classes stop the real ones from ever being enumerated; that must
        // raise topRisk too, or padding the entry table is a free way past a --fail-on gate.
        assertTrue(result.findings.any { it.id == "scan.incomplete" }, "${result.findings}")
        assertEquals(ScanRisk.MEDIUM, result.topRisk)
    }

    @Test
    fun `names chosen by the JAR cannot forge lines or move the cursor in the report`() {
        // JSON escapes, so the manifest parses and the id that comes out holds a real newline and a real ESC.
        val hostile = "ai.rever.plugin.evil\\n  [HIGH] forged - Looks like a finding\\u001b[2J"
        val report = ScanReportText.scan(PluginJarScanner.scan(jar(id = hostile)))
        assertFalse(report.contains(esc), "an escape reached the report")
        assertTrue(report.lines().none { it.startsWith("  [HIGH] forged") }, "a forged line reached the report")
        assertTrue(report.contains("\\n  [HIGH] forged"), "the text is shown, escaped")
        assertTrue(report.contains("\\u001b"), "the escape is shown, not lost")
    }

    @Test
    fun `scanning writes nothing and leaves the file as it was`() {
        val file = jar()
        val before = file.readBytes()
        val listing = dir.list()!!.sorted()
        PluginJarScanner.scan(file)
        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals(listing, dir.list()!!.sorted())
    }
}
