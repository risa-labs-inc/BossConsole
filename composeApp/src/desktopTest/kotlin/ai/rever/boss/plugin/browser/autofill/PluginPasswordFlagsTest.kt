package ai.rever.boss.plugin.browser.autofill

import ai.rever.boss.plugin.browser.BrowserSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** While native autofill is on, the plugin is told "off" for its own prompt, whatever the user's toggles say. */
class PluginPasswordFlagsTest {
    private val savedSuggest = BrowserSettings.suggestPasswords
    private val savedOffer = BrowserSettings.offerToSavePasswords
    private val savedFlag: String? = System.getProperty(BrowserSettings.NATIVE_AUTOFILL_PROP)

    @AfterTest
    fun restore() {
        if (savedFlag ==
            null
        ) {
            System.clearProperty(BrowserSettings.NATIVE_AUTOFILL_PROP)
        } else {
            System.setProperty(BrowserSettings.NATIVE_AUTOFILL_PROP, savedFlag)
        }
        BrowserSettings.suggestPasswords = savedSuggest
        BrowserSettings.offerToSavePasswords = savedOffer
    }

    @Test
    fun `plugin-side value is off whenever native autofill is on`() {
        assertFalse(BrowserSettings.pluginSideValue(true, "true"))
        assertFalse(BrowserSettings.pluginSideValue(false, "true"))
        assertTrue(BrowserSettings.pluginSideValue(true, "false"))
        assertTrue(BrowserSettings.pluginSideValue(true, null))
    }

    @Test
    fun `settings writes cannot turn the plugin prompt back on`() {
        System.setProperty(BrowserSettings.NATIVE_AUTOFILL_PROP, "true")
        BrowserSettings.republishPluginPasswordFlags()
        assertEquals("false", System.getProperty(BrowserSettings.OFFER_TO_SAVE_PASSWORDS_PROP))
        BrowserSettings.offerToSavePasswords = true
        BrowserSettings.suggestPasswords = true
        assertEquals("false", System.getProperty(BrowserSettings.OFFER_TO_SAVE_PASSWORDS_PROP))
        assertEquals("false", System.getProperty(BrowserSettings.SUGGEST_PASSWORDS_PROP))
        // The host still sees the user's choice and drives the native prompt with it.
        assertTrue(BrowserSettings.offerToSavePasswords)

        System.setProperty(BrowserSettings.NATIVE_AUTOFILL_PROP, "false")
        BrowserSettings.republishPluginPasswordFlags()
        assertEquals("true", System.getProperty(BrowserSettings.OFFER_TO_SAVE_PASSWORDS_PROP))
    }

    @Test
    fun `suggestion rows`() {
        val pw = NativeAutofill.rowOf("ada@x.com", "••••", null, null, "PASSWORDS", "example.com")
        assertEquals(SuggestionKind.PASSWORD, pw.kind)
        assertEquals("••••  ·  example.com", pw.secondary)
        val card = NativeAutofill.rowOf("Visa  ••4242, •, 12/30", null, null, null, "CREDIT_CARDS", "x")
        assertEquals(SuggestionKind.CARD, card.kind)
        assertEquals("Visa •••• 4242", card.primary)
        assertEquals("Expires 12/30", card.secondary)
        val withHolder = NativeAutofill.cardRow("Mastercard ••4444", "Test Holder")
        assertEquals("Mastercard •••• 4444", withHolder.primary)
        assertEquals("Test Holder", withHolder.secondary)
        assertEquals(SuggestionKind.ADDRESS, NativeAutofill.rowOf("Ada", null, null, "1 Main St", "ADDRESSES", "").kind)
    }
}
