package dev.ajkneisl.health

import dev.ajkneisl.health.config.ConfigLoader
import dev.ajkneisl.health.config.HealthConfig
import dev.ajkneisl.health.mail.Alerter
import dev.ajkneisl.health.mail.Mailer
import dev.ajkneisl.health.monitor.HealthMonitor
import dev.ajkneisl.health.web.configureRouting
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlin.time.Duration.Companion.seconds

fun Application.rootModule() {
    healthModule(ConfigLoader.load())
}

/** Wires the dashboard against an explicit configuration, which is what the tests use. */
fun Application.healthModule(
    config: HealthConfig,
    alerter: Alerter = Alerter(config.email, Mailer.create(config.email)),
    healthMonitor: HealthMonitor = HealthMonitor(config, listener = alerter),
) {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    install(ContentNegotiation) {
        json()
    }

    install(AutoHeadResponse)

    configureRouting(healthMonitor, alerter)

    healthMonitor.start()
    alerter.startDailyReport { healthMonitor.snapshot() }

    monitor.subscribe(ApplicationStopping) {
        healthMonitor.stop()
        alerter.stop()
    }
}
