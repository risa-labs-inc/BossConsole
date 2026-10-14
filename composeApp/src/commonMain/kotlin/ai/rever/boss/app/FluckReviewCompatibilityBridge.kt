package ai.rever.boss.app

import ai.rever.boss.plugin.api.CustomPluginEvent
import kotlinx.coroutines.CancellationException

internal const val TERMINAL_PLUGIN_ID = "ai.rever.boss.plugin.dynamic.terminaltab"
internal const val CODEBASE_PLUGIN_ID = "ai.rever.boss.plugin.dynamic.codebase"
internal const val HOST_PLUGIN_ID = "ai.rever.boss"
internal const val FLUCK_REVIEW_EVENT = "atlas.review"
internal const val SETUP_DEBUG_OPEN_EVENT = "bossterm.setup.fluck.open"
internal const val SETUP_DEBUG_OPENED_EVENT = "bossterm.setup.fluck.opened"
internal const val SETUP_FLUCK_PROBE_EVENT = "bossterm.setup.fluck.probe"
internal const val SETUP_FLUCK_AVAILABILITY_EVENT = "bossterm.setup.fluck.availability"
internal const val SETUP_OPEN_EVENT = "bossterm.setup.open"

/** A debugging attempt must use a fresh [requestId]; reusing one with different data is rejected. */
internal data class SetupFluckOpenRequest(
    val requestId: String,
    val terminalId: String,
    val prompt: String,
)

internal sealed interface SetupFluckOpenRoute {
    data object Ignore : SetupFluckOpenRoute

    data class Accept(
        val request: SetupFluckOpenRequest,
    ) : SetupFluckOpenRoute

    data class Reject(
        val reason: String,
        val requestId: String? = null,
        val terminalId: String? = null,
    ) : SetupFluckOpenRoute {
        val canAcknowledge: Boolean get() = requestId != null && terminalId != null
    }
}

internal class DeliveredSetupRequestLedger(
    private val capacity: Int = 256,
) {
    enum class Classification { NEW, DUPLICATE, CONFLICT }

    private data class Fingerprint(
        val terminalId: String,
        val prompt: String,
    )

    private val delivered = LinkedHashMap<String, Fingerprint>()

    fun classify(request: SetupFluckOpenRequest): Classification {
        val existing = delivered[request.requestId] ?: return Classification.NEW
        return if (existing == request.fingerprint()) Classification.DUPLICATE else Classification.CONFLICT
    }

    fun recordDelivered(request: SetupFluckOpenRequest) {
        delivered[request.requestId] = request.fingerprint()
        while (delivered.size > capacity) delivered.remove(delivered.keys.first())
    }

    inline fun deliverAndRecord(
        request: SetupFluckOpenRequest,
        deliver: () -> Unit,
    ) {
        deliver()
        recordDelivered(request)
    }

    private fun SetupFluckOpenRequest.fingerprint() = Fingerprint(terminalId, prompt)
}

@Suppress("TooGenericExceptionCaught") // Per-event isolation must also contain plugin Errors.
internal inline fun isolateSetupBridgeEvent(onFailure: (Throwable) -> Unit, handle: () -> Unit) {
    try {
        handle()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        onFailure(error)
    }
}

internal fun CustomPluginEvent.toSetupFluckProbeRequest(expectedWindowId: String): String? {
    val matchesRoute = sourcePluginId == TERMINAL_PLUGIN_ID && eventName == SETUP_FLUCK_PROBE_EVENT
    return (payload["requestId"] as? String)
        ?.takeIf { matchesRoute && payload["windowId"] == expectedWindowId && validOpaqueId(it) }
}

internal fun CustomPluginEvent.isSetupOpenRequest(expectedWindowId: String): Boolean =
    sourcePluginId == TERMINAL_PLUGIN_ID &&
        eventName == SETUP_OPEN_EVENT &&
        payload["windowId"] == expectedWindowId

internal fun setupDebugAcknowledgement(
    requestId: String,
    terminalId: String,
    accepted: Boolean,
    error: String?,
): CustomPluginEvent =
    CustomPluginEvent(
        HOST_PLUGIN_ID,
        SETUP_DEBUG_OPENED_EVENT,
        mapOf(
            "requestId" to requestId,
            "terminalId" to terminalId,
            "accepted" to accepted,
            "error" to error,
        ),
    )

/**
 * Validate the terminal plugin's self-declared route before using the legacy review ingress.
 * The application event bus is not an authentication boundary; plugin trust is decided at install time.
 */
internal fun CustomPluginEvent.routeSetupFluckOpenRequest(
    expectedWindowId: String,
    nowMs: Long = System.currentTimeMillis(),
): SetupFluckOpenRoute {
    if (
        sourcePluginId != TERMINAL_PLUGIN_ID || eventName != SETUP_DEBUG_OPEN_EVENT ||
        payload["windowId"] != expectedWindowId
    ) {
        return SetupFluckOpenRoute.Ignore
    }
    val requestId = payload["requestId"] as? String
    val terminalId = payload["terminalId"] as? String
    val prompt = payload["prompt"] as? String
    val safeRequestId = requestId?.takeIf(::validOpaqueId)
    val safeTerminalId = terminalId?.takeIf(::validOpaqueId)
    // A rejected request may echo a bounded terminal id so Terminal Tab can match the refusal.
    // Successful routing still requires the stricter opaque-id character set above.
    val acknowledgementTerminalId = terminalId?.takeIf { it.length in 1..MAX_ID_LENGTH }

    fun reject(reason: String) = SetupFluckOpenRoute.Reject(reason, safeRequestId, acknowledgementTerminalId)
    val expiresAtMs = exactLong(payload["expiresAtMs"])
    val rejection =
        when {
            safeRequestId == null -> reject("missing or malformed request id")
            safeTerminalId == null -> reject("missing or malformed terminal id")
            expiresAtMs == null -> reject("missing or malformed expiry")
            expiresAtMs !in nowMs..(nowMs + MAX_REQUEST_LIFETIME_MS) -> reject("expired or invalid lifetime")
            prompt.isNullOrBlank() -> reject("missing prompt")
            prompt.length > MAX_PROMPT_LENGTH -> reject("prompt exceeds $MAX_PROMPT_LENGTH characters")
            else -> null
        }
    return rejection ?: SetupFluckOpenRoute.Accept(
        SetupFluckOpenRequest(requireNotNull(safeRequestId), requireNotNull(safeTerminalId), requireNotNull(prompt)),
    )
}

internal fun CustomPluginEvent.toSetupFluckOpenRequest(
    expectedWindowId: String,
    nowMs: Long = System.currentTimeMillis(),
): SetupFluckOpenRequest? =
    routeSetupFluckOpenRequest(expectedWindowId, nowMs)
        .let { it as? SetupFluckOpenRoute.Accept }
        ?.request

private fun exactLong(value: Any?): Long? =
    when (value) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

private fun validOpaqueId(value: String): Boolean = value.length in 1..MAX_ID_LENGTH && value.all(::validIdCharacter)

private fun validIdCharacter(character: Char): Boolean = character.isLetterOrDigit() || character in ID_PUNCTUATION

private const val MAX_ID_LENGTH = 160
private const val MAX_PROMPT_LENGTH = 16_000
private const val MAX_REQUEST_LIFETIME_MS = 30_000L
private const val ID_PUNCTUATION = "-_:"
