package ai.rever.boss.sharing

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI

internal fun appSharingRequest(
    action: String,
    body: JsonObject = buildJsonObject {},
): JsonObject =
    buildJsonObject {
        body.forEach { (key, value) -> put(key, value) }
        put("action", action)
    }

internal fun appSharingHostRequest(
    action: String,
    sessionId: String,
    generation: String,
    peerId: String,
) = appSharingRequest(
    action,
    buildJsonObject {
        put("session_id", sessionId)
        put("generation", generation)
        put("peer_id", peerId)
    },
)

internal data class AppSharingAccountSnapshot(
    val preferences: JsonObject,
    val sessions: List<JsonObject>,
)

internal suspend fun loadAppSharingAccountSnapshot(
    backend: AppSharingBackend,
    owner: String,
): AppSharingAccountSnapshot {
    val preferences = backend.call(owner, appSharingRequest("preferencesGet"))
    val listed = backend.call(owner, appSharingRequest("list"))
    return AppSharingAccountSnapshot(preferences, listed["sessions"]?.jsonArray?.map { it.jsonObject }.orEmpty())
}

internal suspend fun setAppSharingAccountPreference(
    backend: AppSharingBackend,
    owner: String,
    current: JsonObject,
    name: String,
    enabled: Boolean,
): JsonObject {
    require(name in setOf("auto_admit", "auto_control"))
    return backend.call(
        owner,
        appSharingRequest(
            "preferencesSet",
            buildJsonObject {
                put("auto_admit", current["auto_admit"] ?: JsonPrimitive(true))
                put("auto_control", current["auto_control"] ?: JsonPrimitive(true))
                put("revision", current["revision"] ?: JsonPrimitive(0))
                put(name, enabled)
            },
        ),
    )
}

internal fun appSharingViewerConfig(
    descriptor: JsonObject,
    consumed: JsonObject,
    role: String,
    requestedWindowId: String?,
): JsonObject {
    val windowId =
        descriptor
            .getValue("windows")
            .jsonArray
            .first { requestedWindowId == null || it.jsonObject["id"]?.jsonPrimitive?.content == requestedWindowId }
            .jsonObject
            .getValue("id")
            .jsonPrimitive.content
    val key =
        URI(descriptor.getValue("viewer_url").jsonPrimitive.content)
            .rawFragment
            ?.split('&')
            ?.firstOrNull { it.startsWith("k=") }
            ?.removePrefix("k=") ?: error("Missing media key")
    return buildJsonObject {
        put("sessionId", descriptor.getValue("session_id"))
        put("generation", descriptor.getValue("generation"))
        put("peerId", consumed.getValue("peer_id"))
        put("windowId", windowId)
        put("keyEpoch", descriptor.getValue("key_epoch"))
        put("mediaRootKey", key)
        put("hostPublicKey", descriptor.getValue("host_public_key"))
        put("role", consumed["role"] ?: JsonPrimitive(role))
    }
}
