package ai.rever.boss.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The startup load **merges** with what is already recorded, it does not replace it.
 *
 * `RecentFilesManager.init` launches the load, and `recordFileOpen` can land while that read is
 * still in flight - which is the normal case, not an edge case, because restoring a project opens
 * editors immediately. The load used to assign the decoded list straight into the recorded flow,
 * so a file opened during startup was dropped from the list *and* from the save that had been
 * scheduled for it: the file the user had just opened was the one file that never showed up under
 * Recent.
 *
 * Kept pure and separate from the singleton for the same reason as [visibleFiles]: the rule is
 * about list contents, and driving it through the object would mean driving its file I/O.
 */
class RecentFilesMergeTest {
    private fun file(
        path: String,
        lastOpened: Long,
    ) = RecentFile(path = path, name = path.substringAfterLast('/'), lastOpened = lastOpened)

    private val fromDisk = file("/project/Old.kt", lastOpened = 100)

    @Test
    fun `a file recorded while the load was in flight survives the load`() {
        val openedDuringStartup = file("/project/JustOpened.kt", lastOpened = 500)

        val merged = mergeRecorded(loaded = listOf(fromDisk), recorded = listOf(openedDuringStartup), max = 20)

        assertTrue(openedDuringStartup in merged, "the in-flight open must not be discarded by the load")
        assertTrue(fromDisk in merged, "the persisted history must not be discarded either")
    }

    @Test
    fun `the loaded list is kept when nothing was recorded yet`() {
        // The ordinary case: load wins the race, nothing to merge with.
        val loaded = listOf(file("/a.kt", 300), fromDisk)

        assertEquals(loaded, mergeRecorded(loaded = loaded, recorded = emptyList(), max = 20))
    }

    @Test
    fun `a path on both sides appears once, with the later open`() {
        val stale = file("/project/Same.kt", lastOpened = 100)
        val justOpened = file("/project/Same.kt", lastOpened = 900)

        val merged = mergeRecorded(loaded = listOf(stale), recorded = listOf(justOpened), max = 20)

        assertEquals(listOf(justOpened), merged)
    }

    @Test
    fun `the later open wins regardless of which side it arrived from`() {
        // Guards against a merge that just prefers `recorded`: a clock-skewed or replayed record
        // must not be able to push a genuinely newer persisted entry back in time.
        val newerOnDisk = file("/project/Same.kt", lastOpened = 900)
        val olderInMemory = file("/project/Same.kt", lastOpened = 100)

        assertEquals(
            listOf(newerOnDisk),
            mergeRecorded(loaded = listOf(newerOnDisk), recorded = listOf(olderInMemory), max = 20),
        )
    }

    @Test
    fun `the result is ordered newest first`() {
        val oldest = file("/oldest.kt", 1)
        val middle = file("/middle.kt", 2)
        val newest = file("/newest.kt", 3)

        val merged = mergeRecorded(loaded = listOf(oldest, newest), recorded = listOf(middle), max = 20)

        assertEquals(listOf(newest, middle, oldest), merged)
    }

    @Test
    fun `the cap is applied after merging, dropping the oldest`() {
        // Applying it per-side would let a full disk list crowd out a fresher in-memory open.
        val loaded = (1..20).map { file("/disk/$it.kt", lastOpened = it.toLong()) }
        val justOpened = file("/project/JustOpened.kt", lastOpened = 999)

        val merged = mergeRecorded(loaded = loaded, recorded = listOf(justOpened), max = 20)

        assertEquals(20, merged.size)
        assertEquals(justOpened, merged.first())
        assertTrue(merged.none { it.path == "/disk/1.kt" }, "the oldest entry is the one dropped")
    }

    @Test
    fun `merging two empty lists is empty, not a crash`() {
        assertTrue(mergeRecorded(loaded = emptyList(), recorded = emptyList(), max = 20).isEmpty())
    }
}
