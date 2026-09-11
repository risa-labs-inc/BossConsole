package ai.rever.boss.config

import ai.rever.boss.components.settings.sections.nextRenderingMode
import ai.rever.boss.components.settings.sections.restartWouldChangeAnything
import ai.rever.boss.plugin.browser.FluckEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the settings→config bridge in [ChromiumFlagsSettings].
 *
 * The bridge is the part that can fail silently. A setting is written to disk, published as a
 * system property, and read back somewhere else entirely through [ConfigLoader] — so a field with
 * no corresponding entry in [ChromiumFlagsSettings.publishedValue], or a key that is not in
 * [ChromiumFlagKeys.PUBLISHED], produces a control that appears to work, persists, and changes
 * nothing. Nothing else in the codebase would notice.
 */
class ChromiumFlagsSettingsTest {
    @Test
    fun `defaults express no opinion at all`() {
        val defaults = ChromiumFlagsSettings()
        assertTrue(defaults.isDefault)
        // Every published key must be absent, not false or empty: a user who has never opened the
        // screen must get byte-identical engine options to the ones they got before it existed.
        for (key in ChromiumFlagKeys.PUBLISHED) {
            assertNull(defaults.publishedValue(key), "expected no value for $key by default")
        }
    }

    @Test
    fun `every published key is actually produced by some field`() {
        // The coverage guard the KDoc on publishedValue promises. Populated with a value for each
        // field, EVERY published key must resolve — otherwise a field exists that the bridge drops
        // on the floor, and its Settings row is decorative.
        val populated =
            ChromiumFlagsSettings(
                renderingMode = "OFF_SCREEN",
                skikoRenderApi = "METAL",
                topInsetDp = 12,
                browserPrewarm = false,
                rendererProcessLimit = 4,
                enableSkiaGraphite = true,
                disableSandbox = true,
                extraSwitches = "--custom",
            )
        for (key in ChromiumFlagKeys.PUBLISHED) {
            assertNotNull(
                populated.publishedValue(key),
                "no value published for $key - the field feeding it is missing from publishedValue()",
            )
        }
    }

    @Test
    fun `published values use the spellings the read sites parse`() {
        val settings =
            ChromiumFlagsSettings(
                renderingMode = "OFF_SCREEN",
                topInsetDp = 24,
                browserPrewarm = false,
                rendererProcessLimit = 4,
                enableSkiaGraphite = true,
                disableSandbox = true,
            )
        assertEquals("OFF_SCREEN", settings.publishedValue(ChromiumFlagKeys.RENDERING_MODE))
        assertEquals("24", settings.publishedValue(ChromiumFlagKeys.TOP_INSET_DP))
        assertEquals("4", settings.publishedValue(ChromiumFlagKeys.RENDERER_PROCESS_LIMIT))

        // Booleans go out as "true"/"false", which is what FluckEngine's isTruthyFlag and
        // isFalsyFlag accept. Asserted explicitly because these two are read by DIFFERENT
        // predicates: prewarm opts out on a falsy value, the other two opt in on a truthy one, so
        // a single wrong spelling would break one direction and not the other.
        assertEquals("false", settings.publishedValue(ChromiumFlagKeys.PREWARM))
        assertEquals("true", settings.publishedValue(ChromiumFlagKeys.SKIA_GRAPHITE))
        assertEquals("true", settings.publishedValue(ChromiumFlagKeys.DISABLE_SANDBOX))
    }

    @Test
    fun `a blank extra-switches field publishes nothing rather than an empty override`() {
        // Blank is what a user leaves behind after clearing the box. Publishing "" would set the
        // system property, which then OUTRANKS local.properties in ConfigLoader - so clearing the
        // field in the UI would suppress a value the user had deliberately configured elsewhere.
        for (blank in listOf("", "   ", "\n")) {
            assertNull(ChromiumFlagsSettings(extraSwitches = blank).publishedValue(ChromiumFlagKeys.EXTRA_SWITCHES))
        }
        assertEquals(
            "--ok",
            ChromiumFlagsSettings(extraSwitches = "--ok").publishedValue(ChromiumFlagKeys.EXTRA_SWITCHES),
        )
    }

    @Test
    fun `the DevTools port is deliberately not published as a system property`() {
        // It is read straight off the settings object by FluckEngine. Publishing it would put it
        // in ConfigLoader's chain, and from there a line in someone's local.properties could open
        // a DevTools endpoint - full control of the browser profile - on every future run of that
        // checkout, with nothing in the app to reveal it.
        assertFalse(ChromiumFlagKeys.REMOTE_DEBUGGING_PORT in ChromiumFlagKeys.PUBLISHED)
        assertNull(
            ChromiumFlagsSettings(remoteDebuggingPort = 9222)
                .publishedValue(ChromiumFlagKeys.REMOTE_DEBUGGING_PORT),
        )
    }

    @Test
    fun `settings-only fields are not published either`() {
        // These four are consumed directly by FluckEngine.applyPerformanceSwitches, not through a
        // config key, so there is nothing for them to publish. Asserted so that adding a key for
        // one later is a deliberate edit here rather than a surprise.
        val settings =
            ChromiumFlagsSettings(
                diskCacheMb = 128,
                noPings = false,
                disableDomainReliability = false,
                disableWinOcclusion = false,
                enableVaapi = false,
            )
        assertFalse(settings.isDefault)
        for (key in ChromiumFlagKeys.PUBLISHED) {
            assertNull(settings.publishedValue(key), "expected $key to stay unpublished")
        }
    }

    @Test
    fun `any single change is enough to stop looking default`() {
        // isDefault gates whether "Reset" is offered, so it must not be satisfied by a partial
        // match on the fields that happen to be checked first.
        val changes =
            listOf(
                ChromiumFlagsSettings(renderingMode = "OFF_SCREEN"),
                ChromiumFlagsSettings(skikoRenderApi = "SOFTWARE"),
                ChromiumFlagsSettings(topInsetDp = 0),
                ChromiumFlagsSettings(browserPrewarm = true),
                ChromiumFlagsSettings(rendererProcessLimit = 1),
                ChromiumFlagsSettings(enableSkiaGraphite = false),
                ChromiumFlagsSettings(disableSandbox = false),
                ChromiumFlagsSettings(diskCacheMb = 512),
                ChromiumFlagsSettings(noPings = true),
                ChromiumFlagsSettings(disableDomainReliability = true),
                ChromiumFlagsSettings(disableWinOcclusion = true),
                ChromiumFlagsSettings(enableVaapi = true),
                ChromiumFlagsSettings(remoteDebuggingPort = 9222),
                ChromiumFlagsSettings(extraSwitches = "--x"),
            )
        for (changed in changes) {
            assertFalse(changed.isDefault, "expected non-default: $changed")
        }
        // DERIVED from the class, not restated. The previous version asserted `changes.size == 14`,
        // which only checked the length of the list written directly above it — adding a field to
        // ChromiumFlagsSettings left it at 14 and the test still passed, so the guard its own
        // comment described did not exist.
        assertEquals(
            ChromiumFlagsSettings.serializer().descriptor.elementsCount,
            changes.size,
            "every field needs a case here; add one for the field you just added",
        )
    }

    @Test
    fun `every field either publishes a key or is documented as settings-only`() {
        // The guard publishedValue's KDoc used to claim the compiler provided. It does not: the
        // `when` is over a String with an `else`, so a new field silently publishes nothing and
        // its Settings row becomes decorative. Deriving both sides from the class is what makes
        // adding a field a failing test rather than a quiet no-op.
        val fieldCount = ChromiumFlagsSettings.serializer().descriptor.elementsCount
        val publishedCount = ChromiumFlagKeys.PUBLISHED.size
        // The fields deliberately NOT published, each for a stated reason: four consumed directly
        // by applyPerformanceSwitches, and the DevTools port kept out of ConfigLoader on purpose.
        val settingsOnly =
            listOf(
                "diskCacheMb",
                "noPings",
                "disableDomainReliability",
                "disableWinOcclusion",
                "enableVaapi",
                "remoteDebuggingPort",
            )
        assertEquals(
            fieldCount,
            publishedCount + settingsOnly.size,
            "a new field must either be added to PUBLISHED + publishedValue, or listed here as settings-only",
        )
    }

    /**
     * Guards `restartWouldChangeAnything`'s OWN settings-only list.
     *
     * The field-count test above guards the enumeration written in *this file*. A seventh
     * settings-only field would fail it, the author would add the name here, it would go green —
     * and the `boot.copy(...)` in ChromiumFlagsSection would still enumerate six, so changing the
     * new field would never raise the "Changes are waiting for a restart" row. Only asserting on
     * the function itself covers that.
     */
    @Test
    fun `changing any settings-only field is offered a restart`() {
        val cases =
            listOf(
                "diskCacheMb" to ChromiumFlagsSettings(diskCacheMb = 128),
                "noPings" to ChromiumFlagsSettings(noPings = false),
                "disableDomainReliability" to ChromiumFlagsSettings(disableDomainReliability = false),
                "disableWinOcclusion" to ChromiumFlagsSettings(disableWinOcclusion = false),
                "enableVaapi" to ChromiumFlagsSettings(enableVaapi = false),
                "remoteDebuggingPort" to ChromiumFlagsSettings(remoteDebuggingPort = 9222),
            )
        for ((name, changed) in cases) {
            assertTrue(
                restartWouldChangeAnything(changed, ChromiumFlagsSettings()),
                "changing $name must offer a restart; is it missing from the copy() in ChromiumFlagsSection?",
            )
        }
        // Same count as the other list, so the two cannot drift apart silently.
        assertEquals(
            ChromiumFlagsSettings.serializer().descriptor.elementsCount - ChromiumFlagKeys.PUBLISHED.size,
            cases.size,
        )
    }

    /**
     * The Graphite exemption in `restartWouldChangeAnything`, which nothing reached.
     *
     * `any single change is enough to stop looking default` asserts on `isDefault`, and
     * `changing any settings-only field is offered a restart` enumerates the six UNPUBLISHED
     * fields - which excludes `enableSkiaGraphite`. So the toggle-off-then-on case, the entire
     * reason that per-key branch exists, was unpinned: if Graphite's default ever stops following
     * the rendering mode, the exemption would silently suppress a restart that IS needed.
     */
    @Test
    fun `re-enabling Graphite to its default value is not offered a restart`() {
        // A synthetic boot, not the manager's: bootSettings is read from the real path at object
        // construction, so asserting it is default is an assertion about the developer's home
        // directory - and it would fail on any machine that has saved a Chromium setting.
        val boot = ChromiumFlagsSettings()
        // The NEXT-LAUNCH mode, which is what restartWouldChangeAnything resolves against.
        // Substituting the live JxBrowserConfig.renderingMode here is the precise mistake
        // ChromiumFlagsCommandLine documents as the cause of an earlier drift bug; the two agree
        // only while boot is default, which is exactly when a test would stop noticing.
        val default = FluckEngine.resolveSkiaGraphite(null, nextRenderingMode(boot))
        assertFalse(restartWouldChangeAnything(boot.copy(enableSkiaGraphite = default), boot))
        // The opposite choice IS a real change and must still be offered one.
        assertTrue(restartWouldChangeAnything(boot.copy(enableSkiaGraphite = !default), boot))
    }

    /**
     * The security-motivated half of the write path, which was the half still untested.
     *
     * `settingsFile` became an `internal var` precisely to make this reachable. It also pins the
     * non-obvious step: the file is created `rw-------` as a TEMP file and reaches its destination
     * through `ATOMIC_MOVE`, which carries the mode across - a JDK or filesystem change could
     * quietly break that and nothing else would notice.
     */
    @Test
    fun `the saved settings file is owner-only`() {
        val posix =
            java.nio.file.FileSystems
                .getDefault()
                .supportedFileAttributeViews()
                .contains("posix")
        if (!posix) return // Windows runner; the permissions path is a documented no-op there.
        val realFile = ChromiumFlagsSettingsManager.settingsFile
        val temp = java.io.File.createTempFile("chromium-flags-perms", ".json")
        temp.delete() // let the write path create it, so the assertion covers creation
        val before = ChromiumFlagsSettingsManager.currentSettings.value
        ChromiumFlagsSettingsManager.settingsFile = temp
        try {
            kotlinx.coroutines.runBlocking {
                ChromiumFlagsSettingsManager.updateSettings { it.copy(diskCacheMb = 321) }
            }
            assertEquals(
                "rw-------",
                java.nio.file.attribute.PosixFilePermissions
                    .toString(
                        java.nio.file.Files
                            .getPosixFilePermissions(temp.toPath()),
                    ),
                "a file that can turn off the Chromium sandbox must not be group- or world-readable",
            )
        } finally {
            try {
                kotlinx.coroutines.runBlocking { ChromiumFlagsSettingsManager.updateSettings { before } }
            } finally {
                ChromiumFlagsSettingsManager.settingsFile = realFile
                temp.delete()
            }
        }
    }

    @Test
    fun `an unchanged settings object is not offered a restart`() {
        val boot = ChromiumFlagsSettings()
        assertFalse(restartWouldChangeAnything(boot, boot))
    }

    @Test
    fun `envOverride reads the environment only, never system properties`() {
        // Renamed to what it actually pins. It cannot test blank - a JVM cannot set its own
        // environment variables - and the previous name promised coverage that now genuinely
        // lives in ConfigLoaderTest, where the pure resolver takes envValue as a parameter.
        // What IS worth pinning here: envOverride must not fall back to a system property, since
        // those hold this process's own published boot settings and reading them would make the
        // UI report the app's own setting as an environment override.
        System.setProperty("BOSS_TEST_UNUSED_KEY", "ignored")
        try {
            assertNull(
                ChromiumFlagsSettingsManager.envOverride("BOSS_TEST_UNUSED_KEY"),
                "a system property must not be reported as an environment override",
            )
        } finally {
            System.clearProperty("BOSS_TEST_UNUSED_KEY")
        }
    }

    /**
     * A second edit builds on the first instead of overwriting it.
     *
     * Named for the property actually under test, after two failed attempts to dress it as a
     * concurrency test. It never was one: `runBlocking` without a dispatcher runs its children on
     * one event loop, and `updateAndGet` is atomic by construction, so threads add nothing. What
     * the transform API changes is WHEN the caller reads - at apply time rather than at compose
     * time - and that is fully observable sequentially.
     *
     * The two earlier attempts are worth recording because both looked right: one awaited the
     * calls in order and so would have passed against the very bug it existed for; the next made
     * both transforms close over a captured snapshot and ignore their parameter, which is the bug
     * rather than the fix, and failed. The parameter is the whole mechanism.
     *
     * Redirects the manager's file - `updateSettings` persists on every call, and against the
     * default path this writes the developer's and CI's real ~/.boss/chromium-flags.json.
     */
    @Test
    fun `a second edit composes onto the first rather than replacing it`() {
        val realFile = ChromiumFlagsSettingsManager.settingsFile
        val temp = java.io.File.createTempFile("chromium-flags-test", ".json")
        temp.deleteOnExit()
        ChromiumFlagsSettingsManager.settingsFile = temp
        val before = ChromiumFlagsSettingsManager.currentSettings.value
        try {
            kotlinx.coroutines.runBlocking {
                // A UI control holds a value from collectAsState() that may already be one edit
                // stale; reading the parameter is what makes the staleness harmless.
                ChromiumFlagsSettingsManager.updateSettings { it.copy(diskCacheMb = 111) }
                ChromiumFlagsSettingsManager.updateSettings { it.copy(rendererProcessLimit = 7) }
            }
            val after = ChromiumFlagsSettingsManager.currentSettings.value
            assertEquals(111, after.diskCacheMb, "the second edit discarded the first")
            assertEquals(7, after.rendererProcessLimit, "the first edit discarded the second")
        } finally {
            // Nested, so a throwing restore cannot leave the manager pointed at a deleted temp
            // file for the rest of the JVM. updateSettings swallows its IO errors today, which is
            // exactly the kind of thing that stops being true later.
            try {
                kotlinx.coroutines.runBlocking { ChromiumFlagsSettingsManager.updateSettings { before } }
            } finally {
                ChromiumFlagsSettingsManager.settingsFile = realFile
                temp.delete()
            }
        }
    }

    @Test
    fun `previewValue puts the environment ahead of the setting`() {
        // The precedence the whole screen rests on, and the rule the command-line preview has to
        // reproduce or it reports a next launch that will not happen.
        val settings = ChromiumFlagsSettings(renderingMode = "OFF_SCREEN")
        // No env var is set for this key in a test JVM, so the setting shows through.
        assertEquals(
            "OFF_SCREEN",
            ChromiumFlagsSettingsManager.previewValue(settings, ChromiumFlagKeys.RENDERING_MODE),
        )
        // A key with no setting and no env resolves to nothing rather than to a guess.
        assertNull(ChromiumFlagsSettingsManager.previewValue(ChromiumFlagsSettings(), ChromiumFlagKeys.PREWARM))
        // And it never consults system properties, which hold THIS process's published boot
        // values — reading them would make the preview echo the running session back at the user
        // instead of showing what they just chose.
        System.setProperty(ChromiumFlagKeys.SKIKO_RENDER_API, "METAL")
        try {
            assertNull(
                ChromiumFlagsSettingsManager.previewValue(ChromiumFlagsSettings(), ChromiumFlagKeys.SKIKO_RENDER_API),
            )
        } finally {
            System.clearProperty(ChromiumFlagKeys.SKIKO_RENDER_API)
        }
    }
}
