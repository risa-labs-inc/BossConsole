package ai.rever.boss.mcp.coordination

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [AgentClaims], the rules behind the coordination board.
 *
 * Pure: no file, no clock, no registry. The interesting content here is a set of judgement calls
 * (what counts as the same file, when a claim dies, who overlaps with whom) and each one is
 * pinned so changing it is a deliberate act rather than a side effect.
 */
class AgentClaimsTest {
    private val t0 = 1_000_000L

    private fun claim(
        id: String,
        files: List<String> = emptyList(),
        task: String = "work",
        at: Long = t0,
        ttlMinutes: Int = 30,
    ) = AgentClaim(
        agentId = id,
        task = task,
        files = files,
        claimedAtMs = at,
        expiresAtMs = AgentClaims.expiryFor(at, ttlMinutes),
    )

    // ------------------------------------------------------------- identity

    @Test
    fun `an agent id must be a name, not a path or a sentence`() {
        assertEquals("claude-1", AgentClaims.sanitizeAgentId("claude-1"))
        assertEquals("codex.2", AgentClaims.sanitizeAgentId("  codex.2  "), "surrounding space is not an error")
        assertEquals("a_b", AgentClaims.sanitizeAgentId("a_b"))

        assertNull(AgentClaims.sanitizeAgentId(null))
        assertNull(AgentClaims.sanitizeAgentId(""))
        assertNull(AgentClaims.sanitizeAgentId("has space"))
        assertNull(AgentClaims.sanitizeAgentId("../../etc/passwd"), "an id is never a path")
        assertNull(AgentClaims.sanitizeAgentId("-leading-dash"))
        assertNull(AgentClaims.sanitizeAgentId("x".repeat(AgentClaims.MAX_AGENT_ID_LENGTH + 1)))
    }

    // ------------------------------------------------------------- paths

    @Test
    fun `the same file spelled differently still matches`() {
        // The direction that matters: a MISSED overlap is two agents editing one file thinking
        // they are alone, which is the failure this exists to prevent.
        val expected = AgentClaims.pathKey("src/main/Foo.kt")
        listOf(
            """src\main\Foo.kt""",
            "./src/main/Foo.kt",
            "src//main//Foo.kt",
            "  src/main/Foo.kt  ",
            "SRC/MAIN/FOO.KT",
        ).forEach { assertEquals(expected, AgentClaims.pathKey(it), "did not match: $it") }
    }

    @Test
    fun `genuinely different files do not match`() {
        assertTrue(AgentClaims.pathKey("src/Foo.kt") != AgentClaims.pathKey("src/Bar.kt"))
        assertTrue(AgentClaims.pathKey("a/Foo.kt") != AgentClaims.pathKey("b/Foo.kt"))
    }

    @Test
    fun `an unusable path is rejected rather than stored`() {
        assertNull(AgentClaims.pathKey(null))
        assertNull(AgentClaims.pathKey("   "))
        assertNull(AgentClaims.pathKey("/"))
        assertNull(AgentClaims.pathKey("x".repeat(AgentClaims.MAX_PATH_LENGTH + 1)))
    }

    @Test
    fun `duplicate paths collapse and the list is capped`() {
        val files = AgentClaims.sanitizeFiles(listOf("src/A.kt", """src\A.kt""", "./src/A.kt", "src/B.kt"))
        assertEquals(listOf("src/A.kt", "src/B.kt"), files, "three spellings of one file is one file")

        val many = AgentClaims.sanitizeFiles((1..500).map { "src/F$it.kt" })
        assertEquals(AgentClaims.MAX_FILES_PER_CLAIM, many.size)
    }

    // ------------------------------------------------------------- expiry

    @Test
    fun `a claim expires, which is what makes a crashed agent disappear`() {
        val c = claim("ghost", ttlMinutes = 1)
        assertTrue(c.isActive(t0))
        assertTrue(c.isActive(t0 + 59_000))
        assertFalse(c.isActive(t0 + 61_000), "nobody has to clean up after an agent that died")

        assertEquals(1, AgentClaims.active(listOf(c), t0).size)
        assertEquals(0, AgentClaims.active(listOf(c), t0 + 61_000).size)
    }

    @Test
    fun `ttl is clamped rather than rejected`() {
        assertEquals(AgentClaims.DEFAULT_TTL_MINUTES, AgentClaims.clampTtlMinutes(null))
        assertEquals(AgentClaims.MAX_TTL_MINUTES, AgentClaims.clampTtlMinutes(99_999), "no permanent claims")
        assertEquals(AgentClaims.MIN_TTL_MINUTES, AgentClaims.clampTtlMinutes(0))
        assertEquals(AgentClaims.MIN_TTL_MINUTES, AgentClaims.clampTtlMinutes(-5))
        assertEquals(45, AgentClaims.clampTtlMinutes(45))
    }

    // ------------------------------------------------------------- board

    @Test
    fun `a second claim from one agent replaces the first`() {
        // Re-claiming is a new statement of what an agent is doing, not an additional one.
        val board =
            AgentClaims.upsert(
                listOf(claim("claude-1", listOf("A.kt"))),
                claim("claude-1", listOf("B.kt"), at = t0 + 1000),
                t0 + 1000,
            )
        assertEquals(1, board.size)
        assertEquals(listOf("B.kt"), board.single().files)
    }

    @Test
    fun `agent ids are matched case insensitively so one agent cannot appear twice`() {
        val board = AgentClaims.upsert(listOf(claim("Claude-1")), claim("claude-1", at = t0 + 1), t0 + 1)
        assertEquals(1, board.size)
    }

    @Test
    fun `an expired claim is dropped on the next write`() {
        val board =
            AgentClaims.upsert(
                listOf(claim("dead", ttlMinutes = 1)),
                claim("alive", at = t0 + 120_000),
                t0 + 120_000,
            )
        assertEquals(listOf("alive"), board.map { it.agentId })
    }

    @Test
    fun `the board is capped so a runaway cannot wedge it`() {
        val full = (1..AgentClaims.MAX_AGENTS).map { claim("agent$it", at = t0 + it) }
        val board = AgentClaims.upsert(full, claim("newcomer", at = t0 + 10_000), t0 + 10_000)

        assertEquals(AgentClaims.MAX_AGENTS, board.size)
        assertTrue(board.any { it.agentId == "newcomer" }, "the newest claim must survive the cap")
    }

    @Test
    fun `release removes only that agent`() {
        val board = AgentClaims.release(listOf(claim("a"), claim("b")), "a", t0)
        assertEquals(listOf("b"), board.map { it.agentId })
    }

    // ------------------------------------------------------------- overlap

    @Test
    fun `two agents claiming one file are reported as overlapping`() {
        val board =
            listOf(
                claim("claude-1", listOf("src/Auth.kt", "src/User.kt")),
                claim("codex-2", listOf("src/Auth.kt", "src/Other.kt"), task = "add logging"),
            )

        val overlaps = AgentClaims.overlapsFor("claude-1", board, t0)

        assertEquals(1, overlaps.size)
        assertEquals("codex-2", overlaps.single().peerAgentId)
        assertEquals("add logging", overlaps.single().peerTask, "the peer's task is what makes it actionable")
        assertEquals(listOf("src/Auth.kt"), overlaps.single().sharedFiles)
    }

    @Test
    fun `overlap survives a different spelling of the same path`() {
        // The case the normalisation exists for, end to end.
        val board =
            listOf(
                claim("claude-1", listOf("src/Auth.kt")),
                claim("codex-2", listOf("""SRC\Auth.kt""")),
            )
        assertEquals(1, AgentClaims.overlapsFor("claude-1", board, t0).size)
    }

    @Test
    fun `an agent never overlaps with itself`() {
        val board = listOf(claim("solo", listOf("src/A.kt")))
        assertTrue(AgentClaims.overlapsFor("solo", board, t0).isEmpty())
    }

    @Test
    fun `claiming no files collides with nobody`() {
        // "I am busy on something" is legitimate and must not collide with every other agent.
        val board = listOf(claim("vague", emptyList()), claim("busy", listOf("src/A.kt")))
        assertTrue(AgentClaims.overlapsFor("vague", board, t0).isEmpty())
    }

    @Test
    fun `an expired peer does not produce an overlap`() {
        val board =
            listOf(
                claim("claude-1", listOf("src/A.kt"), at = t0 + 200_000),
                claim("ghost", listOf("src/A.kt"), ttlMinutes = 1),
            )
        assertTrue(AgentClaims.overlapsFor("claude-1", board, t0 + 200_000).isEmpty())
    }

    @Test
    fun `an unknown agent asking about overlap gets nothing rather than everything`() {
        val board = listOf(claim("someone", listOf("src/A.kt")))
        assertTrue(AgentClaims.overlapsFor("never-claimed", board, t0).isEmpty())
    }

    @Test
    fun `the worst overlap is reported first`() {
        val board =
            listOf(
                claim("me", listOf("A.kt", "B.kt", "C.kt")),
                claim("slight", listOf("C.kt")),
                claim("severe", listOf("A.kt", "B.kt")),
            )
        assertEquals(
            listOf("severe", "slight"),
            AgentClaims.overlapsFor("me", board, t0).map { it.peerAgentId },
        )
    }
}
