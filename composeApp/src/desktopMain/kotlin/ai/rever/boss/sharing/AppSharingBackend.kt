package ai.rever.boss.sharing

import ai.rever.boss.config.SupabaseClientConfig
import ai.rever.boss.services.supabase.AuthService
import ai.rever.boss.services.supabase.SupabaseConfig
import ai.rever.boss.services.supabase.supabaseJson
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal class AppSharingException(
    val reason: String,
) : IllegalStateException(reason)

/** Host identity stays in the host; trusted viewer assets receive scoped RPC capabilities. */
internal class AppSharingBackend(
    private val endpoint: () -> String = { SupabaseClientConfig.functionUrl.trimEnd('/') + "/app-sharing" },
    private val identity: () -> Pair<String, String>? = {
        runCatching {
            if (AuthService.authState.value !is AuthService.AuthState.Authenticated) return@runCatching null
            val userId = AuthService.currentUser.value?.id ?: return@runCatching null
            val session = SupabaseConfig.client.auth.currentSessionOrNull() ?: return@runCatching null
            if (session.user?.id != userId) return@runCatching null
            userId to session.accessToken
        }.getOrNull()
    },
    private val anonKey: () -> String = { SupabaseClientConfig.anonKey },
) {
    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    suspend fun call(
        owner: String,
        request: JsonObject,
    ): JsonObject =
        withContext(Dispatchers.IO) {
            val credentials = identity()?.takeIf { it.first == owner } ?: throw AppSharingException("sign_in_required")
            val uri = URI(endpoint())
            require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost")))
            require(uri.userInfo == null && uri.fragment == null && uri.rawQuery == null)
            val body = request.toString()
            require(body.length <= MAX_BODY_BYTES)
            val response =
                client.send(
                    HttpRequest
                        .newBuilder(uri)
                        .timeout(Duration.ofSeconds(20))
                        .header("Authorization", "Bearer ${credentials.second}")
                        .header("apikey", anonKey())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                    HttpResponse.BodyHandlers.ofInputStream(),
                )
            val bytes = response.body().use { it.readNBytes(MAX_BODY_BYTES + 1) }
            if (bytes.size > MAX_BODY_BYTES) throw AppSharingException("response_too_large")
            if (identity()?.first != owner) throw AppSharingException("account_changed")
            val parsed = runCatching { supabaseJson.parseToJsonElement(bytes.decodeToString()).jsonObject }.getOrNull()
            if (response.statusCode() !in 200..299) {
                // Never surface raw bodies: descriptors carry private URLs and media keys.
                val code = parsed?.get("error")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                throw AppSharingException(code?.takeIf { it.matches(Regex("[a-z_]{1,80}")) } ?: "service_unavailable")
            }
            parsed ?: throw AppSharingException("invalid_response")
        }

    companion object {
        private const val MAX_BODY_BYTES = 1024 * 1024
    }
}
