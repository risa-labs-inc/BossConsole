package ai.rever.boss.swarm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the swarm session journal.
 *
 * The cases worth pinning are the ones an operator would be misled by if they were wrong: a corrupt
 * journal must not read as an empty one, and an absent journal must not read as a fault. Those two
 * are the difference between "you never ran a swarm" and "your swarm history is unreadable", which
 * are very different things to be told.
 */
class SwarmSessionStoreTest {
    private val tempFiles = mutableListOf<File>()

    private fun createTempJournalFile(): File {
        val dir =
            kotlin.io.path
                .createTempDirectory("swarm-store-test")
                .toFile()
        return File(dir, "swarm-sessions.json").also { tempFiles.add(it) }
    }

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.parentFile?.deleteRecursively() }
        tempFiles.clear()
    }

    private fun session(
        id: String,
        task: String = "add a health endpoint",
        worktrees: List<SwarmWorktree> = emptyList(),
    ) = SwarmSession(
        id = id,
        task = task,
        createdAt = 1_700_000_000_000L,
        worktrees = worktrees,
    )

    private fun worktree(
        id: String,
        status: SwarmWorktreeStatus,
    ) = SwarmWorktree(
        id = id,
        branchName = "swarm/session-1-1",
        path = "/tmp/worktree-1",
        agentKind = SwarmAgentKind.CLAUDE_CODE,
        status = status,
    )

    @Test
    fun `a session round-trips through the journal`() {
        val file = createTempJournalFile()
        val store = SwarmSessionStore(file)
        val original =
            session(
                id = "session-1",
                worktrees =
                    listOf(
                        worktree("wt-1", SwarmWorktreeStatus.RUNNING),
                        worktree("wt-2", SwarmWorktreeStatus.DONE),
                    ),
            )

        assertNull(store.save(listOf(original)), "saving a valid journal must succeed")

        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals(original, loaded.first(), "a session must survive the round trip unchanged")
        assertNull(store.fault.value, "a clean round trip must not raise a fault")
    }

    @Test
    fun `the journal records a version so the format can grow`() {
        val file = createTempJournalFile()
        SwarmSessionStore(file).save(listOf(session("session-1")))

        val document = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(1, document.getValue("version").jsonPrimitive.int)
        assertNotNull(document["sessions"], "sessions must be written under a stable key")
    }

    @Test
    fun `an absent journal reads as empty and is not a fault`() {
        val file = createTempJournalFile()
        assertTrue(!file.exists(), "the fixture must start absent")

        val store = SwarmSessionStore(file)

        assertEquals(emptyList(), store.load(), "a first run has no sessions")
        assertNull(store.fault.value, "never having run a swarm is not a fault")
    }

    @Test
    fun `a corrupt journal reports a fault instead of reading as empty`() {
        val file = createTempJournalFile()
        file.writeText("{ this is not a journal")

        val store = SwarmSessionStore(file)
        val loaded = store.load()

        assertEquals(emptyList(), loaded)
        val fault = store.fault.value
        assertNotNull(fault, "an unreadable journal must be reported, not silently empty")
        assertTrue(
            fault is SwarmStoreFault.Unreadable,
            "the fault must name the read failure: $fault",
        )
        assertTrue(
            fault.message.contains(file.path),
            "the fault must name the offending path: ${fault.message}",
        )
    }

    @Test
    fun `upsert replaces a session in place and preserves order`() {
        val file = createTempJournalFile()
        val store = SwarmSessionStore(file)
        store.save(listOf(session("a"), session("b"), session("c")))

        val written = store.upsert(session("b", task = "revised task"))

        assertEquals(listOf("a", "b", "c"), written.map { it.id }, "order must be preserved")
        assertEquals("revised task", written[1].task, "the existing entry must be replaced")
        assertEquals(written, store.load(), "the journal on disk must match what was returned")
    }

    @Test
    fun `upsert appends a session that is not already journalled`() {
        val file = createTempJournalFile()
        val store = SwarmSessionStore(file)
        store.save(listOf(session("a")))

        val written = store.upsert(session("b"))

        assertEquals(listOf("a", "b"), written.map { it.id })
        assertEquals(2, store.load().size)
    }

    @Test
    fun `a store with no file persists nothing and reports no fault`() {
        val store = SwarmSessionStore(storeFile = null)

        assertNull(store.save(listOf(session("a"))), "a store with no file cannot fail to write")
        assertEquals(emptyList(), store.load())
        assertNull(store.fault.value)
        assertNull(store.persistencePath)
    }

    @Test
    fun `a session counts only worktrees that are still doing something`() {
        val session =
            session(
                id = "session-1",
                worktrees =
                    listOf(
                        worktree("wt-1", SwarmWorktreeStatus.SPAWNING),
                        worktree("wt-2", SwarmWorktreeStatus.RUNNING),
                        worktree("wt-3", SwarmWorktreeStatus.DONE),
                        worktree("wt-4", SwarmWorktreeStatus.MERGED),
                    ),
            )

        // Spawning and running are the two states that count as active. Done, merged and discarded
        // are all finished, which is what the status bar's "N running" is meant to mean.
        assertEquals(2, session.activeWorktreeCount)
    }
}
