package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.api.ApplicationEvent
import ai.rever.boss.plugin.api.ApplicationEventBusRegistry
import ai.rever.boss.plugin.api.BrowserInteractionEvent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The host half of the tamper-resistance design: the channel credential, and what a page
 * without it can and cannot do.
 *
 * JxBrowser 9.5.0 has no isolated JS world, so the injected collector necessarily shares a JS
 * context with the page and the bridge has to be published on the page's own `window`. What
 * keeps a page out is that the collector copies the bridge into a closure and removes it
 * before any page script runs - but "removal never fails" is not a property a Kotlin unit test
 * can establish, and it is not the only thing standing there. This file pins the second line:
 * a batch must carry the per-session nonce, which exists only as a closure variable on the
 * page side and as a literal in the injected source.
 *
 * What a page holding a *valid* nonce can still do is bounded elsewhere and deliberately:
 * `parseBatch` and the sanitizers in [BrowserInteractionBridgeTest], the rate limiter in
 * [BrowserInteractionBridgeTest] and [BrowserAnalyticsEmissionTest]. What is *not* covered by
 * any of them, and is the whole point of this file:
 *
 * - a rejection costs the page nothing, so a page that reaches the bridge without the nonce
 *   cannot spend the legitimate collector's rate budget out from under it;
 * - the nonce and the reset-slot name are independent draws, so learning one by enumerating
 *   `window` does not yield the other;
 * - neither is the fixed name a page used to be able to pre-set to switch collection off.
 *
 * The runtime behaviour of the injected script itself - that the bridge really is unreachable,
 * that the surviving property really is non-writable - lives in
 * `scripts/test/test-browser-collector-tamper.js`, which executes it. Nothing here runs JS.
 */
class BrowserInteractionTamperResistanceTest {
    private val captured = mutableListOf<ApplicationEvent>()
    private var previousPublisher: ((ApplicationEvent) -> Unit)? = null
    private var previousEnabled = true

    /**
     * PROCESS-GLOBAL, like the two sibling suites: the rate window is shared by every bridge
     * in the process, and [BrowserAnalytics]'s kill switch and the bus publisher are
     * process-wide too. Correct today because desktopTest runs one class at a time - see the
     * note on [BrowserInteractionBridgeTest.resetProcessWindow].
     */
    @BeforeTest
    fun install() {
        previousPublisher = ApplicationEventBusRegistry.systemPublisher
        previousEnabled = BrowserAnalytics.telemetryEnabled
        ApplicationEventBusRegistry.systemPublisher = { captured += it }
        // Explicit rather than ambient: on a leg with BOSS_BROWSER_TELEMETRY_DISABLED set,
        // "the nonce gate let this through" would fail as an empty list for a reason that has
        // nothing to do with the nonce.
        BrowserAnalytics.telemetryEnabled = true
        BrowserInteractionBridge.ProcessRateLimit.resetForTest()
    }

    @AfterTest
    fun restore() {
        ApplicationEventBusRegistry.systemPublisher = previousPublisher
        BrowserAnalytics.telemetryEnabled = previousEnabled
    }

    private fun bridge(
        authority: String? = "portal.availity.com",
        clock: () -> Long = System::currentTimeMillis,
    ) = BrowserInteractionBridge(
        authorityProvider = { authority },
        windowId = { "w1" },
        nowMs = clock,
    )

    private val validBatch = """[{"type":"CLICK","tag":"button"}]"""

    // ============================================================
    // The gate itself: which batches survive, and which do not.
    // ============================================================

    @Test
    fun `a batch carrying the session nonce is published`() {
        // The mirror of every rejection below. A gate that refused everything would satisfy
        // all of them and would also mean the feature reports nothing at all.
        val bridge = bridge()
        bridge.emit(bridge.sessionNonce, validBatch)

        assertEquals(1, captured.filterIsInstance<BrowserInteractionEvent>().size)
    }

    @Test
    fun `a batch with no nonce is dropped before it is parsed`() {
        // What a page reaches when the closure copy failed and it found the bridge itself:
        // it can call emit() with anything, and nothing about that call needs to be valid.
        bridge().emit(null, validBatch)

        assertTrue(captured.isEmpty(), "published without a nonce: $captured")
    }

    @Test
    fun `a batch with the wrong nonce is dropped`() {
        val bridge = bridge()
        bridge.emit("0".repeat(bridge.sessionNonce.length), validBatch)
        bridge.emit("", validBatch)
        bridge.emit("not-a-nonce", validBatch)

        assertTrue(captured.isEmpty(), "published on a wrong nonce: $captured")
    }

    @Test
    fun `a nonce that merely starts the real one is refused`() {
        // The bug class a `startsWith` - or an `expected.take(n) == candidate` - comparison
        // would ship: every prefix of the credential is accepted, which reduces the work to
        // guessing one character at a time. Pinned in both directions, because a length check
        // that also rejected the exact value would pass the "prefix refused" half alone.
        val bridge = bridge()
        val real = bridge.sessionNonce

        bridge.emit(real.dropLast(1), validBatch)
        bridge.emit(real.drop(1), validBatch)
        bridge.emit(real + "0", validBatch)
        assertTrue(captured.isEmpty(), "a partial nonce was accepted: $captured")

        bridge.emit(real, validBatch)
        assertEquals(1, captured.filterIsInstance<BrowserInteractionEvent>().size)
    }

    @Test
    fun `an uppercased nonce is refused`() {
        // Hex is emitted lowercase, so a case-insensitive comparison would double the space a
        // guess has to cover for no benefit. Pins the comparison as byte-exact.
        val bridge = bridge()
        bridge.emit(bridge.sessionNonce.uppercase(), validBatch)

        assertTrue(captured.isEmpty(), "case-folded match: $captured")
    }

    @Test
    fun `a rejected batch does not spend the collector's rate budget`() {
        // This is why the nonce check sits ahead of the reservation in handle(). A page that
        // reaches the bridge but not the nonce is the cheap case to defend: it can call
        // emit() in a loop, and if that loop were metered the same way real traffic is, a
        // site could exhaust the window and silence its own - or, worse, its neighbours' -
        // legitimate telemetry without ever producing an event. Rejection has to be free.
        val bridge = bridge(clock = { 0L })
        repeat(BrowserInteractionBridge.MAX_ENTRIES_PER_WINDOW * 4) {
            bridge.emit("0".repeat(bridge.sessionNonce.length), validBatch)
        }

        assertEquals(
            50,
            bridge.tryReserve(50),
            "a wrong-nonce flood was charged to the window",
        )
        assertTrue(captured.isEmpty(), "a wrong-nonce flood published: $captured")
    }

    // ============================================================
    // matchesNonce, as a pure function.
    // ============================================================

    @Test
    fun `matchesNonce compares the whole value, not a prefix`() {
        val real = "0123456789abcdef0123456789abcdef"
        assertTrue(BrowserInteractionBridge.matchesNonce(real, real))
        // Same length, differing in the first byte and in the last: both are refusals, so a
        // comparison that stopped at the first difference would still be correct here - what
        // the pair rules out is one that stopped at the first *match*.
        assertFalse(BrowserInteractionBridge.matchesNonce(real, "x123456789abcdef0123456789abcdef"))
        assertFalse(BrowserInteractionBridge.matchesNonce(real, "0123456789abcdef0123456789abcdey"))
        // Different lengths: the length check is the cheap guard, and it has to refuse both
        // directions rather than only the longer candidate.
        assertFalse(BrowserInteractionBridge.matchesNonce(real, real.dropLast(1)))
        assertFalse(BrowserInteractionBridge.matchesNonce(real, real + "0"))
    }

    @Test
    fun `matchesNonce treats a null or empty candidate as no credential`() {
        assertFalse(BrowserInteractionBridge.matchesNonce("", null))
        assertFalse(BrowserInteractionBridge.matchesNonce("abc", null), "null must never match")
        assertFalse(BrowserInteractionBridge.matchesNonce("abc", ""))
        // Empty-vs-empty is the one accidental match: it is unreachable in production because
        // the expected side is always 32 hex characters, and it is pinned rather than
        // special-cased so the behaviour is a decision instead of a surprise.
        assertTrue(BrowserInteractionBridge.matchesNonce("", ""))
    }

    @Test
    fun `matchesNonce compares encoded bytes, so a multi-byte character cannot half-match`() {
        // The comparison runs over encodeToByteArray(), which is UTF-8. A page-supplied
        // nonce is attacker-controlled text, not necessarily ASCII, and `é` is two bytes -
        // so a comparison that walked `String` characters while sizing on bytes (or the
        // reverse) would disagree about length. Both sides of that are pinned.
        assertTrue(BrowserInteractionBridge.matchesNonce("é", "é"))
        assertFalse(BrowserInteractionBridge.matchesNonce("é", "e"), "one byte is not two")
        assertFalse(BrowserInteractionBridge.matchesNonce("e", "é"))
        assertFalse(BrowserInteractionBridge.matchesNonce("é", "éé"))
    }

    // ============================================================
    // The two injected literals: shape, encoding, and independence.
    // ============================================================

    /**
     * Sixteen bytes chosen to exercise every branch of the hex encoder: a leading zero, a
     * value whose high nibble is zero, the ASCII boundary, and both signed-byte extremes -
     * `-1` and `-128` are where a `toString(16)` without `and 0xFF` produces `-1` / `-80`.
     */
    private val pinnedBytes =
        byteArrayOf(0x00, 0x05, 0x0f, 0x10, 0x7f, -1, -128, 0x0a, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x08)

    @Test
    fun `the nonce is 128 bits of lowercase hex`() {
        val nonce = BrowserInteractionBridge.newSessionNonce()

        assertEquals(32, nonce.length, "16 bytes hex-encode to 32 characters")
        assertTrue(
            nonce.all { it in "0123456789abcdef" },
            "not lowercase hex: $nonce",
        )
    }

    @Test
    fun `the nonce encoding is pinned, so a signed byte cannot corrupt it`() {
        // The supplier is injectable precisely so this can be pinned without depending on
        // entropy. `and 0xFF` is load-bearing: without it the byte -1 encodes as "-1" and the
        // nonce is a different length for some sessions than others, which would read as an
        // intermittent rejection rather than a bug.
        assertEquals(
            "00050f107fff800a1122334455667708",
            BrowserInteractionBridge.newSessionNonce { pinnedBytes },
        )
    }

    @Test
    fun `the reset slot name is the prefixed, host-chosen part of the same encoding`() {
        // Twelve bytes, so 24 hex characters - deliberately shorter than the nonce, because
        // it guards no secret and only has to be unguessable.
        val slot = BrowserInteractionBridge.newResetSlotName { pinnedBytes }

        assertEquals("__boss_i_00050f107fff800a11223344", slot)
        assertEquals(9 + 24, slot.length)
    }

    @Test
    fun `two bridges never share a nonce or a reset slot name`() {
        // The real property, on the real CSPRNG rather than an injected one: "per-session"
        // and "unguessable" both reduce to this. A hardcoded value, a constant seeded once
        // per process, or a slot name derived from the nonce all fail here.
        val bridges = (1..200).map { bridge() }

        val nonces = bridges.map { it.sessionNonce }
        val slots = bridges.map { it.resetSlotName }
        assertEquals(200, nonces.toSet().size, "a nonce repeated across sessions")
        assertEquals(200, slots.toSet().size, "a reset slot name repeated across sessions")
    }

    @Test
    fun `the reset slot name is an independent draw, not the nonce under another name`() {
        // Drawn separately so that a page which learns one by enumerating `window` - the slot
        // name is, by construction, the one property it can find there - does not thereby
        // learn the other. Structural as well as statistical: the slot name carries the
        // `__boss_i_` prefix and is 9 characters longer than the nonce it sits beside.
        for (bridge in (1..50).map { bridge() }) {
            assertNotEquals(bridge.sessionNonce, bridge.resetSlotName)
            assertFalse(bridge.sessionNonce.contains("__boss_i_"))
            assertFalse(bridge.resetSlotName.contains(bridge.sessionNonce))
        }
    }

    @Test
    fun `the reset slot is not the fixed name a page used to be able to pre-set`() {
        // The bug this replaced: the collector looked for `window.__bossInteractionStarted`
        // and returned early if it was truthy, so any page could switch collection off for the
        // rest of the document with one line. A host-chosen name removes that - but only for
        // as long as the name is not one the page already knows.
        val retired = listOf("__bossInteractionStarted", "__bossInteractionReset")
        for (slot in (1..50).map { bridge().resetSlotName }) {
            assertFalse(slot in retired, "the retired fixed name came back: $slot")
            assertFalse(slot.startsWith("__bossInteraction"), "readable from the page: $slot")
        }
    }

    // ============================================================
    // The injected source carries both literals, and nothing else.
    // ============================================================

    @Test
    fun `the collector source carries the nonce and the slot name, and no retired global`() {
        // BrowserHandleImpl passes the two values from the bridge into source(), so this is
        // what makes the host-side gate above reachable at all: a collector that sent some
        // other value, or a fixed one, would be rejected by the very check it depends on.
        val bridge = bridge()
        val code = BrowserInteractionScript.source(nonce = bridge.sessionNonce, slotName = bridge.resetSlotName)

        assertTrue(code.contains(bridge.sessionNonce), "the injected source does not carry the nonce")
        assertTrue(code.contains(bridge.resetSlotName), "the injected source does not carry the slot name")
        assertTrue(code.contains("emit(NONCE,"), "the collector does not send the nonce with its batch")
        // Comments stripped first. The comment above the re-injection check names the retired
        // flag in order to explain what it replaced, and that is documentation worth keeping -
        // so what this looks at is whether the collector still *reads* one. Same idiom as
        // BrowserInteractionBridgeTest.collectorCode, and for the same reason.
        val codeOnly = code.lines().joinToString("\n") { it.substringBefore("//") }
        for (retired in listOf("__bossInteractionStarted", "__bossInteractionReset")) {
            assertFalse(codeOnly.contains(retired), "$retired is still read by the injected collector")
        }
    }
}
