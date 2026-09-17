package ai.rever.boss.services

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogEntry
import ai.rever.boss.plugin.logging.LogLevel
import ai.rever.boss.plugin.logging.LogListener
import ai.rever.boss.utils.WindowFocusManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ExternalUrlLoggingTest {
    private val urls =
        listOf(
            "https://example.com/path?ToKeN=query-secret&type=login#access_token=fragment-secret&view=home" to
                "https://example.com/path?ToKeN=[REDACTED]&type=login#access_token=[REDACTED]&view=home",
            "https://example.com/path#refresh_token=fragment-secret&view=home" to
                "https://example.com/path#refresh_token=[REDACTED]&view=home",
            "https://example.com/#/reset?code=query-secret&type=login" to
                "https://example.com/#/reset?code=[REDACTED]&type=login",
        )

    @Test
    fun `startup queue logs redact secrets without changing queued URLs`() {
        val readyField = URLHandlerService::class.java.getDeclaredField("isAppReady").apply { isAccessible = true }
        val queueField = URLHandlerService::class.java.getDeclaredField("urlQueue").apply { isAccessible = true }
        val wasReady = readyField.getBoolean(URLHandlerService)

        @Suppress("UNCHECKED_CAST")
        val queue = queueField.get(URLHandlerService) as MutableList<String>
        val originalQueue = queue.toList()
        try {
            readyField.setBoolean(URLHandlerService, false)
            assertSanitizedLogs("App not ready, queueing URL") { url -> URLHandlerService.handleURL(url) }
            assertEquals(originalQueue + urls.map { it.first }, queue)
        } finally {
            queue.clear()
            queue.addAll(originalQueue)
            readyField.setBoolean(URLHandlerService, wasReady)
        }
    }

    @Test
    fun `missing window warning redacts query and fragment secrets`() {
        assertNull(WindowFocusManager.resolveActionableWindowId(), "This test must not activate a live window")
        assertSanitizedLogs("No usable window registered, cannot open URL") { url ->
            URLHandlerService::class.java
                .getDeclaredMethod("handleURLInternal", String::class.java)
                .apply { isAccessible = true }
                .invoke(URLHandlerService, url)
        }
    }

    @Test
    fun `emission logger redacts secrets and preserves captured window id`() {
        assertSanitizedLogs("Emitted URL open event", "window-b") { url ->
            // Exercise the exact logger called after route.emit(), without launching a UI or changing its visibility.
            URLHandlerService::class.java
                .getDeclaredMethod("logUrlEmission", String::class.java, String::class.java)
                .apply { isAccessible = true }
                .invoke(URLHandlerService, url, "window-b")
        }
    }

    private fun assertSanitizedLogs(
        message: String,
        windowId: String? = null,
        logUrl: (String) -> Unit,
    ) {
        val entries = mutableListOf<LogEntry>()
        val listener = LogListener { entry -> if (entry.component == "URLHandlerService") entries += entry }
        val previousLevel = BossLogger.globalLevel
        BossLogger.setGlobalLevel(LogLevel.DEBUG)
        BossLogger.addListener(listener)
        try {
            urls.forEach { (url, expected) ->
                entries.clear()
                logUrl(url)
                val entry = entries.single { it.message == message }
                assertEquals(expected, entry.data?.get("url"))
                assertEquals(windowId, entry.data?.get("windowId"))
                entries.forEach {
                    assertFalse(it.toString().contains("query-secret"))
                    assertFalse(it.toString().contains("fragment-secret"))
                }
            }
        } finally {
            BossLogger.removeListener(listener)
            BossLogger.setGlobalLevel(previousLevel)
        }
    }
}
