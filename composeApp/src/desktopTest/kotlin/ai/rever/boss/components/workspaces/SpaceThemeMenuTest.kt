package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.ui.BossThemes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.Circle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `Options > Space Theme` submenu: what it ticks, what it offers and what it says when there
 * is no Space to say it about.
 *
 * A submenu row draws ONE trailing icon, so every rule here is about which of two marks that slot
 * carries.
 */
class SpaceThemeMenuTest {
    private val baseline = BossThemes.CLEAN.id

    private fun itemsFor(
        workspaceId: String,
        overrides: Map<String, String> = emptyMap(),
    ) = spaceThemeMenuItems(workspaceId, overrides, baseline, onChoose = {})

    @Test
    fun `every theme is offered`() {
        val items = itemsFor("workspace-1").filterNot { it.isDivider }

        assertTrue(BossThemes.all.all { theme -> items.any { it.text == theme.name } })
    }

    @Test
    fun `exactly one row is ticked, and it is the theme the Space is showing`() {
        val items = itemsFor(PredefinedWorkspaces.GEMINI_ID)

        val ticked = items.filter { it.trailingIcon == Icons.Default.Check }
        assertEquals(1, ticked.size, "two ticks in one menu reads as a bug")
        assertEquals(BossThemes.BLUEPRINT.name, ticked.single().text, "Gemini's baked theme is Blueprint")
    }

    @Test
    fun `an override moves the tick`() {
        val overrides = mapOf(PredefinedWorkspaces.GEMINI_ID to BossThemes.NVIDIA.id)
        val items = itemsFor(PredefinedWorkspaces.GEMINI_ID, overrides)

        assertEquals(BossThemes.NVIDIA.name, items.single { it.trailingIcon == Icons.Default.Check }.text)
    }

    @Test
    fun `the ticked row carries no swatch colour, and every other row carries its own`() {
        val items = itemsFor("workspace-1").filterNot { it.isDivider }

        items.forEach { item ->
            val theme = BossThemes.all.single { it.name == item.text }
            if (theme.id == baseline) {
                assertNull(item.trailingIconColor, "the theme you are wearing is the whole window")
            } else {
                assertEquals(theme.colors.signal, item.trailingIconColor)
            }
        }
    }

    @Test
    fun `a light theme wears a ring and a dark one a filled dot`() {
        // Blueprint and Blueprint Light have an IDENTICAL signal (#0F5BFF), so hue cannot separate
        // them and the glyph has to.
        val items = itemsFor("workspace-1").filterNot { it.isDivider }
        val blueprint = items.single { it.text == BossThemes.BLUEPRINT.name }
        val blueprintLight = items.single { it.text == BossThemes.BLUEPRINT_LIGHT.name }

        assertEquals(blueprint.trailingIconColor, blueprintLight.trailingIconColor, "the signal really is shared")
        assertEquals(Icons.Filled.Circle, blueprint.trailingIcon)
        assertEquals(Icons.Outlined.Circle, blueprintLight.trailingIcon)
    }

    @Test
    fun `the reset row appears only when there is something to reset`() {
        val untouched = itemsFor(PredefinedWorkspaces.GEMINI_ID)
        val overrides = mapOf(PredefinedWorkspaces.GEMINI_ID to BossThemes.NVIDIA.id)
        val overridden = itemsFor(PredefinedWorkspaces.GEMINI_ID, overrides)

        assertTrue(untouched.none { it.text == SPACE_THEME_RESET_TEXT })
        assertTrue(overridden.any { it.text == SPACE_THEME_RESET_TEXT })
    }

    @Test
    fun `a window with no Space has no theme menu at all`() {
        // Rather than a submenu whose every row writes an assignment against an id nothing can
        // resolve later.
        assertEquals(emptyList(), spaceThemeMenuItems("", emptyMap(), baseline, onChoose = {}))
    }
}
