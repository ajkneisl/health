package dev.ajkneisl

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping

fun Application.rootModule() {
    healthModule(ConfigLoader.load())
}

/**
 * Wires the dashboard against an explicit configuration, which is what the tests use.
 *
 * The generated `configureSecurity()` (sample JWT realm) and `configureRequestValidation()`
 * (sample "body must start with Hello" rule) are intentionally not installed — nothing here needs
 * them. Add the calls back if you want to put the dashboard behind auth.
 */
fun Application.healthModule(
    config: HealthConfig,
    alerter: Alerter = Alerter(config.email, Mailer.create(config.email)),
    healthMonitor: HealthMonitor = HealthMonitor(config, listener = alerter),
) {
    configureWebsockets()
    configureSerialization()
    configureAutoHeadResponse()
    configureRouting(healthMonitor, alerter)

    healthMonitor.start()
    alerter.startDailyReport { healthMonitor.snapshot() }

    monitor.subscribe(ApplicationStopping) {
        healthMonitor.stop()
        alerter.stop()
    }
}
