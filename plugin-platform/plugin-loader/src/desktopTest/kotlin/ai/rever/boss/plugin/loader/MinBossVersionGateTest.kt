package ai.rever.boss.plugin.loader

import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs

class MinBossVersionGateTest {
    private val tempJars = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempJars.forEach { it.delete() }
    }

    private fun manifestOnlyJar(minBossVersion: String): String {
        val jar = File.createTempFile("min-boss-gate", ".jar")
        tempJars += jar
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            out.write(
                """
                {
                  "manifestVersion": 1,
                  "pluginId": "com.example.bossgate.${jar.nameWithoutExtension}",
                  "displayName": "BOSS Gate Test",
                  "version": "1.0.0",
                  "apiVersion": "1.0.0",
                  "mainClass": "com.example.Missing",
                  "minBossVersion": "$minBossVersion"
                }
                """.trimIndent().toByteArray(),
            )
            out.closeEntry()
        }
        return jar.absolutePath
    }

    @Test
    fun `a local build of the required release satisfies the floor`() =
        runBlocking<Unit> {
            val loader = DynamicPluginLoaderImpl().apply { currentBossVersion = "9.5.41-local1833" }

            val result = loader.loadPlugin(manifestOnlyJar("9.5.41"))

            assertIs<PluginClassException>(result.exceptionOrNull())
        }

    @Test
    fun `a numerically older local build remains below the floor`() =
        runBlocking<Unit> {
            val loader = DynamicPluginLoaderImpl().apply { currentBossVersion = "9.5.40-local9999" }

            val result = loader.loadPlugin(manifestOnlyJar("9.5.41"))

            assertIs<PluginBossVersionException>(result.exceptionOrNull())
        }
}
