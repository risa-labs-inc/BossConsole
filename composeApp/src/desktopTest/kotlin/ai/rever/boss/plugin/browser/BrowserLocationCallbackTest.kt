package ai.rever.boss.plugin.browser

import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.browser.event.BrowserClosed
import com.teamdev.jxbrowser.browser.event.FrameDeleted
import com.teamdev.jxbrowser.event.Event
import com.teamdev.jxbrowser.event.Observer
import com.teamdev.jxbrowser.event.Subscription
import com.teamdev.jxbrowser.frame.Frame
import com.teamdev.jxbrowser.navigation.Navigation
import com.teamdev.jxbrowser.navigation.event.NavigationStarted
import com.teamdev.jxbrowser.permission.PermissionType
import com.teamdev.jxbrowser.permission.callback.RequestPermissionCallback
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Drives the actual JxBrowser callback adapter without starting Chromium or opening a window. */
class BrowserLocationCallbackTest {
    private val observers = ConcurrentHashMap<Class<*>, Observer<*>>()
    private val validation = CompletableDeferred<Boolean>()
    private var closed = false
    private var topLevelUrl = "https://location.example/"
    private val now = AtomicLong(0)
    private val notices = AtomicInteger()
    private val prompts = LinkedBlockingQueue<Prompt>()
    private var controller = controller()

    private data class Prompt(
        val origins: LocationPermissionOrigins,
        val request: LocationPermissionRequest,
        val allow: () -> Unit,
    )

    private fun controller(
        configured: Boolean = true,
        timeoutMillis: Long = 60_000,
    ) = LocationPermissionController(
        providerConfigured = { configured },
        presentPrompt = { _, origins, request, allow -> prompts.add(Prompt(origins, request, allow)) },
        timeoutMillis = timeoutMillis,
        nanoTime = now::get,
        showUnavailable = { notices.incrementAndGet() },
    )

    private val navigation = fake<Navigation> { name, args -> observe(name, args) }
    private val browser =
        fake<Browser> { name, args ->
            when (name) {
                "navigation" -> navigation
                "isClosed" -> closed
                "url" -> topLevelUrl
                else -> observe(name, args)
            }
        }
    private val frame = fake<Frame> { name, _ -> if (name == "browser") browser else error(name) }
    private val answers = LinkedBlockingQueue<Boolean>()

    @After
    fun cleanup() {
        controller.unregister(browser)
        validation.complete(false)
    }

    @Test
    fun `unmanaged browser denies without showing dialog`() {
        request()
        assertDenied()
        assertTrue(observers.isEmpty())
    }

    @Test
    fun `malformed requesting origin denies without subscribing`() {
        register()
        request("data:text/html,test")
        assertDenied()
        assertTrue(observers.isEmpty())
    }

    @Test
    fun `document navigation cancels native request and removes listeners`() {
        register()
        request()
        emit<NavigationStarted> { name, _ -> if (name == "isSameDocument") false else error(name) }
        assertDenied()
        assertTrue(observers.isEmpty())
    }

    @Test
    fun `same document navigation keeps request pending`() {
        register()
        request()
        emit<NavigationStarted> { name, _ -> if (name == "isSameDocument") true else error(name) }
        assertTrue(answers.isEmpty())
        controller.unregister(browser)
        assertDenied()
    }

    @Test
    fun `removed requesting iframe cancels native request`() {
        register()
        request()
        emit<FrameDeleted> { name, _ -> if (name == "frame") frame else error(name) }
        assertDenied()
        assertTrue(observers.isEmpty())
    }

    @Test
    fun `browser closing cancels pending native callback`() {
        register()
        request()
        emit<BrowserClosed> { _, _ -> browser }
        assertDenied()
        assertTrue(observers.isEmpty())
    }

    @Test
    fun `already closed browser denies before any UI`() {
        register()
        closed = true
        request()
        assertDenied()
        assertTrue(observers.isEmpty())
    }

    @Test
    fun `same frame concurrent requests share one prompt and grant`() {
        register()
        request()
        request()
        validation.complete(true)
        prompts.poll(5, TimeUnit.SECONDS)!!.allow()
        assertEquals(true, answers.poll(5, TimeUnit.SECONDS))
        assertEquals(true, answers.poll(5, TimeUnit.SECONDS))
        assertTrue(prompts.isEmpty())
        awaitCleanup()
    }

    @Test
    fun `changed origin fails validation and denies`() {
        register()
        request()
        validation.complete(false)
        assertDenied()
    }

    @Test
    fun `failed owner resolution denies and does not retain request`() {
        controller.register(browser, owner = { error("window unavailable") }) { _, _ -> true }
        request()
        assertDenied()
        // Synchronize with completion cleanup, which follows the native answer.
        javax.swing.SwingUtilities.invokeAndWait { }
        assertTrue(observers.isEmpty())
    }

    @Test
    fun `user approval revalidates and grants real native callback`() {
        register()
        request()
        validation.complete(true)
        val prompt = prompts.poll(5, TimeUnit.SECONDS)!!
        assertEquals(LocationPermissionOrigins("https://location.example", "https://location.example"), prompt.origins)
        assertTrue(answers.isEmpty())
        prompt.allow()
        assertEquals(true, answers.poll(5, TimeUnit.SECONDS))
        awaitCleanup()
        assertTrue(answers.isEmpty())
    }

    @Test
    fun `cross origin iframe displays both origins and top level navigation invalidates approval`() {
        topLevelUrl = "https://embedding.example/page"
        controller.register(browser, owner = { null }) { _, origins ->
            locationPermissionOrigin(topLevelUrl) == origins.embedding
        }
        request()
        val prompt = prompts.poll(5, TimeUnit.SECONDS)!!
        assertEquals(LocationPermissionOrigins("https://location.example", "https://embedding.example"), prompt.origins)
        topLevelUrl = "https://other.example/"
        prompt.allow()
        assertDenied()
    }

    @Test
    fun `missing provider denies repeated requests with one passive notice and no modal`() {
        controller = controller(configured = false)
        register()
        repeat(3) {
            request()
            assertDenied()
        }
        assertEquals(1, notices.get())
        assertTrue(prompts.isEmpty())
        assertTrue(observers.isEmpty())
    }

    @Test
    fun `native callback expires and a late allow cannot grant`() {
        controller = controller(timeoutMillis = 1000)
        register()
        validation.complete(true)
        request()
        val prompt = prompts.poll(5, TimeUnit.SECONDS)!!
        assertDenied()
        prompt.allow()
        awaitCleanup()
        assertTrue(answers.isEmpty())
    }

    @Test
    fun `denial backs off repeated prompts then permits a fresh request after cooldown`() {
        register()
        validation.complete(true)
        request()
        prompts.poll(5, TimeUnit.SECONDS)!!.request.finish(false)
        assertDenied()
        awaitCleanup()
        request()
        assertDenied()
        assertTrue(prompts.isEmpty())
        now.set(31_000_000_000)
        request()
        prompts.poll(5, TimeUnit.SECONDS)!!.allow()
        assertEquals(true, answers.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `different origin concurrent request is denied without approving it with first prompt`() {
        register()
        request()
        request("https://other.example/")
        assertDenied()
        validation.complete(true)
        prompts.poll(5, TimeUnit.SECONDS)!!.allow()
        assertEquals(true, answers.poll(5, TimeUnit.SECONDS))
        assertTrue(prompts.isEmpty())
    }

    private fun awaitCleanup() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (observers.isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue(observers.isEmpty())
    }

    private fun register() {
        controller.register(browser, owner = { null }) { _, _ -> validation.await() }
    }

    private fun request(url: String = "https://location.example/page") {
        val params =
            object : RequestPermissionCallback.Params {
                override fun url(): String = url

                override fun frame(): Frame = frame

                override fun permissionType(): PermissionType = PermissionType.GEOLOCATION
            }
        controller.request(params, RequestPermissionCallback.Action { answers.add(it.hasGrant()) })
    }

    private fun assertDenied() {
        assertEquals(false, answers.poll(5, TimeUnit.SECONDS))
    }

    private fun observe(
        name: String,
        args: Array<out Any?>?,
    ): Any {
        check(name == "on") { name }
        val eventType = args!![0] as Class<*>
        val observer = args[1] as Observer<*>
        observers[eventType] = observer
        return object : Subscription {
            override fun unsubscribe() {
                observers.remove(eventType, observer)
            }

            override fun isUnsubscribed(): Boolean = observers[eventType] !== observer
        }
    }

    private inline fun <reified T : Event> emit(crossinline properties: (String, Array<out Any?>?) -> Any?) {
        @Suppress("UNCHECKED_CAST")
        val observer = observers[T::class.java] as Observer<T>
        observer.on(fake<T>(properties))
    }

    private inline fun <reified T> fake(crossinline invoke: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> T::class.java.simpleName
                else -> invoke(method.name, args)
            }
        } as T
}
