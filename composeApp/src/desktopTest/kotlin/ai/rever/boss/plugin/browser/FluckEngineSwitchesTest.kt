package ai.rever.boss.plugin.browser

import ai.rever.boss.config.BossResourceMode
import com.teamdev.jxbrowser.engine.RenderingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the pure flag-parsing helpers in [FluckEngine] — no JxBrowser
 * engine required. These guard the env-driven parts of the Chromium flag audit
 * (extra-switch injection, truthy/falsy env flags) against regressions.
 */
class FluckEngineSwitchesTest {
    @Test
    fun `parseExtraSwitches splits on whitespace and keeps only switch-shaped entries`() {
        assertEquals(
            listOf("--enable-features=SkiaGraphite", "--disk-cache-size=1048576"),
            FluckEngine.parseExtraSwitches("  --enable-features=SkiaGraphite   --disk-cache-size=1048576 "),
        )
        // Entries that don't look like switches are dropped, not passed through.
        assertEquals(
            listOf("--ok"),
            FluckEngine.parseExtraSwitches("not-a-switch --ok rm -single-dash"),
        )
    }

    @Test
    fun `parseExtraSwitches keeps comma-bearing feature lists intact`() {
        // Commas are Chromium's separator INSIDE feature-list values — a
        // multi-feature switch must survive as one token (this is exactly what
        // the last-one-wins KDoc tells operators to write to preserve the
        // platform feature set).
        assertEquals(
            listOf("--enable-features=SkiaGraphite,VaapiVideoDecoder", "--no-first-run"),
            FluckEngine.parseExtraSwitches("--enable-features=SkiaGraphite,VaapiVideoDecoder --no-first-run"),
        )
    }

    @Test
    fun `parseExtraSwitches handles null, empty and switch-less input`() {
        assertEquals(emptyList(), FluckEngine.parseExtraSwitches(null))
        assertEquals(emptyList(), FluckEngine.parseExtraSwitches(""))
        assertEquals(emptyList(), FluckEngine.parseExtraSwitches("a b c"))
    }

    @Test
    fun `truthy flag accepts the documented enable spellings only`() {
        for (v in listOf("1", "true", "yes", "on", " TRUE ", "On")) {
            assertTrue(FluckEngine.isTruthyFlag(v), "expected truthy: '$v'")
        }
        for (v in listOf(null, "", "0", "false", "enabled", "y")) {
            assertFalse(FluckEngine.isTruthyFlag(v), "expected not truthy: '$v'")
        }
    }

    @Test
    fun `falsy flag accepts the documented disable spellings only`() {
        for (v in listOf("0", "false", "no", "off", " FALSE ", "Off")) {
            assertTrue(FluckEngine.isFalsyFlag(v), "expected falsy: '$v'")
        }
        // Unset or unrecognized values must NOT count as an opt-out.
        for (v in listOf(null, "", "1", "true", "disabled", "n")) {
            assertFalse(FluckEngine.isFalsyFlag(v), "expected not falsy: '$v'")
        }
    }

    // --- performanceSwitchesFor: the per-platform flag-audit decision ---

    private fun switchesFor(
        os: String,
        arch: String = "x86_64",
        graphiteOptIn: Boolean = false,
        inContainer: Boolean = false,
        extras: List<String> = emptyList(),
        noPings: Boolean = true,
        domainReliability: Boolean = true,
        winOcclusion: Boolean = true,
        vaapi: Boolean = true,
    ) = FluckEngine.performanceSwitchesFor(
        os,
        arch,
        inContainer,
        extras,
        FluckEngine.SwitchToggles(noPings, domainReliability, winOcclusion, vaapi, graphiteOptIn),
    )

    /**
     * Switches that are not platform-specific, so a per-platform assertion can say what that
     * platform adds ON TOP of the common set without restating it. Kept as a helper rather than
     * inlined so adding another universal switch updates every test at once.
     */
    private val universalSwitches = setOf("--no-pings", "--disable-domain-reliability")

    private fun platformSpecific(switches: List<String>) = switches - universalSwitches

    @Test
    fun `windows disables the native-window occlusion tracker`() {
        assertTrue("--disable-features=CalculateNativeWinOcclusion" in switchesFor("windows 11"))
    }

    @Test
    fun `background network chatter is trimmed on every platform`() {
        // Hyperlink-auditing pings and Chrome's Domain Reliability uploads are dead weight for an
        // embedded browser. Asserted per-platform because it is easy to accidentally nest these
        // inside one branch of the platform `when`.
        for (os in listOf("windows 11", "mac os x", "linux", "freebsd")) {
            val switches = switchesFor(os)
            assertTrue("--no-pings" in switches, "expected --no-pings on '$os'")
            assertTrue("--disable-domain-reliability" in switches, "expected --disable-domain-reliability on '$os'")
        }
    }

    @Test
    fun `renderer process cap is opt-in and rejects meaningless values`() {
        assertEquals("--renderer-process-limit=4", FluckEngine.renderCapSwitch("4"))
        assertEquals("--renderer-process-limit=1", FluckEngine.renderCapSwitch(" 1 "))
        // Unset is the normal case. 0 and negatives are not caps, and must not be passed
        // through as if they were.
        for (raw in listOf(null, "", "   ", "0", "-1", "many", "4.5")) {
            assertEquals(null, FluckEngine.renderCapSwitch(raw), "expected no cap for '$raw'")
        }
    }

    @Test
    fun `reduced tiers supply a renderer cap and FULL supplies none`() {
        assertEquals(null, FluckEngine.resolvedRenderCapSwitch(null, BossResourceMode.FULL))
        assertEquals(
            "--renderer-process-limit=${BossResourceMode.LITE.rendererProcessLimit}",
            FluckEngine.resolvedRenderCapSwitch(null, BossResourceMode.LITE),
        )
        assertEquals(
            "--renderer-process-limit=${BossResourceMode.ULTRA_LITE.rendererProcessLimit}",
            FluckEngine.resolvedRenderCapSwitch(null, BossResourceMode.ULTRA_LITE),
        )
    }

    @Test
    fun `an explicit setting outranks the tier in both directions`() {
        // Raising it above the tier's cap.
        assertEquals(
            "--renderer-process-limit=12",
            FluckEngine.resolvedRenderCapSwitch("12", BossResourceMode.ULTRA_LITE),
        )
        // And lowering it below FULL's absence of one.
        assertEquals(
            "--renderer-process-limit=1",
            FluckEngine.resolvedRenderCapSwitch("1", BossResourceMode.FULL),
        )
    }

    /**
     * An explicit `0` means "no cap" and has to survive a reduced tier, otherwise an operator
     * who deliberately turned the cap off gets silently re-capped the moment the machine is
     * small enough to pick ULTRA_LITE. `renderCapSwitch` alone cannot express this - it maps
     * both `0` and unset to null - which is the whole reason `resolvedRenderCapSwitch` exists.
     */
    @Test
    fun `an explicit zero disables the cap even on a reduced tier`() {
        assertEquals(null, FluckEngine.resolvedRenderCapSwitch("0", BossResourceMode.ULTRA_LITE))
        assertEquals(null, FluckEngine.resolvedRenderCapSwitch(" -1 ", BossResourceMode.LITE))
    }

    @Test
    fun `an unparseable setting falls through to the tier rather than to no cap`() {
        for (raw in listOf("", "   ", "many", "4.5")) {
            assertEquals(
                "--renderer-process-limit=${BossResourceMode.LITE.rendererProcessLimit}",
                FluckEngine.resolvedRenderCapSwitch(raw, BossResourceMode.LITE),
                "expected the tier default for '$raw'",
            )
        }
    }

    @Test
    fun `the renderer cap never leaks into the platform switch decision`() {
        // The cap is resolved by the CALLER and handed in as an extra, never read inside
        // performanceSwitchesFor. If it were read inside, every assertion in this class would
        // depend on whether the developer happens to have BOSS_RENDERER_PROCESS_LIMIT set - it
        // would pass locally and fail in CI, or the reverse.
        assertTrue(switchesFor("windows 11").none { it.startsWith("--renderer-process-limit") })
        // And when the caller does pass it, it still lands before the operator's own extras.
        val withCap = switchesFor("windows 11", extras = listOf("--renderer-process-limit=3", "--custom"))
        assertEquals("--custom", withCap.last())
        assertTrue("--renderer-process-limit=3" in withCap)
    }

    @Test
    fun `SkiaGraphite reaches the switch list only on Apple Silicon`() {
        assertEquals(emptyList(), platformSpecific(switchesFor("mac os x", arch = "aarch64")))
        assertEquals(
            listOf("--enable-features=SkiaGraphite"),
            platformSpecific(switchesFor("mac os x", arch = "aarch64", graphiteOptIn = true)),
        )
        // Intel macs never get Graphite, even when it is switched on. Graphite is Metal-only, so
        // the arch guard is not a preference and must not be reachable from any setting.
        assertEquals(emptyList(), platformSpecific(switchesFor("mac os x", arch = "x86_64", graphiteOptIn = true)))
    }

    /**
     * Graphite's default follows the RENDERING MODE, which is the part worth pinning.
     *
     * It is Chromium's Metal-native raster backend and default-on in stable Chrome on Apple
     * Silicon, so it is the better backend where it works. The one place it is known not to work
     * here is off-screen rendering — verified live 2026-07-13 on JxBrowser 9.3.0 / Chromium 150:
     * pages loaded but frames never reached the Compose surface, leaving a blank content area.
     * That failure is in the OSR frame-export path, which HARDWARE_ACCELERATED does not use.
     *
     * The OFF_SCREEN half is the half that protects someone. OFF_SCREEN is the documented escape
     * hatch from HARDWARE; defaulting Graphite on there would hand exactly those users a blank
     * browser, making the escape hatch worse than what they escaped.
     */
    @Test
    fun `Graphite defaults on for hardware rendering and off for off-screen`() {
        assertTrue(FluckEngine.resolveSkiaGraphite(null, RenderingMode.HARDWARE_ACCELERATED))
        assertFalse(FluckEngine.resolveSkiaGraphite(null, RenderingMode.OFF_SCREEN))
        for (unset in listOf(null, "", "   ", "maybe")) {
            assertFalse(
                FluckEngine.resolveSkiaGraphite(unset, RenderingMode.OFF_SCREEN),
                "expected the off-screen default for '$unset'",
            )
        }
    }

    @Test
    fun `an explicit Graphite value overrides the mode default in both directions`() {
        // Turning it OFF under hardware is the override that matters now: it is the only recourse
        // on a machine where Graphite misbehaves, and before this change it was unreachable
        // because unset already meant off.
        for (mode in listOf(RenderingMode.HARDWARE_ACCELERATED, RenderingMode.OFF_SCREEN)) {
            for (on in listOf("true", "1", "yes", " ON ")) {
                assertTrue(FluckEngine.resolveSkiaGraphite(on, mode), "expected '$on' to enable in $mode")
            }
            for (off in listOf("false", "0", "no", " OFF ")) {
                assertFalse(FluckEngine.resolveSkiaGraphite(off, mode), "expected '$off' to disable in $mode")
            }
        }
    }

    @Test
    fun `linux enables VA-API and adds container-only switches inside containers`() {
        val desktop = switchesFor("linux")
        assertEquals(
            listOf("--enable-features=VaapiVideoDecoder,VaapiVideoDecodeLinuxGL,VaapiVideoEncoder"),
            platformSpecific(desktop),
        )
        assertFalse("--no-sandbox" in desktop)
        val container = switchesFor("linux", inContainer = true)
        assertTrue("--disable-dev-shm-usage" in container)
        // The container sandbox opt-out goes through EngineOptions.disableSandbox()
        // (the supported API) — a raw --no-sandbox switch must NOT appear here.
        assertFalse("--no-sandbox" in container)
        // The base VA-API feature set must survive container mode.
        assertTrue("--enable-features=VaapiVideoDecoder,VaapiVideoDecodeLinuxGL,VaapiVideoEncoder" in container)
    }

    @Test
    fun `partitionExtraSwitches separates accepted switches from dropped tokens in one pass`() {
        val parsed = FluckEngine.partitionExtraSwitches("--ok not-a-switch -single --also-ok")
        assertEquals(listOf("--ok", "--also-ok"), parsed.accepted)
        assertEquals(listOf("not-a-switch", "-single"), parsed.malformed)
        assertEquals(emptyList(), parsed.gated)
    }

    @Test
    fun `extra switches are appended last so operator flags win ties`() {
        val switches = switchesFor("windows 11", extras = listOf("--disk-cache-size=1"))
        assertEquals("--disk-cache-size=1", switches.last())
        assertTrue("--disable-features=CalculateNativeWinOcclusion" in switches)
    }

    @Test
    fun `unknown platforms get no platform-specific switches`() {
        assertEquals(emptyList(), platformSpecific(switchesFor("freebsd")))
        assertEquals(emptyList(), platformSpecific(switchesFor("sunos", inContainer = true)))
    }

    // --- the Settings-driven toggles ---

    /**
     * The default arguments are the shipped behaviour, and that is the part worth pinning: these
     * four became [ChromiumFlagsSettings] rows where null means "no opinion", so a caller that
     * resolved null to `false` instead of `true` would quietly strip flags from every user who has
     * never opened the Settings screen. A signature default of true makes that mistake impossible
     * to make by omission; this test makes it impossible to make by editing the signature.
     */
    @Test
    fun `omitting the toggles yields exactly the pre-Settings switch set`() {
        assertEquals(
            listOf("--no-pings", "--disable-domain-reliability", "--disable-features=CalculateNativeWinOcclusion"),
            FluckEngine.performanceSwitchesFor("windows 11", "x86_64", inContainer = false),
        )
        assertEquals(
            listOf(
                "--no-pings",
                "--disable-domain-reliability",
                "--enable-features=VaapiVideoDecoder,VaapiVideoDecodeLinuxGL,VaapiVideoEncoder",
            ),
            FluckEngine.performanceSwitchesFor("linux", "x86_64", inContainer = false),
        )
    }

    @Test
    fun `each network-trimming switch can be turned off independently`() {
        // Independently, not as a pair: they are two separate rows in Settings, and a user
        // debugging one site's hyperlink auditing should not lose Domain Reliability trimming too.
        // Asserted on "freebsd", which contributes no platform switches, so the whole result IS
        // the universal set and an exact-list assertion catches an unexpected addition too.
        assertEquals(listOf("--disable-domain-reliability"), switchesFor("freebsd", noPings = false))
        assertEquals(listOf("--no-pings"), switchesFor("freebsd", domainReliability = false))
        assertEquals(emptyList(), switchesFor("freebsd", noPings = false, domainReliability = false))
    }

    @Test
    fun `the windows occlusion opt-out can be turned off, leaving the rest of windows intact`() {
        val switches = switchesFor("windows 11", winOcclusion = false)
        assertFalse("--disable-features=CalculateNativeWinOcclusion" in switches)
        assertTrue("--no-pings" in switches, "turning off one switch must not drop the universal set")
    }

    @Test
    fun `VA-API can be turned off for machines whose driver is broken, container mode included`() {
        val desktop = switchesFor("linux", vaapi = false)
        assertEquals(emptyList(), platformSpecific(desktop))
        // The container switch is a separate concern and must survive: /dev/shm sizing has nothing
        // to do with whether video decode is hardware-accelerated.
        val container = switchesFor("linux", inContainer = true, vaapi = false)
        assertEquals(listOf("--disable-dev-shm-usage"), platformSpecific(container))
    }

    /**
     * The exact slip `SwitchToggles.from`'s own KDoc warns about, which nothing tested.
     *
     * The helper in this file builds `SwitchToggles(...)` positionally and never calls `from`, so a
     * `?: false` where a `?: true` belongs — silently stripping `--no-pings` and VA-API from every
     * user who never opened the Settings screen — passed the whole suite.
     */
    @Test
    fun `from() maps a default settings object to the shipped defaults`() {
        assertEquals(
            FluckEngine.SwitchToggles(),
            FluckEngine.SwitchToggles.from(
                ai.rever.boss.config
                    .ChromiumFlagsSettings(),
            ),
        )
    }

    @Test
    fun `from() carries an explicit off through for each switch`() {
        val allOff =
            ai.rever.boss.config.ChromiumFlagsSettings(
                noPings = false,
                disableDomainReliability = false,
                disableWinOcclusion = false,
                enableVaapi = false,
            )
        assertEquals(
            FluckEngine.SwitchToggles(noPings = false, domainReliability = false, winOcclusion = false, vaapi = false),
            FluckEngine.SwitchToggles.from(allOff),
        )
    }

    // --- gated switches: the extra-switches field must not route around a confirmation ---

    @Test
    fun `the extra-switches field refuses switches that have their own confirmed row`() {
        // The text box reached the same end states as the sandbox and DevTools toggles, which are
        // deliberately behind dialogs spelling out the exposure. A confirmation that can be
        // sidestepped by typing is not a confirmation.
        val parsed =
            FluckEngine.partitionExtraSwitches(
                "--no-sandbox --remote-debugging-port=9222 --disable-setuid-sandbox --harmless-flag",
            )
        assertEquals(listOf("--harmless-flag"), parsed.accepted)
        // In `gated`, NOT `malformed`. They are well-formed switches refused for a different
        // reason, and reporting them as "does not start with --" told the user something plainly
        // false and sent them to fix a prefix that was never missing.
        assertEquals(
            listOf("--no-sandbox", "--remote-debugging-port=9222", "--disable-setuid-sandbox"),
            parsed.gated,
        )
        assertEquals(emptyList(), parsed.malformed, "a gated switch is not a malformed one")
    }

    @Test
    fun `gating is narrow and does not pretend to sanitise switches in general`() {
        // Deliberately NOT a general-purpose filter: the field is documented as unrestricted, and
        // trying to make arbitrary Chromium switches safe is not a winnable game. It closes only
        // the paths that bypass a gate this app itself put up.
        val parsed =
            FluckEngine.partitionExtraSwitches("--disable-web-security --proxy-server=http://x --load-extension=/tmp/e")
        assertEquals(3, parsed.accepted.size)
        assertEquals(emptyList(), parsed.gated)
    }

    // --- disk cache ---

    @Test
    fun `disk cache falls back to the shipped size and clamps nonsense`() {
        assertEquals(FluckEngine.DEFAULT_DISK_CACHE_MB, FluckEngine.diskCacheMb(null))
        assertEquals(1024, FluckEngine.diskCacheMb(1024))
        // 0 is the case that matters: JxBrowser/Chromium read a zero cache size as "size it
        // yourself", so honouring a typed 0 as "no cache" would hand the user the several-hundred-MB
        // auto-sized cache instead - the opposite of what they asked for.
        assertEquals(1, FluckEngine.diskCacheMb(0))
        assertEquals(1, FluckEngine.diskCacheMb(-500))
        assertEquals(8192, FluckEngine.diskCacheMb(99_999_999))
    }

    @Test
    fun `cgroup predicate recognizes container runtimes and rejects host cgroups`() {
        assertTrue(FluckEngine.cgroupIndicatesContainer("12:pids:/docker/abc123"))
        assertTrue(FluckEngine.cgroupIndicatesContainer("11:memory:/kubepods/burstable/pod1"))
        assertTrue(FluckEngine.cgroupIndicatesContainer("0::/system.slice/containerd.service/x"))
        assertTrue(FluckEngine.cgroupIndicatesContainer("10:cpu:/lxc/mycontainer"))
        // Typical host cgroups — including the bare cgroup-v2 root a container may
        // ALSO show (documented limitation; BOSS_IN_CONTAINER covers that case).
        assertFalse(FluckEngine.cgroupIndicatesContainer("0::/"))
        assertFalse(FluckEngine.cgroupIndicatesContainer("12:pids:/user.slice/user-501.slice"))
    }

    // --- BOSS_BROWSER_REMOTE_DEBUGGING_PORT ---

    @Test
    fun `remote debugging port accepts only unprivileged ports`() {
        assertEquals(9222, FluckEngine.parseRemoteDebuggingPort("9222"))
        assertEquals(1024, FluckEngine.parseRemoteDebuggingPort(" 1024 "))
        assertEquals(65535, FluckEngine.parseRemoteDebuggingPort("65535"))
    }

    @Test
    fun `remote debugging port rejects anything that is not a usable port`() {
        // "0" matters most: Chromium reads port 0 as "pick any free port", which
        // would open a DevTools endpoint — full control of the browser profile —
        // on a port nobody knows about. A typo must never land there.
        for (raw in listOf(null, "", "  ", "0", "80", "1023", "65536", "-1", "9222x", "nine")) {
            assertEquals(null, FluckEngine.parseRemoteDebuggingPort(raw), "expected rejection of '$raw'")
        }
    }

    @Test
    fun `window-owned browser input routes only to its focused owner`() {
        val focusedRoute =
            FluckEngine.resolveBrowserKeyEventRoute(
                ownerWindowId = "window-a",
                ownerWindowIsFocused = true,
                fallbackFocusedWindowId = "window-b",
            )
        assertTrue(focusedRoute.acceptsInput)
        assertEquals("window-a", focusedRoute.shortcutWindowId)

        // isWindowFocused returns false for both inactive and unregistered owners.
        val unfocusedOrUnregisteredRoute =
            FluckEngine.resolveBrowserKeyEventRoute(
                ownerWindowId = "window-a",
                ownerWindowIsFocused = false,
                fallbackFocusedWindowId = "window-b",
            )
        assertFalse(unfocusedOrUnregisteredRoute.acceptsInput)
        assertEquals(null, unfocusedOrUnregisteredRoute.shortcutWindowId)
    }

    @Test
    fun `legacy unowned browser routes shortcuts to the focused window`() {
        val route =
            FluckEngine.resolveBrowserKeyEventRoute(
                ownerWindowId = null,
                ownerWindowIsFocused = false,
                fallbackFocusedWindowId = "window-b",
            )

        assertTrue(route.acceptsInput)
        assertEquals("window-b", route.shortcutWindowId)
    }
}
