package dev.ajkneisl.health

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    embeddedServer(
            factory = Netty,
            port = System.getenv("PORT")?.toIntOrNull() ?: 8080,
            host = "0.0.0.0",
            module = Application::rootModule,
        )
        .start(wait = true)
}
