package dev.ajkneisl.health.web

import dev.ajkneisl.health.monitor.CheckResult
import dev.ajkneisl.health.monitor.DashboardSnapshot
import dev.ajkneisl.health.monitor.EndpointSnapshot
import dev.ajkneisl.health.monitor.Status
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToLong
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.TBODY
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.id
import kotlinx.html.lang
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.style
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.unsafe

private val clock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/** The dashboard page. */
fun HTML.dashboardPage(snapshot: DashboardSnapshot) {
    lang = "en"
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title { +pageTitle(snapshot) }
        script { unsafe { +"document.documentElement.className = 'js';" } }
        style { unsafe { +DASHBOARD_CSS } }
    }
    body {
        dashboardContent(snapshot)
        script { unsafe { +DASHBOARD_SCRIPT } }
    }
}

/**
 * The same markup as a standalone fragment. The page swaps this element in on every update, which
 * avoids the flash a full reload causes and keeps the scroll position.
 */
fun dashboardFragment(snapshot: DashboardSnapshot): String =
    createHTML().div { renderContent(snapshot) }

private fun FlowContent.dashboardContent(snapshot: DashboardSnapshot) {
    div { renderContent(snapshot) }
}

private fun DIV.renderContent(snapshot: DashboardSnapshot) {
    id = CONTENT_ID
    attributes["data-title"] = pageTitle(snapshot)
    // dashboard.js reads its polling interval from here rather than being templated.
    attributes["data-refresh"] = max(2, snapshot.refreshSeconds).toString()

    h1 { +snapshot.title }

    p {
        +"${snapshot.overall.label}. "
        +"${snapshot.count(Status.UP)} up, "
        +"${snapshot.count(Status.DEGRADED)} degraded, "
        +"${snapshot.count(Status.DOWN)} down of ${snapshot.endpoints.size}. "
        +"Updated ${clock.format(Instant.ofEpochMilli(snapshot.generatedAt))}. "
        if (snapshot.endpoints.isNotEmpty()) {
            span("hint") { +"Click a name for details. " }
        }
        form(action = "/check", method = FormMethod.post) {
            attributes["data-check"] = ""
            button(type = ButtonType.submit) { +"Check now" }
        }
    }

    if (snapshot.endpoints.isEmpty()) {
        p { +"No endpoints configured. Add them to health.json and restart." }
    }

    snapshot.endpoints
        .groupBy { it.group }
        .forEach { (group, endpoints) ->
            h2 { +group }
            table {
                thead {
                    tr {
                        th { +"Endpoint" }
                        th { +"Status" }
                        th { +"Version" }
                        th { +"HTTP" }
                        th { +"Latency" }
                        th { +"Uptime" }
                        th { +"Checked" }
                        th { +"Recent" }
                    }
                }
                tbody {
                    endpoints.forEach { endpointRows(it, snapshot.generatedAt) }
                }
            }
        }
}

private fun TBODY.endpointRows(endpoint: EndpointSnapshot, now: Long) {
    val latest = endpoint.latest

    tr {
        td {
            a(href = "#detail-${endpoint.id}") {
                attributes["data-endpoint"] = endpoint.id
                +endpoint.name
            }
        }
        td { +endpoint.status.label }
        td { +(latest?.version ?: "—") }
        td { +(latest?.httpStatus?.toString() ?: "—") }
        td { +(latest?.let { "${it.latencyMillis} ms" } ?: "—") }
        td { +(endpoint.uptimePercent?.let { "${(it * 10).roundToLong() / 10.0}%" } ?: "—") }
        td { +(latest?.let { relativeTime(it.checkedAt, now) } ?: "never") }
        td {
            span("mono") {
                attributes["title"] = "Last ${endpoint.history.size} checks, oldest first"
                +sparkline(endpoint.history)
            }
        }
    }

    tr("detail") {
        id = "detail-${endpoint.id}"
        attributes["data-endpoint"] = endpoint.id
        td("detail") {
            attributes["colspan"] = "8"
            div {
                a(href = endpoint.url) { +"${endpoint.method} ${endpoint.url}" }
            }
            notes(endpoint, now).forEach { line -> div { +line } }
        }
    }
}

/** Everything that does not fit a column: the error, the stats object, and the check counters. */
private fun notes(endpoint: EndpointSnapshot, now: Long): List<String> {
    val latest = endpoint.latest ?: return emptyList()
    val lines = mutableListOf<String>()

    latest.error?.let { lines += it }

    latest.stats.forEach { stat -> lines += "${stat.label}: ${stat.value}" }

    if (endpoint.totalChecks > 0) {
        lines += buildString {
            append("${endpoint.totalChecks} checks, ${endpoint.totalFailures} failed")
            append(", every ${endpoint.intervalSeconds}s")
            endpoint.avgLatencyMillis?.let { append(", avg ${it} ms") }
            endpoint.statusSince?.let {
                append(
                    ", ${endpoint.status.label.lowercase()} for ${
                        relativeDuration(
                            now - it
                        )
                    }"
                )
            }
            previousVersion(endpoint)?.let { append(", upgraded from $it") }
        }
    }

    return lines
}

/** The retained window as one character per check: `.` up, `~` degraded, `x` down. */
private fun sparkline(history: List<CheckResult>): String =
    if (history.isEmpty()) "—"
    else
        history.joinToString("") { result ->
            when (result.status) {
                Status.UP -> "."
                Status.DEGRADED -> "~"
                Status.DOWN -> "x"
                Status.UNKNOWN -> "?"
            }
        }

/** The last different version in the retained window, if the endpoint changed version recently. */
private fun previousVersion(endpoint: EndpointSnapshot): String? {
    val current = endpoint.latest?.version ?: return null
    return endpoint.history
        .asReversed()
        .firstOrNull { it.version != null && it.version != current }
        ?.version
}

private fun pageTitle(snapshot: DashboardSnapshot): String =
    "${snapshot.title} — ${snapshot.overall.label}"

private fun relativeTime(epochMillis: Long, now: Long): String {
    val delta = now - epochMillis
    return if (delta < 1_000) "just now" else "${relativeDuration(delta)} ago"
}

private fun relativeDuration(millis: Long): String {
    val seconds = millis / 1_000
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3_600 -> "${seconds / 60}m"
        seconds < 86_400 -> "${seconds / 3_600}h ${(seconds % 3_600) / 60}m"
        else -> "${seconds / 86_400}d"
    }
}
