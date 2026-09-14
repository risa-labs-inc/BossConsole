package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.plugin.PluginSectionAbsence
import ai.rever.boss.components.plugin.pluginSectionAbsence
import ai.rever.boss.components.plugin.pluginSectionMessage
import ai.rever.boss.components.plugin.pluginSectionOffersInstall
import ai.rever.boss.plugin.api.PluginState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Why a plugin-backed settings section has no panel, and what it may offer about it.
 *
 * The precedence tests are the ones that matter, because every one of them is a case where the
 * section would otherwise give advice that cannot work:
 *
 * - `MissingPluginOffer.isInstalled` counts a **disabled** or **rejected** plugin as installed -
 *   the jar is on disk - so asking `installed` first puts an Install button in front of a user
 *   whose problem a download cannot fix. That has already shipped here once, with the bookmarks
 *   shelf.
 * - `DynamicPluginManager` records a plugin **hidden for lack of access** as `DISABLED` too, so
 *   asking the state first tells a user to switch on a plugin that is not listed for them.
 */
class PluginSectionAbsenceTest {
    private fun absence(
        installed: Boolean? = true,
        state: PluginState? = PluginState.LOADED,
        isIncompatible: Boolean = false,
        missingPermissions: List<String> = emptyList(),
        servesNoPanel: Boolean = false,
    ) = pluginSectionAbsence(installed, state, isIncompatible, missingPermissions, servesNoPanel)

    @Test
    fun `a plugin that is not installed is the one case that gets an offer`() {
        assertEquals(PluginSectionAbsence.NOT_INSTALLED, absence(installed = false, state = null))
    }

    @Test
    fun `an installed plugin that has not registered yet is starting, not missing`() {
        // Registration is asynchronous, so this is the honest wait - and the only case where
        // "isn't loaded yet" was ever true.
        assertEquals(PluginSectionAbsence.STARTING, absence())
    }

    @Test
    fun `a disabled plugin is disabled, not starting and not missing`() {
        assertEquals(PluginSectionAbsence.DISABLED, absence(state = PluginState.DISABLED))
    }

    @Test
    fun `disabled is decided before installed, so no Install button appears for it`() {
        // Whatever `installed` says, a switched-off plugin must never reach NOT_INSTALLED,
        // because that is the only value that draws the button.
        assertEquals(
            PluginSectionAbsence.DISABLED,
            absence(installed = false, state = PluginState.DISABLED),
        )
        assertEquals(
            PluginSectionAbsence.DISABLED,
            absence(installed = null, state = PluginState.DISABLED),
        )
    }

    @Test
    fun `a rejected plugin needs an update, not an install and not a switch`() {
        // Binary-incompatible: the host records DISABLED and the installer deletes the jar it
        // rejected. Both other readings send the user somewhere that cannot help.
        assertEquals(
            PluginSectionAbsence.INCOMPATIBLE,
            absence(state = PluginState.DISABLED, isIncompatible = true),
        )
        assertEquals(
            PluginSectionAbsence.INCOMPATIBLE,
            absence(installed = false, state = null, isIncompatible = true),
        )
    }

    @Test
    fun `no access wins over every other reason`() {
        // An inaccessible plugin is recorded DISABLED as well, so this has to be asked first or
        // the user is told to enable something that is not listed for them. It also outranks
        // NOT_INSTALLED: installing it again would not grant the permission.
        assertEquals(
            PluginSectionAbsence.NO_ACCESS,
            absence(state = PluginState.DISABLED, missingPermissions = listOf("secret.read")),
        )
        assertEquals(
            PluginSectionAbsence.NO_ACCESS,
            absence(installed = false, state = null, missingPermissions = listOf("secret.read")),
        )
        assertEquals(
            PluginSectionAbsence.NO_ACCESS,
            absence(isIncompatible = true, missingPermissions = listOf("secret.read")),
        )
    }

    @Test
    fun `a loaded plugin whose version serves no panel is not still loading`() {
        // The API is present, so the plugin is loaded and accessible - it just has no panel for
        // this section. Reported as STARTING it says "isn't loaded yet" forever about something
        // that is loaded.
        assertEquals(PluginSectionAbsence.NO_PANEL, absence(servesNoPanel = true))
    }

    @Test
    fun `a load failure is not a wait either`() {
        assertEquals(PluginSectionAbsence.FAILED, absence(state = PluginState.ERROR))
    }

    @Test
    fun `cannot answer is not the same as no`() {
        // Null means no active manager or no injected installer factory - before startup
        // finishes, and in tests. Treating it as "not installed" would offer to install
        // something that may well be there, from a state where the install could not run.
        assertEquals(PluginSectionAbsence.UNKNOWN, absence(installed = null, state = null))
    }

    @Test
    fun `only a missing plugin is offered for install`() {
        // Asserted over every member rather than the one, so a member added later has to decide
        // this deliberately instead of inheriting it.
        PluginSectionAbsence.entries.forEach { absence ->
            assertEquals(
                absence == PluginSectionAbsence.NOT_INSTALLED,
                pluginSectionOffersInstall(absence),
                "offersInstall disagrees for $absence",
            )
        }
    }

    @Test
    fun `every absence has its own sentence`() {
        // The failure this guards is a member falling through to a message written for another
        // one, which is the whole defect the type exists to end.
        val messages =
            PluginSectionAbsence.entries.associateWith {
                pluginSectionMessage(it, "Editor settings", "Code Editor", listOf("editor.read"))
            }
        messages.forEach { (absence, message) ->
            assertTrue(
                message.startsWith("Editor settings are provided by the Code Editor plugin"),
                "$absence does not name the section and its plugin: $message",
            )
        }
        // STARTING and UNKNOWN deliberately share the wait; everything else is distinct.
        val distinct = messages.filterKeys { it != PluginSectionAbsence.UNKNOWN }.values.toSet()
        assertEquals(PluginSectionAbsence.entries.size - 1, distinct.size, "two absences read alike")
    }

    @Test
    fun `the no-access sentence names the permissions to ask for`() {
        val message =
            pluginSectionMessage(
                PluginSectionAbsence.NO_ACCESS,
                "Language-server settings",
                "Code Editor",
                listOf("editor.read", "editor.save"),
            )
        assertTrue(message.contains("editor.read, editor.save"), message)
        assertFalse(pluginSectionOffersInstall(PluginSectionAbsence.NO_ACCESS))
    }
}
