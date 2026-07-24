package ai.rever.boss.services.passkey.supabase

import ai.rever.boss.services.supabase.getSupabaseAnonKey
import ai.rever.boss.services.supabase.getSupabaseFunctionUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json

/**
 * Handles all HTTP communication with Supabase Edge Functions for passkey operations
 *
 * New API uses RESTful endpoints:
 * - POST /passkey/auth/challenge - Generate authentication challenge
 * - POST /passkey/auth/complete - Complete authentication
 * - GET /passkey/auth/status/{sessionId} - Check authentication status
 * - POST /passkey/register/challenge - Generate registration challenge
 * - POST /passkey/register/complete - Complete registration
 * - POST /passkey/manage/list - List user passkeys
 * - POST /passkey/manage/delete - Delete a passkey
 * - POST /passkey/manage/update - Update passkey display name
 */
internal object SupabaseApiClient {
    private val httpClient = HttpClient(CIO)

    // Secure configuration - values loaded from ConfigLoader (environment variables, system properties, or local.properties)
    // Base functions URL (e.g., http://127.0.0.1:54321/functions/v1)
    private val supabaseFunctionBaseUrl: String by lazy {
        getSupabaseFunctionUrl()
    }

    // Passkey function URL (base + /passkey)
    private val passkeyFunctionUrl: String by lazy {
        "$supabaseFunctionBaseUrl/passkey"
    }

    private val supabaseAnonKey: String by lazy {
        getSupabaseAnonKey()
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    // ============================================================================
    // Authentication Endpoints
    // ============================================================================

    /**
     * POST /passkey/auth/challenge - Generate authentication challenge
     */
    suspend inline fun <reified T> invokeAuthenticationChallenge(requestData: T): HttpResponse {
        val jsonBody = json.encodeToString(requestData)

        return httpClient.post("$passkeyFunctionUrl/auth/challenge") {
            contentType(ContentType.Application.Json)
            header("apikey", supabaseAnonKey)
            setBody(jsonBody)
        }
    }

    /**
     * POST /passkey/auth/complete - Complete authentication
     */
    suspend inline fun <reified T> completeAuthentication(requestData: T): HttpResponse {
        val jsonBody = json.encodeToString(requestData)

        return httpClient.post("$passkeyFunctionUrl/auth/complete") {
            contentType(ContentType.Application.Json)
            header("apikey", supabaseAnonKey)
            setBody(jsonBody)
        }
    }

    /**
     * GET /passkey/auth/status/{sessionId} - Check authentication status
     */
    suspend fun checkAuthenticationStatus(sessionId: String): HttpResponse =
        httpClient.get("$passkeyFunctionUrl/auth/status/$sessionId") {
            header("apikey", supabaseAnonKey)
        }

    // ============================================================================
    // Registration Endpoints
    // ============================================================================

    /**
     * POST /passkey/register/challenge - Generate registration challenge
     */
    suspend inline fun <reified T> invokeRegistrationChallenge(requestData: T): HttpResponse {
        val jsonBody = json.encodeToString(requestData)

        return httpClient.post("$passkeyFunctionUrl/register/challenge") {
            contentType(ContentType.Application.Json)
            header("apikey", supabaseAnonKey)
            setBody(jsonBody)
        }
    }

    /**
     * POST /passkey/register/complete - Complete registration
     */
    suspend inline fun <reified T> completeRegistration(requestData: T): HttpResponse {
        val jsonBody = json.encodeToString(requestData)

        return httpClient.post("$passkeyFunctionUrl/register/complete") {
            contentType(ContentType.Application.Json)
            header("apikey", supabaseAnonKey)
            setBody(jsonBody)
        }
    }

    // ============================================================================
    // Management Endpoints
    // ============================================================================

    /**
     * POST /passkey/manage/list - List user passkeys
     */
    suspend inline fun <reified T> listPasskeys(requestData: T): HttpResponse {
        val jsonBody = json.encodeToString(requestData)

        return httpClient.post("$passkeyFunctionUrl/manage/list") {
            contentType(ContentType.Application.Json)
            header("apikey", supabaseAnonKey)
            setBody(jsonBody)
        }
    }

    /**
     * POST /passkey/manage/delete - Delete a passkey
     */
    suspend inline fun <reified T> deletePasskey(requestData: T): HttpResponse {
        val jsonBody = json.encodeToString(requestData)

        return httpClient.post("$passkeyFunctionUrl/manage/delete") {
            contentType(ContentType.Application.Json)
            header("apikey", supabaseAnonKey)
            setBody(jsonBody)
        }
    }
}
