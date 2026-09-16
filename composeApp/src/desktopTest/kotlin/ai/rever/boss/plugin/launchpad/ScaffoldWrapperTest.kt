package ai.rever.boss.plugin.launchpad

import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScaffoldWrapperTest {
    @Test
    fun `bundled wrapper matches upstream and contains launcher`() {
        val bytes =
            PluginScaffolder::class.java.getResourceAsStream("/launcher/gradle-wrapper.jar")!!.use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("cb0da6751c2b753a16ac168bb354870ebb1e162e9083f116729cec9c781156b8", hash)
        val names = mutableSetOf<String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names.add(entry.name)
                entry = zip.nextEntry
            }
        }
        assertTrue("org/gradle/wrapper/GradleWrapperMain.class" in names)
    }
}
