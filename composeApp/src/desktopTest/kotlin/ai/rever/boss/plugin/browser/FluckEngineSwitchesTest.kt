package ai.rever.boss.plugin.browser

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
            FluckEngine.parseExtraSwitches("  --enable-features=SkiaGraphite   --disk-cache-size=1048576 ")
        )
        // Entries that don't look like switches are dropped, not passed through.
        assertEquals(
            listOf("--ok"),
            FluckEngine.parseExtraSwitches("not-a-switch --ok rm -single-dash")
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
            FluckEngine.parseExtraSwitches("--enable-features=SkiaGraphite,VaapiVideoDecoder --no-first-run")
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
    ) = FluckEngine.performanceSwitchesFor(os, arch, graphiteOptIn, inContainer, extras)

    @Test
    fun `windows disables the native-window occlusion tracker`() {
        assertEquals(listOf("--disable-features=CalculateNativeWinOcclusion"), switchesFor("windows 11"))
    }

    @Test
    fun `SkiaGraphite is opt-in only — it blanks OSR output on JxBrowser 9-3`() {
        // Verified live 2026-07-13: Graphite-on produced blank browser content on
        // Apple Silicon (frames never reached the Compose surface); Graphite-off
        // rendered normally. Default must therefore be OFF.
        assertEquals(emptyList(), switchesFor("mac os x", arch = "aarch64"))
        assertEquals(
            listOf("--enable-features=SkiaGraphite"),
            switchesFor("mac os x", arch = "aarch64", graphiteOptIn = true)
        )
        // Intel macs never get Graphite, even opted in.
        assertEquals(emptyList(), switchesFor("mac os x", arch = "x86_64", graphiteOptIn = true))
    }

    @Test
    fun `linux enables VA-API and adds container-only switches inside containers`() {
        val desktop = switchesFor("linux")
        assertEquals(listOf("--enable-features=VaapiVideoDecoder,VaapiVideoDecodeLinuxGL,VaapiVideoEncoder"), desktop)
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
        val (accepted, dropped) = FluckEngine.partitionExtraSwitches("--ok not-a-switch -single --also-ok")
        assertEquals(listOf("--ok", "--also-ok"), accepted)
        assertEquals(listOf("not-a-switch", "-single"), dropped)
    }

    @Test
    fun `extra switches are appended last so operator flags win ties`() {
        val switches = switchesFor("windows 11", extras = listOf("--disk-cache-size=1"))
        assertEquals("--disk-cache-size=1", switches.last())
        assertTrue("--disable-features=CalculateNativeWinOcclusion" in switches)
    }

    @Test
    fun `unknown platforms get no platform-specific switches`() {
        assertEquals(emptyList(), switchesFor("freebsd"))
        assertEquals(emptyList(), switchesFor("sunos", inContainer = true))
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

    @Test
    fun `window-owned browser input routes only to its focused owner`() {
        val focusedRoute = FluckEngine.resolveBrowserKeyEventRoute(
            ownerWindowId = "window-a",
            ownerWindowIsFocused = true,
            fallbackFocusedWindowId = "window-b"
        )
        assertTrue(focusedRoute.acceptsInput)
        assertEquals("window-a", focusedRoute.shortcutWindowId)

        // isWindowFocused returns false for both inactive and unregistered owners.
        val unfocusedOrUnregisteredRoute = FluckEngine.resolveBrowserKeyEventRoute(
            ownerWindowId = "window-a",
            ownerWindowIsFocused = false,
            fallbackFocusedWindowId = "window-b"
        )
        assertFalse(unfocusedOrUnregisteredRoute.acceptsInput)
        assertEquals(null, unfocusedOrUnregisteredRoute.shortcutWindowId)
    }

    @Test
    fun `legacy unowned browser routes shortcuts to the focused window`() {
        val route = FluckEngine.resolveBrowserKeyEventRoute(
            ownerWindowId = null,
            ownerWindowIsFocused = false,
            fallbackFocusedWindowId = "window-b"
        )

        assertTrue(route.acceptsInput)
        assertEquals("window-b", route.shortcutWindowId)
    }
}
