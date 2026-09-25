package ai.rever.boss.window

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginWizardEventTest {
    private lateinit var testScope: CoroutineScope
    private val windowACount = AtomicInteger(0)
    private val windowBCount = AtomicInteger(0)
    private var collectorJobA: Job? = null
    private var collectorJobB: Job? = null

    @BeforeTest
    fun setUp() {
        testScope = CoroutineScope(Dispatchers.Unconfined)
        windowACount.set(0)
        windowBCount.set(0)

        collectorJobA =
            testScope.launch {
                MenuActionsHandler.showPluginWizardEvents.collect { winId ->
                    if (winId == "window-A") {
                        windowACount.incrementAndGet()
                    }
                }
            }

        collectorJobB =
            testScope.launch {
                MenuActionsHandler.showPluginWizardEvents.collect { winId ->
                    if (winId == "window-B") {
                        windowBCount.incrementAndGet()
                    }
                }
            }
    }

    @AfterTest
    fun tearDown() {
        collectorJobA?.cancel()
        collectorJobB?.cancel()
        testScope.cancel()
    }

    @Test
    fun `triggerShowPluginWizard emits to the correct window only`() {
        MenuActionsHandler.triggerShowPluginWizard("window-A")

        assertEquals(1, windowACount.get())
        assertEquals(0, windowBCount.get())
    }

    @Test
    fun `triggerShowPluginWizard emits once per call`() {
        MenuActionsHandler.triggerShowPluginWizard("window-A")
        MenuActionsHandler.triggerShowPluginWizard("window-A")
        MenuActionsHandler.triggerShowPluginWizard("window-A")

        assertEquals(3, windowACount.get())
    }

    @Test
    fun `different windowId does not consume the event`() {
        collectorJobA?.cancel() // Set up a collector only for "window-B"

        MenuActionsHandler.triggerShowPluginWizard("window-A")

        assertEquals(0, windowBCount.get())
    }
}
