package ai.rever.boss.startup

import ai.rever.boss.cli.CLICommand
import ai.rever.boss.utils.DeepLinkOrigin
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelaunchHandoffTest {
    private val dir = createTempDirectory("relaunch-handoff").toFile()
    private val file = File(dir, "run/relaunch-handoff.json")

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `requests survive the relaunch in order and are consumed once`() {
        val commands =
            listOf(
                CLICommand.OpenUrl("boss://open?url=https://example.com", DeepLinkOrigin.EXTERNAL),
                CLICommand.OpenFile("/tmp/notes.md"),
                CLICommand.OpenFolder("/tmp/project"),
                CLICommand.LoadWorkspace("/tmp/space.json", DeepLinkOrigin.EXTERNAL),
                CLICommand.OpenTerminal(null, DeepLinkOrigin.EXTERNAL),
            )
        assertTrue(RelaunchHandoff.write(commands, forcePrewarm = true, file = file, now = 1_000))

        val payload = RelaunchHandoff.consume(file = file, now = 2_000)!!
        assertTrue(payload.forcePrewarm)
        assertEquals(commands, payload.requests.map { RelaunchHandoff.toCommand(it) })
        assertFalse(file.exists())
        assertNull(RelaunchHandoff.consume(file = file, now = 2_000))
    }

    @Test
    fun `operator requests are replayed as external`() {
        val operator =
            listOf(
                CLICommand.OpenTerminal("rm -rf build", DeepLinkOrigin.OPERATOR_CLI),
                CLICommand.OpenUrl("https://example.com", DeepLinkOrigin.OPERATOR_CLI),
                CLICommand.LoadWorkspace("/tmp/space.json", DeepLinkOrigin.OPERATOR_CLI),
            )
        RelaunchHandoff.write(operator, forcePrewarm = false, file = file, now = 0)

        val replayed = RelaunchHandoff.consume(file = file, now = 0)!!.requests.map { RelaunchHandoff.toCommand(it) }
        assertEquals(
            listOf(
                CLICommand.OpenTerminal("rm -rf build", DeepLinkOrigin.EXTERNAL),
                CLICommand.OpenUrl("https://example.com", DeepLinkOrigin.EXTERNAL),
                CLICommand.LoadWorkspace("/tmp/space.json", DeepLinkOrigin.EXTERNAL),
            ),
            replayed,
        )
    }

    @Test
    fun `stale, future-dated and unreadable handoffs are ignored and removed`() {
        RelaunchHandoff.write(emptyList(), forcePrewarm = true, file = file, now = 0)
        assertNull(RelaunchHandoff.consume(file = file, now = RelaunchHandoff.MAX_AGE_MILLIS + 1))
        assertFalse(file.exists())

        RelaunchHandoff.write(emptyList(), forcePrewarm = true, file = file, now = 10_000)
        assertNull(RelaunchHandoff.consume(file = file, now = 0))

        file.writeText("not json")
        assertNull(RelaunchHandoff.consume(file = file, now = 0))
        assertFalse(file.exists())
    }

    @Test
    fun `an unknown request kind is dropped rather than guessed`() {
        assertNull(RelaunchHandoff.toCommand(RelaunchHandoff.Request("shell", "id")))
        assertNull(RelaunchHandoff.toCommand(RelaunchHandoff.Request("url", null)))
    }
}
