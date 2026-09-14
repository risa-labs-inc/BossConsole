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
 * The acceptance test for browser telemetry: drive a deliberately hostile page all the way
 * through the boundary and assert that **nothing identifying reaches the bus**.
 *
 * Every other suite here checks one layer. [BrowserRouteTemplateTest] proves the templater
 * classifies a segment correctly, [BrowserInteractionBridgeTest] proves the parser refuses
 * junk, [BrowserAnalyticsEmissionTest] proves the emitters apply the right sanitizer to the
 * right field. None of them answers the question anyone actually asks of this feature, which
 * is whether a real page full of real identifiers can get one of them out.
 *
 * So this drives the two production entry points - [BrowserAnalytics.pageViewed] for
 * navigation, and [BrowserInteractionBridge.emit] for the page→host bridge, which is the
 * same call `window.__bossInteraction.emit()` makes - and then scans every published event
 * for the identifiers that were fed in.
 *
 * **The scan is the point, and it is a denylist on purpose.** Asserting specific fields (as
 * the other suites do) can only check the fields somebody thought of; a new field added to
 * [BrowserEvent] next year is covered here and nowhere else, because this reads the whole
 * event rather than a list of getters.
 *
 * It replaces a manual check - browse a trap page, read the payload - that was only ever
 * going to be run once.
 */
class BrowserTelemetryEndToEndTest {
    private val captured = mutableListOf<ApplicationEvent>()
    private var previousPublisher: ((ApplicationEvent) -> Unit)? = null
    private var previousEnabled = true

    @BeforeTest
    fun install() {
        previousPublisher = ApplicationEventBusRegistry.systemPublisher
        previousEnabled = BrowserAnalytics.telemetryEnabled
        ApplicationEventBusRegistry.systemPublisher = { captured += it }
        BrowserAnalytics.telemetryEnabled = true
        BrowserInteractionBridge.ProcessRateLimit.resetForTest()
    }

    @AfterTest
    fun restore() {
        ApplicationEventBusRegistry.systemPublisher = previousPublisher
        BrowserAnalytics.telemetryEnabled = previousEnabled
    }

    /**
     * Everything the trap page puts in front of the collector: the identifiers in its URL,
     * its form, its table and its links, plus the URL scheme itself.
     *
     * Matched case-insensitively and as substrings, so `8837261` catches the REF wherever it
     * is embedded - in a path segment, a field name, or a tag. The dates are here because a
     * slashed date is the one shape that looks like a path to the downstream scrubber and is
     * dropped for the wrong reason; catching it at this layer means the guarantee does not
     * depend on that accident.
     */
    private val forbidden =
        listOf(
            "john-smith",
            "John Smith",
            "johnsmith",
            "jane.doe@example.com",
            "jane.doe",
            "8837261",
            "4417882",
            "03/14/1961",
            "11/02/1955",
            "Account 8837261 - John Smith",
            "accounts/",
            "claims/",
            "http://",
            "https://",
            "section-2",
            "report.pdf",
        )

    private fun assertNothingIdentifyingEscaped(where: String) {
        assertTrue(captured.isNotEmpty(), "$where: nothing was published, so this proves nothing")
        val serialized = captured.joinToString("\n") { it.toString() }
        for (needle in forbidden) {
            assertTrue(
                !serialized.contains(needle, ignoreCase = true),
                "$where: '$needle' reached the event bus.\nPublished:\n$serialized",
            )
        }
    }

    private fun bridge() =
        BrowserInteractionBridge(
            authorityProvider = { "portal.acmecorp.com" },
            windowId = { null },
            nowMs = { 0L },
        )

    /** Exactly what a hostile page can put through `window.__bossInteraction.emit()`. */
    private val trapBatch =
        """
        [
          {"type":"CLICK","tag":"a","role":"link","path":"table>tr:4>td:2",
           "linkHost":"portal.partner.example","linkKind":"external"},
          {"type":"FIELD_FOCUSED","tag":"input","inputType":"text","fieldName":"account_ref_8837261"},
          {"type":"FORM_SUBMITTED","tag":"form","fieldName":"John Smith"},
          {"type":"CLICK","tag":"row-4417882","path":"table>tr:2>td:1"},
          {"type":"TEXT_SELECTED","tag":"td","role":"cell","path":"table>tr:4>td:2",
           "selectionChars":47,"selectionWords":7,"selectionHasDigits":true},
          {"type":"CLICK","tag":"a","linkHost":"127.0.0.1","linkKind":"internal"}
        ]
        """.trimIndent()

    @Test
    fun `a url carrying a name and an id publishes neither`() {
        BrowserAnalytics.pageViewed(
            authority = "portal.acmecorp.com",
            navigationType = BrowserNavigationType.LINK,
            pageIndexInVisit = 2,
            path = "https://portal.acmecorp.com/accounts/john-smith/claims/8837261?ref=8837261#tok",
        )

        val event = captured.filterIsInstance<BrowserEvent>().single()
        // Still useful: the shape of the page survives even though nothing on it does.
        assertEquals("acmecorp.com", event.domain, "subdomain must be collapsed")
        assertEquals("accounts>:word>claims>:num", event.route)
        assertEquals(false, event.routeKnown, "two segments were placeholdered")
        assertEquals(2, event.pageIndexInVisit)

        assertNothingIdentifyingEscaped("page view")
    }

    @Test
    fun `a hostile interaction batch publishes only structure`() {
        bridge().emit(trapBatch)

        val events = captured.filterIsInstance<BrowserInteractionEvent>()
        assertEquals(6, events.size, "every entry should publish, reduced rather than refused")

        // The digit runs are redacted rather than the events dropped - the interaction is
        // still worth counting, it just says nothing about which row.
        val focus = events.single { it.interactionType == BrowserInteractionType.FIELD_FOCUSED }
        assertEquals("account_ref_#", focus.fieldName)

        // Two tokens is not a form-encoding key; refusing the value is what stops a name.
        val submit = events.single { it.interactionType == BrowserInteractionType.FORM_SUBMITTED }
        assertNull(submit.fieldName, "a two-token field name must be refused, not welded together")

        val rowClick = events.first { it.elementTag?.startsWith("row") == true }
        assertEquals("row-#", rowClick.elementTag)

        assertNothingIdentifyingEscaped("interaction batch")
    }

    @Test
    fun `link detail is reduced to a domain, and a loopback target to nothing`() {
        bridge().emit(trapBatch)
        val links = captured.filterIsInstance<BrowserInteractionEvent>().filter { it.linkKind != null }

        val external = links.single { it.linkKind == "external" }
        assertEquals("partner.example", external.linkTargetDomain, "the link's subdomain must collapse too")
        assertEquals(true, external.linkIsExternal)

        // A bare IP is not a site. The host decides that, not the page - which is also why
        // `linkIsExternal` is derived here rather than believed.
        val loopback = links.single { it.linkKind == "internal" }
        assertNull(loopback.linkTargetDomain, "a loopback link target must not be reported")
        assertNull(loopback.linkIsExternal, "with no target domain there is nothing to compare")
    }

    @Test
    fun `a selection is published as shape and never as content`() {
        bridge().emit(trapBatch)
        val selection =
            captured
                .filterIsInstance<BrowserInteractionEvent>()
                .single { it.interactionType == BrowserInteractionType.TEXT_SELECTED }

        assertEquals(25, selection.selectionCharBucket, "47 characters floors to the 25 bucket")
        assertEquals(7, selection.selectionWordCount)
        assertEquals(true, selection.selectionHasDigits)
        assertEquals("cell", selection.elementRole, "where it was selected is still reported")

        // The exact length is what fingerprints the underlying value; the bucket is what does not.
        assertTrue(
            !selection.toString().contains("47"),
            "the exact selection length must not survive bucketing: $selection",
        )
    }

    @Test
    fun `a whole hostile session leaks nothing across every event it produces`() {
        // The full sequence a visit produces, in order, through the real entry points.
        BrowserAnalytics.tabEvent(BrowserEventType.TAB_OPENED, "portal.acmecorp.com")
        BrowserAnalytics.pageViewed(
            authority = "portal.acmecorp.com",
            navigationType = BrowserNavigationType.TYPED,
            pageIndexInVisit = 1,
            path = "/accounts/john-smith?ref=8837261",
        )
        bridge().emit(trapBatch)
        BrowserAnalytics.pageLeft("portal.acmecorp.com", dwellMs = 42_000, activeMs = 31_500)
        BrowserAnalytics.tabEvent(BrowserEventType.TAB_CLOSED, "portal.acmecorp.com")

        // Guard against a vacuous pass: a scan over an empty list trivially finds nothing.
        assertEquals(4, captured.filterIsInstance<BrowserEvent>().size)
        assertEquals(6, captured.filterIsInstance<BrowserInteractionEvent>().size)

        assertNothingIdentifyingEscaped("full session")
    }
}
