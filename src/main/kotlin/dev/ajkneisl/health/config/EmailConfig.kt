package dev.ajkneisl.health.config

import kotlinx.serialization.Serializable

@Serializable
data class EmailConfig(
    val enabled: Boolean = false,
    val region: String = "us-east-1",
    val from: String = "",
    val to: List<String> = emptyList(),
    val subjectPrefix: String = "[health]",
    val cooldownMinutes: Long = 15,
    /** Down or degraded checks in a row before an endpoint is mailed about. */
    val alertAfterChecks: Int = 5,
    val notifyOnRecovery: Boolean = false,
    val dailyReport: DailyReportConfig = DailyReportConfig(),
) {
    val deliverable: Boolean
        get() = enabled && from.isNotBlank() && to.isNotEmpty()
}
