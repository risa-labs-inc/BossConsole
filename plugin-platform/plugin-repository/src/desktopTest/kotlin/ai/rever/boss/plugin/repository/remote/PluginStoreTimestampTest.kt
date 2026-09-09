package ai.rever.boss.plugin.repository.remote

import kotlin.test.Test
import kotlin.test.assertEquals

class PluginStoreTimestampTest {
    @Test
    fun `parses strict ISO 8601 timestamps`() {
        // Given a strict ISO 8601 string from typical JSON responses
        val timestamp = "2024-03-21T15:32:00.123Z"

        // When parsed
        val millis = PluginStoreClient.parseTimestamp(timestamp)

        // Then it resolves correctly (1711035120123)
        assertEquals(1711035120123L, millis)
    }

    @Test
    fun `parses Postgres space-separated timestamps`() {
        // Given a Postgres/Supabase style timestamp without T
        val timestamp = "2024-03-21 15:32:00.123Z"

        // When parsed
        val millis = PluginStoreClient.parseTimestamp(timestamp)

        // Then it resolves correctly (1711035120123)
        assertEquals(1711035120123L, millis)
    }

    @Test
    fun `parses timestamps missing timezone indicator`() {
        // Given a Postgres style timestamp missing the Z timezone specifier
        val timestamp = "2024-03-21 15:32:00.123"

        // When parsed
        val millis = PluginStoreClient.parseTimestamp(timestamp)

        // Then it resolves correctly by assuming UTC (Z)
        assertEquals(1711035120123L, millis)
    }

    @Test
    fun `parses timestamps with explicit offset`() {
        // Given a timestamp with a timezone offset
        val timestamp = "2024-03-21 15:32:00.123+00:00"

        // When parsed
        val millis = PluginStoreClient.parseTimestamp(timestamp)

        // Then it resolves correctly
        assertEquals(1711035120123L, millis)
    }

    @Test
    fun `returns 0 for blank input`() {
        assertEquals(0L, PluginStoreClient.parseTimestamp(""))
        assertEquals(0L, PluginStoreClient.parseTimestamp("   "))
    }

    @Test
    fun `returns 0 for invalid input rather than crashing`() {
        // When a completely invalid string is provided
        val millis = PluginStoreClient.parseTimestamp("not-a-timestamp")

        // Then it gracefully returns 0
        assertEquals(0L, millis)
    }
}
