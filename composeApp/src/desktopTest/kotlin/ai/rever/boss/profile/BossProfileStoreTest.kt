package ai.rever.boss.profile

import ai.rever.boss.plugin.PluginPersistence
import ai.rever.boss.plugin.pathutils.BossDirectories
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs against composeApp's hermetic test home (see AGENTS.md), so `BossDirectories.baseDir`
 * is a directory this task owns, never a developer's `~/.boss`.
 */
class BossProfileStoreTest {
    private val mainPlugins = File(BossDirectories.baseDir, "plugins")

    private fun create(
        name: String,
        auth: ProfileAuthMode = ProfileAuthMode.SEPARATE,
        plugins: ProfilePluginSeed = ProfilePluginSeed.EMPTY,
        id: String? = null,
    ) = BossProfileStore.create(name, auth, plugins, id)

    @AfterEach
    fun cleanUp() {
        BossDirectories.profilesDir().deleteRecursively()
        mainPlugins.deleteRecursively()
    }

    @Test
    fun `a created profile is listed, found by id, and rooted under the profiles directory`() {
        val profile = create("Work Account", ProfileAuthMode.SHARED).getOrThrow()

        assertTrue(profile.id.startsWith("work-account-"), profile.id)
        assertTrue(BossDirectories.isValidProfileId(profile.id))
        assertEquals(File(BossDirectories.profilesDir(), profile.id), profile.root)
        assertEquals(profile, BossProfileStore.get(profile.id))
        assertEquals(listOf(profile), BossProfileStore.list())
    }

    @Test
    fun `an explicit id that is taken is refused rather than reused`() {
        create("One", id = "team").getOrThrow()
        assertTrue(create("Two", id = "team").isFailure)
        assertEquals("One", BossProfileStore.get("team")?.name)
    }

    @Test
    fun `invalid ids and blank names are refused`() {
        assertTrue(create("x", id = "../up").isFailure)
        assertTrue(create("   ").isFailure)
        assertNull(BossProfileStore.get("../up"))
    }

    @Test
    fun `binding a Space is idempotent and findable`() {
        val profile = create("P").getOrThrow()
        BossProfileStore.bindWorkspace(profile.id, "workspace-1").getOrThrow()
        BossProfileStore.bindWorkspace(profile.id, "workspace-1").getOrThrow()

        assertEquals(listOf("workspace-1"), BossProfileStore.get(profile.id)?.workspaceIds)
        assertEquals(profile.id, BossProfileStore.profileForWorkspace("workspace-1")?.id)
        assertNull(BossProfileStore.profileForWorkspace("workspace-2"))
    }

    @Test
    fun `copying the main plugins brings jars and enabled flags but no plugin data`() {
        mainPlugins.mkdirs()
        File(mainPlugins, "a_1.0.jar").writeText("jar-a")
        File(mainPlugins, "a_1.0.jar.sig").writeText("sig-a")
        File(mainPlugins, "b-downloading.jar").writeText("partial")
        val mainJar = File(mainPlugins, "a_1.0.jar").absolutePath
        File(mainPlugins, PluginPersistence.CONFIG_FILE_NAME).writeText(
            """{"plugins":[{"pluginId":"a","jarPath":"$mainJar","enabled":false},""" +
                """{"pluginId":"elsewhere","jarPath":"/opt/other/x.jar"}]}""",
        )
        File(BossDirectories.baseDir, "plugin-data/a")
            .apply { mkdirs() }
            .resolve("storage.properties")
            .writeText("secret=1")

        val profile = create("Copy", plugins = ProfilePluginSeed.COPY_MAIN).getOrThrow()
        val profilePlugins = File(profile.root, "plugins")

        assertEquals("jar-a", File(profilePlugins, "a_1.0.jar").readText())
        assertTrue(File(profilePlugins, "a_1.0.jar.sig").exists())
        assertFalse(File(profilePlugins, "b-downloading.jar").exists(), "partial downloads are not copied")
        assertFalse(File(profile.root, "plugin-data").exists(), "plugin data stays with the main profile")
        assertEquals("true", File(profile.root, "pending_wizard_completed").readText())

        val entries =
            Json
                .parseToJsonElement(File(profilePlugins, PluginPersistence.CONFIG_FILE_NAME).readText())
                .jsonObject["plugins"]!!
                .jsonArray
                .map { it.jsonObject }
        val a = entries.first { it["pluginId"]!!.jsonPrimitive.content == "a" }
        assertEquals(File(profilePlugins, "a_1.0.jar").absolutePath, a["jarPath"]!!.jsonPrimitive.content)
        assertEquals("false", a["enabled"]!!.jsonPrimitive.content, "the enabled flag is carried over")
        val other = entries.first { it["pluginId"]!!.jsonPrimitive.content == "elsewhere" }
        assertEquals(
            "/opt/other/x.jar",
            other["jarPath"]!!.jsonPrimitive.content,
            "paths outside plugins/ are left alone",
        )

        // The copy is a copy: changing it never reaches the main profile's jar.
        File(profilePlugins, "a_1.0.jar").writeText("changed")
        assertEquals("jar-a", File(mainPlugins, "a_1.0.jar").readText())
    }

    @Test
    fun `a shared-account profile gets no plugins of its own, since its windows use the main ones`() {
        mainPlugins.mkdirs()
        File(mainPlugins, "a_1.0.jar").writeText("jar-a")
        val profile =
            create("Shared", auth = ProfileAuthMode.SHARED, plugins = ProfilePluginSeed.COPY_MAIN).getOrThrow()
        assertFalse(File(profile.root, "plugins").exists())
        assertFalse(File(profile.root, "pending_wizard_completed").exists())
        assertEquals(ProfileAuthMode.SHARED, BossProfileStore.get(profile.id)?.auth)
    }

    @Test
    fun `an empty seed starts with no plugins and the wizard still to run`() {
        mainPlugins.mkdirs()
        File(mainPlugins, "a_1.0.jar").writeText("jar-a")
        val profile = create("Empty").getOrThrow()
        assertFalse(File(profile.root, "plugins/a_1.0.jar").exists())
        assertFalse(File(profile.root, "pending_wizard_completed").exists())
    }

    @Test
    fun `the main profile's window title is unchanged`() {
        assertNotNull(BossProfileStore.windowTitle("BOSS"))
        if (!BossDirectories.isProfile) assertEquals("BOSS", BossProfileStore.windowTitle("BOSS"))
    }
}
