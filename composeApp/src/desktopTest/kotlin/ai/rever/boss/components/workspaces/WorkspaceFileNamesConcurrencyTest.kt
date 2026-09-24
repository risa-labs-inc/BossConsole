package ai.rever.boss.components.workspaces

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the thread-safety contract for the in-memory `id -> fileName` map the workspace manager
 * keeps to honour the in-memory-migration rule that pre-existing Spaces keep saving to their
 * original file (AGENTS.md "The path is the id").
 *
 * The regression: the field was declared `private val loadedFileNames = mutableMapOf<String, String>()`,
 * a plain `HashMap`. It is written from the init load on `Dispatchers.IO` (line 311) and
 * read/written from `fileNameFor`, `saveCurrentWorkspace`, `saveLastSessionRecord`,
 * `saveLastSessionBlocking`, `importWorkspace`, `deleteWorkspaceById` and `renameWorkspaceById`
 * - many of which run on `Dispatchers.Main`. A plain HashMap is not thread-safe; concurrent
 * puts can lose entries, corrupt the bucket table, and trigger the documented
 * HashMap-infinite-loop on resize.
 *
 * The fix is `ConcurrentHashMap<String, String>()`. This test pins both the field type and the
 * behavioural property that the field survives a concurrent put/get workload - a plain
 * HashMap would corrupt under that load, a `ConcurrentHashMap` retains every entry.
 *
 * `WorkspaceManager` is a class with a private field, so the test instantiates one and reads
 * the field via reflection to verify the type without leaking access into production code.
 */
class WorkspaceFileNamesConcurrencyTest {
    @Test
    fun `loadedFileNames is a ConcurrentHashMap`() {
        val field = WorkspaceManager::class.java.getDeclaredField("loadedFileNames")
        field.isAccessible = true
        val instance = field.get(WorkspaceManager())
        assertTrue(
            instance is ConcurrentHashMap<*, *>,
            "loadedFileNames must be a ConcurrentHashMap so concurrent IO and Main " +
                "callers do not corrupt the bucket table - got ${instance?.javaClass?.name}",
        )
    }

    /**
     * A concurrent put/get workload. A plain `HashMap` corrupts under it - the JDK documents
     * this directly. With `ConcurrentHashMap`, every put survives and every get returns the
     * value the writer set.
     */
    @Test
    fun `concurrent put and get on the file-name map retains every entry`() {
        val field = WorkspaceManager::class.java.getDeclaredField("loadedFileNames")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(WorkspaceManager()) as ConcurrentHashMap<String, String>

        // Keep the count well under the 10 the manager actually deals with; the test is about
        // corruption, not scaling.
        val writes = 100
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)

        val writer =
            Thread {
                start.await()
                for (i in 0 until writes) {
                    map["ws-$i"] = "Workspace_$i.json"
                }
                done.countDown()
            }
        val reader =
            Thread {
                start.await()
                for (i in 0 until writes) {
                    // Reader does no work other than the read - the contract is that every
                    // put survives a concurrent read, asserted after `done.await()`.
                    @Suppress("UNUSED_VARIABLE")
                    val value = map["ws-$i"]
                }
                done.countDown()
            }

        writer.start()
        reader.start()
        start.countDown()
        // Bound the wait so a thread that fails to start on a busy runner cannot pin
        // the whole desktopTest suite - Windows CI runs tests sequentially
        // (`parallel=false`) and an unbounded await blocks every later test until the
        // GitHub runner loses communication. The actual work is 100 map ops per
        // thread, well under a second even on the slowest host.
        assertTrue(
            done.await(30, TimeUnit.SECONDS),
            "the writer and reader threads did not complete in time - one of them " +
                "may have failed to start on this runner",
        )

        // Every entry the writer put is in the map at the end - a plain HashMap would
        // lose entries under this exact workload. This is the actual thread-safety contract;
        // asserting on a "reader observed an in-flight put" counter would be timing-dependent
        // (a fast host can let the writer finish before the reader does its first get) and
        // would not be a meaningful contract check on its own.
        for (i in 0 until writes) {
            assertEquals(
                "Workspace_$i.json",
                map["ws-$i"],
                "every put must survive concurrent reads - entry ws-$i is missing",
            )
        }
    }
}
