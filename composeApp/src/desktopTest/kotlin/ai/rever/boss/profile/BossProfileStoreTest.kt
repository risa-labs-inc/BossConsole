package ai.rever.boss.profile

import ai.rever.boss.plugin.PluginPersistence
import ai.rever.boss.plugin.pathutils.BossDirectories
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs against composeApp's hermetic test home (see AGENTS.md), so `BossDirectories.baseDir`
 * is a directory this task owns, never a developer's `~/.boss`. The guard below refuses to run
 * at all if that ever stops being true, since the cleanup deletes directories under it.
 */
class BossProfileStoreTest {
    private val mainPlugins = File(BossDirectories.baseDir, "plugins")
    private val mainPluginData = File(BossDirectories.baseDir, "plugin-data")

    @BeforeEach
    fun cleanBefore() {
        val home = File(System.getProperty("user.home")).canonicalPath
        check("test-home" in home) { "Refusing to run outside the hermetic test home: $home" }
        cleanUp()
    }

    @AfterEach
    fun cleanUp() {
        BossDirectories.profilesDir().deleteRecursively()
        mainPlugins.deleteRecursively()
        mainPluginData.deleteRecursively()
    }

    private fun create(
        name: String,
        auth: ProfileAuthMode = ProfileAuthMode.SEPARATE,
        plugins: ProfilePluginSeed = ProfilePluginSeed.EMPTY,
        id: String? = null,
    ) = BossProfileStore.create(name, auth, plugins, id)

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
    fun `ids are normalised to lowercase, as BOSS_PROFILE resolution makes them`() {
        val profile = create("Upper", id = "Team-A").getOrThrow()
        assertEquals("team-a", profile.id)
        assertEquals(profile, BossProfileStore.get("TEAM-A"))
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
        writeInstalled(
            mainPlugins,
            "a" to File(mainPlugins, "a_1.0.jar").absolutePath,
            "elsewhere" to File(BossDirectories.baseDir, "sideloaded/x.jar").absolutePath,
        )
        File(mainPluginData, "a").apply { mkdirs() }.resolve("storage.properties").writeText("secret=1")

        val profile = create("Copy", plugins = ProfilePluginSeed.COPY_MAIN).getOrThrow()
        val profilePlugins = File(profile.root, "plugins")

        assertEquals("jar-a", File(profilePlugins, "a_1.0.jar").readText())
        assertTrue(File(profilePlugins, "a_1.0.jar.sig").exists())
        assertFalse(File(profilePlugins, "b-downloading.jar").exists(), "partial downloads are not copied")
        assertFalse(File(profile.root, "plugin-data").exists(), "plugin data stays with the main profile")
        assertEquals("true", File(profile.root, "pending_wizard_completed").readText())

        val entries = readInstalled(profilePlugins)
        val a = entries.getValue("a")
        assertEquals(File(profilePlugins, "a_1.0.jar").absolutePath, a["jarPath"]!!.jsonPrimitive.content)
        assertEquals("false", a["enabled"]!!.jsonPrimitive.content, "the enabled flag is carried over")
        assertEquals("kept", a["futureField"]!!.jsonPrimitive.content, "unmodelled fields survive")
        assertEquals(
            File(BossDirectories.baseDir, "sideloaded/x.jar").absolutePath,
            entries.getValue("elsewhere")["jarPath"]!!.jsonPrimitive.content,
            "a jar outside plugins/ has no copy to point at, so it is left alone",
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
    fun `window titles name the profile a window belongs to`() {
        val profile = create("Research").getOrThrow()
        assertEquals("BOSS - Research", BossProfileStore.windowTitle("BOSS", profile.id))
        assertEquals("BOSS - gone", BossProfileStore.windowTitle("BOSS", "gone"), "an unknown id falls back to the id")
        if (!BossDirectories.isProfile) assertEquals("BOSS", BossProfileStore.windowTitle("BOSS"))
    }

    private fun writeInstalled(
        dir: File,
        vararg plugins: Pair<String, String>,
    ) {
        val content =
            buildJsonObject {
                putJsonArray("plugins") {
                    plugins.forEach { (id, jarPath) ->
                        addJsonObject {
                            put("pluginId", id)
                            put("jarPath", jarPath)
                            put("enabled", false)
                            put("futureField", "kept")
                        }
                    }
                }
            }
        File(dir, PluginPersistence.CONFIG_FILE_NAME).writeText(content.toString())
    }

    private fun readInstalled(dir: File): Map<String, JsonObject> =
        Json
            .parseToJsonElement(File(dir, PluginPersistence.CONFIG_FILE_NAME).readText())
            .jsonObject["plugins"]!!
            .jsonArray
            .map { it.jsonObject }
            .associateBy { it["pluginId"]!!.jsonPrimitive.content }

    @Test
    fun `an explicit id naming a stray directory is refused, never adopted`() {
        val stray = BossDirectories.profileRoot("stray").apply { mkdirs() }
        File(stray, "leftover").writeText("someone's data")
        assertTrue(create("Stray", id = "stray").isFailure)
        assertTrue(File(stray, "leftover").isFile)
        assertNull(BossProfileStore.get("stray"))
    }

    @Test
    fun `a reservation left by a creation that died is reclaimed once stale, and only then`() {
        val root = BossDirectories.profileRoot("half").apply { mkdirs() }
        val reservation = File(root, ".creating").apply { writeText("") }
        assertTrue(create("Half", id = "half").isFailure, "a fresh reservation may still be seeding")
        reservation.setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000L)
        val profile = create("Half", id = "half").getOrThrow()
        assertEquals(profile, BossProfileStore.get("half"))
        assertFalse(File(root, ".creating").exists())
    }
}
