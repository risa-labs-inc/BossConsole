package ai.rever.boss.run

import ai.rever.boss.mcp.secrets.captureHostLogs
import ai.rever.boss.plugin.pathutils.BossDirectories
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunConfigurationPersistenceTest {
    @Test
    fun `parallel additions preserve every configuration in memory and on disk`() =
        runBlocking {
            val prefix = UUID.randomUUID().toString()
            val configs =
                (0 until 24).map { index ->
                    RunConfiguration(
                        id = "$prefix-$index",
                        name = "Concurrent run $index",
                        type = RunConfigurationType.MAIN_FUNCTION,
                        filePath = "/example/$prefix/$index.kt",
                        lineNumber = 1,
                        language = Language.KOTLIN,
                        command = "",
                        workingDirectory = "",
                    )
                }
            val start = CompletableDeferred<Unit>()

            try {
                val jobs =
                    configs.map { config ->
                        async(Dispatchers.Default) {
                            start.await()
                            RunConfigurationManager.addConfiguration(config)
                        }
                    }
                start.complete(Unit)
                jobs.awaitAll()

                val expected = configs.map { it.id }.toSet()
                val inMemory =
                    RunConfigurationManager.currentSettings.value.configurations
                        .map { it.id }
                        .toSet()
                val saved =
                    Json
                        .decodeFromString<RunConfigurationSettings>(
                            BossDirectories.resolve("run-configurations.json").readText(),
                        ).configurations
                        .map { it.id }
                        .toSet()

                assertTrue(inMemory.containsAll(expected), "Concurrent updates must not drop a configuration")
                assertEquals(inMemory, saved, "The last completed update must be the one on disk")
            } finally {
                configs.forEach { RunConfigurationManager.removeConfiguration(it.id) }
            }
        }

    @Test
    fun `failed startup cleanup write preserves loaded configurations`() {
        val file = Files.createTempFile("run-config-startup-", ".json").toFile()
        val original =
            RunConfiguration(
                id = "keep-this-configuration",
                name = "Main",
                type = RunConfigurationType.MAIN_FUNCTION,
                filePath = "/example/Main.kt",
                lineNumber = 1,
                language = Language.KOTLIN,
                command = "",
                workingDirectory = "",
            )
        val duplicate = original.copy(id = "duplicate-path")

        try {
            file.writeText(
                Json.encodeToString(
                    RunConfigurationSettings.serializer(),
                    RunConfigurationSettings(configurations = listOf(original, duplicate)),
                ),
            )

            val loaded =
                RunConfigurationManager.loadSettingsFromFile(file) { target, _ ->
                    // A failed write may have already damaged the file; the in-memory
                    // result must survive either way.
                    target.writeText("")
                    error("Simulated cleanup write failure")
                }

            assertEquals(
                listOf(original.id),
                loaded.configurations.map { it.id },
                "A failed cleanup write must not discard configurations loaded from disk",
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a malformed settings file still reaches the startup error handler`() {
        val file = Files.createTempFile("run-config-malformed-", ".json").toFile()
        try {
            file.writeText("{ not json")
            assertFailsWith<SerializationException> {
                RunConfigurationManager.loadSettingsFromFile(file) { _, _ ->
                    error("must not be reached")
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a corrupt run settings file is logged without its command`() {
        val file = Files.createTempFile("run-config-private-", ".json").toFile()
        val secret = "private-command-${System.nanoTime()}"
        try {
            file.writeText("{\"configurations\":[{\"id\":\"one\",\"command\":\"$secret")

            val (_, logged) = captureHostLogs { RunConfigurationManager.resetForTesting(file) }

            val failure = logged.single { it.message == "Failed to load run settings" }
            assertNull(failure.error, "the decoder exception includes command lines and must not be attached")
            assertEquals("JsonDecodingException", failure.data?.get("decodeFailure"))
            for (entry in logged) {
                assertFalse(secret in "${entry.message} ${entry.data} ${entry.error}", "leaked in: $entry")
            }
        } finally {
            RunConfigurationManager.resetForTesting()
            file.delete()
        }
    }
}
