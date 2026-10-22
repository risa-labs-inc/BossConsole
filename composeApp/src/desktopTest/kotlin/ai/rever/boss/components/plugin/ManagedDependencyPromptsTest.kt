package ai.rever.boss.components.plugin

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManagedDependencyPromptsTest {
    private val installer =
        object : MissingDependencyInstaller {
            override fun isInstalled(pluginId: String) = false

            override suspend fun displayNameFor(pluginId: String): String? = null

            override suspend fun install(pluginId: String) = Result.success(Unit)
        }

    private fun missing(id: String, optional: Boolean) = MissingPluginDependency("ai.example.max", "Max", id, optional)

    @Test
    fun `parses the plugin ids and ignores anything else`() {
        assertEquals(setOf("a.b", "c.d"), ManagedDependencyPrompts.parse("""{"neverOfferOptional":["a.b"," c.d ","",3,null]}"""))
        assertEquals(emptySet(), ManagedDependencyPrompts.parse("""{"neverOfferOptional":"a.b"}"""))
        assertEquals(emptySet(), ManagedDependencyPrompts.parse("[]"))
        assertEquals(emptySet(), ManagedDependencyPrompts.parse("not json"))
    }

    @Test
    fun `a missing or unreadable file suppresses nothing`() {
        val dir = Files.createTempDirectory("dep-prompts").toFile()
        assertEquals(emptySet(), ManagedDependencyPrompts.load(dir.resolve(ManagedDependencyPrompts.FILE_NAME)))
        val f = dir.resolve(ManagedDependencyPrompts.FILE_NAME).apply { writeText("""{"neverOfferOptional":["ai.rever.boss.plugin.dynamic.phimask"]}""") }
        assertEquals(setOf("ai.rever.boss.plugin.dynamic.phimask"), ManagedDependencyPrompts.load(f))
        dir.deleteRecursively()
    }

    @Test
    fun `a listed optional dependency is never offered, a required one still is, and an offer the user opens still shows`() {
        val bus = PluginDependencyBus { setOf("ai.rever.boss.plugin.dynamic.phimask") }
        assertTrue(bus.wasDeclined(missing("ai.rever.boss.plugin.dynamic.phimask", optional = true)))
        assertFalse(bus.wasDeclined(missing("ai.rever.boss.plugin.dynamic.phimask", optional = false)))
        assertFalse(bus.wasDeclined(missing("ai.rever.boss.plugin.dynamic.other", optional = true)))

        bus.report(MissingDependencyPrompt(missing("ai.rever.boss.plugin.dynamic.phimask", optional = true), installer, windowId = "a"))
        val asked = MissingDependencyPrompt(missing("ai.rever.boss.plugin.dynamic.phimask", optional = true), installer, windowId = "a", userInitiated = true)
        bus.report(asked)
        assertTrue(bus.claim(asked), "an offer the user opened is not suppressed")
    }
}
