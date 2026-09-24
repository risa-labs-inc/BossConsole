package ai.rever.boss.window

import ai.rever.boss.components.plugin.registries.PluginShortcutRegistryImpl
import ai.rever.boss.keymap.KeymapSettingsManager
import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeyStroke
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.TabSwitchMode
import ai.rever.boss.plugin.api.KeyChordSpec
import ai.rever.boss.plugin.api.PluginShortcutSpec
import ai.rever.boss.plugin.api.ShortcutActionProvider
import ai.rever.boss.utils.SystemUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.awt.Canvas
import java.awt.KeyboardFocusManager
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.beans.PropertyChangeEvent
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Press/release semantics of [AWTKeyboardInterceptor], driven through [AWTKeyboardInterceptor.processKeyEvent].
 *
 * BossConsole#1568 moved the action back to KEY_PRESSED: firing on the primary key's release and
 * cancelling when a modifier came up first silently dropped fast taps, where Cmd lifts a few ms
 * before the letter. What BossConsole#490 asked for still holds: a held chord fires once however
 * many auto-repeat presses arrive, and no half of a shortcut keystroke reaches the focused
 * component. The state table these tests pin:
 *
 * | State                     | Event                        | Fires          | Consumed |
 * |---------------------------|------------------------------|----------------|----------|
 * | idle                      | chord press                  | once, now      | yes      |
 * | chord held                | auto-repeat press            | no             | yes      |
 * | chord held                | primary release              | no             | yes      |
 * | chord held                | modifier release             | no (MRU commit)| no       |
 * | modifier released first   | auto-repeat press (bare)     | no             | yes      |
 * | modifier released first   | primary release              | no             | yes      |
 * | primary released first    | modifier release             | no (MRU commit)| no       |
 * | chord held, focus lost    | primary release              | no             | no       |
 * | print armed (see below)   | first primary/modifier release | once         | primary  |
 *
 * Browser print is the one chord that still fires on release; see
 * `AWTKeyboardInterceptor.RELEASE_FIRED_ACTIONS`.
 */
class ShortcutKeySemanticsTest {
    private lateinit var previousSettings: KeymapSettings
    private lateinit var settingsState: MutableStateFlow<KeymapSettings>
    private lateinit var canvas: Canvas
    private val windowId = "test-window-key-semantics"
    private lateinit var testScope: CoroutineScope
    private val newTabEventCount = AtomicInteger(0)
    private val printEventCount = AtomicInteger(0)
    private val collectorJobs = mutableListOf<Job>()

    @Suppress("DEPRECATION")
    private val primaryModifierMask =
        if (SystemUtils.isMacOS) {
            (InputEvent.META_DOWN_MASK or InputEvent.META_MASK)
        } else {
            (InputEvent.CTRL_DOWN_MASK or InputEvent.CTRL_MASK)
        }
    private val modifierKeyCode =
        if (SystemUtils.isMacOS) KeyEvent.VK_META else KeyEvent.VK_CONTROL

    @BeforeTest
    fun setUp() {
        testScope = CoroutineScope(Dispatchers.Unconfined)
        newTabEventCount.set(0)
        printEventCount.set(0)
        collectorJobs +=
            testScope.launch {
                MenuActionsHandler.newTabEvents.collect { if (it == windowId) newTabEventCount.incrementAndGet() }
            }
        collectorJobs +=
            testScope.launch {
                MenuActionsHandler.printBrowserEvents.collect { if (it == windowId) printEventCount.incrementAndGet() }
            }

        AWTKeyboardInterceptor.install()
        canvas = Canvas()
        AWTKeyboardInterceptor.cancelPendingShortcut()
        val field = KeymapSettingsManager::class.java.getDeclaredField("_currentSettings")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = field.get(KeymapSettingsManager) as MutableStateFlow<KeymapSettings>
        settingsState = state
        previousSettings = state.value
        useBindings(KeyBinding(actionId = KeymapActions.TAB_NEW, key = "T", modifiers = listOf("Cmd")))
    }

    @AfterTest
    fun tearDown() {
        AWTKeyboardInterceptor.cancelPendingShortcut()
        AWTKeyboardInterceptor.uninstall()
        settingsState.value = previousSettings
        collectorJobs.forEach { it.cancel() }
        collectorJobs.clear()
        testScope.cancel()
    }

    private fun useBindings(vararg bindings: KeyBinding) {
        settingsState.value = KeymapSettings.fromBindings(bindings.toList())
    }

    private fun dispatchKeyEvent(event: KeyEvent): Boolean = AWTKeyboardInterceptor.processKeyEvent(event, windowId)

    private fun key(
        id: Int,
        keyCode: Int = KeyEvent.VK_T,
        modifiers: Int = primaryModifierMask,
    ) = KeyEvent(canvas, id, 0, modifiers, keyCode, keyCode.toChar())

    private fun collectTabSwitches(into: MutableList<MenuActionsHandler.TabSwitchAction>): Job =
        testScope.launch {
            MenuActionsHandler.tabSwitchEvents.collect { if (it.first == windowId) into.add(it.second) }
        }

    private fun modifierRelease(keyCode: Int = modifierKeyCode) =
        KeyEvent(canvas, KeyEvent.KEY_RELEASED, 0, 0, keyCode, KeyEvent.CHAR_UNDEFINED)

    @Test
    fun `a matching press fires the action at once and its release is consumed without firing again`() {
        val press = key(KeyEvent.KEY_PRESSED)
        assertTrue(dispatchKeyEvent(press), "a matched press must be consumed")
        assertTrue(press.isConsumed)
        assertEquals(1, newTabEventCount.get(), "the action runs on the press, not on the release")

        val release = key(KeyEvent.KEY_RELEASED)
        assertTrue(dispatchKeyEvent(release), "the primary release must not leak to the focused component")
        assertTrue(release.isConsumed)
        assertEquals(1, newTabEventCount.get(), "the release must not fire a second time")
        assertTrue(AWTKeyboardInterceptor.pendingShortcuts.isEmpty())
        assertTrue(AWTKeyboardInterceptor.claimedKeys.isEmpty())
    }

    @Test
    fun `a fast tap that releases the modifier before the key still fires once - BossConsole#1568`() {
        assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_PRESSED)))
        assertFalse(dispatchKeyEvent(modifierRelease()), "a modifier release is never consumed")
        val release = key(KeyEvent.KEY_RELEASED, modifiers = 0)
        assertTrue(dispatchKeyEvent(release), "the primary release still belongs to the chord")
        assertTrue(release.isConsumed)
        assertEquals(1, newTabEventCount.get())
    }

    @Test
    fun `releasing Shift first on a two-modifier chord still fires once`() {
        useBindings(KeyBinding(actionId = KeymapActions.TAB_NEW, key = "T", modifiers = listOf("Cmd", "Shift")))
        val chord = primaryModifierMask or InputEvent.SHIFT_DOWN_MASK
        assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_PRESSED, modifiers = chord)))
        assertFalse(dispatchKeyEvent(modifierRelease(KeyEvent.VK_SHIFT)))
        assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_RELEASED)))
        assertEquals(1, newTabEventCount.get())
    }

    @Test
    fun `a held chord fires once however many auto-repeat presses arrive`() {
        repeat(5) {
            assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_PRESSED)), "every repeat press must stay consumed")
        }
        assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_RELEASED)))
        assertEquals(1, newTabEventCount.get(), "holding the chord must fire it exactly once (BossConsole#490)")
    }

    @Test
    fun `repeats of a key still held after its modifier came up stay consumed and fire nothing`() {
        assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_PRESSED)))
        assertFalse(dispatchKeyEvent(modifierRelease()))
        repeat(3) {
            assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_PRESSED, modifiers = 0)), "no bare 't' may leak")
        }
        assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_RELEASED, modifiers = 0)))
        assertEquals(1, newTabEventCount.get())
    }

    @Test
    fun `focus loss clears held state so a later release is not claimed`() {
        assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_PRESSED)))
        assertEquals(1, newTabEventCount.get())

        // Exercise the listener registered by install without creating or focusing a window.
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val listenerField = AWTKeyboardInterceptor::class.java.getDeclaredField("focusListener")
        listenerField.isAccessible = true
        val listener = listenerField.get(AWTKeyboardInterceptor) as java.beans.PropertyChangeListener
        assertTrue(manager.getPropertyChangeListeners("focusedWindow").contains(listener))
        listener.propertyChange(PropertyChangeEvent(manager, "focusedWindow", null, null))
        assertTrue(AWTKeyboardInterceptor.pendingShortcuts.isEmpty())

        assertFalse(dispatchKeyEvent(key(KeyEvent.KEY_RELEASED)))
        assertEquals(1, newTabEventCount.get())
    }

    @Test
    fun `MRU steps on each Tab press and commits on the modifier release, keeping the last step`() {
        useBindings(KeyBinding(actionId = KeymapActions.TAB_NEXT, key = "Tab", modifiers = listOf("Cmd")))
        settingsState.value = settingsState.value.copy(tabSwitchMode = TabSwitchMode.MRU)
        val events = mutableListOf<MenuActionsHandler.TabSwitchAction>()
        val job = collectTabSwitches(events)
        try {
            assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_TAB)))
            assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_RELEASED, KeyEvent.VK_TAB)))
            assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_TAB)))
            assertEquals(listOf(NEXT, NEXT), events, "each Tab press steps at once; the cycle is still open")
            // Ctrl up before the second Tab is released: the step it made is kept, then committed.
            assertFalse(dispatchKeyEvent(modifierRelease()))
            assertEquals(listOf(NEXT, NEXT, COMMIT), events)
            // A Tab still held after the commit neither steps nor reopens the switcher.
            assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_TAB, modifiers = 0)))
            assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_RELEASED, KeyEvent.VK_TAB, modifiers = 0)))
            assertFalse(dispatchKeyEvent(modifierRelease()), "no cycle is open any more")
            assertEquals(listOf(NEXT, NEXT, COMMIT), events)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `an unavailable host binding leaves both events unclaimed`() {
        useBindings(KeyBinding(actionId = KeymapActions.QUICK_SWITCHER_OPEN, key = "T", modifiers = listOf("Cmd")))
        for (id in listOf(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED)) {
            val event = key(id)
            assertFalse(dispatchKeyEvent(event))
            assertFalse(event.isConsumed)
        }
        assertEquals(0, newTabEventCount.get())
    }

    @Test
    fun `overlapping chords each fire once, on their own press`() {
        useBindings(
            KeyBinding(
                actionId = KeymapActions.TAB_NEW,
                key = "T",
                modifiers = listOf("Cmd"),
                alternateKeystrokes = listOf(KeyStroke("N", listOf("Cmd"))),
            ),
        )
        for ((index, keyCode) in listOf(KeyEvent.VK_N, KeyEvent.VK_T).withIndex()) {
            assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_PRESSED, keyCode)))
            assertEquals(index + 1, newTabEventCount.get())
        }
        for (keyCode in listOf(KeyEvent.VK_N, KeyEvent.VK_T)) {
            assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_RELEASED, keyCode)))
        }
        assertEquals(2, newTabEventCount.get())
    }

    @Test
    fun `plugin defaults and rebinds invoke once on press and unregistering closes the gate`() {
        val action = "plugin.shortcut-review.run"
        var calls = 0
        val provider = shortcutProvider(action) { calls++ }
        PluginShortcutRegistryImpl.register(provider)
        try {
            for ((round, rebound) in listOf(false, true).withIndex()) {
                // The rebind uses J, not the default K, so this round can only pass via the keymap.
                val rebind = KeyBinding(actionId = action, key = "J", modifiers = listOf("Cmd"))
                settingsState.value = KeymapSettings.fromBindings(listOfNotNull(rebind.takeIf { rebound }))
                val keyCode = if (rebound) KeyEvent.VK_J else KeyEvent.VK_K
                repeat(3) { assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_PRESSED, keyCode))) }
                assertEquals(round + 1, calls, "fires on the first press only")
                assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_RELEASED, keyCode)))
            }
            assertFalse(dispatchKeyEvent(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_K)), "a rebind retires the default")
            assertFalse(dispatchKeyEvent(key(KeyEvent.KEY_RELEASED, KeyEvent.VK_K)))
            assertEquals(2, calls)
            useBindings()
            PluginShortcutRegistryImpl.unregister(provider.providerId)
            assertFalse(dispatchKeyEvent(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_K)))
            assertFalse(dispatchKeyEvent(key(KeyEvent.KEY_RELEASED, KeyEvent.VK_K)))
            assertEquals(2, calls)
        } finally {
            PluginShortcutRegistryImpl.unregister(provider.providerId)
        }
    }

    @Test
    fun `a plugin handler that throws still consumes its chord`() {
        val provider = shortcutProvider("plugin.shortcut-review.throws") { error("boom") }
        PluginShortcutRegistryImpl.register(provider)
        try {
            useBindings()
            val press = key(KeyEvent.KEY_PRESSED, KeyEvent.VK_K)
            assertTrue(dispatchKeyEvent(press), "a thrown handler is logged, not leaked")
            assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_RELEASED, KeyEvent.VK_K)))
        } finally {
            PluginShortcutRegistryImpl.unregister(provider.providerId)
        }
    }

    @Test
    fun `an action that moves focus while it runs leaves no held state behind`() {
        // What the focus listener does when the action opens or closes a window. Holding the
        // chord before dispatching is what lets that clear stick instead of being re-inserted.
        val provider =
            shortcutProvider("plugin.shortcut-review.focus") { AWTKeyboardInterceptor.cancelPendingShortcut() }
        PluginShortcutRegistryImpl.register(provider)
        try {
            useBindings()
            assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_K)))
            assertTrue(AWTKeyboardInterceptor.pendingShortcuts.isEmpty())
            assertTrue(AWTKeyboardInterceptor.claimedKeys.isEmpty())
            assertFalse(dispatchKeyEvent(key(KeyEvent.KEY_RELEASED, KeyEvent.VK_K)))
        } finally {
            PluginShortcutRegistryImpl.unregister(provider.providerId)
        }
    }

    @Test
    fun `a chord left held in another window by a lost release fires once in the new one`() {
        assertTrue(AWTKeyboardInterceptor.processKeyEvent(key(KeyEvent.KEY_PRESSED), "other-window"))
        assertEquals(0, newTabEventCount.get(), "the first press ran in the other window")
        // Its release never arrives. The same chord pressed in this window is a new press, not a repeat.
        assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_PRESSED)))
        repeat(2) { assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_PRESSED))) }
        assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_RELEASED)))
        assertEquals(1, newTabEventCount.get())
    }

    private fun shortcutProvider(
        action: String,
        onInvoke: () -> Unit,
    ): ShortcutActionProvider =
        object : ShortcutActionProvider {
            override val providerId = "shortcut-review"

            override fun shortcuts() =
                listOf(
                    PluginShortcutSpec(
                        action,
                        "Test shortcut",
                        defaultBinding = KeyChordSpec("K", setOf("Cmd")),
                    ),
                )

            override fun onAction(
                actionId: String,
                windowId: String?,
            ) {
                onInvoke()
            }
        }

    @Test
    fun `lost primary release cannot swallow a later bare character`() {
        assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_PRESSED)))
        assertFalse(dispatchKeyEvent(key(KeyEvent.KEY_PRESSED, modifiers = 0)))
        assertFalse(dispatchKeyEvent(key(KeyEvent.KEY_RELEASED, modifiers = 0)))
        assertEquals(1, newTabEventCount.get())
    }

    @Test
    fun `a gate closing while the chord is held changes nothing - the action already ran`() {
        useBindings(KeyBinding(actionId = KeymapActions.TAB_NEXT_POSITIONAL, key = "T", modifiers = listOf("Cmd")))
        val events = mutableListOf<MenuActionsHandler.TabSwitchAction>()
        val job = collectTabSwitches(events)
        MenuActionsHandler.updateActivePanelTabCount(windowId, 2)
        try {
            assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_PRESSED)))
            assertEquals(1, events.size)
            MenuActionsHandler.updateActivePanelTabCount(windowId, 1)
            assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_RELEASED)))
            assertFalse(dispatchKeyEvent(modifierRelease()))
            assertEquals(1, events.size, "positional stepping never opens an MRU cycle, so no COMMIT")
        } finally {
            job.cancel()
            MenuActionsHandler.updateActivePanelTabCount(windowId, 0)
        }
    }

    @Test
    fun `browser print waits for release and still fires when the modifier comes up first`() {
        // GLOBAL here only because a headless run has no focused window for detectCurrentContext.
        useBindings(KeyBinding(actionId = KeymapActions.BROWSER_PRINT, key = "P", modifiers = listOf("Cmd")))
        assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_P)))
        repeat(2) { assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_P))) }
        assertEquals(0, printEventCount.get(), "print is armed on the press so the native callback can take it")
        assertFalse(dispatchKeyEvent(modifierRelease()))
        assertEquals(1, printEventCount.get(), "a modifier release fires it rather than dropping it")
        assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_RELEASED, KeyEvent.VK_P, modifiers = 0)))
        assertEquals(1, printEventCount.get())

        assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_P)))
        assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_RELEASED, KeyEvent.VK_P)))
        assertEquals(2, printEventCount.get(), "the primary release fires it too")

        assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_P)))
        AWTKeyboardInterceptor.cancelPendingNativePrint(windowId)
        assertTrue(dispatchKeyEvent(key(KeyEvent.KEY_RELEASED, KeyEvent.VK_P)), "the release stays consumed")
        assertEquals(2, printEventCount.get(), "the native print took it, so AWT must not print again")
    }

    private companion object {
        val NEXT = MenuActionsHandler.TabSwitchAction.NEXT
        val COMMIT = MenuActionsHandler.TabSwitchAction.COMMIT
    }
}
