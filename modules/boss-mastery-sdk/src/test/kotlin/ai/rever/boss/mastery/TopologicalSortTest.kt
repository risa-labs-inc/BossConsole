package ai.rever.boss.mastery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TopologicalSortTest {
    data class Node(
        val id: String,
        val deps: List<String>,
    )

    /** Helper: sort and return level IDs as sorted string lists for deterministic assertions. */
    private fun sort(nodes: List<Node>): List<List<String>> =
        TopologicalSort
            .sort(nodes, { it.id }, { it.deps })
            .map { level -> level.map { it.id }.sorted() }

    @Test
    fun `linear chain produces three sequential levels`() {
        val nodes =
            listOf(
                Node("a", emptyList()),
                Node("b", listOf("a")),
                Node("c", listOf("b")),
            )
        val levels = sort(nodes)
        assertEquals(3, levels.size)
        assertEquals(listOf("a"), levels[0])
        assertEquals(listOf("b"), levels[1])
        assertEquals(listOf("c"), levels[2])
    }

    @Test
    fun `diamond shape produces three levels with parallel middle`() {
        // a → b → d
        //   ↘ c ↗
        val nodes =
            listOf(
                Node("a", emptyList()),
                Node("b", listOf("a")),
                Node("c", listOf("a")),
                Node("d", listOf("b", "c")),
            )
        val levels = sort(nodes)
        assertEquals(3, levels.size)
        assertEquals(listOf("a"), levels[0])
        assertEquals(listOf("b", "c"), levels[1]) // parallel — sorted alphabetically
        assertEquals(listOf("d"), levels[2])
    }

    @Test
    fun `independent nodes produce a single level`() {
        val nodes =
            listOf(
                Node("a", emptyList()),
                Node("b", emptyList()),
                Node("c", emptyList()),
            )
        val levels = sort(nodes)
        assertEquals(1, levels.size)
        assertEquals(listOf("a", "b", "c"), levels[0])
    }

    @Test
    fun `empty node list returns empty result`() {
        assertEquals(0, sort(emptyList()).size)
    }

    @Test
    fun `cycle detection throws IllegalArgumentException`() {
        // a → b → c → a  (cycle)
        val nodes =
            listOf(
                Node("a", listOf("c")),
                Node("b", listOf("a")),
                Node("c", listOf("b")),
            )
        assertFailsWith<IllegalArgumentException> {
            sort(nodes)
        }
    }

    @Test
    fun `unknown dependency throws IllegalArgumentException`() {
        val nodes =
            listOf(
                Node("a", listOf("does-not-exist")),
            )
        assertFailsWith<IllegalArgumentException> {
            sort(nodes)
        }
    }
}
