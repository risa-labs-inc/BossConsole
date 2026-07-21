package ai.rever.boss.plugin.repository.remote

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Desktop-specific HTTP client using CIO engine.
 */
internal actual fun createHttpClient(): HttpClient {
    return HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                isLenient = true
            })
        }

        engine {
            requestTimeout = 30_000 // 30 seconds
            endpoint {
                connectTimeout = 10_000 // 10 seconds
                keepAliveTime = 5_000 // 5 seconds
            }
        }
    }
}
