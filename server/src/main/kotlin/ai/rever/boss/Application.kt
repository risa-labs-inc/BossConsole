package ai.rever.boss

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

internal const val SERVER_HOST = "127.0.0.1"

fun main() {
    embeddedServer(Netty, port = 8080, host = SERVER_HOST, module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    routing {
        get("/") {
            call.respondText("BOSS server is running")
        }
    }
}
