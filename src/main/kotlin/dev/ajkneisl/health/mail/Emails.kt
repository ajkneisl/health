package dev.ajkneisl.health.mail

import dev.ajkneisl.health.config.EmailConfig
import dev.ajkneisl.health.monitor.DashboardSnapshot
import dev.ajkneisl.health.monitor.EndpointSnapshot
import dev.ajkneisl.health.monitor.Status
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.br
import kotlinx.html.div
import kotlinx.html.h2
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.stream.createHTML
import kotlinx.html.style
import kotlinx.html.table
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.tr
import kotlinx.html.ul

/**
 * The bodies of the alert and report emails, in the same plain style as the dashboard. Every
 * message carries an HTML and a text part; SES sends both and the client picks.
 */
object Emails {

    private const val TABLE_STYLE =
        "border-collapse: collapse; font-family: sans-serif; font-size: 14px;"
    private const val CELL_STYLE = "text-align: left; padding: 2px 12px 2px 0; vertical-align: top;"

    /** Fired on a transition into down or degraded, and on recovery when that is enabled. */
    fun alert(
        endpoint: EndpointSnapshot,
        previous: Status,
        config: EmailConfig,
        zone: ZoneId,
    ): EmailMessage {
        val headline =
            when (endpoint.status) {
                Status.DOWN -> "${endpoint.name} is down"
                Status.DEGRADED -> "${endpoint.name} is degraded"
                Status.UP -> "${endpoint.name} recovered"
                Status.UNKNOWN -> "${endpoint.name} has no result"
            }
        val latest = endpoint.latest
        val rows = buildList {
            add("Status" to "${endpoint.status.label} (was ${previous.label})")
            add("Endpoint" to "${endpoint.method} ${endpoint.url}")
            latest?.error?.let { add("Error" to it) }
            add("HTTP" to (latest?.httpStatus?.toString() ?: "no response"))
            add("Latency" to (latest?.let { "${it.latencyMillis} ms" } ?: "—"))
            latest?.version?.let { add("Version" to it) }
            endpoint.uptimePercent?.let { add("Uptime (window)" to percent(it)) }
            add("Checks" to "${endpoint.totalChecks} total, ${endpoint.totalFailures} failed")
            add("Checked at" to timestamp(latest?.checkedAt, zone))
        }
        val stats = latest?.stats.orEmpty()

        val html =
            createHTML().div {
                h2 { +headline }
                keyValueTable(rows)
                if (stats.isNotEmpty()) {
                    p { +"Stats" }
                    ul { stats.forEach { stat -> li { +"${stat.label}: ${stat.value}" } } }
                }
                p {
                    a(href = endpoint.url) { +endpoint.url }
                }
            }

        val text = buildString {
            appendLine(headline)
            appendLine()
            rows.forEach { (label, value) -> appendLine("$label: $value") }
            if (stats.isNotEmpty()) {
                appendLine()
                appendLine("Stats")
                stats.forEach { stat -> appendLine("  ${stat.label}: ${stat.value}") }
            }
        }

        return EmailMessage(
            subject = "${config.subjectPrefix} $headline".trim(),
            html = html,
            text = text,
        )
    }

    /** The once-a-day summary of every monitored endpoint. */
    fun dailyReport(snapshot: DashboardSnapshot, config: EmailConfig, zone: ZoneId): EmailMessage {
        val summary =
            "${snapshot.count(Status.UP)} up, " +
                "${snapshot.count(Status.DEGRADED)} degraded, " +
                "${snapshot.count(Status.DOWN)} down of ${snapshot.endpoints.size}"
        val headline = "Daily status report — $summary"

        val html =
            createHTML().div {
                h2 { +"Daily status report" }
                p { +"$summary. Generated ${timestamp(snapshot.generatedAt, zone)}." }

                snapshot.endpoints
                    .groupBy { it.group }
                    .forEach { (group, endpoints) ->
                        p { +group }
                        table {
                            style = TABLE_STYLE
                            tr {
                                listOf(
                                        "Endpoint",
                                        "Status",
                                        "Version",
                                        "HTTP",
                                        "Latency",
                                        "Uptime",
                                        "Checks",
                                        "Last check",
                                    )
                                    .forEach { heading ->
                                        th {
                                            style = CELL_STYLE
                                            +heading
                                        }
                                    }
                            }
                            endpoints.forEach { endpoint ->
                                val latest = endpoint.latest
                                tr {
                                    td {
                                        style = CELL_STYLE
                                        a(href = endpoint.url) { +endpoint.name }
                                    }
                                    td {
                                        style = CELL_STYLE
                                        +endpoint.status.label
                                    }
                                    td {
                                        style = CELL_STYLE
                                        +(latest?.version ?: "—")
                                    }
                                    td {
                                        style = CELL_STYLE
                                        +(latest?.httpStatus?.toString() ?: "—")
                                    }
                                    td {
                                        style = CELL_STYLE
                                        +(latest?.let { "${it.latencyMillis} ms" } ?: "—")
                                    }
                                    td {
                                        style = CELL_STYLE
                                        +(endpoint.uptimePercent?.let(::percent) ?: "—")
                                    }
                                    td {
                                        style = CELL_STYLE
                                        +"${endpoint.totalChecks} / ${endpoint.totalFailures} failed"
                                    }
                                    td {
                                        style = CELL_STYLE
                                        +timestamp(latest?.checkedAt, zone)
                                    }
                                }
                                val details = detailLines(endpoint)
                                if (details.isNotEmpty()) {
                                    tr {
                                        td {
                                            style = "$CELL_STYLE color: #555; padding-bottom: 8px;"
                                            attributes["colspan"] = "8"
                                            details.forEachIndexed { index, line ->
                                                if (index > 0) br()
                                                +line
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
            }

        val text = buildString {
            appendLine("Daily status report")
            appendLine("$summary. Generated ${timestamp(snapshot.generatedAt, zone)}.")
            snapshot.endpoints
                .groupBy { it.group }
                .forEach { (group, endpoints) ->
                    appendLine()
                    appendLine(group)
                    endpoints.forEach { endpoint ->
                        val latest = endpoint.latest
                        appendLine(
                            "  ${endpoint.name}: ${endpoint.status.label}" +
                                (latest?.version?.let { " v$it" } ?: "") +
                                (latest?.let { " · ${it.latencyMillis} ms" } ?: "") +
                                (endpoint.uptimePercent?.let { " · uptime ${percent(it)}" } ?: "") +
                                " · ${endpoint.totalChecks} checks, ${endpoint.totalFailures} failed"
                        )
                        detailLines(endpoint).forEach { line -> appendLine("      $line") }
                    }
                }
        }

        return EmailMessage(
            subject = "${config.subjectPrefix} $headline".trim(),
            html = html,
            text = text,
        )
    }

    private fun detailLines(endpoint: EndpointSnapshot): List<String> {
        val latest = endpoint.latest ?: return emptyList()
        return buildList {
            latest.error?.let { add(it) }
            latest.stats
                .takeIf { it.isNotEmpty() }
                ?.let { stats -> add(stats.joinToString(", ") { "${it.label}: ${it.value}" }) }
        }
    }

    private fun FlowContent.keyValueTable(rows: List<Pair<String, String>>) {
        table {
            style = TABLE_STYLE
            rows.forEach { (label, value) ->
                tr {
                    th {
                        style = CELL_STYLE
                        +label
                    }
                    td {
                        style = CELL_STYLE
                        +value
                    }
                }
            }
        }
    }

    private fun percent(value: Double): String = "${(value * 10).roundToLong() / 10.0}%"

    private fun timestamp(epochMillis: Long?, zone: ZoneId): String =
        epochMillis?.let {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
                .withZone(zone)
                .format(Instant.ofEpochMilli(it))
        } ?: "never"
}
