package ai.rever.boss.components.windows

import ai.rever.boss.components.settings.sidebar.SettingsSection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [resolveSettingsDeepLink], and specifically that it has an "I cannot show this" answer.
 *
 * The bug it exists to prevent lives one layer below `SettingsWindowState`, which is why that
 * suite could pass while the window did the wrong thing: the holder correctly recorded a section
 * request, and the window then resolved an unknown string to a FLUCK default and navigated an open
 * window the user was reading. `SettingsProviderImpl.openSettings` forwards an arbitrary string
 * from any plugin, so "unknown string" is a normal input, not a malformed one.
 */
class SettingsDeepLinkTest {
    private val pages = setOf("jupyter-notebook", "ai-gateway")

    @Test
    fun `a built-in section name resolves to that section`() {
        assertEquals(
            SettingsDeepLink.Section(SettingsSection.KEYMAP),
            resolveSettingsDeepLink("KEYMAP", pages),
        )
    }

    @Test
    fun `section names are matched ignoring case`() {
        // The shortcut-help deep link passes "KEYMAP"; menu actions and plugins pass whatever they
        // like. Matching exactly would send a lowercase caller down the plugin-page branch and out
        // the Unresolved end.
        assertEquals(
            SettingsDeepLink.Section(SettingsSection.KEYMAP),
            resolveSettingsDeepLink("keymap", pages),
        )
    }

    @Test
    fun `a visible plugin page id resolves to that page`() {
        assertEquals(
            SettingsDeepLink.Page("ai-gateway"),
            resolveSettingsDeepLink("ai-gateway", pages),
        )
    }

    @Test
    fun `a plugin page that is not currently visible resolves to nothing`() {
        // The case that made this a bug rather than a rough edge: a plugin deep-linking to its own
        // page while it is disabled, RBAC-hidden or not yet registered. Answering FLUCK here means
        // a plugin can navigate an open settings window away from whatever the user was reading.
        assertEquals(
            SettingsDeepLink.Unresolved,
            resolveSettingsDeepLink("secret-manager", pages),
        )
    }

    @Test
    fun `an unrecognised string resolves to nothing rather than to a default`() {
        assertEquals(SettingsDeepLink.Unresolved, resolveSettingsDeepLink("not-a-thing", pages))
    }

    @Test
    fun `no section at all resolves to nothing`() {
        // A plain open() passes null. It must not be a navigation - that is the property the state
        // holder is careful about, and it would be undone here.
        assertEquals(SettingsDeepLink.Unresolved, resolveSettingsDeepLink(null, pages))
    }

    @Test
    fun `a built-in section wins over a plugin page claiming the same id`() {
        // Order matters and nothing else would notice it changing: a plugin registering a page id
        // that collides with a built-in section name must not be able to shadow that section.
        assertEquals(
            SettingsDeepLink.Section(SettingsSection.KEYMAP),
            resolveSettingsDeepLink("KEYMAP", pages + "KEYMAP"),
        )
    }
}
