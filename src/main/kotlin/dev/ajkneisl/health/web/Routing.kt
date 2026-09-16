package dev.ajkneisl.health.web

import dev.ajkneisl.health.mail.Alerter
import dev.ajkneisl.health.monitor.HealthMonitor
import io.ktor.http.ContentType
import io.ktor.server.application.*
import io.ktor.server.html.respondHtml
import io.ktor.server.request.header
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.drop

fun Application.configureRouting(monitor: HealthMonitor, alerter: Alerter) {
    routing {
        get("/") {
            call.respondHtml { dashboardPage(monitor.snapshot()) }
        }

        get("/fragment") {
            call.respondText(dashboardFragment(monitor.snapshot()), ContentType.Text.Html)
        }

        get("/api/health") {
            call.respond(monitor.snapshot())
        }

        post("/check") {
            monitor.checkAll()
            if (call.request.header("X-Fragment") != null) {
                call.respondText(dashboardFragment(monitor.snapshot()), ContentType.Text.Html)
            } else {
                call.respondRedirect("/")
            }
        }

        post("/report") {
            alerter.sendDailyReport(monitor.snapshot())
            call.respondText("Daily status report sent.")
        }

        webSocket("/ws/health") {
            monitor.version.drop(1).collect { version ->
                send(Frame.Text(version.toString()))
            }
        }
    }
}
