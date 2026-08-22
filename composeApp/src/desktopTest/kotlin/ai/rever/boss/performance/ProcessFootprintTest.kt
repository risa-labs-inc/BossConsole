package ai.rever.boss.performance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Guards the ownership rule and the per-platform parsers in [ProcessFootprint].
 *
 * The ownership rule is the part worth testing, because getting it wrong is silent in both
 * directions and neither direction shows up as an exception. Claim too much and the indicator
 * charges BOSS for the user's own workload - measured at 4,414 MB of `claude` CLIs, Python MCP
 * children and a tunnel on the machine this was written on, all of them descendants of the host
 * JVM by way of a terminal tab. Claim too little and the orphaned engine and plugin processes
 * this indicator exists to surface go uncounted, which is the failure that made it necessary.
 */
class ProcessFootprintTest {
    private val hostPid = 57786L
    private val engineDirs = listOf("/Users/dev/.boss/boss-chromium")

    private fun classify(
        commandLine: String,
        pid: Long = 99999L,
    ) = ProcessFootprint.classify(pid, commandLine, hostPid, engineDirs)

    // region ownership

    @Test
    fun `the host JVM is claimed by pid`() {
        assertEquals(ProcessFootprint.Owner.HOST, classify("/Applications/BOSS.app/Contents/MacOS/BOSS", pid = hostPid))
    }

    @Test
    fun `plugin hosts are claimed by their main class`() {
        assertEquals(
            ProcessFootprint.Owner.PLUGIN,
            classify("/usr/bin/java -Xmx2621m -cp boss-plugin-docker-1.0.1.jar ai.rever.boss.PluginProcessMainKt"),
        )
    }

    @Test
    fun `both the engine main process and its helpers are claimed`() {
        assertEquals(
            ProcessFootprint.Owner.BROWSER,
            classify("/Users/dev/.boss/boss-chromium/BOSS.app/Contents/MacOS/BOSS --port=62988 --browsercore"),
        )
        assertEquals(
            ProcessFootprint.Owner.BROWSER,
            classify(
                "/Users/dev/.boss/boss-chromium/BOSS.app/Contents/Frameworks/Chromium Framework.framework/" +
                    "Helpers/BOSS Helper.app/Contents/MacOS/BOSS Helper --type=renderer",
            ),
        )
    }

    /**
     * The reparented-helper case. Ownership is decided by the executable path alone, so a process
     * that has been orphaned to pid 1 and is no longer a descendant of ours is still counted -
     * which is the entire reason this is a command-line match and not a descendant walk.
     */
    @Test
    fun `an engine process orphaned to init is still ours`() {
        assertEquals(
            ProcessFootprint.Owner.BROWSER,
            classify(
                "/Users/dev/.boss/boss-chromium/BOSS.app/Contents/Frameworks/Chromium Framework.framework/A",
                pid = 57827L,
            ),
        )
    }

    /**
     * The 4.4 GB regression. Every one of these was a live descendant of the host JVM when this
     * was written, by way of a shell in a terminal tab. None of them is BOSS's memory, and none
     * of it is memory BOSS could release.
     */
    @Test
    fun `processes the user started in a terminal are not ours`() {
        assertNull(classify("claude --dangerously-skip-permissions"))
        val python = "/opt/homebrew/Cellar/python@3.14/3.14.6/Frameworks/Python.framework/Versions/3.14/bin/python3"
        assertNull(classify(python))
        assertNull(classify("/Users/dev/.bossterm/bin/cloudflared --no-autoupdate tunnel --url http://127.0.0.1:8080"))
        assertNull(classify("/bin/zsh -l"))
        assertNull(classify("/opt/homebrew/bin/uv tool uvx --from git+https://github.com/oraios/serena serena"))
    }

    /**
     * A different BOSS install's engine, or the user's own Chrome, must not be charged to us.
     */
    @Test
    fun `chromium outside our engine directory is not ours`() {
        assertNull(classify("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"))
        assertNull(classify("/Users/other/.boss/boss-chromium/BOSS.app/Contents/MacOS/BOSS"))
    }

    /**
     * An empty prefix would make `startsWith` true for every process on the machine, so a failed
     * engine-directory lookup would silently claim the entire system as BOSS's footprint. The
     * guard against that is one `isNotEmpty()` call and this is what holds it in place.
     */
    @Test
    fun `an empty engine directory claims nothing`() {
        assertNull(ProcessFootprint.classify(99999L, "/bin/zsh -l", hostPid, listOf("")))
        assertNull(ProcessFootprint.classify(99999L, "/bin/zsh -l", hostPid, emptyList()))
    }

    // endregion

    // region sampling cadence

    /**
     * The cadence exists for a measured reason: on a machine running 1,227 processes the
     * ownership scan cost ~95 ms against ~48 ms to measure the dozen pids we own, and running
     * both every 10 s burnt about 1.5% of a core continuously to draw one status-bar glyph.
     */
    @Test
    fun `discovery is skipped while the process set looks unchanged`() {
        val t0 = 1_000_000L
        assertEquals(false, ProcessFootprint.needsDiscovery(t0, t0 + 1_000, 1227, countAtDiscovery = 1227))
        assertEquals(
            false,
            ProcessFootprint.needsDiscovery(t0, t0 + ProcessFootprint.DISCOVERY_TTL_MS - 1, 1227, 1227),
        )
    }

    @Test
    fun `a changed process count rediscovers immediately`() {
        // A new renderer or plugin host must not wait out the age ceiling to be counted.
        val t0 = 1_000_000L
        assertEquals(true, ProcessFootprint.needsDiscovery(t0, t0 + 1, 1228, countAtDiscovery = 1227))
        assertEquals(true, ProcessFootprint.needsDiscovery(t0, t0 + 1, 1226, countAtDiscovery = 1227))
    }

    @Test
    fun `the age ceiling still forces a rediscovery`() {
        // The count is only a proxy: a simultaneous start and stop leaves it unchanged while
        // membership has moved underneath. The ceiling is what bounds that error.
        val t0 = 1_000_000L
        assertEquals(true, ProcessFootprint.needsDiscovery(t0, t0 + ProcessFootprint.DISCOVERY_TTL_MS, 1227, 1227))
    }

    @Test
    fun `the first call always discovers`() {
        assertEquals(true, ProcessFootprint.needsDiscovery(0L, 0L, 0, countAtDiscovery = -1))
    }

    // endregion

    // region parsers

    @Test
    fun `ps output parses to bytes`() {
        val parsed = ProcessFootprint.parsePsRssOutput("  57786 2597568\n  57824  266240\n")
        assertEquals(mapOf(57786L to 2597568L * 1024, 57824L to 266240L * 1024), parsed)
    }

    @Test
    fun `ps garbage is skipped rather than failing the whole reading`() {
        val parsed = ProcessFootprint.parsePsRssOutput("57786 2597568\nps: bad pid\n\n57824 notanumber\n")
        assertEquals(mapOf(57786L to 2597568L * 1024), parsed)
    }

    @Test
    fun `powershell csv parses past its header and quoting`() {
        val csv = "\"Id\",\"WorkingSet64\"\r\n\"4812\",\"2721513472\"\r\n\"5120\",\"272629760\"\r\n"
        assertEquals(mapOf(4812L to 2721513472L, 5120L to 272629760L), ProcessFootprint.parseWindowsCsv(csv))
    }

    @Test
    fun `proc fields parse by label`() {
        val rollup = "Rss:               12345 kB\nPss:                8192 kB\nShared_Clean:       1024 kB\n"
        assertEquals(8192L, ProcessFootprint.parseProcKb(rollup, "Pss:"))
        assertEquals(12345L, ProcessFootprint.parseProcKb(rollup, "Rss:"))
        assertNull(ProcessFootprint.parseProcKb(rollup, "VmRSS:"))
    }

    @Test
    fun `a failed query yields no entries rather than zeroes`() {
        assertEquals(emptyMap(), ProcessFootprint.parsePsRssOutput(null))
        assertEquals(emptyMap(), ProcessFootprint.parseWindowsCsv(null))
    }

    // endregion
}
