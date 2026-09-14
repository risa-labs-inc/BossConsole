package ai.rever.boss.window

import ai.rever.boss.components.plugin.registries.PluginShortcutRegistryImpl
import ai.rever.boss.keymap.KeymapSettingsManager
import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.ShortcutContext
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
 * Tests for [AWTKeyboardInterceptor] key release semantics.
 *
 * Verifies that:
 * 1. A matching KEY_PRESSED event arms the chord and is consumed without dispatching the action.
 * 2. Auto-repeat KEY_PRESSED events are consumed and do not dispatch the action.
 * 3. The matching KEY_RELEASED event of the primary key dispatches the action and is consumed.
 * 4. Releasing a modifier alone cancels the pending shortcut and does not dispatch.
 * 5. Window unregister or clearPendingShortcut cancels the pending shortcut.
 */
class ShortcutKeyUpSemanticsTest {
    private lateinit var previousSettings: KeymapSettings
    private lateinit var settingsState: MutableStateFlow<KeymapSettings>
    private lateinit var canvas: Canvas
    private val windowId = "test-window-key-release"
    private lateinit var testScope: CoroutineScope
    private val newTabEventCount = AtomicInteger(0)
    private var collectorJob: Job? = null

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
        collectorJob =
            testScope.launch {
                MenuActionsHandler.newTabEvents.collect { winId ->
                    if (winId == windowId) {
                        newTabEventCount.incrementAndGet()
                    }
                }
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

        // Configure a known test binding
        val binding =
            KeyBinding(
                actionId = KeymapActions.TAB_NEW,
                key = "T",
                modifiers = listOf("Cmd"),
                context = ShortcutContext.GLOBAL,
                enabled = true,
            )
        settingsState.value = KeymapSettings.fromBindings(listOf(binding))
    }

    @AfterTest
    fun tearDown() {
        AWTKeyboardInterceptor.cancelPendingShortcut()
        AWTKeyboardInterceptor.uninstall()
        settingsState.value = previousSettings
        collectorJob?.cancel()
        testScope.cancel()
    }

    private fun dispatchKeyEvent(event: KeyEvent): Boolean = AWTKeyboardInterceptor.processKeyEvent(event, windowId)

    @Test
    fun `matching KEY_PRESSED arms pending shortcut and consumes without action dispatch`() {
        val keyDown =
            KeyEvent(
                canvas,
                KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),
                primaryModifierMask,
                KeyEvent.VK_T,
                'T',
            )

        val consumed = dispatchKeyEvent(keyDown)

        assertTrue(consumed, "KeyDown matching shortcut must be consumed")
        assertTrue(keyDown.isConsumed, "KeyEvent must be marked consumed")
        assertTrue(
            AWTKeyboardInterceptor.pendingShortcuts.isNotEmpty(),
            "AWTKeyboardInterceptor must have pending shortcut armed",
        )
        assertEquals(0, newTabEventCount.get(), "Action must not be dispatched on key-down")
    }

    @Test
    fun `matching KEY_RELEASED on primary key dispatches action exactly once`() {
        val keyDown =
            KeyEvent(
                canvas,
                KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),
                primaryModifierMask,
                KeyEvent.VK_T,
                'T',
            )
        dispatchKeyEvent(keyDown)
        assertEquals(0, newTabEventCount.get(), "Action must not dispatch on KeyDown")

        val keyUp =
            KeyEvent(
                canvas,
                KeyEvent.KEY_RELEASED,
                System.currentTimeMillis(),
                primaryModifierMask,
                KeyEvent.VK_T,
                'T',
            )
        val consumed = dispatchKeyEvent(keyUp)

        assertTrue(consumed, "KeyUp on primary key must be consumed")
        assertTrue(keyUp.isConsumed, "KeyUp event must be marked consumed")
        assertEquals(1, newTabEventCount.get(), "Action must be dispatched exactly once on KeyUp")
        assertFalse(
            AWTKeyboardInterceptor.pendingShortcuts.isNotEmpty(),
            "Pending shortcut must be cleared after dispatch",
        )
    }

    @Test
    fun `auto-repeat KEY_PRESSED events do not cause multiple dispatches`() {
        // Simulate 5 auto-repeat key-down events
        repeat(5) {
            val keyDown =
                KeyEvent(
                    canvas,
                    KeyEvent.KEY_PRESSED,
                    System.currentTimeMillis(),
                    primaryModifierMask,
                    KeyEvent.VK_T,
                    'T',
                )
            val consumed = dispatchKeyEvent(keyDown)
            assertTrue(consumed, "Auto-repeat KeyDown must be consumed")
            assertEquals(0, newTabEventCount.get(), "Auto-repeat must not dispatch action")
        }

        // Release primary key
        val keyUp =
            KeyEvent(
                canvas,
                KeyEvent.KEY_RELEASED,
                System.currentTimeMillis(),
                primaryModifierMask,
                KeyEvent.VK_T,
                'T',
            )
        val consumed = dispatchKeyEvent(keyUp)

        assertTrue(consumed, "KeyUp must be consumed")
        assertEquals(1, newTabEventCount.get(), "Action must be dispatched exactly once on release")
    }

    @Test
    fun `releasing modifier alone does not dispatch action and cancels pending shortcut`() {
        val keyDown =
            KeyEvent(
                canvas,
                KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),
                primaryModifierMask,
                KeyEvent.VK_T,
                'T',
            )
        dispatchKeyEvent(keyDown)
        assertTrue(AWTKeyboardInterceptor.pendingShortcuts.isNotEmpty())

        // Release modifier key alone (e.g. Cmd/Ctrl)
        val modifierKeyUp =
            KeyEvent(
                canvas,
                KeyEvent.KEY_RELEASED,
                System.currentTimeMillis(),
                0,
                modifierKeyCode,
                KeyEvent.CHAR_UNDEFINED,
            )
        val consumed = dispatchKeyEvent(modifierKeyUp)

        assertFalse(consumed, "Releasing modifier alone should not be consumed as shortcut execution")
        assertEquals(0, newTabEventCount.get(), "Action must not be dispatched when modifier is released alone")
        assertFalse(
            AWTKeyboardInterceptor.pendingShortcuts.isNotEmpty(),
            "Pending shortcut must be cancelled on modifier release",
        )

        // Subsequent primary key release does nothing
        val keyUp =
            KeyEvent(
                canvas,
                KeyEvent.KEY_RELEASED,
                System.currentTimeMillis(),
                0,
                KeyEvent.VK_T,
                'T',
            )
        val keyUpConsumed = dispatchKeyEvent(keyUp)

        assertTrue(keyUpConsumed, "The cancelled chord still owns the primary release")
        assertEquals(0, newTabEventCount.get())
    }

    @Test
    fun `clearPendingShortcut cancels pending shortcut on focus loss or window unregister`() {
        val keyDown =
            KeyEvent(
                canvas,
                KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),
                primaryModifierMask,
                KeyEvent.VK_T,
                'T',
            )
        dispatchKeyEvent(keyDown)
        assertTrue(AWTKeyboardInterceptor.pendingShortcuts.isNotEmpty())

        // Exercise the listener registered by install without creating or focusing a window.
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val listenerField = AWTKeyboardInterceptor::class.java.getDeclaredField("focusListener")
        listenerField.isAccessible = true
        val listener = listenerField.get(AWTKeyboardInterceptor) as java.beans.PropertyChangeListener
        assertTrue(manager.getPropertyChangeListeners("focusedWindow").contains(listener))
        listener.propertyChange(PropertyChangeEvent(manager, "focusedWindow", null, null))
        assertFalse(AWTKeyboardInterceptor.pendingShortcuts.isNotEmpty())

        // Key up after cancellation should not dispatch action
        val keyUp =
            KeyEvent(
                canvas,
                KeyEvent.KEY_RELEASED,
                System.currentTimeMillis(),
                primaryModifierMask,
                KeyEvent.VK_T,
                'T',
            )
        val consumed = dispatchKeyEvent(keyUp)

        assertFalse(consumed)
        assertEquals(0, newTabEventCount.get())
    }

    @Test
    fun `Shift release cancels and held repeats cannot rearm the action`() {
        val press = KeyEvent(canvas, KeyEvent.KEY_PRESSED, 0, primaryModifierMask, KeyEvent.VK_T, 'T')
        assertTrue(dispatchKeyEvent(press))
        assertFalse(
            dispatchKeyEvent(
                KeyEvent(
                    canvas,
                    KeyEvent.KEY_RELEASED,
                    1,
                    primaryModifierMask,
                    KeyEvent.VK_SHIFT,
                    KeyEvent.CHAR_UNDEFINED,
                ),
            ),
        )
        repeat(3) { assertTrue(dispatchKeyEvent(press)) }
        assertTrue(
            dispatchKeyEvent(KeyEvent(canvas, KeyEvent.KEY_RELEASED, 2, primaryModifierMask, KeyEvent.VK_T, 'T')),
        )
        assertEquals(0, newTabEventCount.get())
    }

    @Test
    fun `MRU commits once and cancels a second held Tab when modifier releases first`() {
        settingsState.value =
            KeymapSettings
                .fromBindings(
                    listOf(
                        KeyBinding(actionId = KeymapActions.TAB_NEXT, key = "Tab", modifiers = listOf("Cmd")),
                    ),
                ).copy(tabSwitchMode = ai.rever.boss.keymap.model.TabSwitchMode.MRU)
        val events = mutableListOf<Pair<String, MenuActionsHandler.TabSwitchAction>>()
        val job = testScope.launch { MenuActionsHandler.tabSwitchEvents.collect { events.add(it) } }

        fun tab(id: Int) = KeyEvent(canvas, id, 0, primaryModifierMask, KeyEvent.VK_TAB, '\t')
        assertTrue(dispatchKeyEvent(tab(KeyEvent.KEY_PRESSED)))
        assertTrue(events.isEmpty())
        assertTrue(dispatchKeyEvent(tab(KeyEvent.KEY_RELEASED)))
        assertTrue(dispatchKeyEvent(tab(KeyEvent.KEY_PRESSED)))
        assertFalse(
            dispatchKeyEvent(KeyEvent(canvas, KeyEvent.KEY_RELEASED, 1, 0, modifierKeyCode, KeyEvent.CHAR_UNDEFINED)),
        )
        assertTrue(dispatchKeyEvent(tab(KeyEvent.KEY_RELEASED)))
        assertEquals(
            listOf(
                windowId to MenuActionsHandler.TabSwitchAction.NEXT,
                windowId to MenuActionsHandler.TabSwitchAction.COMMIT,
            ),
            events,
        )
        job.cancel()
    }

    @Test
    fun `an unavailable host binding leaves both events unclaimed`() {
        settingsState.value =
            KeymapSettings.fromBindings(
                listOf(
                    KeyBinding(actionId = KeymapActions.QUICK_SWITCHER_OPEN, key = "T", modifiers = listOf("Cmd")),
                ),
            )
        for (id in listOf(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED)) {
            val event = KeyEvent(canvas, id, 0, primaryModifierMask, KeyEvent.VK_T, 'T')
            assertFalse(dispatchKeyEvent(event))
            assertFalse(event.isConsumed)
        }
        assertEquals(0, newTabEventCount.get())
    }

    @Test
    fun `overlapping chords each fire once on their own release`() {
        settingsState.value =
            KeymapSettings.fromBindings(
                listOf(
                    KeyBinding(
                        actionId = KeymapActions.TAB_NEW,
                        key = "T",
                        modifiers = listOf("Cmd"),
                        alternateKeystrokes =
                            listOf(
                                ai.rever.boss.keymap.model
                                    .KeyStroke("N", listOf("Cmd")),
                            ),
                    ),
                ),
            )
        for (key in listOf(KeyEvent.VK_N, KeyEvent.VK_T)) {
            assertTrue(
                dispatchKeyEvent(KeyEvent(canvas, KeyEvent.KEY_PRESSED, 0, primaryModifierMask, key, key.toChar())),
            )
        }
        assertEquals(0, newTabEventCount.get())
        for ((index, key) in listOf(KeyEvent.VK_N, KeyEvent.VK_T).withIndex()) {
            assertTrue(
                dispatchKeyEvent(KeyEvent(canvas, KeyEvent.KEY_RELEASED, 0, primaryModifierMask, key, key.toChar())),
            )
            assertEquals(index + 1, newTabEventCount.get())
        }
    }

    @Test
    fun `plugin defaults and rebinds invoke once and unregistering closes the gate`() {
        val action = "plugin.shortcut-review.run"
        var calls = 0
        val provider = shortcutProvider(action) { calls++ }
        PluginShortcutRegistryImpl.register(provider)
        try {
            for (rebound in listOf(false, true)) {
                settingsState.value =
                    KeymapSettings.fromBindings(
                        if (rebound) {
                            listOf(
                                KeyBinding(actionId = action, key = "K", modifiers = listOf("Cmd")),
                            )
                        } else {
                            emptyList()
                        },
                    )
                repeat(3) {
                    assertTrue(
                        dispatchKeyEvent(
                            pluginKeyEvent(KeyEvent.KEY_PRESSED),
                        ),
                    )
                }
                assertEquals(if (rebound) 1 else 0, calls)
                assertTrue(
                    dispatchKeyEvent(
                        pluginKeyEvent(KeyEvent.KEY_RELEASED),
                    ),
                )
            }
            assertEquals(2, calls)
            assertTrue(
                dispatchKeyEvent(pluginKeyEvent(KeyEvent.KEY_PRESSED)),
            )
            PluginShortcutRegistryImpl.unregister(provider.providerId)
            assertTrue(
                dispatchKeyEvent(pluginKeyEvent(KeyEvent.KEY_RELEASED)),
            )
            assertEquals(2, calls)
            assertFalse(
                dispatchKeyEvent(pluginKeyEvent(KeyEvent.KEY_PRESSED)),
            )
        } finally {
            PluginShortcutRegistryImpl.unregister(provider.providerId)
        }
    }

    private fun pluginKeyEvent(eventId: Int) = KeyEvent(canvas, eventId, 0, primaryModifierMask, KeyEvent.VK_K, 'K')

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
    fun `lost primary release cannot turn a later bare character into an action`() {
        assertTrue(dispatchKeyEvent(KeyEvent(canvas, KeyEvent.KEY_PRESSED, 0, primaryModifierMask, KeyEvent.VK_T, 'T')))
        assertFalse(dispatchKeyEvent(KeyEvent(canvas, KeyEvent.KEY_PRESSED, 1, 0, KeyEvent.VK_T, 't')))
        assertFalse(dispatchKeyEvent(KeyEvent(canvas, KeyEvent.KEY_RELEASED, 2, 0, KeyEvent.VK_T, 't')))
        assertEquals(0, newTabEventCount.get())
    }

    @Test
    fun `closing a dispatch gate while held consumes release without emitting the action`() {
        settingsState.value =
            KeymapSettings.fromBindings(
                listOf(
                    KeyBinding(actionId = KeymapActions.TAB_NEXT_POSITIONAL, key = "T", modifiers = listOf("Cmd")),
                ),
            )
        val events = mutableListOf<Pair<String, MenuActionsHandler.TabSwitchAction>>()
        val job = testScope.launch { MenuActionsHandler.tabSwitchEvents.collect { events.add(it) } }
        MenuActionsHandler.updateActivePanelTabCount(windowId, 2)
        try {
            assertTrue(
                dispatchKeyEvent(KeyEvent(canvas, KeyEvent.KEY_PRESSED, 0, primaryModifierMask, KeyEvent.VK_T, 'T')),
            )
            assertTrue(events.isEmpty())
            MenuActionsHandler.updateActivePanelTabCount(windowId, 1)
            assertTrue(
                dispatchKeyEvent(KeyEvent(canvas, KeyEvent.KEY_RELEASED, 0, primaryModifierMask, KeyEvent.VK_T, 'T')),
            )
            assertTrue(events.isEmpty())
        } finally {
            job.cancel()
            MenuActionsHandler.updateActivePanelTabCount(windowId, 0)
        }
    }
}
