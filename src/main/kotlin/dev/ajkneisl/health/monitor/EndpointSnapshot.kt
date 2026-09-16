package dev.ajkneisl.health.monitor

import kotlinx.serialization.Serializable

@Serializable
data class EndpointSnapshot(
    val id: String,
    val name: String,
    val url: String,
    val method: String,
    val group: String,
    val intervalSeconds: Long,
    val status: Status,
    val latest: CheckResult? = null,
    val history: List<CheckResult> = emptyList(),
    val uptimePercent: Double? = null,
    val avgLatencyMillis: Long? = null,
    val totalChecks: Long = 0,
    val totalFailures: Long = 0,
    val statusSince: Long? = null,
    /** Down or degraded checks in a row, ending with the latest; 0 when the latest was up. */
    val consecutiveFailures: Int = 0,
)
