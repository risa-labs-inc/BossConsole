package ai.rever.boss.plugin.repository.remote

import io.github.jan.supabase.realtime.Column
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.serializer.KotlinXSerializer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * #1630 pairing on the client side: once "Published versions are viewable" gates
 * `plugin_versions` on finalized rows, a pending INSERT never reaches a non-owner
 * realtime subscriber -- the finalize UPDATE on the same row is the only signal that
 * a version was published at all. [PluginStoreRealtimeService.onVersionAction] must
 * therefore answer UPDATEs, not just INSERTs, or another user's store silently goes
 * stale at exactly the moment a version goes live.
 *
 * The handler is driven directly with constructed actions, no websocket: the collect
 * loop needs a channel, this does not.
 */
class PluginStoreRealtimeVersionUpdateTest {
    private fun versionRecord(version: String) =
        buildJsonObject {
            put("version", JsonPrimitive(version))
            put("sha256", JsonPrimitive("a".repeat(64)))
            put("jar_size", JsonPrimitive(2048))
        }

    private fun columns() =
        listOf(
            Column("version", "text"),
            Column("sha256", "text"),
            Column("jar_size", "int8"),
        )

    private fun insertAction(version: String) =
        PostgresAction.Insert(
            record = versionRecord(version),
            columns = columns(),
            commitTimestamp = Instant.parse("2026-09-30T00:00:00Z"),
            serializer = KotlinXSerializer(),
        )

    private fun updateAction(version: String) =
        PostgresAction.Update(
            record = versionRecord(version),
            // The pending shape the row had before finalization. Under RLS the
            // old record may not even arrive, so the handler must not need it.
            oldRecord =
                buildJsonObject {
                    put("version", JsonPrimitive(version))
                    put("sha256", JsonPrimitive("pending"))
                    put("jar_size", JsonPrimitive(0))
                },
            columns = columns(),
            commitTimestamp = Instant.parse("2026-09-30T00:00:01Z"),
            serializer = KotlinXSerializer(),
        )

    private fun deleteAction() =
        PostgresAction.Delete(
            oldRecord =
                buildJsonObject {
                    put("version", JsonPrimitive("1.0.0"))
                },
            columns = columns(),
            commitTimestamp = Instant.parse("2026-09-30T00:00:02Z"),
            serializer = KotlinXSerializer(),
        )

    @Test
    fun `a finalize UPDATE emits VersionPublished and requests a refresh`(): Unit =
        runBlocking {
            val service = PluginStoreRealtimeService()
            val refreshed = CompletableDeferred<Unit>()
            service.onRefreshRequested = { refreshed.complete(Unit) }
            val events = Channel<PluginStoreEvent>(Channel.UNLIMITED)
            // UNDISPATCHED so the subscription is registered before emit runs.
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    service.events.collect { events.send(it) }
                }
            try {
                service.onVersionAction(updateAction("2.0.0"))

                val event = withTimeout(2_000) { events.receive() }
                assertIs<PluginStoreEvent.VersionPublished>(event)
                assertEquals("2.0.0", event.version)
                withTimeout(2_000) { refreshed.await() }
            } finally {
                collector.cancel()
                service.dispose()
            }
        }

    @Test
    fun `an INSERT still emits VersionPublished and requests a refresh`(): Unit =
        runBlocking {
            val service = PluginStoreRealtimeService()
            val refreshed = CompletableDeferred<Unit>()
            service.onRefreshRequested = { refreshed.complete(Unit) }
            val events = Channel<PluginStoreEvent>(Channel.UNLIMITED)
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    service.events.collect { events.send(it) }
                }
            try {
                service.onVersionAction(insertAction("1.5.0"))

                val event = withTimeout(2_000) { events.receive() }
                assertIs<PluginStoreEvent.VersionPublished>(event)
                assertEquals("1.5.0", event.version)
                withTimeout(2_000) { refreshed.await() }
            } finally {
                collector.cancel()
                service.dispose()
            }
        }

    @Test
    fun `an UPDATE with no version column still refreshes`(): Unit =
        runBlocking {
            // The row's observable state changed either way; a missing column must not
            // turn the event into a no-op.
            val service = PluginStoreRealtimeService()
            val refreshed = CompletableDeferred<Unit>()
            service.onRefreshRequested = { refreshed.complete(Unit) }
            try {
                service.onVersionAction(
                    PostgresAction.Update(
                        record = buildJsonObject { put("sha256", JsonPrimitive("b".repeat(64))) },
                        oldRecord = buildJsonObject { put("sha256", JsonPrimitive("pending")) },
                        columns = columns(),
                        commitTimestamp = Instant.parse("2026-09-30T00:00:03Z"),
                        serializer = KotlinXSerializer(),
                    ),
                )
                withTimeout(2_000) { refreshed.await() }
            } finally {
                service.dispose()
            }
        }

    @Test
    fun `a DELETE does not emit or refresh`(): Unit =
        runBlocking {
            // Deletions that matter to a subscriber (admin plugin delete) cascade, and the
            // plugins-table DELETE already refreshes; pending-row reaps never reached the
            // subscriber in the first place.
            val service = PluginStoreRealtimeService()
            val refreshed = CompletableDeferred<Unit>()
            service.onRefreshRequested = { refreshed.complete(Unit) }
            val events = Channel<PluginStoreEvent>(Channel.UNLIMITED)
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    service.events.collect { events.send(it) }
                }
            try {
                service.onVersionAction(deleteAction())

                assertNull(withTimeoutOrNull(500) { refreshed.await() })
                assertNull(withTimeoutOrNull(500) { events.receiveCatching().getOrNull() })
            } finally {
                collector.cancel()
                service.dispose()
            }
        }
}
