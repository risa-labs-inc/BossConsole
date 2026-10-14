package ai.rever.boss.service.settings

import ai.rever.boss.ipc.proto.services.GetSettingRequest
import ai.rever.boss.ipc.proto.services.ListSettingsRequest
import ai.rever.boss.ipc.proto.services.SetSettingRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsServiceImplTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `initialization loads existing settings from disk`() =
        runBlocking {
            val root = temporary.newFolder("settings-dir")
            val settingsFile = File(root, "settings.json")
            settingsFile.writeText(
                """
                [
                    {
                        "key": "theme",
                        "value": "dark",
                        "namespace": "ui",
                        "updatedAt": 12345
                    }
                ]
                """.trimIndent(),
            )

            val service = SettingsServiceImpl(settingsFile)
            val setting =
                service.getSetting(
                    GetSettingRequest
                        .newBuilder()
                        .setKey("theme")
                        .setNamespace("ui")
                        .build(),
                )

            assertTrue(setting.found)
            assertEquals("theme", setting.key)
            assertEquals("dark", setting.value)
            assertEquals("ui", setting.namespace)
            assertEquals(12345L, setting.updatedAt)
        }

    @Test
    fun `getSetting returns default value when key does not exist`() =
        runBlocking {
            val root = temporary.newFolder("settings-dir")
            val settingsFile = File(root, "settings.json")
            val service = SettingsServiceImpl(settingsFile)

            val setting =
                service.getSetting(
                    GetSettingRequest
                        .newBuilder()
                        .setKey("nonexistent")
                        .setDefaultValue("fallback")
                        .build(),
                )

            assertFalse(setting.found)
            assertEquals("nonexistent", setting.key)
            assertEquals("fallback", setting.value)
        }

    @Test
    fun `setSetting persists atomically and reload retains value`() =
        runBlocking {
            val root = temporary.newFolder("settings-dir")
            val settingsFile = File(root, "settings.json")
            val service = SettingsServiceImpl(settingsFile)

            val setResponse =
                service.setSetting(
                    SetSettingRequest
                        .newBuilder()
                        .setKey("font_size")
                        .setValue("14")
                        .setNamespace("editor")
                        .build(),
                )

            assertTrue(setResponse.found)
            assertEquals("font_size", setResponse.key)
            assertEquals("14", setResponse.value)
            assertEquals("editor", setResponse.namespace)

            assertTrue(settingsFile.exists())
            assertTrue(settingsFile.readText().contains("font_size"))

            // Verify a fresh service instance reloads the persisted value
            val reloadedService = SettingsServiceImpl(settingsFile)
            val reloaded =
                reloadedService.getSetting(
                    GetSettingRequest
                        .newBuilder()
                        .setKey("font_size")
                        .setNamespace("editor")
                        .build(),
                )

            assertTrue(reloaded.found)
            assertEquals("14", reloaded.value)
        }

    @Test
    fun `watchSetting streams updates for specific key and namespace`() =
        runBlocking {
            val root = temporary.newFolder("settings-dir")
            val settingsFile = File(root, "settings.json")
            val service = SettingsServiceImpl(settingsFile)

            val watchDeferred =
                async {
                    service
                        .watchSetting(
                            GetSettingRequest
                                .newBuilder()
                                .setKey("zoom")
                                .setNamespace("browser")
                                .build(),
                        ).first { it.value == "125%" }
                }

            service.setSetting(
                SetSettingRequest
                    .newBuilder()
                    .setKey("zoom")
                    .setValue("125%")
                    .setNamespace("browser")
                    .build(),
            )

            val observed = watchDeferred.await()
            assertEquals("125%", observed.value)
            assertEquals("zoom", observed.key)
            assertEquals("browser", observed.namespace)
        }

    @Test
    fun `listSettings filters by namespace prefix and supports pagination`() =
        runBlocking {
            val root = temporary.newFolder("settings-dir")
            val settingsFile = File(root, "settings.json")
            val service = SettingsServiceImpl(settingsFile)

            service.setSetting(
                SetSettingRequest
                    .newBuilder()
                    .setKey("k1")
                    .setValue("v1")
                    .setNamespace("boss.term")
                    .build(),
            )
            service.setSetting(
                SetSettingRequest
                    .newBuilder()
                    .setKey("k2")
                    .setValue("v2")
                    .setNamespace("boss.editor")
                    .build(),
            )
            service.setSetting(
                SetSettingRequest
                    .newBuilder()
                    .setKey("k3")
                    .setValue("v3")
                    .setNamespace("plugin.fluck")
                    .build(),
            )

            val bossList =
                service.listSettings(
                    ListSettingsRequest
                        .newBuilder()
                        .setNamespacePrefix("boss.")
                        .build(),
                )

            assertEquals(2, bossList.totalCount)
            assertEquals(2, bossList.settingsCount)

            val paged =
                service.listSettings(
                    ListSettingsRequest
                        .newBuilder()
                        .setNamespacePrefix("boss.")
                        .setLimit(1)
                        .setOffset(0)
                        .build(),
                )

            assertEquals(2, paged.totalCount)
            assertEquals(1, paged.settingsCount)
        }

    @Test
    fun `concurrent setSetting calls serialize and preserve all keys on disk`() =
        runBlocking {
            val root = temporary.newFolder("settings-dir")
            val settingsFile = File(root, "settings.json")
            val service = SettingsServiceImpl(settingsFile)

            val total = 40
            val jobs =
                (0 until total).map { i ->
                    async {
                        service.setSetting(
                            SetSettingRequest
                                .newBuilder()
                                .setKey("key_$i")
                                .setValue("val_$i")
                                .setNamespace("test")
                                .build(),
                        )
                    }
                }
            jobs.forEach { it.await() }

            // Recreate service to ensure full persistence on disk without corruption
            val reloaded = SettingsServiceImpl(settingsFile)
            val list =
                reloaded.listSettings(
                    ListSettingsRequest
                        .newBuilder()
                        .setNamespacePrefix("test")
                        .build(),
                )

            assertEquals(total, list.totalCount)
            for (i in 0 until total) {
                val s =
                    reloaded.getSetting(
                        GetSettingRequest
                            .newBuilder()
                            .setKey("key_$i")
                            .setNamespace("test")
                            .build(),
                    )
                assertTrue(s.found, "Expected key_$i to be found")
                assertEquals("val_$i", s.value)
            }
        }

    @Test
    fun `saveToDisk pins owner-only permissions on posix filesystems`() =
        runBlocking {
            val root = temporary.newFolder("settings-dir")
            val settingsFile = File(root, "settings.json")
            val service = SettingsServiceImpl(settingsFile)

            service.setSetting(
                SetSettingRequest
                    .newBuilder()
                    .setKey("secret")
                    .setValue("value")
                    .build(),
            )

            val path = settingsFile.toPath()
            val hasPosix = path.fileSystem.supportedFileAttributeViews().contains("posix")
            if (hasPosix) {
                val perms = Files.getPosixFilePermissions(path)
                val ownerOnly =
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                    )
                val extraPerms = perms - ownerOnly
                assertTrue(extraPerms.isEmpty(), "Expected owner-only permissions, found: $perms")
            }
        }
}
