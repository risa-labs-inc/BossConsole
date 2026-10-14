package ai.rever.boss.plugin.browser

import ai.rever.boss.keymap.KeymapSettingsManager
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.presets.KeymapPresets
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserPrintingTest {
    @Test
    fun `print targets only the active browser in the originating window`() =
        runBlocking {
            val printed = mutableListOf<String>()
            val ids = listOf("print-active", "print-background", "print-other-window")
            try {
                ids.forEachIndexed { index, id ->
                    val handle =
                        Proxy.newProxyInstance(
                            BrowserHandle::class.java.classLoader,
                            arrayOf(BrowserHandle::class.java),
                        ) { _, method, args ->
                            when (method.name) {
                                "getId" -> {
                                    id
                                }

                                "isValid" -> {
                                    true
                                }

                                "executeJavaScript" -> {
                                    assertEquals(PRINT_BROWSER_SCRIPT, args!![0])
                                    printed.add(id)
                                    null
                                }

                                else -> {
                                    error("Unexpected call ${method.name}")
                                }
                            }
                        } as BrowserHandle
                    ActiveBrowserRegistry.register(handle, if (index == 2) "print-w2" else "print-w1", true, index != 1)
                }
                printActiveBrowser("print-w1")
                printActiveBrowser("missing-print-window")
                assertEquals(listOf("print-active"), printed)
            } finally {
                ids.forEach(ActiveBrowserRegistry::unregister)
            }
        }

    @Test
    fun `default print chord is browser scoped and disabled print is not intercepted`() {
        val defaults = KeymapPresets.getBOSSDefault()
        assertTrue(usesNativePrintChord(defaults))
        assertFalse(usesNativePrintChord(defaults.withBindingDisabled(KeymapActions.BROWSER_PRINT)))
        val print = defaults.getBinding(KeymapActions.BROWSER_PRINT)!!
        assertFalse(usesNativePrintChord(defaults.withBinding(print.copy(key = "F12"))))
    }

    @Test
    fun `VS Code keeps quick open and offers printing only through menu`() {
        val settings = KeymapPresets.getVSCodePreset()
        assertNull(settings.getBinding(KeymapActions.BROWSER_PRINT))
        assertEquals("P", settings.getBinding(KeymapActions.QUICK_SWITCHER_OPEN)?.key)
        assertFalse(usesNativePrintChord(settings))
    }

    @Test
    fun `migration preserves a customized conflicting chord`() {
        val old = KeymapPresets.getBOSSDefault().withoutBinding(KeymapActions.BROWSER_PRINT)
        val quickOpen = old.getBinding(KeymapActions.QUICK_SWITCHER_OPEN)!!
        val customized = old.withBinding(quickOpen.copy(key = "P", modifiers = listOf("Cmd")))
        assertNull(KeymapSettingsManager.migrateSettings(customized).getBinding(KeymapActions.BROWSER_PRINT))
    }
}
