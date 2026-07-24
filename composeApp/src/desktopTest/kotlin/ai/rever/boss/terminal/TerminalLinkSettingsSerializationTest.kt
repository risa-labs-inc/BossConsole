package ai.rever.boss.terminal

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the persisted terminal-link settings format: every open mode must
 * survive a serialization round-trip (values are stored by name in
 * ~/.boss/terminal-link-settings.json, so renaming or removing an enum value
 * would break existing settings files).
 */
class TerminalLinkSettingsSerializationTest {
    // Mirrors DesktopTerminalLinkSettingsManager's configuration.
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Test
    fun `every open mode round-trips`() {
        for (mode in TerminalLinkOpenMode.entries) {
            val settings = TerminalLinkSettings(openMode = mode)
            val decoded =
                json.decodeFromString<TerminalLinkSettings>(
                    json.encodeToString(TerminalLinkSettings.serializer(), settings),
                )
            assertEquals(settings, decoded, "round-trip failed for $mode")
        }
    }

    @Test
    fun `system default decodes from persisted json by name`() {
        val decoded =
            json.decodeFromString<TerminalLinkSettings>(
                """{"openMode": "SYSTEM_DEFAULT", "existingSplitTarget": "MOST_RECENT_ACTIVE"}""",
            )
        assertEquals(TerminalLinkOpenMode.SYSTEM_DEFAULT, decoded.openMode)
    }

    @Test
    fun `defaults still apply when fields are missing`() {
        val decoded = json.decodeFromString<TerminalLinkSettings>("{}")
        assertEquals(TerminalLinkOpenMode.ALWAYS_ASK, decoded.openMode)
        assertEquals(ExistingSplitTargetMode.MOST_RECENT_ACTIVE, decoded.existingSplitTarget)
    }
}
