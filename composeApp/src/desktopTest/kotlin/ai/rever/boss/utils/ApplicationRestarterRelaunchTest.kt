package ai.rever.boss.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplicationRestarterRelaunchTest {
    @Test
    fun `a translocated bundle is not relaunched`() {
        assertTrue(MacBundleRelaunch.isRelaunchable("/Applications/BOSS.app"))
        assertTrue(MacBundleRelaunch.isRelaunchable("/Volumes/BOSS/BOSS.app"))
        assertFalse(
            MacBundleRelaunch.isRelaunchable("/private/var/folders/x1/abc/T/AppTranslocation/0F1E2D3C/d/BOSS.app"),
        )
    }

    @Test
    fun `only non-credential BOSS overrides are forwarded, by reference`() {
        val names =
            listOf("PATH", "BOSS_TOOLKIT_PRELOAD", "BOSS_DEV_MODE", "BOSS_API_TOKEN", "SUPABASE_ANON_KEY", "boss_x")
        assertEquals(
            "--env \"BOSS_DEV_MODE=\$BOSS_DEV_MODE\" --env \"BOSS_TOOLKIT_PRELOAD=\$BOSS_TOOLKIT_PRELOAD\" ",
            MacBundleRelaunch.forwardedEnvFlags(names),
        )
    }

    @Test
    fun `open falls back to no overrides and is plain without any`() {
        assertEquals("open 'BOSS.app'", MacBundleRelaunch.openCommand("'BOSS.app'", listOf("HOME")))
        assertEquals(
            "open --env \"BOSS_DEV_MODE=\$BOSS_DEV_MODE\" 'BOSS.app' || open 'BOSS.app'",
            MacBundleRelaunch.openCommand("'BOSS.app'", listOf("BOSS_DEV_MODE")),
        )
    }
}
