package ai.rever.boss.plugin.browser

import org.junit.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserTransportFailureWiringTest {
    private fun source(name: String): String {
        val relative = "src/desktopMain/kotlin/ai/rever/boss/plugin/browser/$name.kt"
        return listOf(File(relative), File("composeApp/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?.replace("\r\n", "\n")
            ?: error("Cannot locate browser source $relative from ${File(".").absolutePath}")
    }

    @Test
    fun `direct browser calls latch connectionDead on transport failure`() {
        val handle = source("BrowserHandleImpl")

        val loadUrlBody =
            handle
                .substringAfter("override suspend fun loadUrl(")
                .substringBefore("override suspend fun loadUrlAndWait(")
        assertTrue(loadUrlBody.contains("catch (e: Exception)"), "loadUrl must catch exceptions")
        assertTrue(
            loadUrlBody.contains("isTransportFailure(e)"),
            "loadUrl must check for transport failure",
        )
        assertTrue(
            loadUrlBody.contains("connectionDead.set(true)"),
            "loadUrl must latch connectionDead",
        )
        assertTrue(loadUrlBody.contains("throw e"), "loadUrl must rethrow")

        val loadUrlAndWaitBody =
            handle
                .substringAfter("override suspend fun loadUrlAndWait(")
                .substringBefore("override suspend fun executeJavaScript(")
        assertTrue(loadUrlAndWaitBody.contains("catch (e: Exception)"), "loadUrlAndWait must catch")
        assertTrue(
            loadUrlAndWaitBody.contains("isTransportFailure(e)"),
            "loadUrlAndWait must check for transport failure",
        )
        assertTrue(
            loadUrlAndWaitBody.contains("connectionDead.set(true)"),
            "loadUrlAndWait must latch connectionDead",
        )
        assertTrue(loadUrlAndWaitBody.contains("throw e"), "loadUrlAndWait must rethrow")

        val executeJsBody =
            handle
                .substringAfter("override suspend fun executeJavaScript(")
                .substringBefore("override fun getCurrentUrl()")
        assertTrue(
            executeJsBody.contains("onError = { e ->"),
            "executeJavaScript must use BoundedBrowserCall onError",
        )
        assertTrue(
            executeJsBody.contains("isTransportFailure(e)"),
            "executeJavaScript must check for transport failure in onError",
        )
        assertTrue(
            executeJsBody.contains("connectionDead.set(true)"),
            "executeJavaScript must latch connectionDead",
        )
    }
}
