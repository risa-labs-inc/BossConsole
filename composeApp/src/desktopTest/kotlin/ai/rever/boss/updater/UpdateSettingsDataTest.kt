package ai.rever.boss.updater

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateSettingsDataTest {
    @Test
    fun `older settings default last seen release to null`() {
        val settings =
            Json.decodeFromString(
                UpdateSettingsData.serializer(),
                """
                {
                    "autoCheckEnabled": true,
                    "checkIntervalHours": 6,
                    "includePreReleases": false,
                    "lastDismissedVersion": null
                }
                """.trimIndent(),
            )

        assertNull(settings.lastSeenReleaseVersion)
    }

    @Test
    fun `last seen release version survives serialization`() {
        val original = UpdateSettingsData(lastSeenReleaseVersion = "9.5.8")

        val encoded = Json.encodeToString(UpdateSettingsData.serializer(), original)
        val restored = Json.decodeFromString(UpdateSettingsData.serializer(), encoded)

        assertEquals("9.5.8", restored.lastSeenReleaseVersion)
    }
}
