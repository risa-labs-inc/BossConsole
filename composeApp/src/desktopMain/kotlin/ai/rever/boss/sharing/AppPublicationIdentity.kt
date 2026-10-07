package ai.rever.boss.sharing

import ai.rever.boss.config.SupabaseClientConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.UUID

/** Fresh authority for each start. The signing key is never part of the registry request. */
internal class AppPublicationIdentity(
    private val target: AppCaptureTarget,
) {
    val sessionId: String = UUID.randomUUID().toString()
    private val rootKey = AppSharingAssets.randomSecret()
    private val keyEpoch = UUID.randomUUID().toString()
    private val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val publicKey = encoder.encodeToString(keys.public.encoded)

    fun registration(
        deviceId: String,
        instanceId: String,
        title: String,
        windows: List<SharedAppWindow> = listOf(SharedAppWindow(target.windowId, title)),
    ): JsonObject =
        buildJsonObject {
            put("session_id", sessionId)
            put("generation", target.generation)
            put("device_id", deviceId)
            put("instance_id", instanceId)
            put("name", title.take(120))
            putJsonArray("windows") {
                windows.forEach { window ->
                    add(
                        buildJsonObject {
                            put("id", window.id)
                            put("title", window.title.take(120))
                        },
                    )
                }
            }
            val base = SupabaseClientConfig.functionUrl.trimEnd('/')
            put("viewer_url", "$base/app-sharing/viewer?session=$sessionId#k=$rootKey")
            put("key_epoch", keyEpoch)
            put("host_public_key", publicKey)
        }

    fun hostConfig(
        peerId: String,
        windowId: String = target.windowId,
    ): JsonObject =
        buildJsonObject {
            put("sessionId", sessionId)
            put("generation", target.generation)
            put("peerId", peerId)
            put("windowId", windowId)
            put("keyEpoch", keyEpoch)
            put("mediaRootKey", rootKey)
            put("hostPublicKey", publicKey)
            put("hostPrivateKey", encoder.encodeToString(keys.private.encoded))
            put("autoStart", false)
        }
}
