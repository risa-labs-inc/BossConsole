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

    @Test
    fun failedWindowCleanupStillPreparesOtherWindowsAndCompletesQuitAfterDisposal() {
        val events = mutableListOf<String>()
        val lifecycle = ApplicationQuitLifecycle()
        val response =
            object : QuitResponse {
                override fun performQuit() {
                    events.add("native quit")
                }

                override fun cancelQuit() {
                    events.add("cancel")
                }
            }

        lifecycle.requestQuit(response) {
            lifecycle.closeApplication(
                windowIds = listOf("failing", "healthy"),
                prepareWindow = { id ->
                    events.add(id)
                    if (id == "failing") throw NoClassDefFoundError("plugin classloader closed")
                },
                exitApplication = { events.add("exit application") },
            )
        }
        assertEquals(listOf("failing", "healthy", "exit application"), events)

        events.add("composition disposed")
        lifecycle.completeQuit()
        lifecycle.completeQuit()
        assertEquals(listOf("failing", "healthy", "exit application", "composition disposed", "native quit"), events)
    }

    @Test
    fun quitWithNoWindowsStillExits() {
        var exits = 0
        ApplicationQuitLifecycle().closeApplication(
            windowIds = emptyList(),
            prepareWindow = { error("No window should need preparation") },
            exitApplication = { exits++ },
        )
        assertEquals(1, exits)
    }
}
