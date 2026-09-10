package ai.rever.boss.plugin

import ai.rever.boss.plugin.PluginPersistence.InstalledPluginEntry
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the two merge rules that decide whether a build verdict survives, and the back-compat of the
 * fields it is stored in.
 *
 * The rules are exercised through the pure functions rather than the object's file-backed API on
 * purpose: `PluginPersistence` resolves `installed.json` from `PluginStoreSetup.getPluginDir()`, so a
 * test that went through the real thing would rewrite the developer's own plugin config.
 */
class InstalledPluginEntryMergeTest {
    private companion object {
        const val PLUGIN = "ai.rever.boss.plugin.dynamic.probe"
        const val JAR = "/Users/someone/.boss/plugins/probe-1.0.3.jar"
        const val OTHER_JAR = "/Users/someone/.boss/plugins/probe-1.0.4.jar"
        const val INSTALLED_AT = 1_754_700_000_000L
        const val STAMP = 1_754_890_231_447L
        const val NOW = 1_754_999_999_999L
    }

    private val hotRow =
        InstalledPluginEntry(
            pluginId = PLUGIN,
            jarPath = JAR,
            enabled = true,
            sourceUrl = "https://store.example/probe.jar",
            installedVersion = "1.0.3",
            installedAt = INSTALLED_AT,
            buildStamp = STAMP,
            buildTag = PluginBuildProbe.TAG_HOT,
        )

    @Test
    fun `a repoint to the same jar keeps the build verdict`() {
        // The startup repoint. Without this the persisted-load pass would erase the verdict on every
        // launch, and the tag would only ever survive until the next restart.
        val merged =
            with(PluginPersistence) {
                InstalledPluginEntry(PLUGIN, JAR, enabled = true, installedVersion = "1.0.3")
                    .carryingBuildFrom(hotRow, NOW)
            }

        assertEquals(PluginBuildProbe.TAG_HOT, merged.buildTag)
        assertEquals(STAMP, merged.buildStamp)
        assertEquals(INSTALLED_AT, merged.installedAt, "the original install time must not be reset")
    }

    @Test
    fun `a different jar drops the verdict`() {
        // A store update lands under a new versioned filename. Inheriting "hot" here would tag a
        // freshly downloaded release as unreleased - the exact false positive that teaches a user to
        // ignore the tag.
        val merged =
            with(PluginPersistence) {
                InstalledPluginEntry(PLUGIN, OTHER_JAR, enabled = true, installedVersion = "1.0.4")
                    .carryingBuildFrom(hotRow, NOW)
            }

        assertNull(merged.buildTag)
        assertNull(merged.buildStamp)
        assertEquals(NOW, merged.installedAt)
    }

    @Test
    fun `recording a build never loses enabled or sourceUrl`() {
        // recordBuild upserts on rows other paths own, so a blind replace here would silently
        // re-enable a plugin the user disabled.
        val disabled = hotRow.copy(enabled = false, buildTag = PluginBuildProbe.TAG_DEBUG, buildStamp = INSTALLED_AT)

        val merged =
            PluginPersistence.mergeBuildInto(
                existing = disabled,
                fresh =
                    InstalledPluginEntry(
                        pluginId = PLUGIN,
                        jarPath = JAR,
                        enabled = true,
                        installedVersion = null,
                        installedAt = NOW,
                        buildStamp = STAMP,
                        buildTag = PluginBuildProbe.TAG_HOT,
                    ),
                now = NOW,
            )

        assertEquals(false, merged.enabled, "an existing row's enabled state is not ours to change")
        assertEquals("https://store.example/probe.jar", merged.sourceUrl)
        assertEquals("1.0.3", merged.installedVersion, "a null version must not erase the recorded one")
        assertEquals(PluginBuildProbe.TAG_HOT, merged.buildTag)
        assertEquals(STAMP, merged.buildStamp)
    }

    @Test
    fun `a row created by recordBuild is enabled regardless of how the load went`() {
        // The sideload and Toolbox-install case, which previously wrote no row at all. Writing
        // enabled=false for a plugin that was merely hidden for lack of access, or failed to
        // register, would stop it being loaded at all on the next launch.
        val merged =
            PluginPersistence.mergeBuildInto(
                existing = null,
                fresh =
                    InstalledPluginEntry(
                        pluginId = PLUGIN,
                        jarPath = JAR,
                        enabled = true,
                        installedVersion = "1.0.3",
                        installedAt = NOW,
                        buildStamp = STAMP,
                        buildTag = PluginBuildProbe.TAG_DEBUG,
                    ),
                now = NOW,
            )

        assertTrue(merged.enabled)
        assertEquals(NOW, merged.installedAt)
        assertEquals(PluginBuildProbe.TAG_DEBUG, merged.buildTag)
    }

    @Test
    fun `a row written by an older host still decodes, and the new fields come back null`() {
        // The additive-migration trap: a strict decode of an unknown-shaped list throws and
        // loadConfigInternal falls back to an EMPTY config, which would make every installed plugin
        // vanish. The reader sets ignoreUnknownKeys, and the new fields carry defaults, so both
        // directions survive.
        val json = Json { ignoreUnknownKeys = true }
        val oldRow = """{"pluginId":"$PLUGIN","jarPath":"$JAR","enabled":true}"""

        val decoded = json.decodeFromString<InstalledPluginEntry>(oldRow)

        assertNull(decoded.installedAt)
        assertNull(decoded.buildStamp)
        assertNull(decoded.buildTag)
        assertNull(decoded.failureReason)
        assertNull(decoded.failureTimestamp)
        assertNull(decoded.failureRestartAttempts)
    }

    @Test
    fun `re-enable clears restart disable and preserves manual disables`() {
        val disabled = PluginPersistence.restartLimitFailureEntry(hotRow, 3, NOW)
        val recovered = PluginPersistence.restartLimitRecoveryEntry(disabled)
        assertEquals(true, recovered.enabled)
        assertNull(recovered.failureReason)
        assertNull(recovered.failureTimestamp)
        assertNull(recovered.failureRestartAttempts)
        assertEquals(hotRow.jarPath, recovered.jarPath)
        val manual = hotRow.copy(enabled = false)
        assertEquals(manual, PluginPersistence.restartLimitRecoveryEntry(manual))
    }

    @Test
    fun `restart-limit failure keeps plugin metadata and records the automatic disable details`() {
        val failure = PluginPersistence.restartLimitFailureEntry(hotRow, restartAttempts = 3, timestamp = NOW)

        assertEquals(false, failure.enabled)
        assertEquals(PluginPersistence.MAX_RESTART_ATTEMPTS_FAILURE_REASON, failure.failureReason)
        assertEquals(NOW, failure.failureTimestamp)
        assertEquals(3, failure.failureRestartAttempts)
        assertEquals(hotRow.jarPath, failure.jarPath)
        assertEquals(hotRow.installedVersion, failure.installedVersion)
        assertEquals(hotRow.sourceUrl, failure.sourceUrl)
    }

    @Test
    fun `a row written by this host decodes on a reader that does not know the new fields`() {
        val strict = Json { ignoreUnknownKeys = true }
        val encoded = Json { encodeDefaults = true }.encodeToString(hotRow)

        // Stand-in for the older host's model: the same class minus the three build fields.
        val roundTripped = strict.decodeFromString<LegacyEntry>(encoded)

        assertEquals(PLUGIN, roundTripped.pluginId)
        assertEquals(JAR, roundTripped.jarPath)
        assertTrue(roundTripped.enabled)
    }

    @kotlinx.serialization.Serializable
    private data class LegacyEntry(
        val pluginId: String,
        val jarPath: String,
        val enabled: Boolean = true,
        val sourceUrl: String? = null,
        val installedVersion: String? = null,
    )
}
