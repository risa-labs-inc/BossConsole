package ai.rever.boss.search

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandSearchHistoryTest {
    @BeforeTest
    fun setUp() {
        CommandSearchHistoryRegistry.clear()
    }

    @Test
    fun `addQuery stores search query in history`() {
        CommandSearchHistoryRegistry.addQuery("open file")

        val queries = CommandSearchHistoryRegistry.getRecentQueries()
        assertEquals(1, queries.size)
        assertEquals("open file", queries[0].query)
        assertFalse(queries[0].isFavorite)
    }

    @Test
    fun `addQuery deduplicates identical queries and promotes to top`() {
        CommandSearchHistoryRegistry.addQuery("git status")
        CommandSearchHistoryRegistry.addQuery("open settings")
        CommandSearchHistoryRegistry.addQuery("git status")

        val queries = CommandSearchHistoryRegistry.getRecentQueries()
        assertEquals(2, queries.size)
        assertEquals("git status", queries[0].query)
        assertEquals("open settings", queries[1].query)
    }

    @Test
    fun `toggleFavorite marks query as favorite`() {
        CommandSearchHistoryRegistry.addQuery("build desktop")
        val isFav = CommandSearchHistoryRegistry.toggleFavorite("build desktop")

        assertTrue(isFav)
        val favorites = CommandSearchHistoryRegistry.getFavorites()
        assertEquals(1, favorites.size)
        assertEquals("build desktop", favorites[0].query)
    }

    @Test
    fun `clearNonFavorites preserves favorite queries`() {
        CommandSearchHistoryRegistry.addQuery("temp query")
        CommandSearchHistoryRegistry.addQuery("favorite query")
        CommandSearchHistoryRegistry.toggleFavorite("favorite query")

        CommandSearchHistoryRegistry.clearNonFavorites()

        val remaining = CommandSearchHistoryRegistry.getRecentQueries()
        assertEquals(1, remaining.size)
        assertEquals("favorite query", remaining[0].query)
    }

    @Test
    fun `ignores queries shorter than 2 characters`() {
        CommandSearchHistoryRegistry.addQuery("a")
        CommandSearchHistoryRegistry.addQuery(" ")

        val queries = CommandSearchHistoryRegistry.getRecentQueries()
        assertEquals(0, queries.size)
    }
}
