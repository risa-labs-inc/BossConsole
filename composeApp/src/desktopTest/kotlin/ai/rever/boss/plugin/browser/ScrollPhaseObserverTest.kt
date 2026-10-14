package ai.rever.boss.plugin.browser

import com.sun.jna.Pointer
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScrollPhaseObserverTest {
    private class Harness {
        val tasks = ArrayDeque<() -> Unit>()
        val properties = mutableMapOf<String, String>()
        val publications = mutableListOf<Pair<String, String>>()
        val errors = mutableListOf<Throwable>()
        var event: (Long, Long, Double, Double) -> Unit = { _, _, _, _ -> }
        var interrupted: () -> Unit = {}
        var opens = 0
        var closes = 0
        var deny = false
        var now = 1_000L
        var duringPoll: () -> Boolean = { false }
        val observer =
            ScrollPhaseObserver(
                open = { onEvent, onInterrupted ->
                    opens++
                    if (deny) throw ScrollPhasePermissionDenied()
                    event = onEvent
                    interrupted = onInterrupted
                    object : ScrollPhaseSession {
                        override fun poll(): Boolean = duringPoll()

                        override fun close() {
                            closes++
                        }
                    }
                },
                launch = { tasks.addLast(it) },
                publish = { key, value ->
                    properties[key] = value
                    publications.add(key to value)
                },
                reportFailure = { errors.add(it) },
                clock = { now },
            )

        fun run() = tasks.removeFirst().invoke()

        fun begin() = event(1, 0, -20.0, 0.0)
    }

    @Test
    fun `registration and status reads do not start observation while disabled`() {
        val h = Harness()
        val claim = h.observer.register { error("unexpected end") }
        repeat(3) { h.observer.setEnabled(false) }
        assertEquals(ScrollPhaseAvailability.DISABLED, h.observer.availability.value)
        assertNull(claim.currentGestureToken())
        assertTrue(h.tasks.isEmpty())
        assertEquals(0, h.opens)
        claim.close()
    }

    @Test
    fun `disable cancels claimants immediately and reenable fences old callbacks`() {
        val h = Harness()
        val ends = mutableListOf<ScrollGestureEnd>()
        val claim = h.observer.register(ends::add)
        h.observer.setEnabled(true)
        h.duringPoll = {
            h.begin()
            assertTrue(claim.currentGestureToken()!!.startsWith("1:"))
            val staleEvent = h.event
            h.observer.setEnabled(false)
            assertNull(claim.currentGestureToken())
            assertEquals(listOf(true), ends.map { it.cancelled })
            h.observer.setEnabled(true)
            staleEvent(1, 0, -200.0, 0.0)
            true
        }
        h.run()
        assertEquals(1, h.closes)
        h.duringPoll = {
            h.begin()
            assertTrue(claim.currentGestureToken()!!.startsWith("2:"))
            h.observer.setEnabled(false)
            true
        }
        h.run()
        assertEquals(2, h.opens)
        assertEquals(2, h.closes)
        assertEquals(listOf(1L, 2L), ends.map { it.id })
    }

    @Test
    fun `denied permission is reported separately and toggle retries`() {
        val h = Harness()
        h.deny = true
        h.observer.setEnabled(true)
        h.run()
        assertEquals(ScrollPhaseAvailability.PERMISSION_DENIED, h.observer.availability.value)
        assertTrue(h.errors.isEmpty())
        h.observer.setEnabled(false)
        h.deny = false
        h.observer.setEnabled(true)
        h.duringPoll = {
            h.observer.setEnabled(false)
            true
        }
        h.run()
        assertEquals(2, h.opens)
        assertEquals(1, h.closes)
    }

    @Test
    fun `unexpected return and thrown failure both cancel release and can restart`() {
        for (throws in listOf(false, true)) {
            val h = Harness()
            val ends = mutableListOf<ScrollGestureEnd>()
            val claim = h.observer.register(ends::add)
            h.duringPoll = {
                h.begin()
                claim.currentGestureToken()
                if (throws) error("failed poll")
                false
            }
            h.observer.setEnabled(true)
            h.run()
            assertEquals(ScrollPhaseAvailability.FAILED, h.observer.availability.value)
            assertEquals(listOf(true), ends.map { it.cancelled })
            assertNull(claim.currentGestureToken())
            assertEquals("unavailable", h.properties[MacOSScrollGesturePhases.PHASE_PROPERTY])
            assertEquals(1, h.closes)
            h.observer.setEnabled(true)
            h.duringPoll = {
                h.observer.setEnabled(false)
                true
            }
            h.run()
            assertEquals(2, h.opens)
        }
    }

    @Test
    fun `movement does not republish and terminal history survives rapid contacts`() {
        val h = Harness()
        val ends = mutableListOf<ScrollGestureEnd>()
        val claim =
            h.observer.register {
                assertTrue(h.properties[MacOSScrollGesturePhases.TERMINALS_PROPERTY]!!.contains("${it.id}:"))
                ends.add(it)
            }
        h.observer.setEnabled(true)
        h.duringPoll = {
            repeat(40) {
                h.begin()
                claim.currentGestureToken()
                val before = h.publications.size
                repeat(20) { h.event(2, 0, -1.0, 0.0) }
                assertEquals(before, h.publications.size)
                h.event(4, 0, 0.0, 0.0)
                h.event(4, 0, 0.0, 0.0) // Duplicate terminal is inert.
            }
            h.begin()
            val history = h.properties[MacOSScrollGesturePhases.TERMINALS_PROPERTY]!!.split(';')
            assertEquals(32, history.size)
            assertTrue(history.first().startsWith("9:ended:"))
            assertTrue(history.last().startsWith("40:ended:"))
            assertEquals(40, ends.size)
            h.observer.setEnabled(false)
            true
        }
        h.run()
    }

    @Test
    fun `active publication carries the previous callback cutoff across replacement and restart`() {
        val h = Harness()
        h.observer.setEnabled(true)
        h.duringPoll = {
            h.begin()
            assertEquals("1:active:1000", h.properties[MacOSScrollGesturePhases.PHASE_PROPERTY])
            h.now = 1_960
            h.event(4, 0, 0.0, 0.0)
            h.now = 2_000
            h.begin()
            assertEquals("2:active:2000:1960", h.properties[MacOSScrollGesturePhases.PHASE_PROPERTY])
            h.now = 2_100
            h.begin()
            assertEquals("3:active:2100:2100", h.properties[MacOSScrollGesturePhases.PHASE_PROPERTY])
            val replacementPublications = h.publications.takeLast(3)
            assertEquals(MacOSScrollGesturePhases.TERMINALS_PROPERTY, replacementPublications[0].first)
            assertEquals(MacOSScrollGesturePhases.PHASE_PROPERTY, replacementPublications[1].first)
            assertEquals("3:active:2100:2100", replacementPublications[2].second)
            h.now = 2_200
            h.observer.setEnabled(false)
            h.observer.setEnabled(true)
            true
        }
        h.run()
        h.duringPoll = {
            h.now = 2_300
            h.begin()
            assertEquals("4:active:2300:2200", h.properties[MacOSScrollGesturePhases.PHASE_PROPERTY])
            h.observer.setEnabled(false)
            true
        }
        h.run()
    }

    @Test
    fun `replacement and interrupted taps cancel every registered claimant once`() {
        val h = Harness()
        val first = mutableListOf<ScrollGestureEnd>()
        val second = mutableListOf<ScrollGestureEnd>()
        val a = h.observer.register(first::add)
        val b = h.observer.register(second::add)
        h.observer.setEnabled(true)
        h.duringPoll = {
            h.begin()
            a.currentGestureToken()
            b.currentGestureToken()
            h.begin()
            assertEquals(listOf(1L), first.map { it.id })
            assertEquals(first, second)
            a.currentGestureToken()
            b.close()
            h.interrupted()
            assertEquals(listOf(1L, 2L), first.map { it.id })
            assertEquals(1, second.size)
            assertTrue(first.all { it.cancelled })
            assertNull(a.currentGestureToken())
            h.observer.setEnabled(false)
            true
        }
        h.run()
    }

    @Test
    fun `native setup failures release every acquired resource`() {
        val failurePoints =
            listOf(
                "CGEventTapCreate",
                "CFMachPortCreateRunLoopSource",
                "CFRunLoopAddSource",
                "CGEventTapEnable",
                "none",
            )
        for (failAt in failurePoints) {
            val calls = mutableListOf<String>()
            val api =
                Proxy.newProxyInstance(
                    ApplicationServices::class.java.classLoader,
                    arrayOf(ApplicationServices::class.java),
                ) { _, method, _ ->
                    calls.add(method.name)
                    if (method.name == failAt) {
                        if (failAt == "CFRunLoopAddSource" || failAt == "CGEventTapEnable") error("setup failed")
                        null
                    } else {
                        when (method.name) {
                            "CGPreflightListenEventAccess" -> true
                            "CFRunLoopGetCurrent", "CGEventTapCreate", "CFMachPortCreateRunLoopSource" -> Pointer(1)
                            "CFRunLoopRunInMode" -> 4
                            else -> null
                        }
                    }
                } as ApplicationServices
            val result = runCatching { openScrollPhaseSession(api, Pointer(2), { _, _, _, _ -> }, {}) }
            if (failAt == "none") {
                assertTrue(result.getOrThrow().poll())
                result.getOrThrow().close()
            } else {
                assertTrue(result.isFailure, failAt)
            }
            val expectedReleases =
                when (failAt) {
                    "CGEventTapCreate" -> 0
                    "CFMachPortCreateRunLoopSource" -> 1
                    else -> 2
                }
            assertEquals(expectedReleases, calls.count { it == "CFRelease" }, failAt)
            assertEquals(expectedReleases > 0, "CFMachPortInvalidate" in calls, failAt)
        }
    }

    @Test
    fun `settings distinguish permission failure and environment ownership`() {
        assertTrue("Starting" in swipeNavSettingsDescription(null, ScrollPhaseAvailability.STARTING))
        val denied = swipeNavSettingsDescription(null, ScrollPhaseAvailability.PERMISSION_DENIED)
        assertTrue("Input Monitoring" in denied)
        val failure = swipeNavSettingsDescription(null, ScrollPhaseAvailability.FAILED)
        assertFalse("Input Monitoring" in failure)
        assertTrue("off and on" in failure)
        val envFailure = swipeNavSettingsDescription("true", ScrollPhaseAvailability.FAILED)
        assertTrue("BOSS_BROWSER_SWIPE_NAV=true" in envFailure)
        assertTrue("Release detection stopped" in envFailure)
        assertTrue("Restart BOSS" in envFailure)
        assertFalse("off and on" in envFailure)
        assertFalse("environment" in swipeNavSettingsDescription("typo", ScrollPhaseAvailability.AVAILABLE))
        assertFalse("Input Monitoring" in swipeNavSettingsDescription("false", ScrollPhaseAvailability.DISABLED))
    }

    @Test
    fun `a queued start disabled before setup acquires no native resources`() {
        val h = Harness()
        h.observer.setEnabled(true)
        h.observer.setEnabled(false)
        h.run()
        assertEquals(0, h.opens)
        assertEquals(ScrollPhaseAvailability.DISABLED, h.observer.availability.value)
    }

    @Test
    fun `claims racing native release are either delivered or refused`() {
        val h = Harness()
        val delivered =
            java.util.concurrent.atomic
                .AtomicReference<ScrollGestureEnd?>()
        val claim = h.observer.register { delivered.set(it) }
        h.observer.setEnabled(true)
        h.duringPoll = {
            repeat(30) {
                h.begin()
                delivered.set(null)
                val claimed =
                    java.util.concurrent.atomic
                        .AtomicReference<String?>()
                val start = java.util.concurrent.CountDownLatch(1)
                val claimer =
                    Thread {
                        start.await()
                        claimed.set(claim.currentGestureToken())
                    }
                val releaser =
                    Thread {
                        start.await()
                        h.event(4, 0, 0.0, 0.0)
                    }
                claimer.start()
                releaser.start()
                start.countDown()
                claimer.join(5000)
                releaser.join(5000)
                assertFalse(claimer.isAlive || releaser.isAlive, "claim/release deadlocked")
                if (claimed.get() != null) {
                    assertEquals(claimed.get()!!.substringBefore(":").toLong(), delivered.get()?.id)
                } else {
                    assertNull(delivered.get())
                }
                assertNull(claim.currentGestureToken())
            }
            h.observer.setEnabled(false)
            true
        }
        h.run()
    }
}
