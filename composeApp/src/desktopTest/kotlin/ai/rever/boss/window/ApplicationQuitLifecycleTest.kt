package ai.rever.boss.window

import java.awt.desktop.QuitResponse
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationQuitLifecycleTest {
    @Test
    fun nativeQuitStaysPendingUntilCompositionCleanupCompletes() {
        val events = mutableListOf<String>()
        val response =
            object : QuitResponse {
                override fun performQuit() {
                    events.add("native quit")
                }

                override fun cancelQuit() {
                    events.add("cancel")
                }
            }
        val lifecycle = ApplicationQuitLifecycle()

        lifecycle.requestQuit(response) { events.add("close application") }
        assertEquals(listOf("close application"), events)

        events.add("composition disposed")
        lifecycle.completeQuit()
        assertEquals(listOf("close application", "composition disposed", "native quit"), events)

        lifecycle.completeQuit()
        assertEquals(3, events.size)
    }

    @Test
    fun automaticUpdateQuitNeedsNoNativeResponse() {
        ApplicationQuitLifecycle().completeQuit()
    }
}
