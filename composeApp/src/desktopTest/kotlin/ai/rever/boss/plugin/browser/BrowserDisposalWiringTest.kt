package ai.rever.boss.plugin.browser

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Limited source guards for the production boundaries the engine-free behavioral tests cannot instantiate. */
class BrowserDisposalWiringTest {
    private fun source(name: String): String {
        val relative = "src/desktopMain/kotlin/ai/rever/boss/plugin/browser/$name.kt"
        return listOf(File(relative), File("composeApp/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?.replace("\r\n", "\n")
            ?: error("Cannot locate browser source $relative from ${File(".").absolutePath}")
    }

    @Test
    fun `every declared native worker participates in the disposal drain`() {
        val handle = source("BrowserHandleImpl")
        val declarations = Regex("private val (\\w+)\\s*=\\s*(DrainingBrowserExecutor|BoundedBrowserCall)\\(")
        val declared =
            declarations
                .findAll(handle)
                .map {
                    it.groupValues[1] + if (it.groupValues[2] == "BoundedBrowserCall") ".executor" else ""
                }.toSet()
        assertTrue(declared.isNotEmpty())
        assertFalse(
            handle.contains("Executors.new"),
            "Native workers must use the owned draining factory to participate in disposal",
        )
        val owner = handle.substringAfter("private val ownedExecutors =").substringBefore("private val nativeDisposal")
        val owned =
            Regex("listOf\\(([^)]*)\\)")
                .find(owner)!!
                .groupValues[1]
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
        assertEquals(declared, owned)
        assertTrue(handle.contains("BrowserNativeDisposal(ownedExecutors,"))
        assertTrue(handle.substringAfter("override fun dispose()").contains("nativeDisposal.start()"))
    }

    @Test
    fun `early teardown failures and renderer close cannot bypass native disposal`() {
        val handle = source("BrowserHandleImpl")
        val dispose = handle.substringAfter("override fun dispose()")
        assertTrue(dispose.contains("val popOutCleanup = runCatching { closePopOutOnEdt() }"))
        assertTrue(dispose.contains("try {\n            popOutCleanup.getOrThrow()"))
        val completion = dispose.substringAfter("} finally {")
        assertTrue(completion.contains("finishLocalBrowserDisposal("))
        assertTrue(completion.contains("currentViewState?.close()"))
        assertTrue(completion.contains("requestNativeClose = { nativeDisposal.start() }"))
        val closed =
            handle.substringAfter("browser.on(BrowserClosed::class.java)").substringBefore("rendererPid.onGone()")
        assertTrue(closed.contains("disposed.set(true)"))
        assertTrue(
            closed.contains("nativeDisposal.start()"),
            "External close must settle disposal despite the disposed guard",
        )
    }

    @Test
    fun `both service entry points schedule durable cleanup without awaiting native close`() {
        val service = source("BrowserServiceImpl")
        val suspendEntry =
            service
                .substringAfter(
                    "override suspend fun disposeBrowser(",
                ).substringBefore("override fun getActiveBrowserCount")
        val windowEntry =
            service
                .substringAfter(
                    "private fun disposeTrackedBrowserBlocking(",
                ).substringBefore("private const val EPHEMERAL_PREFIX")
        for (entry in listOf(suspendEntry, windowEntry)) {
            assertTrue(entry.contains("disposeBrowserResources("))
            assertTrue(entry.contains("awaitNativeDisposal()"))
            assertTrue(entry.contains("managedByHandle.remove(handle.id)"))
            assertFalse(entry.contains(".await()"))
        }
        val acquireStart = "private suspend fun acquireManagedProfile("
        val seedStart = "override suspend fun seedProfile("
        assertTrue(service.contains(acquireStart))
        assertTrue(service.contains(seedStart))
        val acquire = service.substringAfter(acquireStart).substringBefore("private fun releaseManaged")
        val seed = service.substringAfter(seedStart).substringBefore("override fun deleteProfile")
        assertTrue(acquire.contains("acquireManagedProfileLock(mutex)"))
        assertTrue(seed.contains("acquireManagedProfileLock(mutex)"))
    }
}
