package ai.rever.boss.sharing

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppInputDispatcherTest {
    private class Sink : AppScopedInputSink {
        var available = true
        var released = 0
        val events = mutableListOf<AppInputEvent>()

        override fun isAvailable() = available

        override fun apply(event: AppInputEvent): Boolean {
            events.add(event)
            return true
        }

        override fun releaseAll() {
            released++
        }
    }

    private class Fixture : AutoCloseable {
        val session = UUID.randomUUID().toString()
        val generation = UUID.randomUUID().toString()
        val window = UUID.randomUUID().toString()
        val peer = UUID.randomUUID().toString()
        var now = 1000L
        val sink = Sink()
        val dispatcher = AppInputDispatcher(session, generation) { now }
        val lease = AppControlLease(UUID.randomUUID().toString(), peer, generation, 2000L)

        init {
            dispatcher.register(AppInputTarget(window, generation, 1L, sink))
            check(dispatcher.installLease(lease))
        }

        fun event(sequence: Long = 1) =
            AppInputEnvelope(
                session,
                generation,
                window,
                1L,
                lease.id,
                peer,
                sequence,
                AppInputEvent.Pointer("down", 0.4, 0.5, 0),
            )

        override fun close() = dispatcher.close()
    }

    @Test fun `authority and identity fields cannot be supplied by viewer`() {
        Fixture().use { f ->
            val valid = f.event()
            listOf(
                valid.copy(sessionId = "other"),
                valid.copy(generation = "other"),
                valid.copy(windowId = "other"),
                valid.copy(geometryRevision = 2),
                valid.copy(leaseId = "other"),
                valid.copy(peerId = "other"),
                valid.copy(sequence = 0),
            ).forEach { assertFalse(f.dispatcher.dispatch(f.peer, it)) }
            assertFalse(f.dispatcher.dispatch("other", valid))
            assertTrue(f.dispatcher.dispatch(f.peer, valid))
            assertFalse(f.dispatcher.dispatch(f.peer, valid))
            assertEquals(1, f.sink.events.size)
        }
    }

    @Test fun `expiry and local takeover release input and retired lease cannot return`() {
        Fixture().use { f ->
            assertTrue(f.dispatcher.dispatch(f.peer, f.event()))
            val before = f.sink.released
            f.now = 2001
            assertFalse(f.dispatcher.dispatch(f.peer, f.event(2)))
            assertTrue(f.sink.released > before)
            assertFalse(f.dispatcher.installLease(f.lease.copy(expiresAtMillis = 3000)))
            val next = f.lease.copy(id = UUID.randomUUID().toString(), expiresAtMillis = 3000)
            assertTrue(f.dispatcher.installLease(next))
            f.dispatcher.revoke()
            assertFalse(f.dispatcher.installLease(next))
        }
    }

    @Test fun `geometry replacement and unavailable target release held events`() {
        Fixture().use { f ->
            assertTrue(f.dispatcher.dispatch(f.peer, f.event()))
            val before = f.sink.released
            f.dispatcher.register(AppInputTarget(f.window, f.generation, 2L, f.sink))
            assertTrue(f.sink.released > before)
            assertFalse(f.dispatcher.dispatch(f.peer, f.event(2)))
            assertTrue(f.dispatcher.dispatch(f.peer, f.event(2).copy(geometryRevision = 2)))
            f.sink.available = false
            assertFalse(f.dispatcher.dispatch(f.peer, f.event(3).copy(geometryRevision = 2)))
            f.dispatcher.remove(f.window)
            assertFalse(f.dispatcher.dispatch(f.peer, f.event(4)))
        }
    }

    @Test fun `transient suspension releases input and verified recovery retains replay protection`() {
        Fixture().use { f ->
            assertTrue(f.dispatcher.dispatch(f.peer, f.event()))
            val before = f.sink.released
            f.dispatcher.suspend()
            assertTrue(f.sink.released > before)
            assertFalse(f.dispatcher.dispatch(f.peer, f.event(2)))
            assertTrue(f.dispatcher.installLease(f.lease))
            assertFalse(f.dispatcher.dispatch(f.peer, f.event()))
            assertTrue(f.dispatcher.dispatch(f.peer, f.event(2)))
            f.dispatcher.suspend()
            f.dispatcher.revoke()
            assertFalse(f.dispatcher.installLease(f.lease))
        }
    }

    @Test fun `malformed coordinates unsupported keys and oversized frames are refused`() {
        assertNull(parseAppInput("x".repeat(16_385)))
        assertNull(parseAppInput("{}"))
        assertFalse(validAppInputEvent(AppInputEvent.Pointer("down", Double.NaN, 0.5, 0)))
        assertFalse(validAppInputEvent(AppInputEvent.Pointer("down", 0.5, 1.1, 0)))
        assertFalse(validAppInputEvent(AppInputEvent.Wheel(0.5, 0.5, Double.POSITIVE_INFINITY, 0.0)))
        assertFalse(validAppInputEvent(AppInputEvent.Key("down", "LaunchMail", "", false, false, false, false)))
        assertTrue(validAppInputEvent(AppInputEvent.Key("down", "ArrowLeft", "ArrowLeft", false, false, false, false)))
    }

    @Test fun `keyboard survives popup geometry updates while coordinate input and future revisions are rejected`() {
        Fixture().use { f ->
            f.dispatcher.register(AppInputTarget(f.window, f.generation, 2L, f.sink))
            val key = f.event().copy(event = AppInputEvent.Key("down", "KeyA", "a", false, false, false, false))
            assertTrue(f.dispatcher.dispatch(f.peer, key))
            assertFalse(f.dispatcher.dispatch(f.peer, key))
            assertFalse(f.dispatcher.dispatch(f.peer, key.copy(sequence = 2, geometryRevision = 3)))
            assertFalse(f.dispatcher.dispatch(f.peer, f.event(2)))
            f.sink.available = false
            assertFalse(f.dispatcher.dispatch(f.peer, key.copy(sequence = 2)))
        }
    }

    @Test fun `wire protocol parser delivers valid control and refuses wrong protocol`() {
        Fixture().use { f ->
            val json =
                """
                {"protocol":"boss-app-share/1","sessionId":"${f.session}","generation":"${f.generation}",
                "windowId":"${f.window}","geometryRevision":1,"leaseId":"${f.lease.id}","peerId":"${f.peer}",
                "sequence":1,"event":{"type":"key","action":"down","code":"ArrowLeft","key":"ArrowLeft"}}
                """.trimIndent()
            assertTrue(f.dispatcher.dispatchJson(f.peer, json))
            assertFalse(f.dispatcher.dispatchJson(f.peer, json))
            assertNull(parseAppInput(json.replace("boss-app-share/1", "boss-app-share/0")))
            assertEquals("ArrowLeft", (f.sink.events.single() as AppInputEvent.Key).code)
        }
    }

    @Test fun `window recovery retains lease and identity checks without requiring pixel coordinates`() {
        Fixture().use { f ->
            val json =
                """
                {"protocol":"boss-app-share/1","sessionId":"${f.session}","generation":"${f.generation}",
                "windowId":"${f.window}","geometryRevision":1,"leaseId":"${f.lease.id}","peerId":"${f.peer}",
                "sequence":1,"event":{"type":"window","action":"restore"}}
                """.trimIndent()
            f.dispatcher.register(AppInputTarget(f.window, f.generation, 2L, f.sink))
            assertTrue(f.dispatcher.dispatchJson(f.peer, json))
            assertFalse(f.dispatcher.dispatchJson(f.peer, json))
            assertEquals(AppInputEvent.Window("restore"), f.sink.events.single())
            assertNull(parseAppInput(json.replace("restore", "start-capture")))
            val next = f.event(2).copy(event = AppInputEvent.Window("exit-fullscreen"))
            assertFalse(f.dispatcher.dispatch(f.peer, next.copy(geometryRevision = 0)))
            assertFalse(f.dispatcher.dispatch(f.peer, next.copy(geometryRevision = 3)))
            assertFalse(f.dispatcher.dispatch(f.peer, next.copy(windowId = UUID.randomUUID().toString())))
            assertFalse(f.dispatcher.dispatch(UUID.randomUUID().toString(), next))
            assertTrue(f.dispatcher.dispatch(f.peer, next))
            f.now = f.lease.expiresAtMillis
            assertFalse(f.dispatcher.dispatch(f.peer, next.copy(sequence = 3)))
        }
    }

    @Test fun `ordinary window commands require current geometry unlike recovery`() {
        Fixture().use { f ->
            f.dispatcher.register(AppInputTarget(f.window, f.generation, 2L, f.sink))
            for ((index, action) in listOf("minimize", "maximize", "unmaximize", "close").withIndex()) {
                val event = f.event(index.toLong() + 1).copy(event = AppInputEvent.Window(action))
                assertFalse(f.dispatcher.dispatch(f.peer, event.copy(geometryRevision = 1)))
                assertTrue(f.dispatcher.dispatch(f.peer, event.copy(geometryRevision = 2)))
            }
            assertEquals(4, f.sink.events.size)
            f.dispatcher.revoke()
            assertFalse(f.dispatcher.dispatch(f.peer, f.event(5).copy(event = AppInputEvent.Window("close"))))
        }
    }

    @Test fun `switching selected windows releases old target and flooding revokes lease`() {
        Fixture().use { f ->
            assertTrue(f.dispatcher.dispatch(f.peer, f.event()))
            val next = UUID.randomUUID().toString()
            val nextSink = Sink()
            f.dispatcher.register(AppInputTarget(next, f.generation, 1, nextSink))
            val before = f.sink.released
            assertTrue(f.dispatcher.dispatch(f.peer, f.event(2).copy(windowId = next)))
            assertTrue(f.sink.released > before)
            for (sequence in 3L..241L) f.dispatcher.dispatch(f.peer, f.event(sequence).copy(windowId = next))
            assertTrue(nextSink.released > 0)
            f.now += 1000
            assertFalse(f.dispatcher.dispatch(f.peer, f.event(242)))
        }
    }

    @Test
    fun `capture visibility permits background windows but rejects hidden and minimized surfaces`() {
        checkCaptureVisibility(displayable = true, showing = true, minimized = false)
        assertFailsWith<IllegalStateException> { checkCaptureVisibility(false, true, false) }
        assertFailsWith<IllegalStateException> { checkCaptureVisibility(true, false, false) }
        assertFailsWith<IllegalStateException> { checkCaptureVisibility(true, true, true) }
    }

    @Test fun `exact capture matching refuses missing duplicated or foreign process identities`() {
        assertEquals(1, exactCaptureWindowIndex(listOf(1L to 50L, 2L to 50L), 2, 50))
        assertNull(exactCaptureWindowIndex(listOf(2L to 51L), 2, 50))
        assertNull(exactCaptureWindowIndex(listOf(2L to 50L, 2L to 50L), 2, 50))
        assertNull(exactCaptureWindowIndex(listOf(2L to 50L, 2L to 51L), 2, 50))
        assertNull(exactCaptureWindowIndex(emptyList(), 2, 50))
    }
}
