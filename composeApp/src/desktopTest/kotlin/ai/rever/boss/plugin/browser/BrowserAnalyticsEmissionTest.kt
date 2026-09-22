package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.api.ApplicationEvent
import ai.rever.boss.plugin.api.ApplicationEventBusRegistry
import ai.rever.boss.plugin.api.BrowserEvent
import ai.rever.boss.plugin.api.BrowserEventType
import ai.rever.boss.plugin.api.BrowserInteractionEvent
import ai.rever.boss.plugin.api.BrowserInteractionType
import ai.rever.boss.plugin.api.BrowserNavigationType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the emitters actually put on the bus.
 *
 * The other suites call the sanitizers directly, which proves the sanitizers work but not
 * that [BrowserAnalytics] still *applies* them — and applies the right one to the right
 * field. A test asserting `value.takeIf { it in 0..100 }` is null has re-implemented the
 * predicate and passes whether or not the production code kept it. So these capture real
 * events through [ApplicationEventBusRegistry.systemPublisher] and read the fields off them.
 *
 * That wiring is the part most likely to rot: adding a field to the event, or swapping which
 * sanitizer guards `elementRole`, breaks nothing that the direct-call tests can see.
 */
class BrowserAnalyticsEmissionTest {
    private val captured = mutableListOf<ApplicationEvent>()
    private var previousPublisher: ((ApplicationEvent) -> Unit)? = null
    private var previousEnabled = true

    @BeforeTest
    fun install() {
        previousPublisher = ApplicationEventBusRegistry.systemPublisher
        previousEnabled = BrowserAnalytics.telemetryEnabled
        ApplicationEventBusRegistry.systemPublisher = { captured += it }
        // Explicit, not ambient. Most of these assert events DID reach the bus, so on a
        // machine or CI leg with BOSS_BROWSER_TELEMETRY_DISABLED set they would otherwise
        // fail as confusing empty-list assertions about something they do not test.
        BrowserAnalytics.telemetryEnabled = true
        BrowserInteractionBridge.ProcessRateLimit.resetForTest()
    }

    @AfterTest
    fun restore() {
        ApplicationEventBusRegistry.systemPublisher = previousPublisher
        BrowserAnalytics.telemetryEnabled = previousEnabled
    }

    private fun browserEvents() = captured.filterIsInstance<BrowserEvent>()

    private fun interactions() = captured.filterIsInstance<BrowserInteractionEvent>()

    // ============================================================
    // The kill switch — the one control an operator has, and until now untested.
    // ============================================================

    @Test
    fun `nothing at all reaches the bus when telemetry is disabled`() {
        BrowserAnalytics.telemetryEnabled = false

        BrowserAnalytics.pageViewed("availity.com", BrowserNavigationType.TYPED, 1)
        BrowserAnalytics.pageLeft("availity.com", dwellMs = 5_000, activeMs = 5_000)
        BrowserAnalytics.tabEvent(BrowserEventType.TAB_OPENED, "availity.com")
        BrowserAnalytics.tabEvent(BrowserEventType.TAB_CLOSED, null)
        BrowserAnalytics.interaction(BrowserInteractionType.CLICK, "availity.com", elementTag = "button")

        assertTrue(captured.isEmpty(), "kill switch let through: $captured")
    }

    @Test
    fun `every event source is live again when telemetry is enabled`() {
        // The mirror of the above: a gate that blocked everything permanently would also
        // pass that test.
        BrowserAnalytics.telemetryEnabled = true

        BrowserAnalytics.pageViewed("availity.com", BrowserNavigationType.TYPED, 1)
        BrowserAnalytics.pageLeft("availity.com", dwellMs = 5_000, activeMs = 5_000)
        BrowserAnalytics.tabEvent(BrowserEventType.TAB_OPENED, "availity.com")
        BrowserAnalytics.interaction(BrowserInteractionType.CLICK, "availity.com", elementTag = "button")

        assertEquals(3, browserEvents().size)
        assertEquals(1, interactions().size)
    }

    // ============================================================
    // Page views and engagement.
    // ============================================================

    @Test
    fun `a page view carries the reduced domain and the navigation context`() {
        BrowserAnalytics.pageViewed(
            authority = "portal.availity.com:443",
            navigationType = BrowserNavigationType.TYPED,
            pageIndexInVisit = 3,
            windowId = "w1",
        )

        val event = browserEvents().single()
        assertEquals(BrowserEventType.PAGE_VIEWED, event.browserEventType)
        assertEquals("availity.com", event.domain, "the subdomain and port must not survive")
        assertEquals(BrowserNavigationType.TYPED, event.navigationType)
        assertEquals(3, event.pageIndexInVisit)
        assertEquals("w1", event.windowId, "per-window attribution has to reach the event")
    }

    @Test
    fun `an unreportable authority emits no page view at all`() {
        BrowserAnalytics.pageViewed("localhost:3000")
        BrowserAnalytics.pageViewed("192.168.1.20")
        BrowserAnalytics.pageViewed("intranet")

        assertTrue(browserEvents().isEmpty(), "leaked: ${browserEvents().map { it.domain }}")
    }

    @Test
    fun `active time is clamped to wall clock rather than reported above it`() {
        // Drift between two counters is expected; a page reported as more-than-fully-read
        // is not, and would put engagement over 100% in every downstream average.
        BrowserAnalytics.pageLeft("availity.com", dwellMs = 5_000, activeMs = 9_999)

        val event = browserEvents().single()
        assertEquals(5_000L, event.dwellMs)
        assertEquals(5_000L, event.activeMs)
    }

    @Test
    fun `an impossible visit length is dropped instead of reported`() {
        BrowserAnalytics.pageLeft("availity.com", dwellMs = -1, activeMs = 0)
        BrowserAnalytics.pageLeft("availity.com", dwellMs = 0, activeMs = -1)
        // Beyond twelve hours the clock is suspect, not the user diligent.
        BrowserAnalytics.pageLeft("availity.com", dwellMs = 13L * 60 * 60 * 1000, activeMs = 0)

        assertTrue(browserEvents().isEmpty(), "reported: ${browserEvents().map { it.dwellMs }}")
    }

    @Test
    fun `a tab with nothing loaded is distinguishable from one on an unreportable host`() {
        BrowserAnalytics.tabEvent(BrowserEventType.TAB_OPENED, null)
        BrowserAnalytics.tabEvent(BrowserEventType.TAB_OPENED, "localhost:3000")
        BrowserAnalytics.tabEvent(BrowserEventType.TAB_OPENED, "portal.availity.com")

        assertEquals(
            listOf(
                BrowserAnalytics.BLANK_TAB_DOMAIN,
                BrowserAnalytics.UNREPORTABLE_TAB_DOMAIN,
                "availity.com",
            ),
            browserEvents().map { it.domain },
        )
    }

    // ============================================================
    // Interactions: which sanitizer guards which field.
    // ============================================================

    @Test
    fun `each interaction field is guarded by its own sanitizer`() {
        // Structural fields are refused whole; the field name is cleaned; the path must be
        // tags and ordinals only. Swapping any two of these compiles and passes every
        // direct-call test, and only fails here.
        BrowserAnalytics.interaction(
            type = BrowserInteractionType.FIELD_FOCUSED,
            authority = "portal.availity.com",
            elementTag = "Patient: John Smith",
            elementRole = "MRN 4417882",
            inputType = "TEXT",
            fieldName = "patient_mrn_4417882",
            elementPath = "form>div:2>input:1",
            windowId = "w1",
        )

        val event = interactions().single()
        assertEquals(BrowserInteractionType.FIELD_FOCUSED, event.interactionType)
        assertEquals("availity.com", event.domain)
        assertNull(event.elementTag, "free text refused as a tag")
        assertNull(event.elementRole, "free text refused as a role")
        assertEquals("text", event.inputType, "a real control kind survives, lowercased")
        assertEquals("patient_mrn_#", event.fieldName, "the schema stays, the identifier goes")
        assertEquals("form>div:2>input:1", event.elementPath)
        assertEquals("w1", event.windowId)
    }

    @Test
    fun `a field name that looks like a person is refused before it becomes an event`() {
        BrowserAnalytics.interaction(
            type = BrowserInteractionType.FIELD_FOCUSED,
            authority = "availity.com",
            fieldName = "John Smith",
        )

        assertNull(interactions().single().fieldName)
    }

    @Test
    fun `an element path carrying a selector never reaches the event`() {
        BrowserAnalytics.interaction(
            type = BrowserInteractionType.CLICK,
            authority = "availity.com",
            elementPath = "form>input[value='John Smith']",
        )

        assertNull(interactions().single().elementPath)
    }

    @Test
    fun `out of range counts are dropped by the emitter, not merely by the test`() {
        BrowserAnalytics.interaction(
            type = BrowserInteractionType.SCROLL_DEPTH,
            authority = "availity.com",
            scrollDepthPercent = 9_999,
            repeatCount = 0,
        )
        BrowserAnalytics.interaction(
            type = BrowserInteractionType.RAGE_CLICK,
            authority = "availity.com",
            scrollDepthPercent = 75,
            repeatCount = 3,
        )

        val (bogus, real) = interactions()
        assertNull(bogus.scrollDepthPercent, "a percentage over 100 is not a percentage")
        assertNull(bogus.repeatCount, "a repeat count starts at 1")
        assertEquals(75, real.scrollDepthPercent)
        assertEquals(3, real.repeatCount)
    }

    @Test
    fun `an interaction on an unreportable host emits nothing`() {
        BrowserAnalytics.interaction(BrowserInteractionType.CLICK, "localhost:3000", elementTag = "button")

        assertTrue(interactions().isEmpty())
    }

    // ============================================================
    // All the way through: a JSON batch off the page becomes an event.
    // ============================================================

    private fun bridge(authority: String? = "portal.availity.com") =
        BrowserInteractionBridge(
            authorityProvider = { authority },
            windowId = { "w1" },
        )

    /**
     * Emit exactly as the injected collector does, with this session's nonce.
     *
     * These tests are about what a *legitimately sent* batch turns into, so they have to clear
     * the nonce gate. What a batch without the nonce does is pinned separately, in
     * [BrowserInteractionTamperResistanceTest].
     */
    private fun BrowserInteractionBridge.emitFromCollector(json: String) = emit(sessionNonce, json)

    @Test
    fun `a batch off the page becomes an event with every field in its own slot`() {
        // The tests either side of this one cover parseBatch and BrowserAnalytics.interaction
        // separately; the eight-field hand-mapping in handle() sits between them and is
        // covered by neither. Writing `fieldName = entry.path` there passes every other test
        // in this suite while shipping an element path into whatever a dashboard labels
        // "field name", so the wire names and the event fields are pinned together here.
        bridge().emitFromCollector(
            """
            [{"type":"FIELD_FOCUSED","tag":"input","role":"searchbox","inputType":"TEXT",
              "fieldName":"patient_mrn_4417882","path":"form>div:2>input:1"}]
            """.trimIndent(),
        )

        val event = interactions().single()
        assertEquals(BrowserInteractionType.FIELD_FOCUSED, event.interactionType)
        assertEquals("availity.com", event.domain, "reduced, not the authority the bridge holds")
        assertEquals("input", event.elementTag)
        assertEquals("searchbox", event.elementRole)
        assertEquals("text", event.inputType)
        assertEquals("patient_mrn_#", event.fieldName)
        assertEquals("form>div:2>input:1", event.elementPath)
        assertEquals("w1", event.windowId)
    }

    @Test
    fun `a hostile batch reaches the bus with its content stripped, not with an error`() {
        bridge().emitFromCollector(
            """
            [{"type":"CLICK","tag":"Patient: John Smith","role":"MRN 4417882",
              "fieldName":"John Smith","path":"form>input[value='John Smith']"},
             {"type":"KEYSTROKE","tag":"input"},
             {"type":"SCROLL_DEPTH","scrollDepthPercent":9999}]
            """.trimIndent(),
        )

        assertEquals(2, interactions().size, "the unknown type is dropped, the rest survive")
        val click = interactions().first()
        assertEquals("availity.com", click.domain)
        assertNull(click.elementTag)
        assertNull(click.elementRole)
        assertNull(click.fieldName)
        assertNull(click.elementPath)
        assertNull(interactions()[1].scrollDepthPercent)
        assertTrue(
            captured.none { it.toString().contains("John Smith") },
            "page content reached the bus: $captured",
        )
    }

    @Test
    fun `a batch arriving with no reportable page emits nothing`() {
        // This is the mechanism that keeps clicks on an error page - or on a dev server -
        // off a domain the user never reached: dispose() and a failed navigation both clear
        // the authority the bridge reads.
        bridge(authority = null).emitFromCollector("""[{"type":"CLICK","tag":"button"}]""")
        bridge(authority = "localhost:3000").emitFromCollector("""[{"type":"CLICK","tag":"button"}]""")

        assertTrue(interactions().isEmpty(), "emitted: ${interactions()}")
    }

    @Test
    fun `the kill switch stops a page batch too, not just the host emitters`() {
        BrowserAnalytics.telemetryEnabled = false

        bridge().emitFromCollector("""[{"type":"CLICK","tag":"button"}]""")

        assertTrue(captured.isEmpty())
    }
}
