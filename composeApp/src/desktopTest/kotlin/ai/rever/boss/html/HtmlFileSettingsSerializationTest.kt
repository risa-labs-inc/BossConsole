package ai.rever.boss.html

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the persisted html-file settings format: every open mode must
 * survive a serialization round-trip.
 */
class HtmlFileSettingsSerializationTest {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Test
    fun `every open mode round-trips`() {
        for (mode in HtmlFileOpenMode.entries) {
            val settings = HtmlFileSettings(openMode = mode)
            val decoded =
                json.decodeFromString<HtmlFileSettings>(
                    json.encodeToString(HtmlFileSettings.serializer(), settings),
                )
            assertEquals(settings, decoded, "round-trip failed for $mode")
        }
    }

    @Test
    fun `open mode decodes from persisted json by name`() {
        val decoded =
            json.decodeFromString<HtmlFileSettings>(
                """{"openMode": "BROWSER"}""",
            )
        assertEquals(HtmlFileOpenMode.BROWSER, decoded.openMode)
    }

    @Test
    fun `defaults still apply when fields are missing`() {
        val decoded = json.decodeFromString<HtmlFileSettings>("{}")
        assertEquals(HtmlFileOpenMode.ALWAYS_ASK, decoded.openMode)
    }
}
