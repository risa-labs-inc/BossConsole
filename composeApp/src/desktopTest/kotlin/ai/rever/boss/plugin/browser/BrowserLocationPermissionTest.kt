package ai.rever.boss.plugin.browser

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserLocationPermissionTest {
    @Test
    fun `origin excludes private paths query and fragment`() {
        assertEquals(
            "https://example.com",
            locationPermissionOrigin("https://example.com/private?token=secret#fragment"),
        )
    }

    @Test
    fun `origin preserves nondefault ports and normalizes host`() {
        assertEquals("https://example.com:8443", locationPermissionOrigin("HTTPS://EXAMPLE.COM:8443/path"))
        assertEquals("https://example.com", locationPermissionOrigin("https://example.com:443"))
    }

    @Test
    fun `rejects opaque malformed insecure and credential bearing origins`() {
        listOf(
            "data:text/html,test",
            "about:blank",
            "file:///tmp/location.html",
            "https://",
            "https://example.com:70000",
            "https://trusted.example@evil.example/path",
            "https://user:password@example.com/",
            "http://example.com",
            "https://example.com\ntrusted.example",
        ).forEach { assertNull(locationPermissionOrigin(it), it) }
    }

    @Test
    fun `permits secure context loopback origins for local development`() {
        assertEquals("http://localhost:3000", locationPermissionOrigin("http://localhost:3000/test"))
        assertEquals("http://127.0.0.1", locationPermissionOrigin("http://127.0.0.1:80"))
        assertEquals("http://[::1]:3000", locationPermissionOrigin("http://[::1]:3000/test"))
        assertNull(locationPermissionOrigin("http://localhost.evil.example"))
    }

    @Test
    fun `display never decodes punycode into lookalike domain`() {
        assertEquals("https://xn--pple-43d.com", locationPermissionOrigin("https://xn--pple-43d.com"))
    }

    @Test
    fun `allow resolves once and releases callback listeners`() {
        val answers = mutableListOf<Boolean>()
        var releases = 0
        val request = LocationPermissionRequest { answers += it }
        request.onFinished { releases++ }
        request.finish(true)
        request.finish(false)
        assertEquals(listOf(true), answers)
        assertEquals(1, releases)
        assertTrue(request.isCompleted)
    }

    @Test
    fun `dismiss navigation or timeout prevents a late allow`() {
        val answers = mutableListOf<Boolean>()
        val request = LocationPermissionRequest { answers += it }
        request.finish(false)
        request.finish(true)
        assertEquals(listOf(false), answers)
    }

    @Test
    fun `listener installed after navigation is immediately released`() {
        val request = LocationPermissionRequest { }
        request.finish(false)
        var released = false
        request.onFinished { released = true }
        assertTrue(released)
    }

    @Test
    fun `failing resource release does not retain remaining listeners`() {
        val request = LocationPermissionRequest { }
        var released = false
        request.onFinished { error("native peer already gone") }
        request.onFinished { released = true }
        request.finish(false)
        assertTrue(released)
    }

    @Test
    fun `answer failure still releases all resources`() {
        val request = LocationPermissionRequest { error("native peer already gone") }
        var released = false
        request.onFinished { released = true }
        assertTrue(runCatching { request.finish(false) }.isFailure)
        assertTrue(released)
        assertTrue(request.isCompleted)
    }

    @Test
    fun `simultaneous timeout and user choice produce one answer`() {
        val answers = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()
        val request = LocationPermissionRequest { answers.add(it) }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts =
                listOf(true, false).map { allow ->
                    executor.submit {
                        start.await()
                        request.finish(allow)
                    }
                }
            start.countDown()
            attempts.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, answers.size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `pending request does not approve without user decision`() {
        var answered = false
        val request = LocationPermissionRequest { answered = true }
        assertFalse(request.isCompleted)
        assertFalse(answered)
    }
}
