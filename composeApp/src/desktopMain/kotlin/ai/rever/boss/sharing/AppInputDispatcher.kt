package ai.rever.boss.sharing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.UUID
import javax.swing.Timer

internal data class AppControlLease(
    val id: String,
    val peerId: String,
    val generation: String,
    val expiresAtMillis: Long,
)

internal data class AppInputEnvelope(
    val sessionId: String,
    val generation: String,
    val windowId: String,
    val geometryRevision: Long,
    val leaseId: String,
    val peerId: String,
    val sequence: Long,
    val event: AppInputEvent,
)

internal sealed interface AppInputEvent {
    data class Window(
        val action: String,
    ) : AppInputEvent

    data class Pointer(
        val action: String,
        val x: Double,
        val y: Double,
        val button: Int,
    ) : AppInputEvent

    data class Wheel(
        val x: Double,
        val y: Double,
        val deltaX: Double,
        val deltaY: Double,
    ) : AppInputEvent

    data class Key(
        val action: String,
        val code: String,
        val key: String,
        val alt: Boolean,
        val ctrl: Boolean,
        val meta: Boolean,
        val shift: Boolean,
    ) : AppInputEvent
}

internal interface AppScopedInputSink {
    /** Rechecked for each event: exact live surfaces, current geometry, and scoped input policy. */
    fun isAvailable(): Boolean

    /** Coordinate-free keys still require current ownership and modal scope. */
    fun isAvailableFor(event: AppInputEvent): Boolean = isAvailable()

    fun apply(event: AppInputEvent): Boolean

    /** Deferred native work must also finish while this verified lease remains valid. */
    fun apply(
        event: AppInputEvent,
        validUntilMillis: Long,
    ): Boolean = apply(event)

    /** Must release only events held in this sink, never global OS input. */
    fun releaseAll()
}

internal data class AppInputTarget(
    val windowId: String,
    val generation: String,
    val geometryRevision: Long,
    val sink: AppScopedInputSink,
)

/**
 * Input authority is provided by the host's verified backend lease, never by the JSON payload.
 * All state transitions and delivery are serialized on the EDT, including immediate host takeover.
 * This avoids holding a lock while waiting on the EDT (which would deadlock local revocation).
 */
// Lease suspension, retirement and delivery share one EDT owner for atomic authority changes.
@Suppress("TooManyFunctions")
internal class AppInputDispatcher(
    private val sessionId: String,
    private val generation: String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val targets = mutableMapOf<String, AppInputTarget>()
    private var lease: AppControlLease? = null
    private val retiredLeases = mutableSetOf<String>()
    private var lastSequence = 0L
    private var expiry: Timer? = null
    private var activeWindow: String? = null
    private var closed = false
    private var suspended = false
    private var rateStart = 0L
    private var rateCount = 0

    fun register(target: AppInputTarget) =
        onEdt {
            require(!closed && target.generation == generation && target.geometryRevision > 0)
            UUID.fromString(target.windowId)
            targets.put(target.windowId, target)?.let { previous ->
                if (previous.geometryRevision != target.geometryRevision ||
                    previous.sink !== target.sink
                ) {
                    previous.sink.releaseAll()
                }
            }
            Unit
        }

    fun remove(windowId: String) =
        onEdt {
            targets.remove(windowId)?.sink?.releaseAll()
            if (activeWindow == windowId) revokeInternal()
        }

    /** Caller must verify ownership, role, approval policy and backend lease before this call. */
    fun installLease(verifiedLease: AppControlLease): Boolean =
        onEdt {
            if (closed || verifiedLease.id in retiredLeases || retiredLeases.size >= 4096) return@onEdt false
            if (!validAppLease(verifiedLease, generation, nowMillis)) return@onEdt false
            if (lease?.id == verifiedLease.id && lease?.peerId != verifiedLease.peerId) return@onEdt false
            if (lease?.id != verifiedLease.id || lease?.peerId != verifiedLease.peerId) {
                revokeInternal()
                lastSequence = 0L
            }
            lease = verifiedLease
            suspended = false
            expiry?.stop()
            expiry =
                Timer((verifiedLease.expiresAtMillis - nowMillis()).coerceIn(1, 300_000).toInt()) { expire() }
                    .apply {
                        isRepeats = false
                        start()
                    }
            true
        }

    fun dispatchJson(
        authenticatedPeerId: String,
        json: String,
    ): Boolean = parseAppInput(json)?.let { dispatch(authenticatedPeerId, it) } ?: false

    fun dispatch(
        authenticatedPeerId: String,
        input: AppInputEnvelope,
    ): Boolean =
        onEdt {
            if (closed || suspended) return@onEdt false
            expireInternal()
            val current = lease ?: return@onEdt false
            if (!input.validAppEnvelope(
                    authenticatedPeerId,
                    current,
                    sessionId,
                    generation,
                    lastSequence,
                )
            ) {
                return@onEdt false
            }
            val target = targets[input.windowId] ?: return@onEdt false
            if (!target.availableFor(input, generation)) {
                target.sink.releaseAll()
                return@onEdt false
            }
            if (!validAppInputEvent(input.event)) return@onEdt false
            val now = nowMillis()
            if (now - rateStart >= 1000 || now < rateStart) {
                rateStart = now
                rateCount = 0
            }
            if (++rateCount > 240) {
                revokeInternal()
                return@onEdt false
            }
            if (activeWindow != null && activeWindow != input.windowId) targets[activeWindow]?.sink?.releaseAll()
            activeWindow = input.windowId
            // Consume the sequence before delivery: a failed native handler must not make replay safe.
            lastSequence = input.sequence
            runCatching { target.sink.apply(input.event, current.expiresAtMillis) }.getOrElse {
                revokeInternal()
                false
            }
        }

    fun expire() = onEdt { expireInternal() }

    fun revoke() = onEdt { revokeInternal() }

    /** A transient authority outage blocks input without forgetting the lease's replay history. */
    fun suspend() =
        onEdt {
            suspended = true
            targets.values.forEach { runCatching { it.sink.releaseAll() } }
        }

    private fun expireInternal() {
        if (lease?.expiresAtMillis?.let { it <= nowMillis() } == true) revokeInternal()
    }

    private fun revokeInternal() {
        expiry?.stop()
        expiry = null
        lease?.let { retiredLeases.add(it.id) }
        lease = null
        activeWindow = null
        targets.values.forEach { runCatching { it.sink.releaseAll() } }
    }

    override fun close() =
        onEdt {
            revokeInternal()
            targets.clear()
            closed = true
        }
}

private fun AppInputTarget.acceptsGeometry(input: AppInputEnvelope): Boolean =
    geometryRevision == input.geometryRevision ||
        (
            (input.event is AppInputEvent.Key || input.event.isWindowRecovery()) &&
                input.geometryRevision in 1..geometryRevision
        )

private fun AppInputTarget.availableFor(
    input: AppInputEnvelope,
    generation: String,
): Boolean = this.generation == generation && acceptsGeometry(input) && sink.isAvailableFor(input.event)

private fun validAppLease(
    candidate: AppControlLease,
    generation: String,
    nowMillis: () -> Long,
): Boolean {
    val remaining = candidate.expiresAtMillis - nowMillis()
    val identityValid = candidate.generation == generation && candidate.peerId.isNotBlank()
    return identityValid && remaining in 1..300_000 && runCatching { UUID.fromString(candidate.id) }.isSuccess
}

private fun AppInputEnvelope.validAppEnvelope(
    authenticatedPeerId: String,
    current: AppControlLease,
    sessionId: String,
    generation: String,
    lastSequence: Long,
): Boolean {
    val sessionMatches = this.sessionId == sessionId && this.generation == generation
    val leaseMatches =
        leaseId == current.id && peerId == current.peerId && authenticatedPeerId == current.peerId
    val sequenceFresh = sequence > lastSequence && sequence > 0
    return sessionMatches && leaseMatches && sequenceFresh
}

private fun AppInputEvent.isWindowRecovery(): Boolean {
    val event = this as? AppInputEvent.Window ?: return false
    return event.action in setOf("restore", "exit-fullscreen")
}

internal fun validAppInputEvent(event: AppInputEvent): Boolean =
    when (event) {
        is AppInputEvent.Window -> {
            event.action in setOf("restore", "exit-fullscreen", "minimize", "maximize", "unmaximize", "close")
        }

        is AppInputEvent.Pointer -> {
            validPointer(event)
        }

        is AppInputEvent.Wheel -> {
            validWheel(event)
        }

        is AppInputEvent.Key -> {
            event.action in setOf("down", "up") && event.code in appSupportedKeyCodes &&
                event.key.length <= 32 && event.key.none { it == '\u0000' }
        }
    }

private fun coordinate(value: Double) = value.isFinite() && value in 0.0..1.0

private fun validPointer(event: AppInputEvent.Pointer) =
    event.action in setOf("move", "down", "up") && event.button in 0..2 && coordinate(event.x) && coordinate(event.y)

private fun validWheel(event: AppInputEvent.Wheel): Boolean {
    val deltaValid =
        event.deltaX.isFinite() && event.deltaY.isFinite() &&
            kotlin.math.abs(event.deltaX) <= 1000 && kotlin.math.abs(event.deltaY) <= 1000
    return coordinate(event.x) && coordinate(event.y) && deltaValid
}

internal val appSupportedKeyCodes: Set<String> =
    ('A'..'Z').map { "Key$it" }.toSet() + ('0'..'9').map { "Digit$it" } +
        setOf(
            "Enter",
            "Escape",
            "Backspace",
            "Tab",
            "Space",
            "ArrowLeft",
            "ArrowRight",
            "ArrowUp",
            "ArrowDown",
            "Delete",
            "Home",
            "End",
            "PageUp",
            "PageDown",
            "ShiftLeft",
            "ShiftRight",
            "ControlLeft",
            "ControlRight",
            "AltLeft",
            "AltRight",
            "MetaLeft",
            "MetaRight",
            "Minus",
            "Equal",
            "BracketLeft",
            "BracketRight",
            "Backslash",
            "Semicolon",
            "Quote",
            "Comma",
            "Period",
            "Slash",
            "Backquote",
        )

internal fun parseAppInput(raw: String): AppInputEnvelope? {
    if (raw.length > 16_384) return null
    return runCatching {
        val root = Json.parseToJsonElement(raw).jsonObject

        fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

        fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.double
        require(root.str("protocol") == "boss-app-share/1")
        val event = root.getValue("event").jsonObject
        val action =
            when (event.str("type")) {
                "window" -> {
                    AppInputEvent.Window(event.str("action"))
                }

                "pointer" -> {
                    AppInputEvent.Pointer(
                        event.str("action"),
                        event.number("x"),
                        event.number("y"),
                        event.getValue("button").jsonPrimitive.int,
                    )
                }

                "wheel" -> {
                    AppInputEvent.Wheel(
                        event.number("x"),
                        event.number("y"),
                        event.number("deltaX"),
                        event.number("deltaY"),
                    )
                }

                "key" -> {
                    AppInputEvent.Key(
                        event.str("action"),
                        event.str("code"),
                        event.str("key"),
                        event["alt"]?.jsonPrimitive?.booleanOrNull ?: false,
                        event["ctrl"]?.jsonPrimitive?.booleanOrNull ?: false,
                        event["meta"]?.jsonPrimitive?.booleanOrNull ?: false,
                        event["shift"]?.jsonPrimitive?.booleanOrNull ?: false,
                    )
                }

                else -> {
                    error("Unsupported event")
                }
            }
        require(validAppInputEvent(action))
        AppInputEnvelope(
            root.str("sessionId"),
            root.str("generation"),
            root.str("windowId"),
            root.getValue("geometryRevision").jsonPrimitive.long,
            root.str("leaseId"),
            root.str("peerId"),
            root.getValue("sequence").jsonPrimitive.long,
            action,
        )
    }.getOrNull()
}
