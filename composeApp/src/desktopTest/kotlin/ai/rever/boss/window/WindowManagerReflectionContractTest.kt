package ai.rever.boss.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Locks the reflection contract the terminal-tab plugin relies on to drive
 * BossTerm "Fit host to my screen". The plugin has no compile-time dependency on
 * the host, so it resolves these on [WindowManager] by name + parameter types
 * (the same pattern it uses for WindowFocusManager / DeepLinkHandler). A rename
 * or signature change would break that silently at runtime — this test fails at
 * build time instead. If you intentionally change the signature, update the
 * plugin's reflection call in lockstep, then update this test.
 */
class WindowManagerReflectionContractTest {
    private val type = WindowManager::class.java

    @Test
    fun windowManager_exposesSingletonInstance() {
        // Kotlin `object` → public static INSTANCE the plugin fetches to invoke on.
        val instance = type.getField("INSTANCE").get(null)
        assertNotNull(instance, "WindowManager.INSTANCE must be reflection-reachable")
        assertEquals(WindowManager, instance)
    }

    @Test
    fun fitWindowByDelta_isReachableByReflection() {
        // fun fitWindowByDelta(String, Float, Float) → (String, float, float) on the JVM.
        val method =
            type.getMethod(
                "fitWindowByDelta",
                String::class.java,
                java.lang.Float.TYPE,
                java.lang.Float.TYPE,
            )
        assertNotNull(method)
    }

    @Test
    fun restoreWindowSize_isReachableByReflection() {
        val method = type.getMethod("restoreWindowSize", String::class.java)
        assertNotNull(method)
    }
}
