package ai.rever.boss.cli

import com.github.ajalt.clikt.core.parse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A fresh JVM proves the command reads files and the embedded resource, not test-only properties. */
class BossConfigProcessTest {
    @Test
    fun `real command reports all five loaded tiers`(
        @TempDir directory: Path,
    ) {
        Files.createDirectories(directory.resolve(".boss"))
        Files.writeString(directory.resolve(".boss/env_vars"), "BOSS_MODE=KERNEL\n")
        Files.writeString(directory.resolve("local.properties"), "SUPABASE_URL=https://local.invalid\n")
        Files.writeString(
            directory.resolve("boss-build-config.properties"),
            "SUPABASE_FUNCTION_URL=https://embedded.invalid\n",
        )
        val urls =
            generateSequence(javaClass.classLoader) { it.parent }
                .filterIsInstance<URLClassLoader>()
                .flatMap { it.getURLs().asSequence() }
                .map { File(it.toURI()).path }
                .toList()
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath =
            (listOf(directory.toString()) + urls + System.getProperty("java.class.path").split(File.pathSeparator))
                .distinct()
                .joinToString(File.pathSeparator)
        val quotedClasspath = classpath.replace("\\", "\\\\").replace("\"", "\\\"")
        val quotedHome = directory.toString().replace("\\", "\\\\").replace("\"", "\\\"")
        val argumentsFile = directory.resolve("config-probe.args")
        Files.writeString(
            argumentsFile,
            listOf(
                "\"-Duser.home=$quotedHome\"",
                "-DBOSS_LOG_LEVEL=from-system-property",
                "-cp",
                "\"$quotedClasspath\"",
                BossConfigProcessProbe::class.java.name,
            ).joinToString("\n", postfix = "\n"),
        )
        val process =
            ProcessBuilder(javaExecutable, "@${argumentsFile.toAbsolutePath()}")
                .directory(directory.toFile())
                .apply {
                    environment()["BOSS_BROWSER_SWIPE_NAV"] = "from-environment"
                    environment().remove("BOSS_MODE")
                    environment().remove("SUPABASE_URL")
                    environment().remove("SUPABASE_FUNCTION_URL")
                }.start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "config probe must finish")
        assertEquals(0, process.exitValue(), stderr)
        val payload = Json.parseToJsonElement(stdout.trim()).jsonObject
        val rows = payload.getValue("rows").jsonArray
        val sources =
            rows.associate { row ->
                val fields = row.jsonObject
                fields.getValue("key").jsonPrimitive.content to fields.getValue("source").jsonPrimitive.content
            }
        assertEquals("environment", sources["BOSS_BROWSER_SWIPE_NAV"])
        assertEquals("system property", sources["BOSS_LOG_LEVEL"])
        assertEquals("env_vars file", sources["BOSS_MODE"])
        assertEquals("local.properties", sources["SUPABASE_URL"])
        assertEquals("embedded", sources["SUPABASE_FUNCTION_URL"])
        assertTrue(stdout.contains("https://embedded.invalid"))
    }
}

object BossConfigProcessProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        configureHeadlessLogging()
        createBossCLI().parse(
            listOf(
                "config",
                "show",
                "--json",
                "--key",
                "BOSS_BROWSER_SWIPE_NAV",
                "--key",
                "BOSS_LOG_LEVEL",
                "--key",
                "BOSS_MODE",
                "--key",
                "SUPABASE_URL",
                "--key",
                "SUPABASE_FUNCTION_URL",
            ),
        )
    }
}
