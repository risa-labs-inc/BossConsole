package ai.rever.boss.startup

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopLaunchArgumentsTest {
    @Test
    fun `windowless flag is removed before forwarding open requests`() {
        val launch = parseDesktopLaunchArguments(arrayOf("--no-window", "boss://workspace/example", "--no-window"))
        assertTrue(launch.windowlessRequested)
        assertContentEquals(arrayOf("boss://workspace/example"), launch.cliArgs)
    }

    @Test
    fun `headless commands receive only their CLI arguments`() {
        val launch = parseDesktopLaunchArguments(arrayOf("--no-window", "status", "--json"))
        assertTrue(launch.windowlessRequested)
        assertContentEquals(arrayOf("status", "--json"), launch.cliArgs)
        assertTrue(CliBootstrap.isHeadlessCli(launch.cliArgs))
    }

    @Test
    fun `normal launches retain their arguments and request a window`() {
        val args = arrayOf("--workspace", "/tmp/example")
        val launch = parseDesktopLaunchArguments(args)
        assertFalse(launch.windowlessRequested)
        assertContentEquals(args, launch.cliArgs)
    }
}
