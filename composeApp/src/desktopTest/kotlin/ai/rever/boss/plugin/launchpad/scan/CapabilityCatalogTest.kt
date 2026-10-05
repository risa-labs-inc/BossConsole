package ai.rever.boss.plugin.launchpad.scan

import ai.rever.boss.plugin.api.PluginContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Each capability is recognised in a real compiled class, and the catalogue cannot drift from the API it describes. */
class CapabilityCatalogTest {
    private fun ids(cls: Class<*>) = ScanFixtures.ids(cls)

    @Test
    fun `running other programs is recognised`() {
        assertTrue("process.exec" in ids(ExecFixture::class.java))
    }

    @Test
    fun `outbound network use is recognised and the listening kind is separate`() {
        assertTrue("net.client" in ids(NetFixture::class.java))
        assertTrue("net.listen" in ids(ListenFixture::class.java))
        assertTrue("net.listen" !in ids(NetFixture::class.java))
    }

    @Test
    fun `file writes deletes and reads are three different capabilities`() {
        val found = ids(FileFixture::class.java)
        assertTrue("fs.write" in found && "fs.delete" in found && "fs.read" in found, "$found")
    }

    @Test
    fun `reflection and access bypass are recognised`() {
        val found = ids(ReflectFixture::class.java)
        assertTrue("reflect.use" in found && "reflect.access" in found, "$found")
    }

    @Test
    fun `native code environment exit and credential paths are recognised`() {
        assertTrue("native.code" in ids(NativeFixture::class.java))
        assertTrue("env.read" in ids(EnvFixture::class.java))
        assertTrue("jvm.exit" in ids(ExitFixture::class.java))
        assertTrue("cred.path" in ids(CredentialPathFixture::class.java))
    }

    @Test
    fun `subclassing a class loader is recognised from the superclass alone`() {
        assertTrue("dynamic.classload" in ids(LoaderFixture::class.java))
    }

    @Test
    fun `references after long and double constants are still found`() {
        // A long or double takes two pool slots. Miscounting them shifts every later entry.
        assertTrue("process.exec" in ids(WideConstantsFixture::class.java))
    }

    @Test
    fun `host APIs are recognised by getter, by type and by the ungated project rewrite`() {
        val found = ids(HostApiFixture::class.java)
        val expected =
            listOf("host.secrets", "host.event-bus", "host.project-search", "host.project-replace", "host.mcp")
        for (id in expected) {
            assertTrue(id in found, "$id missing from $found")
        }
    }

    @Test
    fun `a class that touches nothing reports nothing`() {
        assertEquals(emptySet(), ids(BenignFixture::class.java))
    }

    @Test
    fun `evidence names the class and the reference`() {
        val hits = CapabilityCatalog.detect(ScanFixtures.info(ExecFixture::class.java))
        val hit = hits.first { it.capability.id == "process.exec" }
        assertEquals("ai/rever/boss/plugin/launchpad/scan/ExecFixture", hit.evidence.className)
        assertTrue(hit.evidence.detail.startsWith("java/lang/"), hit.evidence.detail)
    }

    @Test
    fun `capability ids are unique and every one carries a reason`() {
        val all = CapabilityCatalog.all
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertTrue(all.all { it.why.isNotBlank() && it.title.isNotBlank() })
    }

    @Test
    fun `every host type the catalogue names exists in the plugin API`() {
        val missing =
            CapabilityCatalog.hostTypes.values
                .flatten()
                .filter { runCatching { Class.forName(it.replace('/', '.')) }.isFailure }
        assertEquals(emptyList(), missing, "the API renamed or removed a type the catalogue relies on")
    }

    @Test
    fun `every PluginContext getter the catalogue names exists`() {
        val declared =
            PluginContext::class.java.methods
                .map { it.name }
                .toSet()
        val missing =
            CapabilityCatalog.contextGetters.values
                .flatten()
                .filter { it !in declared }
        assertEquals(emptyList(), missing, "the API renamed or removed a getter the catalogue relies on")
    }
}
