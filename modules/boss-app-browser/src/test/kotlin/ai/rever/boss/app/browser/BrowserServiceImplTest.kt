package ai.rever.boss.app.browser

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.NavigateBrowserRequest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserServiceImplTest {
    private val service = BrowserServiceImpl()

    @Test
    fun `prohibited schemes javascript and data are refused`() =
        runBlocking<Unit> {
            val jsResponse =
                service.navigate(
                    NavigateBrowserRequest
                        .newBuilder()
                        .setWindowId("w1")
                        .setUrl("javascript:alert(1)")
                        .build(),
                )
            assertFalse(jsResponse.success)
            assertTrue(jsResponse.errorMessage.contains("javascript:"))

            val dataResponse =
                service.navigate(
                    NavigateBrowserRequest
                        .newBuilder()
                        .setWindowId("w1")
                        .setUrl("data:text/html,hello")
                        .build(),
                )
            assertFalse(dataResponse.success)
            assertTrue(dataResponse.errorMessage.contains("data:"))
        }

    @Test
    fun `http and https navigations succeed`() =
        runBlocking<Unit> {
            val response =
                service.navigate(
                    NavigateBrowserRequest
                        .newBuilder()
                        .setWindowId("w1")
                        .setUrl("https://example.com/test")
                        .build(),
                )
            assertTrue(response.success)
            assertEquals("https://example.com/test", response.finalUrl)
        }

    @Test
    fun `URL userinfo credentials are redacted`() {
        val raw = "https://user:Secret123@internal-host/path"
        val safe = service.redactUrlUserInfo(raw)
        assertEquals("https://[REDACTED]@internal-host/path", safe)
        assertFalse(safe.contains("Secret123"))
    }

    @Test
    fun `reload does not leave page loading state stranded true`() =
        runBlocking<Unit> {
            service.navigate(
                NavigateBrowserRequest
                    .newBuilder()
                    .setWindowId("w1")
                    .setUrl("https://example.com")
                    .build(),
            )
            service.reload(Empty.getDefaultInstance())
            val pageInfo = service.getPageInfo(Empty.getDefaultInstance())
            assertFalse(pageInfo.isLoading)
        }
}
