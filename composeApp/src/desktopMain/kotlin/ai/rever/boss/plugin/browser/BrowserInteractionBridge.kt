package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.api.BrowserInteractionType
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import com.teamdev.jxbrowser.js.JsAccessible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom

/**
 * Page→host bridge for in-page interaction telemetry.
 *
 * An instance is published on each page's `window.__bossInteraction` and the injected
 * [BrowserInteractionScript] calls [emit] with a JSON batch. This class parses the batch
 * and hands each entry to [BrowserAnalytics], which sanitizes every field before an event
 * exists.
 *
 * **Everything arriving here is untrusted.** The bridge has to be published on the page's own
 * `window` at injection time, because JxBrowser 9.5.0 offers no isolated JS world to publish
 * it into (see [BrowserInteractionScript], which records how that was established). The
 * injected collector copies it into a closure and removes it immediately, so a page script
 * running after document-start injection never obtains a reference — but this class does not
 * rest on that alone:
 *
 * - every batch must carry the **per-session nonce**, which exists only as a closure variable
 *   in the collector and as a literal in the injected source. A batch without it is dropped
 *   before it is parsed, so a page that does reach the bridge cannot forge events with it;
 * - it still parses defensively (unknown interaction types dropped, non-conforming fields
 *   dropped by the sanitizers, oversized batches truncated), because a nonce proves which
 *   script sent a batch, not that the batch is well-formed;
 * - and it is rate-limited per tab and per process, so even a page holding a valid nonce
 *   cannot use it to flood the shared bus.
 *
 * The nonce is a second line, not the first: what keeps the page out is that it has no
 * reference to this object. See [BrowserInteractionScript]'s KDoc for what remains — a page
 * can always synthesise the DOM events the collector observes, and those arrive here
 * legitimately, carrying a valid nonce.
 *
 * [emit] runs on a JxBrowser thread and must not block or throw into the page's JS thread.
 */
internal class BrowserInteractionBridge(
    private val authorityProvider: () -> String?,
    /** Resolved per batch, not captured: a tab moves between windows. */
    private val windowId: () -> String?,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /**
     * This session's channel credential: one per bridge, so one per browser handle.
     *
     * Never written to a property the page can read by name, and injected into the collector
     * as a literal, so the only way to obtain it is to read a closure variable.
     */
    internal val sessionNonce: String = newSessionNonce(),
    /**
     * The host-chosen name of the one property the collector leaves on `window` across a
     * re-injection: its per-route reset hook.
     *
     * Drawn independently of [sessionNonce], so a page that learns one by enumerating
     * `window` does not thereby learn the other.
     */
    internal val resetSlotName: String = newResetSlotName(),
) {
    private val logger = BossLogger.forComponent("BrowserInteractionBridge")

    @JsAccessible
    fun emit(
        nonce: String?,
        json: String,
    ) {
        if (!nonceMatches(nonce)) {
            reportRejectedNonce()
            return
        }
        try {
            handle(json)
        } catch (e: LinkageError) {
            // A wiring break rather than bad input: the bridge or the api jar is not what
            // this was compiled against. Enumerated rather than caught as Throwable, matching
            // BrowserHandleImpl.deliverContextMenu - an OutOfMemoryError or StackOverflowError
            // is not this boundary's to swallow, and swallowing it silently here would turn a
            // fatal process condition into a page that quietly reports nothing.
            reportFailure(e)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // Never propagate into the page's JS thread: a throw here surfaces in the site's
            // own console and can break its JS. Genuinely any Exception, because the input is
            // a hostile page and the failure modes are not enumerable - what matters is that
            // Error is NOT included, so a fatal process condition still escapes.
            reportFailure(e)
        }
    }

    /**
     * Log at most one failure per rate window.
     *
     * Total silence was the wrong trade in the other direction: a page nobody touches and a
     * bridge that was never published look identical, and `flush()` swallows on the page side
     * too, so a wiring break had no signal anywhere along the path. The flood argument against
     * logging is real - a hostile page can call `emit()` in a loop - but the rate limiter
     * already exists, so one line per window costs nothing and makes the failure findable.
     * The exception class only, never its message: page detail must not reach a log line.
     */
    private fun reportFailure(error: Throwable) {
        if (!shouldLogRejection()) return
        logger.debug(
            LogCategory.BROWSER,
            "Interaction batch rejected",
            mapOf("error" to (error::class.simpleName ?: "Throwable")),
        )
    }

    /**
     * A batch that did not carry this session's nonce.
     *
     * Shares [shouldLogRejection]'s window with [reportFailure]: a page that reaches the
     * bridge can call it in a loop, so the line exists to make the attempt findable rather
     * than to record every attempt. Deliberately does not log the value it received - that is
     * page-controlled and must not reach a log line.
     */
    private fun reportRejectedNonce() {
        if (!shouldLogRejection()) return
        logger.debug(
            LogCategory.BROWSER,
            "Interaction batch rejected: missing or wrong channel nonce",
            mapOf("reason" to "nonce"),
        )
    }

    /** At most one rejection line per [RATE_WINDOW_MS], whichever reason produced it. */
    private fun shouldLogRejection(): Boolean {
        val now = nowMs()
        return synchronized(this) {
            (now - lastFailureLogMs !in 0 until RATE_WINDOW_MS).also { if (it) lastFailureLogMs = now }
        }
    }

    /** [sessionNonce] is the expected value. See [matchesNonce]. */
    private fun nonceMatches(candidate: String?): Boolean = matchesNonce(sessionNonce, candidate)

    // Each return is a distinct reason a batch costs the page nothing further: no page, no
    // reportable host, no budget. Folding them into one condition would hide which is which.
    @Suppress("ReturnCount")
    private fun handle(json: String) {
        val authority = authorityProvider() ?: return
        // Drop a batch for an unreportable host before parsing it, not after: the reduction
        // is the same answer for all fifty entries, and paying a 64 KB parse to then discard
        // every entry is work done on the page's JS thread for nothing.
        //
        // Only a gate. interaction() still reduces the authority itself, and deliberately:
        // an overload taking an already-reduced domain would let some later caller hand the
        // privacy boundary a raw URL under the name `domain` and bypass the one reduction
        // everything depends on. Repeating a short-string trim/split fifty times per two-
        // second flush is not worth trading that for.
        if (BrowserAnalytics.registrableDomain(authority) == null) return
        // Reserved BEFORE parsing, because parsing is the expensive part and it runs on the
        // page's JS thread. Admitting afterwards bounded what reached the bus but not the
        // work done to get there: a page looping emit() with 64 KB payloads still bought
        // itself an unbounded run of JSON parses. A full batch is reserved because the size
        // isn't known until it is parsed, and whatever the batch didn't use is released
        // straight back, so an honest page sending three events is not charged for fifty.
        val reservation = reserve(MAX_BATCH_ENTRIES)
        val budget = reservation.count
        if (budget <= 0) return
        val parsed = parseBatch(json)
        // Resolved once, as this parameter's KDoc says: it was being called per entry.
        val window = windowId()
        // Refund what the batch didn't use, but never the whole reservation: one entry per
        // emit() is non-refundable. Refunding against the *publishable* count let a page
        // parse for free - 64 KB of `{"type":"KEYSTROKE"}`, or valid JSON that is an object
        // rather than an array, costs a full parseToJsonElement and yields zero entries, so
        // windowEntries never advanced and the loop the pre-parse reservation exists to bound
        // was unbounded again. Charging for the call itself bounds calls; refunding the rest
        // still means an honest three-event flush is not billed for fifty.
        release(reservation, budget - minOf(budget, parsed.size).coerceAtLeast(1))
        for (entry in parsed.take(budget)) {
            BrowserAnalytics.interaction(
                type = entry.type,
                authority = authority,
                elementTag = entry.tag,
                elementRole = entry.role,
                inputType = entry.inputType,
                fieldName = entry.fieldName,
                elementPath = entry.path,
                scrollDepthPercent = entry.scrollDepthPercent,
                repeatCount = entry.repeatCount,
                windowId = window,
            )
        }
    }

    /**
     * How many of [requested] entries may be published right now, rate-limited per bridge
     * (so per tab). Excess is dropped silently.
     *
     * The per-batch caps in [parseBatch] bound the cost of one call, not the call *rate*.
     * That matters more here than it looks: `ApplicationEventBus` is a `MutableSharedFlow`
     * with `extraBufferCapacity = 64` published via a non-suspending `tryEmit`, so once the
     * buffer fills, events are **dropped on the floor**. This bridge is the first
     * page-controlled producer on that shared bus — without a rate cap, a page looping
     * `emit()` could evict `AuthEvent` / `TabEvent` / `FileChangeEvent` deliveries out from
     * under any subscriber that isn't keeping up. A fixed window is enough: the goal is to
     * keep abuse cheap to attempt and useless in effect, not to meter precisely.
     *
     * Named for the side effect, since it takes budget rather than merely reporting it.
     */
    internal fun tryReserve(requested: Int): Int = reserve(requested).count

    /**
     * Take up to [requested] entries out of the current window's budget.
     *
     * Returns a token rather than a bare count so [release] can name the window it is giving
     * back to. A shared "last reservation" field would be exact single-threaded and wrong the
     * moment two `emit()` calls overlap: a reservation taken in one window could be credited
     * into the next, handing it free allowance. Nothing documents JxBrowser as delivering one
     * batch at a time per handle, so the bookkeeping does not assume it.
     */
    @Synchronized
    private fun reserve(requested: Int): Reservation {
        val now = nowMs()
        // Also resets when the clock jumps backwards, which costs at most one window.
        if (now - windowStartMs !in 0 until RATE_WINDOW_MS) {
            windowStartMs = now
            windowEntries = 0
        }
        // coerceAtLeast on the request first: coerceIn(0, requested) THROWS for a negative
        // requested rather than returning nothing, and tryReserve is internal and reachable.
        val perTab = (MAX_ENTRIES_PER_WINDOW - windowEntries).coerceIn(0, requested.coerceAtLeast(0))
        // The bus this protects is process-wide, so a per-tab cap alone is not one: the real
        // ceiling was MAX_ENTRIES_PER_WINDOW x open tabs, and twenty tabs is 5,000/sec into a
        // 64-slot tryEmit buffer - the eviction of AuthEvent / TabEvent / FileChangeEvent the
        // cap exists to prevent. Under this class's own threat model (any page can call
        // emit()) that needs nothing unusual, just a site opening tabs. Per-tab stays as the
        // fair-share limit so one busy page cannot spend the whole process budget.
        val taken = ProcessRateLimit.take(now, perTab)
        windowEntries += taken.count
        return Reservation(
            count = taken.count,
            windowStartMs = windowStartMs,
            processWindowStartMs = taken.windowStartMs,
        )
    }

    /**
     * Give back [unused] of [reservation] that the batch turned out not to need.
     *
     * Only into the window it was taken from: if the window rolled over between reserving
     * and parsing, the reservation is already forgotten and crediting it would hand the new
     * window free allowance.
     */
    @Synchronized
    private fun release(
        reservation: Reservation,
        unused: Int,
    ) {
        if (unused <= 0) return
        // The two levels are refunded independently, each against its own window stamp. An
        // early return on the per-tab check would also skip the process refund, so a per-tab
        // window rolling between reserve and release left the process budget over-charged
        // while the process window itself was still perfectly valid.
        if (reservation.windowStartMs == windowStartMs) {
            windowEntries = (windowEntries - unused).coerceAtLeast(0)
        }
        ProcessRateLimit.giveBack(reservation.processWindowStartMs, unused)
    }

    /**
     * A window shared by every bridge in the process, because the bus is.
     *
     * Deliberately coarse: it exists to keep a hostile page from evicting other subsystems'
     * events out of a 64-slot buffer, not to meter precisely. Its own window is tracked
     * separately from any tab's, so a refund arriving after the process window rolled over is
     * dropped rather than credited to the next one.
     */
    internal object ProcessRateLimit {
        private var windowStartMs: Long = 0
        private var entries: Int = 0

        /** [count] entries taken from the process window that began at [windowStartMs]. */
        data class Taken(
            val count: Int,
            val windowStartMs: Long,
        )

        @Synchronized
        fun take(
            now: Long,
            requested: Int,
        ): Taken {
            roll(now)
            val allowed = (MAX_ENTRIES_PER_PROCESS_WINDOW - entries).coerceIn(0, requested.coerceAtLeast(0))
            entries += allowed
            return Taken(count = allowed, windowStartMs = windowStartMs)
        }

        /**
         * Give back [unused] entries taken from the window that began at [takenFrom].
         *
         * Stamped rather than checked for freshness. Asking "is the current window young"
         * says yes for every window in its first RATE_WINDOW_MS, including a brand-new one -
         * so a refund landing just after a roll credited the new window with entries bought
         * in the old one. The per-tab side already carries a token; this makes the two levels
         * symmetrical rather than only claiming to be.
         */
        @Synchronized
        fun giveBack(
            takenFrom: Long,
            unused: Int,
        ) {
            if (takenFrom != windowStartMs) return
            entries = (entries - unused).coerceAtLeast(0)
        }

        private fun roll(now: Long) {
            if (now - windowStartMs !in 0 until RATE_WINDOW_MS) {
                windowStartMs = now
                entries = 0
            }
        }

        @Synchronized
        internal fun resetForTest() {
            windowStartMs = 0
            entries = 0
        }
    }

    /**
     * A claim on [count] entries, naming **both** windows it came from.
     *
     * The process window needs its own stamp for the reason [ProcessRateLimit.giveBack]
     * explains: freshness is not identity.
     */
    private data class Reservation(
        val count: Int,
        val windowStartMs: Long,
        val processWindowStartMs: Long,
    )

    private var windowStartMs: Long = 0
    private var windowEntries: Int = 0

    /** When a rejection was last logged, so a looping page cannot flood the log. */

    /** 0, not MIN_VALUE: the latter only works because `now - it` overflows to negative. */
    private var lastFailureLogMs: Long = 0

    /** One entry off the wire, still unsanitized — [BrowserAnalytics] is what cleans these. */
    internal data class ParsedInteraction(
        val type: BrowserInteractionType,
        val tag: String? = null,
        val role: String? = null,
        val inputType: String? = null,
        val fieldName: String? = null,
        val path: String? = null,
        val scrollDepthPercent: Int? = null,
        val repeatCount: Int? = null,
    )

    internal companion object {
        /**
         * Parse a wire batch into entries worth reporting. Total function: anything
         * unparseable, oversized, or of an unknown type yields fewer entries rather than an
         * error, because the caller is a web page and errors are its normal output.
         */
        fun parseBatch(json: String): List<ParsedInteraction> {
            val batch =
                json
                    .takeIf { it.length <= MAX_PAYLOAD_CHARS }
                    ?.let { runCatching { parser.parseToJsonElement(it) }.getOrNull() } as? JsonArray
                    ?: return emptyList()
            return batch.take(MAX_BATCH_ENTRIES).mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                val type = entry.text("type")?.let(::interactionType) ?: return@mapNotNull null
                ParsedInteraction(
                    type = type,
                    tag = entry.text("tag"),
                    role = entry.text("role"),
                    inputType = entry.text("inputType"),
                    fieldName = entry.text("fieldName"),
                    path = entry.text("path"),
                    scrollDepthPercent = entry.int("scrollDepthPercent"),
                    repeatCount = entry.int("repeatCount"),
                )
            }
        }

        private fun JsonObject.text(key: String): String? = runCatching { str(key) }.getOrNull()

        /**
         * `JsonNull.content` is the four-letter string `"null"`, which passes [sanitizeToken]
         * as a perfectly good tag name — so a JSON null arrived as an element literally named
         * "null" in a dashboard. Read it as the absent value it is.
         */
        private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

        /**
         * A count off the wire.
         *
         * Falls back to parsing as a double so `50.0` is fifty rather than absent. The
         * collector only ever emits integers, but this class's contract is that hostile input
         * costs the page events and nothing else — a silent drop on a value any JSON encoder
         * might produce is an accident, not a decision. Non-finite values (`NaN`, `Infinity`,
         * which `isLenient` accepts) have no integer meaning and stay absent.
         */
        private fun JsonObject.int(key: String): Int? {
            val raw = text(key) ?: return null
            return raw.toIntOrNull()
                ?: raw.toDoubleOrNull()?.takeIf { it.isFinite() && it in INT_RANGE }?.toInt()
        }

        /**
         * Resolve a wire name to a known interaction type, or null.
         *
         * An explicit `when` rather than `enumValueOf`: this is a boundary a page can reach,
         * and the set of interactions the collector may report should be readable here rather
         * than implied by whatever the enum happens to contain. A new constant is then a
         * deliberate addition in two places, not an accidental widening in one.
         */
        private fun interactionType(raw: String): BrowserInteractionType? =
            when (raw) {
                "CLICK" -> BrowserInteractionType.CLICK
                "RAGE_CLICK" -> BrowserInteractionType.RAGE_CLICK
                "SCROLL_DEPTH" -> BrowserInteractionType.SCROLL_DEPTH
                "FIELD_FOCUSED" -> BrowserInteractionType.FIELD_FOCUSED
                "FORM_SUBMITTED" -> BrowserInteractionType.FORM_SUBMITTED
                "COPY" -> BrowserInteractionType.COPY
                "PASTE" -> BrowserInteractionType.PASTE
                else -> null
            }

        private val parser =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }

        /** Bounds the double fallback in [int], so a huge literal cannot wrap on `toInt()`. */
        private val INT_RANGE = Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()

        /** A well-behaved batch is a few KB; beyond this the caller is not the collector. */
        const val MAX_PAYLOAD_CHARS = 64 * 1024
        const val MAX_BATCH_ENTRIES = 50

        /**
         * The collector flushes every 2s with at most 50 entries, so it needs ~25/sec. The
         * cap is an order of magnitude above that: generous for a busy page, far below what
         * it takes to starve a 64-slot bus.
         */
        const val RATE_WINDOW_MS = 1000L
        const val MAX_ENTRIES_PER_WINDOW = 250

        /**
         * The ceiling across every tab, since the bus is process-wide.
         *
         * Sized against the buffer rather than against what looks generous. The bus is a
         * `MutableSharedFlow` with `extraBufferCapacity = 64` published via non-suspending
         * `tryEmit`, and `tryEmit` fails for *every* subscriber once the slowest is behind -
         * so at 1000/sec a subscriber stalling 64ms starts dropping, which is a routine GC
         * pause. The collector needs about 25/sec per tab, so 300 still leaves a dozen busy
         * tabs untouched while keeping the residual an order of magnitude below the rate that
         * evicts `AuthEvent` / `TabEvent` / `FileChangeEvent` out from under a slow consumer.
         */
        const val MAX_ENTRIES_PER_PROCESS_WINDOW = 300

        /** 128 bits of CSPRNG output, hex-encoded. See [newSessionNonce]. */
        private const val NONCE_BYTES = 16

        /** Enough to make the reset slot name unguessable. It guards no secret. */
        private const val RESET_SLOT_BYTES = 12

        /**
         * Names the reset slot as the collector's own.
         *
         * A named constant rather than an inline literal so the line above stays inside the
         * limit without ktlint wanting the body joined onto the signature - which it would, at
         * exactly the limit, and which would read worse than this does.
         */
        private const val RESET_SLOT_PREFIX = "__boss_i_"

        /**
         * A fresh channel credential for one session - one [BrowserInteractionBridge], so one
         * browser handle.
         *
         * [random] is injectable so a test can pin the encoding rather than the entropy.
         */
        internal fun newSessionNonce(random: () -> ByteArray = ::secureRandomBytes): String = hex(random(), NONCE_BYTES)

        /**
         * The host-chosen name of the collector's per-route reset hook - the one property it
         * leaves on `window`.
         *
         * A fixed name was the bug: a page could pre-set `window.__bossInteractionStarted` and
         * the collector returned early, so nothing was collected for the rest of that
         * document's life. A name the page cannot guess removes that.
         */
        internal fun newResetSlotName(random: () -> ByteArray = ::secureRandomBytes): String =
            RESET_SLOT_PREFIX + hex(random(), RESET_SLOT_BYTES)

        private fun hex(
            bytes: ByteArray,
            count: Int,
        ): String =
            bytes
                .take(count)
                .joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }

        /**
         * Constant-time comparison, so the nonce cannot be recovered byte by byte from how
         * long a rejection takes. Timing is a weak channel over an in-process bridge, but the
         * comparison is free and the alternative is a `==` that short-circuits on the first
         * differing byte.
         */
        internal fun matchesNonce(
            expected: String,
            candidate: String?,
        ): Boolean {
            val a = expected.encodeToByteArray()
            // `?.` folds an absent credential into the length check: null and wrong-length are
            // the same answer, and one branch keeps this to two returns, which is what
            // detekt's ReturnCount allows. Semantics are unchanged - see the tests, which pin
            // null, empty, prefix, suffix and case separately.
            val b = candidate?.encodeToByteArray()
            if (b == null || a.size != b.size) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
            return diff == 0
        }

        private fun secureRandomBytes(): ByteArray = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
    }
}
