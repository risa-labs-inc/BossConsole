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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Drives the actual JxBrowser callback adapter without starting Chromium or opening a window. */
class BrowserLocationCallbackTest {
    private val observers = ConcurrentHashMap<Class<*>, Observer<*>>()
    private val validation = CompletableDeferred<Boolean>()
    private var closed = false
    private val navigation = fake<Navigation> { name, args -> observe(name, args) }
    private val browser =
        fake<Browser> { name, args ->
            when (name) {
                "navigation" -> navigation
                "isClosed" -> closed
                else -> observe(name, args)
            }
        }
    private val frame = fake<Frame> { name, _ -> if (name == "browser") browser else error(name) }
    private val answers = LinkedBlockingQueue<Boolean>()

    @After
    fun cleanup() {
        BrowserLocationPermission.unregister(browser)
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
        BrowserLocationPermission.unregister(browser)
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
    fun `second simultaneous request is denied without replacing first`() {
        register()
        request()
        request()
        assertDenied()
        assertEquals(3, observers.size)
        BrowserLocationPermission.unregister(browser)
        assertDenied()
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
        BrowserLocationPermission.register(browser, owner = { error("window unavailable") }) { _, _ -> true }
        request()
        assertDenied()
        // Synchronize with completion cleanup, which follows the native answer.
        javax.swing.SwingUtilities.invokeAndWait { }
        assertTrue(observers.isEmpty())
    }

    private fun register() {
        BrowserLocationPermission.register(browser, owner = { null }) { _, _ -> validation.await() }
    }

    private fun request(url: String = "https://location.example/page") {
        val params =
            object : RequestPermissionCallback.Params {
                override fun url(): String = url

                override fun frame(): Frame = frame

                override fun permissionType(): PermissionType = PermissionType.GEOLOCATION
            }
        BrowserLocationPermission.request(params, RequestPermissionCallback.Action { answers.add(it.hasGrant()) })
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
