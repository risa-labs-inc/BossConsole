package ai.rever.boss.service.settings

import ai.rever.boss.ipc.proto.services.GetSettingRequest
import ai.rever.boss.ipc.proto.services.ListSettingsRequest
import ai.rever.boss.ipc.proto.services.SetSettingRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsServiceImplTest {
    private lateinit var tempDir: File
    private lateinit var settingsFile: File
    private lateinit var service: SettingsServiceImpl

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("settings-service-test").toFile()
        settingsFile = File(tempDir, "settings.json")
        service = SettingsServiceImpl(storageFile = settingsFile)
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `getSetting returns default value when not found`() =
        runBlocking {
            val req =
                GetSettingRequest
                    .newBuilder()
                    .setNamespace("editor")
                    .setKey("fontSize")
                    .setDefaultValue("14")
                    .build()
            val result = service.getSetting(req)
            assertFalse(result.found)
            assertEquals("14", result.value)
            assertEquals("fontSize", result.key)
            assertEquals("editor", result.namespace)
        }

    @Test
    fun `setSetting persists and getSetting retrieves setting`() =
        runBlocking {
            val setReq =
                SetSettingRequest
                    .newBuilder()
                    .setNamespace("editor")
                    .setKey("theme")
                    .setValue("dark")
                    .build()
            val setRes = service.setSetting(setReq)
            assertTrue(setRes.found)
            assertEquals("dark", setRes.value)
            assertTrue(settingsFile.exists())

            val getReq =
                GetSettingRequest
                    .newBuilder()
                    .setNamespace("editor")
                    .setKey("theme")
                    .setDefaultValue("light")
                    .build()
            val getRes = service.getSetting(getReq)
            assertTrue(getRes.found)
            assertEquals("dark", getRes.value)
        }

    @Test
    fun `reloading service loads persisted settings from disk`() =
        runBlocking {
            val setReq =
                SetSettingRequest
                    .newBuilder()
                    .setNamespace("ui")
                    .setKey("density")
                    .setValue("compact")
                    .build()
            service.setSetting(setReq)

            // Re-instantiate service reading from same disk file
            val newService = SettingsServiceImpl(storageFile = settingsFile)
            val getReq =
                GetSettingRequest
                    .newBuilder()
                    .setNamespace("ui")
                    .setKey("density")
                    .setDefaultValue("comfortable")
                    .build()
            val getRes = newService.getSetting(getReq)
            assertTrue(getRes.found)
            assertEquals("compact", getRes.value)
        }

    @Test
    fun `concurrent setSetting calls succeed serially without corruption`() =
        runBlocking {
            val jobs =
                (1..20).map { i ->
                    async {
                        service.setSetting(
                            SetSettingRequest
                                .newBuilder()
                                .setNamespace("concurrent")
                                .setKey("key-$i")
                                .setValue("val-$i")
                                .build(),
                        )
                    }
                }
            jobs.awaitAll()

            val listRes =
                service.listSettings(
                    ListSettingsRequest
                        .newBuilder()
                        .setNamespacePrefix("concurrent")
                        .build(),
                )
            assertEquals(20, listRes.totalCount)

            // Verify clean disk re-load
            val freshService = SettingsServiceImpl(storageFile = settingsFile)
            val freshList =
                freshService.listSettings(
                    ListSettingsRequest
                        .newBuilder()
                        .setNamespacePrefix("concurrent")
                        .build(),
                )
            assertEquals(20, freshList.totalCount)
        }
}
