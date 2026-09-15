package ai.rever.boss.startup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.window.PopupProperties
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import androidx.compose.foundation.layout.width as modifierWidth

/**
 * Regression coverage for the actual path used by Fluck's PageContextMenu and model pickers.
 *
 * These tests deliberately use Material [DropdownMenu], not `BossPopup` or a renderer stub. The
 * same Material position provider and popup properties continue to run after the host selects
 * Compose's native-window layer, so this pins the sizing, offset, edge and dismissal semantics that
 * a custom heavyweight renderer would otherwise have to reproduce.
 */
class MaterialDropdownMenuIntegrationTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `a real Material menu keeps intrinsic width and offset from a narrow anchor`() {
        setMenu(anchorAtWindowEdge = false, popupOffset = DpOffset(13.dp, 9.dp))

        val anchor: DpRect = rule.onNodeWithTag(ANCHOR_TAG).getUnclippedBoundsInRoot()
        val menuNode = rule.onNodeWithTag(MENU_TAG)
        menuNode.assertIsDisplayed()
        val menu: DpRect = menuNode.getUnclippedBoundsInRoot()

        assertTrue(menu.width > anchor.width, "the 180dp menu was constrained to its 40dp anchor")
        assertEquals(anchor.left + 13.dp, menu.left, "Material's horizontal offset was lost")
        assertEquals(anchor.bottom + 9.dp, menu.top, "Material's vertical offset was lost")
    }

    @Test
    fun `a real Material menu flips inside the bottom-right window edges`() {
        setMenu(anchorAtWindowEdge = true, popupOffset = DpOffset(8.dp, 6.dp))

        val host: DpRect = rule.onNodeWithTag(HOST_TAG).getUnclippedBoundsInRoot()
        val anchor: DpRect = rule.onNodeWithTag(ANCHOR_TAG).getUnclippedBoundsInRoot()
        val menuNode = rule.onNodeWithTag(MENU_TAG)
        menuNode.assertIsDisplayed()
        val menu: DpRect = menuNode.getUnclippedBoundsInRoot()

        assertTrue(menu.width > anchor.width, "edge handling must not collapse intrinsic menu width")
        assertTrue(menu.left < anchor.left, "the menu should flip left of a right-edge anchor")
        assertTrue(menu.top < anchor.top, "the menu should flip above a bottom-edge anchor")
        assertTrue(menu.left >= host.left && menu.right <= host.right, "menu escaped horizontal window bounds")
        assertTrue(menu.top >= host.top && menu.bottom <= host.bottom, "menu escaped vertical window bounds")
    }

    @Test
    fun `a real Material menu owns focus and enables Escape dismissal`() {
        setMenu(anchorAtWindowEdge = false, popupOffset = DpOffset.Zero)

        // A focusable popup owns a second scene/semantics root rather than borrowing the underlying
        // application's focus path. The test API cannot synthesize Compose Desktop's platform
        // navigation event, so read the exact default properties passed by this Material build too.
        rule.onAllNodes(isRoot()).assertCountEquals(2)
        val properties = materialDefaultMenuProperties()
        assertTrue(properties.focusable, "Material menu popup must own keyboard focus")
        assertTrue(properties.dismissOnBackPress, "Escape/back dismissal was disabled")
        assertTrue(properties.dismissOnClickOutside, "outside-click dismissal was disabled")
    }

    @Test
    fun `a Material menu dismisses on an outside click`() {
        setMenu(anchorAtWindowEdge = false, popupOffset = DpOffset.Zero)
        val host: DpRect = rule.onNodeWithTag(HOST_TAG).getUnclippedBoundsInRoot()

        rule.onNodeWithTag(HOST_TAG).performMouseInput {
            click(Offset(host.width.value - 4f, 4f))
        }
        rule.waitForIdle()

        rule.onNodeWithTag(MENU_TAG).assertDoesNotExist()
        assertEquals(1, dismissCount)
    }

    private lateinit var expanded: MutableState<Boolean>
    private var dismissCount = 0

    private fun setMenu(
        anchorAtWindowEdge: Boolean,
        popupOffset: DpOffset,
    ) {
        expanded = mutableStateOf(true)
        dismissCount = 0
        rule.setContent {
            BoxWithConstraints(Modifier.fillMaxSize().testTag(HOST_TAG)) {
                val anchorX = if (anchorAtWindowEdge) maxWidth - ANCHOR_WIDTH else 96.dp
                val anchorY = if (anchorAtWindowEdge) maxHeight - ANCHOR_HEIGHT else 80.dp
                Box(
                    Modifier
                        .offset(anchorX, anchorY)
                        .size(ANCHOR_WIDTH, ANCHOR_HEIGHT)
                        .testTag(ANCHOR_TAG),
                ) {
                    DropdownMenu(
                        expanded = expanded.value,
                        onDismissRequest = {
                            dismissCount++
                            expanded.value = false
                        },
                        offset = popupOffset,
                        modifier = Modifier.modifierWidth(180.dp).testTag(MENU_TAG),
                    ) {
                        DropdownMenuItem(onClick = {}) { Text("First model") }
                        DropdownMenuItem(onClick = {}) { Text("Second model") }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("First model").assertIsDisplayed()
    }

    private fun materialDefaultMenuProperties(): PopupProperties {
        // The getter is public in the pinned JVM bytecode but internal in Kotlin metadata. Reflection
        // keeps the test attached to Material's real default instead of duplicating it in a fixture.
        val menuClass = Class.forName("androidx.compose.material.Menu_skikoKt")
        return menuClass.getMethod("getDefaultMenuProperties").invoke(null) as PopupProperties
    }

    private companion object {
        const val HOST_TAG = "material-menu-host"
        const val ANCHOR_TAG = "material-menu-anchor"
        const val MENU_TAG = "material-menu"
        val ANCHOR_WIDTH = 40.dp
        val ANCHOR_HEIGHT = 24.dp
    }
}
