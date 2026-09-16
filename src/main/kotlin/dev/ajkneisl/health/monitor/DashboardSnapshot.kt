package dev.ajkneisl.health.monitor

import kotlinx.serialization.Serializable

@Serializable
data class DashboardSnapshot(
    val title: String,
    val generatedAt: Long,
    val refreshSeconds: Int,
    val endpoints: List<EndpointSnapshot>,
) {
    fun count(status: Status): Int = endpoints.count { it.status == status }

    val overall: Status
        get() =
            when {
                endpoints.isEmpty() -> Status.UNKNOWN
                endpoints.any { it.status == Status.DOWN } -> Status.DOWN
                endpoints.any { it.status == Status.DEGRADED } -> Status.DEGRADED
                endpoints.all { it.status == Status.UNKNOWN } -> Status.UNKNOWN
                else -> Status.UP
            }
}
