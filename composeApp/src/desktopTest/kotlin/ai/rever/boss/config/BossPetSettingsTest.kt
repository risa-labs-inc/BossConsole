package ai.rever.boss.config

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the two things about the pet setting that a bad value could break: how the on/off string is
 * parsed, and whether an old or partial stored file still decodes.
 *
 * Kept to the pure parse function and the serializer, not the singleton manager: [BossPetSettings]
 * is decoded ahead of installed builds every time the model gains a field, so tolerating an
 * unexpected shape is the property that matters, and it can be pinned without touching process
 * globals.
 */
class BossPetSettingsTest {
    // Matches the manager's own decoder: unknown keys tolerated, defaults written.
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Test
    fun `explicit off values parse to false`() {
        for (v in listOf("off", "false", "0", "no", "OFF", " No ")) {
            assertEquals(false, parseBossPetEnabled(v), "'$v' should parse to false")
        }
    }

    @Test
    fun `explicit on values parse to true`() {
        for (v in listOf("on", "true", "1", "yes", "ON", " Yes ")) {
            assertEquals(true, parseBossPetEnabled(v), "'$v' should parse to true")
        }
    }

    @Test
    fun `an unparseable value is no opinion, not off`() {
        // The same rule swipe-nav follows: a typo must not silently hide the feature.
        assertNull(parseBossPetEnabled("maybe"))
        assertNull(parseBossPetEnabled(""))
        assertNull(parseBossPetEnabled(null))
    }

    @Test
    fun `the default is off with no remembered position`() {
        val d = BossPetSettings()
        assertFalse(d.enabled)
        assertNull(d.anchorX)
        assertNull(d.anchorY)
    }

    @Test
    fun `settings round-trip through json`() {
        val original = BossPetSettings(enabled = true, anchorX = 120, anchorY = 40)
        val decoded =
            json.decodeFromString<BossPetSettings>(json.encodeToString(BossPetSettings.serializer(), original))
        assertEquals(original, decoded)
    }

    @Test
    fun `a file missing the position fields still decodes`() {
        val decoded = json.decodeFromString<BossPetSettings>("""{"enabled":true}""")
        assertTrue(decoded.enabled)
        assertNull(decoded.anchorX)
    }

    @Test
    fun `an unknown key from a newer build does not throw`() {
        val decoded = json.decodeFromString<BossPetSettings>("""{"enabled":false,"unknownFromLater":7}""")
        assertFalse(decoded.enabled)
    }
}
