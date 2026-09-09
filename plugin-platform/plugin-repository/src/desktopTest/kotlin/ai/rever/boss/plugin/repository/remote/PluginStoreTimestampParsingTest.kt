package ai.rever.boss.plugin.repository.remote

import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * BossConsole#337: `PluginDetailResponse.toPluginInfo()`'s `publishedAt` used to be a stub that
 * always returned `0L` regardless of what `updatedAt` held, so every plugin fetched from the
 * store showed "Last Updated" as the Unix epoch in the Toolbox UI.
 *
 * Exercised through the real client's `toPluginInfo()`, not a lookalike parser, matching this
 * file's own convention next to it (`PluginStoreResponseDecodingTest`) - the property under test
 * is that the real response type produces a real timestamp.
 */
class PluginStoreTimestampParsingTest {
    private fun responseWithUpdatedAt(updatedAt: String) =
        PluginDetailResponse(
            id = "0f6a1c62-0000-4000-8000-000000000001",
            pluginId = "ai.rever.boss.plugin.dynamic.example",
            displayName = "Example",
            description = "",
            authorName = "RISA Labs",
            type = "tab",
            apiVersion = "1.0",
            verified = true,
            updatedAt = updatedAt,
        )

    @Test
    fun `an ISO-8601 timestamp with a Z offset parses to real epoch millis`() {
        val info = responseWithUpdatedAt("2024-05-12T14:30:00Z").toPluginInfo()

        assertEquals(1715524200000L, info.publishedAt)
    }

    @Test
    fun `an ISO-8601 timestamp with a numeric offset parses too`() {
        val info = responseWithUpdatedAt("2024-05-12T14:30:00+00:00").toPluginInfo()

        assertEquals(1715524200000L, info.publishedAt)
    }

    @Test
    fun `a blank updatedAt falls back to 0 rather than throwing`() {
        val info = responseWithUpdatedAt("").toPluginInfo()

        assertEquals(0L, info.publishedAt)
    }

    @Test
    fun `a malformed timestamp falls back to 0 rather than throwing`() {
        val info = responseWithUpdatedAt("not-a-timestamp").toPluginInfo()

        assertEquals(0L, info.publishedAt)
    }

    @Test
    fun `list and detail conversions preserve offsets and fractional precision`() {
        val timestamps =
            listOf(
                "2024-05-12T14:30:00.123456789Z",
                "2024-05-12T20:00:00.123456789+05:30",
                "2024-05-12T07:30:00.123456789-07:00",
                "2024-05-12 14:30:00.123456789+00",
                "2024-05-12 14:30:00.123456789",
                "2024-05-12t14:30:00.123456789z",
                " 2024-05-12T14:30:00.123456789Z ",
            )
        timestamps.forEach { timestamp ->
            assertEquals(1715524200123L, responseWithUpdatedAt(timestamp).toPluginInfo().publishedAt, timestamp)
            assertEquals(1715524200123L, listItem(timestamp).toPluginInfo().publishedAt, timestamp)
        }
    }

    @Test
    fun `invalid calendar values and absent dates fall back in both conversions`() {
        listOf("", "   ", "not-a-timestamp", "2024-02-30T14:30:00Z", "2024-05-12T14:30:00+25:00").forEach { timestamp ->
            assertEquals(0L, responseWithUpdatedAt(timestamp).toPluginInfo().publishedAt, timestamp)
            assertEquals(0L, listItem(timestamp).toPluginInfo().publishedAt, timestamp)
        }
        assertEquals(0L, listItem().toPluginInfo().publishedAt)
    }

    @Test
    fun `detail uses the matching release date instead of admin metadata update time`() {
        val response =
            responseWithUpdatedAt("2024-05-13T14:30:00Z").copy(
                latestVersion = "2.0.0",
                versions =
                    listOf(
                        VersionInfo(id = "older", version = "1.0.0", publishedAt = "2023-05-12T14:30:00Z"),
                        VersionInfo(id = "latest", version = "2.0.0", publishedAt = "2024-05-12T14:30:00Z"),
                    ),
            )
        assertEquals(1715524200000L, response.toPluginInfo().publishedAt)
    }

    @Test
    fun `detail falls back to metadata time when the release date is unavailable`() {
        val response = responseWithUpdatedAt("2024-05-12T14:30:00Z").copy(latestVersion = "2.0.0")
        val versionLists =
            listOf(
                emptyList(),
                listOf(VersionInfo(id = "older", version = "1.0.0", publishedAt = "2023-05-12T14:30:00Z")),
                listOf(VersionInfo(id = "latest", version = "2.0.0", publishedAt = "")),
            )
        versionLists.forEach { versions ->
            assertEquals(1715524200000L, response.copy(versions = versions).toPluginInfo().publishedAt)
        }
    }

    @Test
    fun `offset-less timestamps use UTC even on a non-UTC host`() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
            val timestamp = "2024-05-12 14:30:00.123"
            assertEquals(1715524200123L, responseWithUpdatedAt(timestamp).toPluginInfo().publishedAt)
            assertEquals(1715524200123L, listItem(timestamp).toPluginInfo().publishedAt)
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    private fun listItem(updatedAt: String = "") =
        PluginListItem(
            id = "example",
            pluginId = "ai.rever.boss.plugin.dynamic.example",
            displayName = "Example",
            description = "",
            author = "RISA Labs",
            type = "tab",
            apiVersion = "1.0",
            verified = true,
            updatedAt = updatedAt,
        )
}
