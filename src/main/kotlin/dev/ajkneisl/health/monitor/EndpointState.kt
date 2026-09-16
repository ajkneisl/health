package dev.ajkneisl.health.monitor

import dev.ajkneisl.health.config.Endpoint

/** Rolling state for one endpoint. All mutation happens under the instance lock. */
internal class EndpointState(val endpoint: Endpoint) {
    private val history = ArrayDeque<CheckResult>()
    private var latest: CheckResult? = null
    private var totalChecks = 0L
    private var totalFailures = 0L
    private var statusSince: Long? = null
    private var consecutiveFailures = 0

    /** @return the status it moved away from, or null when the status did not change. */
    fun record(result: CheckResult): Status? = synchronized(this) {
        val previous = latest?.status ?: Status.UNKNOWN
        val changed = previous != result.status
        latest = result
        totalChecks++
        if (result.status == Status.DOWN) totalFailures++
        if (changed) statusSince = result.checkedAt
        consecutiveFailures =
            if (result.status == Status.DOWN || result.status == Status.DEGRADED) consecutiveFailures + 1
            else 0

        // History is only used for the sparkline and windowed uptime; drop the stats payload.
        history.addLast(result.copy(stats = emptyList()))
        while (history.size > endpoint.historySize) history.removeFirst()
        previous.takeIf { changed }
    }

    fun snapshot(): EndpointSnapshot = synchronized(this) {
        val window = history.toList()
        val measured = window.filter { it.status != Status.UNKNOWN }
        EndpointSnapshot(
            id = endpoint.id,
            name = endpoint.name,
            url = endpoint.url,
            method = endpoint.method,
            group = endpoint.group,
            intervalSeconds = endpoint.intervalSeconds,
            status = latest?.status ?: Status.UNKNOWN,
            latest = latest,
            history = window,
            uptimePercent = measured
                .takeIf { it.isNotEmpty() }
                ?.let { results -> 100.0 * results.count { it.status != Status.DOWN } / results.size },
            avgLatencyMillis = window
                .filter { it.status != Status.DOWN }
                .takeIf { it.isNotEmpty() }
                ?.let { results -> results.sumOf { it.latencyMillis } / results.size },
            totalChecks = totalChecks,
            totalFailures = totalFailures,
            statusSince = statusSince,
            consecutiveFailures = consecutiveFailures,
        )
    }
}
