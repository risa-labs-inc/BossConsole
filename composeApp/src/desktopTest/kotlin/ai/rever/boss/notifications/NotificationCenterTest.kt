package ai.rever.boss.notifications

import ai.rever.boss.mcp.secrets.captureHostLogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Persistence and concurrency tests for [NotificationCenter], pinning the same contract every
 * small BOSS state file follows: atomic writes, mutex-serialized mutations, forward-coercing
 * reads, and a bounded inbox. Each test runs against a hermetic temp file and restores the
 * singleton to the real file afterwards.
 */
class NotificationCenterTest {
    private lateinit var tempDir: File
    private lateinit var tempFile: File
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("notif-test-").toFile()
        tempFile = File(tempDir, "notifications.json")
        NotificationCenter.resetForTesting(tempFile)
    }

    @AfterTest
    fun tearDown() {
        NotificationCenter.resetForTesting()
        tempDir.deleteRecursively()
    }

    @Test
    fun `post persists and survives a reload, newest first`() =
        runBlocking {
            NotificationCenter.post("First", origin = NotificationOrigin.HOST)
            NotificationCenter.post("Second", "body", NotificationLevel.WARNING, "task-a", NotificationOrigin.HOST)

            NotificationCenter.resetForTesting(tempFile)
            val all = NotificationCenter.notifications.value
            assertEquals(2, all.size)
            assertEquals("Second", all.first().title, "newest first")
            assertEquals(NotificationLevel.WARNING, all.first().level)
            assertEquals("task-a", all.first().source)
            assertEquals(NotificationOrigin.HOST, all.first().origin, "stamped origin survives a reload")
        }

    @Test
    fun `a blank title is rejected`() {
        runBlocking {
            assertFailsWith<IllegalArgumentException> {
                NotificationCenter.post("  ", origin = NotificationOrigin.HOST)
            }
        }
    }

    @Test
    fun `unreadCount and markRead behave`() =
        runBlocking {
            val a = NotificationCenter.post("A", origin = NotificationOrigin.HOST)
            NotificationCenter.post("B", origin = NotificationOrigin.HOST)
            assertEquals(2, NotificationCenter.unreadCount())

            assertTrue(NotificationCenter.markRead(a.id))
            assertEquals(1, NotificationCenter.unreadCount())
            assertFalse(NotificationCenter.markRead(a.id), "already read returns false")
            assertFalse(NotificationCenter.markRead("notif-missing"), "unknown id returns false")
        }

    @Test
    fun `markAllRead reports the number changed`() =
        runBlocking {
            NotificationCenter.post("A", origin = NotificationOrigin.HOST)
            NotificationCenter.post("B", origin = NotificationOrigin.HOST)
            assertEquals(2, NotificationCenter.markAllRead())
            assertEquals(0, NotificationCenter.unreadCount())
            assertEquals(0, NotificationCenter.markAllRead(), "nothing left to change")
        }

    @Test
    fun `clear empties the inbox`() =
        runBlocking {
            NotificationCenter.post("A", origin = NotificationOrigin.HOST)
            assertEquals(1, NotificationCenter.clear())
            assertTrue(NotificationCenter.notifications.value.isEmpty())
            assertEquals(0, NotificationCenter.clear())
        }

    @Test
    fun `the inbox is bounded to MAX_ENTRIES newest entries`() =
        runBlocking {
            var t = 0L
            NotificationCenter.clock = { t++ }
            repeat(NotificationCenter.MAX_ENTRIES + 25) { i ->
                NotificationCenter.post("N$i", origin = NotificationOrigin.HOST)
            }

            val all = NotificationCenter.notifications.value
            assertEquals(NotificationCenter.MAX_ENTRIES, all.size)
            // The most recent post is retained and is first; the earliest are dropped.
            assertEquals("N${NotificationCenter.MAX_ENTRIES + 24}", all.first().title)
        }

    @Test
    fun `concurrent posts all persist without a lost update`() =
        runBlocking {
            val count = 40
            (1..count)
                .map { i ->
                    async(Dispatchers.Default) { NotificationCenter.post("N$i", origin = NotificationOrigin.HOST) }
                }.awaitAll()

            assertEquals(count, NotificationCenter.notifications.value.size)
            val onDisk = json.decodeFromString(NotificationStore.serializer(), tempFile.readText())
            assertEquals(count, onDisk.notifications.size)
        }

    @Test
    fun `a corrupt file loads as an empty inbox rather than throwing`() {
        tempFile.writeText("{ not valid")
        NotificationCenter.resetForTesting(tempFile)
        assertTrue(NotificationCenter.notifications.value.isEmpty())
    }

    @Test
    fun `a corrupt notification file is logged without its message`() {
        val secret = "private-notification-body"
        tempFile.writeText(
            """{"notifications":[{"id":"n1","title":"Build","message":"$secret"""",
        )

        val (_, logged) =
            captureHostLogs {
                NotificationCenter.resetForTesting(tempFile)
            }

        val failure = logged.single { it.message == "Failed to load notifications" }
        assertNull(failure.error, "the decoder exception includes the document and must not be attached")
        assertEquals("JsonDecodingException", failure.data?.get("decodeFailure"))
        for (entry in logged) {
            assertFalse(secret in "${entry.message} ${entry.data} ${entry.error}", "leaked in: $entry")
        }
    }

    @Test
    fun `an unknown level string falls back to INFO`() {
        assertEquals(NotificationLevel.INFO, NotificationLevel.fromString("wat"))
        assertEquals(NotificationLevel.INFO, NotificationLevel.fromString(null))
        assertEquals(NotificationLevel.ERROR, NotificationLevel.fromString("error"))
    }

    @Test
    fun `loading an oversized file keeps only the newest entries`() {
        val entries =
            (0 until NotificationCenter.MAX_ENTRIES + 5).map { index ->
                BossNotification(id = "n$index", title = "N$index", createdAt = index.toLong())
            }
        tempFile.writeText(json.encodeToString(NotificationStore.serializer(), NotificationStore(entries)))
        NotificationCenter.resetForTesting(tempFile)

        assertEquals(NotificationCenter.MAX_ENTRIES, NotificationCenter.notifications.value.size)
        assertEquals(
            "n${NotificationCenter.MAX_ENTRIES + 4}",
            NotificationCenter.notifications.value
                .first()
                .id,
        )
        assertEquals(
            "n5",
            NotificationCenter.notifications.value
                .last()
                .id,
        )
    }

    @Test
    fun `ids remain unique when the clock and random source collide`() =
        runBlocking {
            NotificationCenter.clock = { 1_000L }
            repeat(10) { NotificationCenter.post("N$it", origin = NotificationOrigin.HOST) }
            assertEquals(
                10,
                NotificationCenter.notifications.value
                    .map { it.id }
                    .toSet()
                    .size,
            )
        }

    @Test
    fun `an agent-supplied system source cannot present as system origin`() =
        runBlocking {
            val posted =
                NotificationCenter.post("Migration finished", source = "System", origin = NotificationOrigin.AGENT)

            assertEquals(NotificationOrigin.AGENT, posted.origin)
            assertEquals("agent: System", posted.source, "the label is demoted, not echoed")

            NotificationCenter.resetForTesting(tempFile)
            assertEquals(
                NotificationOrigin.AGENT,
                NotificationCenter.notifications.value
                    .first()
                    .origin,
            )
            assertEquals(
                "agent: System",
                NotificationCenter.notifications.value
                    .first()
                    .source,
            )
        }

    @Test
    fun `an agent post with no label is stamped agent`() =
        runBlocking {
            val posted = NotificationCenter.post("Needs your input", origin = NotificationOrigin.AGENT)

            assertEquals(NotificationCenter.AGENT_SOURCE_PREFIX, posted.source)
            assertEquals(NotificationOrigin.AGENT, posted.origin)
        }

    @Test
    fun `an agent display label is bounded`() =
        runBlocking {
            val posted =
                NotificationCenter.post(
                    "Long label",
                    source = "x".repeat(500),
                    origin = NotificationOrigin.AGENT,
                )

            assertTrue(posted.source.startsWith("${NotificationCenter.AGENT_SOURCE_PREFIX}: "), "prefix kept")
            assertTrue(
                posted.source.length <= "${NotificationCenter.AGENT_SOURCE_PREFIX}: ".length +
                    NotificationCenter.MAX_SOURCE_LABEL_CHARS,
                "label bounded",
            )
        }

    @Test
    fun `the host may label its own notice`() =
        runBlocking {
            val posted =
                NotificationCenter.post("Update available", source = "System", origin = NotificationOrigin.HOST)

            assertEquals(NotificationOrigin.HOST, posted.origin)
            assertEquals("System", posted.source, "host provenance trusts the host's own label")
        }

    @Test
    fun `an entry with no origin loads as agent origin`() {
        // The shape of a pre-provenance file: entries that carry a `source` string but no origin
        // field. Missing provenance must fail closed - it cannot present as a host notice.
        tempFile.writeText(
            """
            {
                "notifications": [
                    {
                        "id": "legacy-1",
                        "title": "Old agent note",
                        "source": "System"
                    }
                ]
            }
            """.trimIndent(),
        )
        NotificationCenter.resetForTesting(tempFile)

        val loaded = NotificationCenter.notifications.value.single()
        assertEquals(NotificationOrigin.AGENT, loaded.origin, "missing provenance fails closed")
        assertEquals("System", loaded.source, "the raw label still loads; the origin field is the trust decision")
    }

    @Test
    fun `an unknown origin value loads as agent with the entry preserved`() {
        // One bad byte must not cost the operator the whole inbox: `ignoreUnknownKeys` covers
        // unknown KEYS only, so an unknown enum VALUE used to throw at decode, and loadSync's
        // catch emptied the list - which the next persist wrote back to disk.
        tempFile.writeText(
            """
            {
                "notifications": [
                    {
                        "id": "bogus-1",
                        "title": "Hand-edited entry",
                        "origin": "BOGUS"
                    }
                ]
            }
            """.trimIndent(),
        )
        NotificationCenter.resetForTesting(tempFile)

        val loaded = NotificationCenter.notifications.value
        assertEquals(1, loaded.size, "the entry survives the decode")
        assertEquals(
            NotificationOrigin.AGENT,
            loaded.single().origin,
            "an unknown origin coerces to the fail-closed default",
        )
        assertEquals("Hand-edited entry", loaded.single().title)

        runBlocking {
            NotificationCenter.post("Posted after the load", origin = NotificationOrigin.HOST)
        }
        NotificationCenter.resetForTesting(tempFile)
        assertEquals(
            2,
            NotificationCenter.notifications.value.size,
            "the next persist does not write the inbox back empty",
        )
    }

    @Test
    fun `an interior newline in an agent label cannot defeat the agent prefix`() =
        runBlocking {
            val posted =
                NotificationCenter.post(
                    "Task finished",
                    source = "ok\nSystem: update ready",
                    origin = NotificationOrigin.AGENT,
                )

            assertEquals(
                "${NotificationCenter.AGENT_SOURCE_PREFIX}: ok System: update ready",
                posted.source,
                "the interior newline is flattened to a space before the prefix",
            )
        }

    @Test
    fun `control characters and line separators are flattened out of an agent label`() =
        runBlocking {
            val swept =
                NotificationCenter.post(
                    "Sweep",
                    source = "a\rb\tc\u2028d\u2029e\u0000f",
                    origin = NotificationOrigin.AGENT,
                )
            assertEquals("${NotificationCenter.AGENT_SOURCE_PREFIX}: a b c d e f", swept.source)

            val blanked =
                NotificationCenter.post(
                    "Nothing left",
                    source = "\n\r\t",
                    origin = NotificationOrigin.AGENT,
                )
            assertEquals(
                NotificationCenter.AGENT_SOURCE_PREFIX,
                blanked.source,
                "a label of only control characters behaves like a blank one",
            )
        }

    // -----------------------------------------------------------------
    // Size bounds live here, so a host publisher meets them as the MCP tool does (#1644 review).
    // -----------------------------------------------------------------

    @Test
    fun `an over-long title from either origin, or an over-long agent message, is refused and stores nothing`(): Unit =
        runBlocking {
            val longTitle = "t".repeat(NotificationCenter.MAX_TITLE_CHARS + 1)
            for (origin in NotificationOrigin.entries) {
                assertFailsWith<IllegalArgumentException>("$origin title") {
                    NotificationCenter.post(longTitle, origin = origin)
                }
            }
            assertFailsWith<IllegalArgumentException>("agent message") {
                val longMessage = "m".repeat(NotificationCenter.MAX_MESSAGE_CHARS + 1)
                NotificationCenter.post("ok", message = longMessage, origin = NotificationOrigin.AGENT)
            }
            assertTrue(NotificationCenter.notifications.value.isEmpty(), "a refused post must store nothing")
        }

    /** A host builds its message from dynamic text; refusing would turn one problem into two. */
    @Test
    fun `an over-long host message is cut to the cap and ends in an ellipsis`(): Unit =
        runBlocking {
            val longMessage = "m".repeat(NotificationCenter.MAX_MESSAGE_CHARS + 50)

            val posted = NotificationCenter.post("Build failed", longMessage, origin = NotificationOrigin.HOST)

            assertEquals(NotificationCenter.MAX_MESSAGE_CHARS, posted.message.length)
            assertTrue(posted.message.endsWith("\u2026"), "a cut message says so")
            assertEquals(
                posted.message,
                NotificationCenter.notifications.value
                    .single()
                    .message,
                "stored as returned",
            )
        }

    @Test
    fun `cutting a host message never splits a surrogate pair`(): Unit =
        runBlocking {
            // The emoji's high surrogate sits exactly where the cut would otherwise fall.
            val prefix = "a".repeat(NotificationCenter.MAX_MESSAGE_CHARS - 2)
            val message = prefix + "\uD83D\uDE00" + "tail"
            val posted = NotificationCenter.post("x", message, origin = NotificationOrigin.HOST)

            assertEquals(prefix + "\u2026", posted.message, "the whole emoji goes rather than half of it")
        }

    @Test
    fun `fields exactly at their limits are accepted`(): Unit =
        runBlocking {
            val posted =
                NotificationCenter.post(
                    "t".repeat(NotificationCenter.MAX_TITLE_CHARS),
                    message = "m".repeat(NotificationCenter.MAX_MESSAGE_CHARS),
                    source = "s".repeat(NotificationCenter.MAX_SOURCE_LABEL_CHARS),
                    origin = NotificationOrigin.HOST,
                )
            // Stored intact, not merely accepted: nothing at its limit is cut or marked.
            assertEquals(NotificationCenter.MAX_TITLE_CHARS, posted.title.length)
            assertEquals("m".repeat(NotificationCenter.MAX_MESSAGE_CHARS), posted.message)
            assertEquals(NotificationCenter.MAX_SOURCE_LABEL_CHARS, posted.source.length)
        }

    /** The check measures what is stored, so whitespace that trim removes cannot tip a label over. */
    @Test
    fun `a host label at the cap once trimmed is accepted and stored trimmed`(): Unit =
        runBlocking {
            val label = "s".repeat(NotificationCenter.MAX_SOURCE_LABEL_CHARS)

            val posted = NotificationCenter.post("x", source = "  $label  ", origin = NotificationOrigin.HOST)

            assertEquals(label, posted.source)
        }

    @Test
    fun `a host label is flattened to one line like an agent's`(): Unit =
        runBlocking {
            val posted = NotificationCenter.post("x", source = "Updater\nSystem", origin = NotificationOrigin.HOST)

            assertEquals("Updater System", posted.source)
        }

    /** An agent's label is cut, because the agent is not ours to fix; a host label that long is a bug. */
    @Test
    fun `an over-long source label is refused from the host and truncated from an agent`(): Unit =
        runBlocking {
            val longLabel = "x".repeat(NotificationCenter.MAX_SOURCE_LABEL_CHARS + 1)

            assertFailsWith<IllegalArgumentException> {
                NotificationCenter.post("Host", source = longLabel, origin = NotificationOrigin.HOST)
            }
            val fromAgent = NotificationCenter.post("Agent", source = longLabel, origin = NotificationOrigin.AGENT)

            val prefix = "${NotificationCenter.AGENT_SOURCE_PREFIX}: "
            assertEquals(prefix + "x".repeat(NotificationCenter.MAX_SOURCE_LABEL_CHARS), fromAgent.source)
        }
}
