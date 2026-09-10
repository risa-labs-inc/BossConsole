package ai.rever.boss.plugin.sandbox.notification

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PluginSandboxNotificationListenerTest {
    @Test
    fun `restart-limit exhaustion shows details and suppresses the following generic disable`() {
        val toasts = RecordingToastController()
        val listener =
            PluginSandboxNotificationListener(BossPluginNotificationService(toasts, displayNameOf = { "Example" }))

        listener.onPluginRestartLimitExceeded("example.plugin", restartAttempts = 3)
        listener.onPluginDisabled("example.plugin")

        assertEquals(1, toasts.messages.size)
        assertEquals("Plugin Disabled", toasts.messages.single().title)
        assertEquals(
            "Plugin 'Example' was disabled: Maximum restart attempts exceeded (3 attempts).",
            toasts.messages.single().message,
        )
        assertEquals(ToastType.ERROR, toasts.messages.single().type)
        assertEquals(ToastDuration.INDEFINITE, toasts.messages.single().duration)
        assertEquals(
            "Re-enable",
            toasts.messages
                .single()
                .action
                ?.label,
        )
    }

    @Test
    fun `manual disable still shows the existing generic notification`() {
        val toasts = RecordingToastController()
        val listener = PluginSandboxNotificationListener(BossPluginNotificationService(toasts))

        listener.onPluginDisabled("example.plugin")

        assertEquals(1, toasts.messages.size)
        assertEquals("Plugin Disabled", toasts.messages.single().title)
        assertEquals(
            "Plugin 'example.plugin' has been disabled due to repeated failures",
            toasts.messages.single().message,
        )
    }

    private class RecordingToastController : ToastController {
        val messages = mutableListOf<ToastMessage>()

        override fun show(message: ToastMessage) {
            messages += message
        }

        override fun dismiss(id: String) = Unit

        override fun dismissAll() = Unit
    }
}
