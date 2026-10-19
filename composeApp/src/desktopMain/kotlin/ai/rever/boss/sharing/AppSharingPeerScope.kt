package ai.rever.boss.sharing

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Restricts a local page to the one peer admitted for it, even if its script is compromised. */
internal data class AppSharingPeerScope(
    val sessionId: String,
    val generation: String,
    val peerId: String,
    val host: Boolean,
    val windowId: String? = null,
) {
    fun bind(request: JsonObject): JsonObject {
        val action = request["action"]?.jsonPrimitive?.contentOrNull
        val allowed = if (host) HOST_ACTIONS else VIEWER_ACTIONS
        require(action in allowed) { "Action is not available to this viewer" }
        val pinned =
            buildMap {
                put("session_id", sessionId)
                put("generation", generation)
                put("peer_id", peerId)
                windowId?.let { put("window_id", it) }
                if (action == "controlPoll") put("host_peer_id", peerId)
            }
        pinned.forEach { (key, value) ->
            require(request[key] == null || request[key]?.jsonPrimitive?.contentOrNull == value) {
                "Peer identity mismatch"
            }
        }
        return buildJsonObject {
            request.forEach { (key, value) -> put(key, value) }
            pinned.forEach { (key, value) -> put(key, value) }
        }
    }

    companion object {
        private val COMMON = setOf("mediaCreate", "mediaRenegotiate", "mediaClose", "dataEstablish", "peerHeartbeat")
        private val HOST_ACTIONS = COMMON + setOf("mediaPublish", "dataPublish", "controlPoll", "controlRelease")
        private val VIEWER_ACTIONS =
            COMMON +
                setOf(
                    "mediaSubscribe",
                    "dataSubscribe",
                    "dataRevoke",
                    "controlAcquire",
                    "controlRenew",
                    "controlRelease",
                )
    }
}
